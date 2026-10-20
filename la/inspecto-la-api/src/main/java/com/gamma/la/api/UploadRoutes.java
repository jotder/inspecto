package com.gamma.la.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.audit.EventType;
import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.la.core.UploadLandingPort;
import com.gamma.la.core.UploadLandingPorts;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <b>Analyst upload</b> (LA-ANALYST-UPLOAD-1, roadmap D4, {@code docs/superpower/la-analyst-upload-plan.md}):
 * {@code POST /inv/uploads/{collector}?name=<file>.csv[&replace=true]} lands the raw CSV body in the poll directory of a
 * Collector that opted in ({@code collector.accepts_upload: true}). It lands BYTES and nothing else: the Collector's own
 * Pipeline parses, types, masks and quarantines the file, the Dataset it fills is then readable by the identity import and the
 * index build unchanged - this is the existing ingest door, not a parallel path. The analyst never names a Pipeline, schema,
 * store or path; only the Collector, from the allow-list the Pipelines themselves declare.
 *
 * <p><b>Gates, in order</b> (each refusal is audited after the capability gate): capability {@code canUploadData} (401/403) →
 * write root 503 → {@code name} missing 422 → name is a path 403 ({@code PATH_JAIL_VIOLATION}) → Collector unknown, not
 * upload-enabled, or not a local poll Collector 404 (one answer for all three) → name charset / {@code .csv} / the Collector's
 * include patterns 422 → size cap 413 ({@code upload.max_bytes}, refused on the declared length before any byte is read, and on
 * the bytes read for a chunked body) → empty / zip-or-XLSX / NUL / not UTF-8 422 → resolved target jailed to the inbox 403 →
 * D-U7 four-eyes row budget 403 ({@link InvRoutes#refuseIfSensitive}, rows = lines) → name already in the inbox 409 unless
 * {@code replace=true} → per-Space daily quota 413 ({@code upload.daily_bytes}) → atomic temp + move.
 *
 * <p>Edition gating is by the module: the route is registered by la-api (feature {@code geoLink}); an edition without it answers
 * the absent-module 503. One {@code LA_UPLOAD} audit event per attempt: Collector, file, bytes, sha256, outcome, refusing gate;
 * never a row or a header value.
 */
public final class UploadRoutes implements RouteModule {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Object QUOTA_LOCK = new Object();

    @Override
    public Set<String> featureIds() {
        return Set.of("geoLink", "investigations");
    }

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERAL on purpose (CapabilityManifestTest's scanner).
        api.post("/inv/uploads/([^/]+)", ApiContext.withCapability("canUploadData",
                (e, m) -> upload(api, e, ApiContext.name(m))));
        // LA-I2-GAPS-RESIDUALS-1: the status reads and the uploader's own delete (same capability; own rows only unless canAdminister).
        api.get("/inv/uploads", ApiContext.withCapability("canUploadData", (e, m) -> targets(api)));
        api.get("/inv/uploads/([^/]+)", ApiContext.withCapability("canUploadData",
                (e, m) -> list(api, e, ApiContext.name(m))));
        api.delete("/inv/uploads/([^/]+)/([^/]+)", ApiContext.withCapability("canUploadData",
                (e, m) -> delete(api, e, ApiContext.name(m), ApiContext.param(m, 2))));
    }

    /** Upload records older than this are pruned (metadata only; the landed file follows the Collector's own retention). */
    static final int RECORD_RETENTION_DAYS = 90;
    private static final int MAX_LISTED = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path recordDir(Path root, String collector) {
        return root.resolve("audit").resolve("la-uploads").resolve("records").resolve(collector.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** Whether the caller may act on every upload (Space administrator, or no Subject at all as on Personal). */
    private static boolean isAdmin(HttpExchange ex) {
        return ApiContext.subject(ex).map(s -> s.capabilities().contains("canAdminister")).orElse(true);
    }

    /** {@code GET /inv/uploads} - the Collectors an analyst may upload to (id, Pipeline, accepted file patterns, the size cap). */
    private Object targets(ApiContext api) {
        long max = api.writeRoot() == null ? LaLimit.UPLOAD_MAX_BYTES.defaultValue().longValue() : SpaceLimits.of(api.writeRoot()).get(LaLimit.UPLOAD_MAX_BYTES);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UploadLandingPort.Target t : UploadLandingPorts.active().map(p -> p.targets(api)).orElse(List.of())) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("collector", t.collector());
            r.put("pipeline", t.pipeline());
            r.put("includes", t.includes());
            r.put("maxBytes", max);
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collectors", rows);
        return out;
    }

    /** {@code GET /inv/uploads/{collector}} - the caller's own uploads (every upload for canAdminister), newest first, with ingest state. */
    private Object list(ApiContext api, HttpExchange ex, String collector) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "analyst upload status");
        UploadLandingPort port = UploadLandingPorts.active().orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND,
                "no Collector '" + collector + "' accepts uploads"));
        UploadLandingPort.Target target = port.target(api, collector).orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND,
                "no Collector '" + collector + "' accepts uploads"));
        boolean admin = isAdmin(ex);
        String me = ApiContext.actor(ex);
        Path dir = recordDir(root, target.collector());
        prune(dir);
        // Read the (small, retention-bounded) records, keep the caller's, sort, cut the page - and only THEN probe the
        // Collector's footprint for the rows on the page.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> rec : records(dir))
            if (admin || String.valueOf(me).equals(rec.get("uploader"))) rows.add(rec);
        rows.sort((a, b) -> String.valueOf(b.get("landedAt")).compareTo(String.valueOf(a.get("landedAt"))));
        int total = rows.size();
        List<Map<String, Object>> page = total > MAX_LISTED ? new ArrayList<>(rows.subList(0, MAX_LISTED)) : rows;
        for (Map<String, Object> rec : page) {
            UploadLandingPort.Ingest in = port.ingest(api, target, String.valueOf(rec.get("file")));
            rec.put("state", in.state());
            if (in.rejectedRows() != null) rec.put("rejectedRows", in.rejectedRows());
            if (in.rejectedCapped()) rec.put("rejectedRowsCapped", true);
            if (in.reason() != null) rec.put("quarantineReason", in.reason());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collector", target.collector());
        out.put("total", total);
        out.put("truncated", total > MAX_LISTED);
        out.put("uploads", page);
        return out;
    }

    /** Every readable record in {@code dir}. */
    private static List<Map<String, Object>> records(Path dir) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                Map<String, Object> rec = readRecord(p);
                if (rec != null) out.add(rec);
            }
        }
        return out;
    }

    /** The record of {@code file}, matched by EXACT (case-sensitive) name against the stored records - never a filesystem
     *  lookup that a case-insensitive disk would answer for another spelling. */
    private static Path recordOf(Path dir, String file) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        String want = file + ".json";
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().equals(want)).findFirst().orElse(null);
        }
    }

    /** Drop records past {@link #RECORD_RETENTION_DAYS} (best effort; one directory listing). */
    private static void prune(Path dir) {
        if (!Files.isDirectory(dir)) return;
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(RECORD_RETENTION_DAYS));
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList())
                if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) Files.deleteIfExists(p);
        } catch (IOException | RuntimeException ignored) {
            // retention is housekeeping; a failed prune retries on the next read or upload
        }
    }

    private static boolean ownerOrAdmin(HttpExchange ex, Map<String, Object> rec) {
        return isAdmin(ex) || (rec != null && String.valueOf(ApiContext.actor(ex)).equals(rec.get("uploader")));
    }

    /**
     * {@code DELETE /inv/uploads/{collector}/{file}} - withdraw an upload the Collector has not picked up yet. Gates, in order:
     * capability 401/403 → write root 503 → Collector 404 → no record of that file 404 → not the uploader and not canAdminister
     * 403 → the Collector already picked it up (ingested, quarantined or consumed) 409: its rows are in the Dataset and this
     * route never removes them → delete the inbox file and the record, audit {@code deleted}.
     */
    private Object delete(ApiContext api, HttpExchange ex, String collector, String file) throws IOException {
        String[] seen = new String[]{file.length() > 120 ? file.substring(0, 120) : file, null};
        GATE.remove();
        try {
            return withdraw(api, ex, collector, file, seen);
        } catch (ApiException refused) {
            emit(ex, "la.upload.delete", collector, seen[0], -1, seen[1], "refused", GATE.get() == null ? "write-root" : GATE.get());
            throw refused;
        } catch (IOException | RuntimeException fault) {
            // never an unaudited 500: a filesystem fault is a refusal like any other
            emit(ex, "la.upload.delete", collector, seen[0], -1, seen[1], "refused", "io");
            throw new ApiException(409, ErrorCodes.CONFLICT, "the upload could not be deleted (" + fault.getClass().getSimpleName()
                    + "); nothing was changed - try again");
        } finally {
            GATE.remove();
        }
    }

    private Object withdraw(ApiContext api, HttpExchange ex, String collector, String file, String[] seen) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "analyst upload delete");
        UploadLandingPort port = UploadLandingPorts.active().orElseThrow(() -> refuse("collector", 404, ErrorCodes.NOT_FOUND,
                "no Collector '" + collector + "' accepts uploads"));
        UploadLandingPort.Target target = port.target(api, collector).orElseThrow(() -> refuse("collector", 404, ErrorCodes.NOT_FOUND,
                "no Collector '" + collector + "' accepts uploads"));
        String noSuch = "no upload '" + file + "' for Collector '" + collector + "'";
        if (!SAFE_NAME.matcher(file).matches()) throw refuse("name", 404, ErrorCodes.NOT_FOUND, noSuch);
        Path recordFile;
        Map<String, Object> rec;
        try {
            recordFile = recordOf(recordDir(root, target.collector()), file);
            rec = recordFile == null ? null : readRecord(recordFile);
        } catch (IOException unreadable) {
            throw refuse("record", 503, ErrorCodes.CONTROL_PLANE_READ_ONLY, "the upload records cannot be read right now; nothing was deleted");
        }
        if (rec == null) throw refuse("record", 404, ErrorCodes.NOT_FOUND, noSuch);
        // Another analyst's upload answers exactly like an unknown one: no existence oracle.
        if (!ownerOrAdmin(ex, rec)) throw refuse("owner", 404, ErrorCodes.NOT_FOUND, noSuch);
        file = String.valueOf(rec.get("file"));     // the stored spelling, for the act and the audit
        seen[0] = file;
        seen[1] = String.valueOf(rec.get("sha256"));
        UploadLandingPort.Withdrawal w = port.withdraw(api, target, file);
        switch (w.outcome()) {
            case "deleted" -> { }
            case "picked-up" -> throw refuse("picked-up", 409, ErrorCodes.CONFLICT, "'" + file + "' is already " + w.state()
                    + ": the Collector has picked it up, and its rows stay in the Dataset - this route only withdraws a file not yet ingested");
            case "busy" -> throw refuse("busy", 409, ErrorCodes.CONFLICT, "'" + file
                    + "' may be being ingested right now (" + w.detail() + "); nothing was deleted - try again when the run finishes");
            default -> throw refuse("io", 409, ErrorCodes.CONFLICT, "'" + file + "' could not be deleted (" + w.detail() + "); it is still in the inbox");
        }
        try {
            Files.deleteIfExists(recordFile);
        } catch (IOException stale) {
            // the file is gone; a stale record only shows it as "picked up" until retention prunes it
        }
        emit(ex, "la.upload.delete", collector, file, ((Number) rec.getOrDefault("bytes", -1)).longValue(), seen[1], "deleted", null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collector", target.collector());
        out.put("file", file);
        out.put("deleted", true);
        return ApiContext.respondJson(ex, 200, out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readRecord(Path p) {
        try {
            return JSON.readValue(Files.readAllBytes(p), LinkedHashMap.class);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Best-effort: write the record of a landed upload and prune records past {@link #RECORD_RETENTION_DAYS}. */
    private static void record(Path root, UploadLandingPort.Target target, String file, long bytes, long lines, String sha256,
                               String uploader, boolean replaced, String landedAt) {
        try {
            Path dir = recordDir(root, target.collector());
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("collector", target.collector());
            rec.put("file", file);
            rec.put("bytes", bytes);
            rec.put("lines", lines);
            rec.put("sha256", sha256);
            rec.put("uploader", uploader);
            rec.put("replaced", replaced);
            rec.put("landedAt", landedAt);
            AtomicFiles.write(dir.resolve(file + ".json"), JSON.writeValueAsBytes(rec), ".rec-");
            prune(dir);
        } catch (IOException | RuntimeException ignored) {
            // the file landed; a record that cannot be written only hides it from the status list
        }
    }

    private static final ThreadLocal<String> GATE = new ThreadLocal<>();

    /** A refusal that remembers which gate raised it (on this request's thread), for the audit event. */
    private static ApiException refuse(String gate, int status, String code, String message) {
        GATE.set(gate);
        return new ApiException(status, code, message);
    }

    private Object upload(ApiContext api, HttpExchange ex, String collector) throws IOException {
        String[] seen = new String[]{null, null};   // file name, sha256 - for the audit of a refusal
        long[] size = {-1};
        GATE.remove();
        try {
            return land(api, ex, collector, seen, size);
        } catch (ApiException refused) {
            emit(ex, collector, seen[0], size[0], seen[1], "refused", GATE.get() == null ? "write-root" : GATE.get());
            throw refused;
        } finally {
            GATE.remove();
        }
    }

    private Object land(ApiContext api, HttpExchange ex, String collector, String[] seen, long[] size) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "analyst upload");

        String name = ApiContext.query(ex, "name");
        if (name == null || name.isBlank())
            throw refuse("name", 422, ErrorCodes.CONFIG_VALIDATION_FAILED, "query parameter 'name' (the file name) is required");
        name = name.trim();
        seen[0] = name.length() > 120 ? name.substring(0, 120) : name;
        if (name.contains("/") || name.contains("\\") || name.contains(":") || name.contains("..")
                || name.chars().anyMatch(ch -> ch < 0x20))
            throw refuse("path-jail", 403, ErrorCodes.PATH_JAIL_VIOLATION, "'name' must be a bare file name, not a path");

        UploadLandingPort.Target target = UploadLandingPorts.active().flatMap(p -> p.target(api, collector))
                .orElseThrow(() -> refuse("collector", 404, ErrorCodes.NOT_FOUND,
                        "no Collector '" + collector + "' accepts uploads"));

        if (!SAFE_NAME.matcher(name).matches() || !name.toLowerCase(Locale.ROOT).endsWith(".csv"))
            throw refuse("name", 422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "'name' must be 1-100 characters of letters, digits, '.', '_' or '-', start with a letter or digit, and end in .csv");
        if (!target.accepts(name))
            throw refuse("name", 422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + name + "' does not match the file pattern Collector '"
                    + target.collector() + "' picks up (" + target.includes() + "), so it would never be ingested");

        LaLimitValues lim = SpaceLimits.of(root);
        long maxBytes = Math.min(lim.get(LaLimit.UPLOAD_MAX_BYTES), Integer.MAX_VALUE - 8);
        byte[] bytes;
        try {
            bytes = api.rawBody(ex, (int) maxBytes);   // 413 on the declared length before reading; at most max+1 bytes otherwise
        } catch (ApiException tooBig) {
            if (tooBig.status == 413) throw refuse("size", 413, ErrorCodes.PAYLOAD_TOO_LARGE,
                    "the upload is over the Space's upload.max_bytes (" + maxBytes + " bytes)");
            throw tooBig;
        }
        size[0] = bytes.length;
        seen[1] = EntityFactLog.sha256(bytes);

        sniff(bytes);

        Path inbox = target.inbox();
        Path dest = inbox.resolve(name).normalize();
        if (!inbox.equals(dest.getParent()))
            throw refuse("path-jail", 403, ErrorCodes.PATH_JAIL_VIOLATION, "'" + name + "' escapes the Collector's inbox");

        try {
            InvRoutes.refuseIfSensitive(root, "an analyst upload of " + bytes.length + " bytes", lines(bytes), 0);
        } catch (ApiException sensitive) {
            throw refuse("four-eyes", sensitive.status, ErrorCodes.PERMISSION_DENIED, sensitive.getMessage());
        }

        boolean replace = "true".equalsIgnoreCase(ApiContext.query(ex, "replace"));
        boolean existed = Files.exists(dest);
        if (existed && (!replace || !Files.isRegularFile(dest)))
            throw refuse("conflict", 409, ErrorCodes.CONFLICT, "'" + name + "' is already in the Collector's inbox"
                    + (Files.isRegularFile(dest) ? "; pass replace=true to replace it (a re-delivery keeps the SAME name)" : " and is not a file"));
        // Replace is the uploader's (or an administrator's) act, exactly like delete; the record keeps its ORIGINAL uploader.
        String uploader = ApiContext.actor(ex);
        if (existed) {
            Path recPath = recordOf(recordDir(root, target.collector()), name);
            Map<String, Object> prior = recPath == null ? null : readRecord(recPath);
            if (!ownerOrAdmin(ex, prior))
                throw refuse("owner", 403, ErrorCodes.PERMISSION_DENIED, "'" + name
                        + "' is waiting in the inbox as another analyst's upload; only they or an administrator may replace it");
            if (prior != null && prior.get("uploader") != null) uploader = String.valueOf(prior.get("uploader"));
        }

        long daily = lim.get(LaLimit.UPLOAD_DAILY_BYTES);
        final String landName = name;
        java.util.concurrent.Callable<Boolean> landIt = () -> {
            // A replace decided on a file that was in the inbox; under the claim, check it still is (the Collector may have
            // taken it in between) - writing it fresh would be a new upload the analyst did not ask for.
            if (existed && !Files.isRegularFile(dest))
                throw refuse("conflict", 409, ErrorCodes.CONFLICT, "'" + landName + "' left the Collector's inbox while the replace was"
                        + " being made (the Collector picked it up); nothing was changed - upload it again without replace");
            synchronized (QUOTA_LOCK) {
                Path ledger = root.resolve("audit").resolve("la-uploads")
                        .resolve(java.time.LocalDate.now(ZoneOffset.UTC) + ".bytes");
                long used = readLong(ledger);
                if (used + bytes.length > daily)
                    throw refuse("quota", 413, ErrorCodes.PAYLOAD_TOO_LARGE, "the Space's analyst uploads have landed " + used
                            + " of upload.daily_bytes (" + daily + ") today; this file would exceed it");
                AtomicFiles.write(dest, bytes, ".upload-");
                try {
                    AtomicFiles.write(ledger, Long.toString(used + bytes.length).getBytes(StandardCharsets.UTF_8), ".quota-");
                } catch (IOException ledgerFault) {
                    // the file landed; an unwritable ledger must not fail a landed upload (the next write retries)
                }
            }
            return Boolean.TRUE;
        };
        if (existed) {
            // Replacing overwrites a file the Collector may be reading: take the run claim, like delete does (LA-CASE-HISTORY-
            // UPLOAD-FOLLOWUPS-1 (6)). A fresh name needs none - the poll never sees a name before the atomic move lands it.
            Optional<Boolean> done;
            try {
                done = UploadLandingPorts.active().orElseThrow().whileIdle(api, target, landIt);
            } catch (ApiException refused) {
                throw refused;
            } catch (Exception fault) {
                throw refuse("io", 409, ErrorCodes.CONFLICT, "'" + name + "' could not be replaced (" + fault.getClass().getSimpleName()
                        + "); nothing was changed - try again");
            }
            if (done.isEmpty())
                throw refuse("busy", 409, ErrorCodes.CONFLICT, "'" + name + "' may be being ingested right now (a run of the Pipeline is in flight);"
                        + " nothing was replaced - try again when the run finishes");
        } else {
            try {
                landIt.call();
            } catch (IOException | RuntimeException fault) {
                throw fault;
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collector", target.collector());
        out.put("pipeline", target.pipeline());
        out.put("file", name);
        out.put("bytes", bytes.length);
        out.put("sha256", seen[1]);
        out.put("replaced", existed);
        String landedAt = Instant.now().toString();
        out.put("landedAt", landedAt);
        record(root, target, name, bytes.length, lines(bytes), seen[1], uploader, existed, landedAt);
        emit(ex, collector, name, bytes.length, seen[1], existed ? "replaced" : "landed", null);
        return ApiContext.respondJson(ex, 201, out);
    }

    /** Content sniff: a spreadsheet container, binary content or a non-UTF-8 body is not a CSV. */
    private static void sniff(byte[] b) {
        if (b.length == 0) throw refuse("content", 422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the uploaded file is empty");
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4)
            throw refuse("content", 422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "this is a zip container (an XLSX workbook?), not a CSV - export the sheet as CSV (UTF-8) and upload that");
        for (byte x : b)
            if (x == 0) throw refuse("content", 422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the file holds binary (NUL) bytes - it is not a CSV");
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b));
        } catch (CharacterCodingException notUtf8) {
            throw refuse("content", 422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the file is not valid UTF-8 - export the sheet as CSV UTF-8");
        }
    }

    private static long lines(byte[] b) {
        long n = 0;
        for (byte x : b) if (x == '\n') n++;
        return n;
    }

    private static long readLong(Path p) {
        try {
            return Files.exists(p) ? Long.parseLong(Files.readString(p).trim()) : 0L;
        } catch (IOException | NumberFormatException unreadable) {
            return 0L;
        }
    }

    /** Best-effort audit, one per attempt. Metadata only: never a row, a header or a column name. */
    private static void emit(HttpExchange ex, String collector, String file, long bytes, String sha256, String outcome, String gate) {
        emit(ex, "la.upload", collector, file, bytes, sha256, outcome, gate);
    }

    private static void emit(HttpExchange ex, String action, String collector, String file, long bytes, String sha256,
                             String outcome, String gate) {
        try {
            Event.Builder b = Event.builder(EventType.LA_UPLOAD).source("inv")
                    .message(action + " - " + outcome + (gate == null ? "" : " at " + gate))
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis")
                    .attr("collector", collector).attr("outcome", outcome);
            if (file != null) b.attr("file", file);
            if (bytes >= 0) b.attr("bytes", bytes);
            if (sha256 != null) b.attr("sha256", sha256);
            if (gate != null) b.attr("gate", gate);
            EventSink.current().emit(b);
        } catch (RuntimeException ignored) {
            // best effort - the file landed (or was refused) and that is what matters
        }
    }
}

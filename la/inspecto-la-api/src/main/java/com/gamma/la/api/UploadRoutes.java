package com.gamma.la.api;

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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

        long daily = lim.get(LaLimit.UPLOAD_DAILY_BYTES);
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collector", target.collector());
        out.put("pipeline", target.pipeline());
        out.put("file", name);
        out.put("bytes", bytes.length);
        out.put("sha256", seen[1]);
        out.put("replaced", existed);
        out.put("landedAt", Instant.now().toString());
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
        try {
            Event.Builder b = Event.builder(EventType.LA_UPLOAD).source("inv")
                    .message("la.upload - " + outcome + (gate == null ? "" : " at " + gate))
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("la.upload").actionCategory("analysis")
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

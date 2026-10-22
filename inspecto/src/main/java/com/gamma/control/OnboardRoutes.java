package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.WriteGates;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.entitystore.LaLimits;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <b>Add data</b> - the one server route family that turns a file into a Dataset ({@code LA-ONBOARD-1} OB-2, OB-3,
 * OB-7, OB-8; {@code DATA-TIERS-1} DT-2, DT-4, DT-8, DT-15, DT-16, DT-17). Core, every edition (Basic included), not
 * under {@code /inv}. The verb is <i>Onboard</i> (GLOSSARY); the screens call it <i>Add data</i>.
 *
 * <ul>
 *   <li>{@code POST /onboard/files?name=&kind=stream|reference[&delimiter=&header=&quote=]} - raw file body; stores it in
 *       the Space's draft area and answers the DRAFT ({@link OnboardDraft}, {@link EntityShapes}): every proposed choice is
 *       {@code {value, evidence, confidence}}. Nothing is written to config.</li>
 *   <li>{@code POST /onboard/files/{draftId}/commit} - the confirmed choices; writes the schema and an ordinary Pipeline
 *       marked {@code origin: import}, registers it, lands the file, runs it once, registers the Dataset and answers the
 *       run summary ({@link OnboardImport}). A failed commit leaves nothing behind. Above
 *       {@code import.inline_max_bytes} it answers 202 and the run is followed at {@code GET /onboard/runs/{runId}}.</li>
 *   <li>{@code POST /datasets/{id}/files?name=[&mode=replace|append]} - <b>Add a file</b> to an imported Dataset and run it;
 *       {@code POST /datasets/{id}/run} - <b>Run now</b>.</li>
 *   <li>{@code PUT /datasets/{id}/columns/{column}} - change one column's Entity Type (classification); the Dataset is
 *       re-assessed on the next read.</li>
 * </ul>
 *
 * <p><b>Gates, in order.</b> Capability 401/403 ({@code canUploadData}; {@code canAuthorWorkbench} for the column
 * route) → no write root 503 → the Space's {@code data.self_import} is off 403 ({@link DataSettings}) → the route's own
 * checks. With no sign-in every capability check passes and {@code data.self_import} defaults to on.
 */
final class OnboardRoutes implements RouteModule {

    static final ObjectMapper JSON = new ObjectMapper();
    /** Where drafts live, under the Space's DATA root (they hold the user's data, never config). */
    static final String DRAFTS = "_onboard";
    /** A draft not committed within this many hours is removed on the next upload. */
    static final long DRAFT_TTL_HOURS = 24;
    private static final Pattern DRAFT_ID = Pattern.compile("d[0-9a-f]{32}");
    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,199}");

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose (CapabilityManifestTest's scanner).
        api.post("/onboard/files", ApiContext.withCapability("canUploadData", (e, m) -> draft(api, e)));
        api.post("/onboard/files/([^/]+)/commit", ApiContext.withCapability("canUploadData",
                (e, m) -> OnboardImport.commit(api, e, ApiContext.name(m), api.body(e))));
        api.get("/onboard/runs/([^/]+)", ApiContext.withCapability("canUploadData",
                (e, m) -> OnboardImport.run(e, ApiContext.name(m))));
        api.post("/datasets/([^/]+)/files", ApiContext.withCapability("canUploadData",
                (e, m) -> OnboardImport.addFile(api, e, ApiContext.name(m))));
        api.post("/datasets/([^/]+)/run", ApiContext.withCapability("canUploadData",
                (e, m) -> OnboardImport.runNow(api, e, ApiContext.name(m))));
        api.put("/datasets/([^/]+)/columns/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> OnboardImport.setColumn(api, e, ApiContext.name(m), ApiContext.param(m, 2), api.body(e))));
    }

    /** The standard rate-limit bucket covers these (each reads, or runs, a whole file). */
    static boolean isRateLimited(String method, String path) {
        return "POST".equals(method) && (path.equals("/onboard/files") || path.startsWith("/onboard/files/")
                || (path.startsWith("/datasets/") && (path.endsWith("/files") || path.endsWith("/run"))));
    }

    // ── gates shared by the family ──────────────────────────────────────────────────────────────────────────

    /** Write root 503, then {@code data.self_import} 403. Returns the write root. */
    static Path requireSelfImport(ApiContext api, String what) {
        Path root = WriteGates.requireWriteRoot(api, what);
        if (!DataSettings.forRoot(root).selfImportInForce())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                    "Add data is turned off in this Space: its data comes from Pipelines - ask an administrator to turn it on");
        return root;
    }

    static Path requireDataRoot(ApiContext api) {
        Path d = api.dataRoot();
        if (d == null)
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "this Space has no data directory, so nothing can be imported");
        return d.toAbsolutePath().normalize();
    }

    static LaLimitValues limits(Path writeRoot) {
        return LaLimits.forSpace(writeRoot);
    }

    /** A bare file name from {@code ?name=}: 422 when missing or unusable, 403 when it is a path. */
    static String fileName(HttpExchange ex) {
        String name = ApiContext.query(ex, "name");
        if (name == null || name.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "query parameter 'name' (the file name) is required");
        name = name.trim();
        if (name.contains("/") || name.contains("\\") || name.contains(":") || name.contains("..") || name.chars().anyMatch(ch -> ch < 0x20))
            throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, "'name' must be a bare file name, not a path");
        if (!SAFE_FILE.matcher(name).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "'name' must be 1-200 letters, digits, spaces, '.', '_' or '-', starting with a letter or digit");
        return name;
    }

    /**
     * Stream the request body to {@code target}, refusing more than {@code max} bytes (413 on a declared length before
     * reading, else after max+1 bytes). Never buffers the file in memory.
     */
    static long receive(ApiContext api, HttpExchange ex, Path target, long max) throws IOException {
        String declared = ex.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared.trim()) > max) throw tooLarge(max);
            } catch (NumberFormatException ignored) {
                // a malformed length is the transport's problem; the copy below still bounds the body
            }
        }
        Files.createDirectories(target.getParent());
        long n = 0;
        if (ApiContext.attr(ex, ApiContext.ATTR_RAW_BODY) instanceof byte[] cached) {
            if (cached.length > max) throw tooLarge(max);
            Files.write(target, cached);
            n = cached.length;
        } else {
            try (InputStream in = ex.getRequestBody(); OutputStream out = Files.newOutputStream(target)) {
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) != -1; ) {
                    n += r;
                    if (n > max) throw tooLarge(max);
                    out.write(buf, 0, r);
                }
            } catch (ApiException | IOException fault) {
                Files.deleteIfExists(target);
                throw fault;
            }
        }
        if (n == 0) {
            Files.deleteIfExists(target);
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the file is empty");
        }
        return n;
    }

    private static ApiException tooLarge(long max) {
        return new ApiException(413, ErrorCodes.PAYLOAD_TOO_LARGE, "the file is larger than this Space's import.max_bytes ("
                + max + " bytes)");
    }

    /** Whether the caller is an administrator (or there is no sign-in): may act on another person's draft or run. */
    static boolean isAdmin(HttpExchange ex) {
        return ApiContext.subject(ex).map(s -> s.capabilities().contains("canAdminister")).orElse(true);
    }

    static boolean signedIn(HttpExchange ex) {
        return ApiContext.attr(ex, ApiContext.ATTR_SUBJECT) instanceof Subject;
    }

    // ── POST /onboard/files ─────────────────────────────────────────────────────────────────────────────────

    private Object draft(ApiContext api, HttpExchange ex) throws IOException {
        Path writeRoot = requireSelfImport(api, "Add data");
        Path dataRoot = requireDataRoot(api);
        String name = fileName(ex);
        String kind = kind(ApiContext.query(ex, "kind"));
        LaLimitValues lim = limits(writeRoot);

        prune(dataRoot.resolve(DRAFTS));
        String draftId = "d" + UUID.randomUUID().toString().replace("-", "");
        Path dir = dataRoot.resolve(DRAFTS).resolve(draftId);
        Path file = dir.resolve(storedName(name));
        boolean kept = false;
        try {
            long bytes = receive(api, ex, file, lim.get(LaLimit.IMPORT_MAX_BYTES));
            String type = OnboardDraft.fileType(file, name);
            OnboardDraft.Analysis a = OnboardDraft.analyse(dir, file, name, type, overrides(ex, type));
            long maxRows = lim.get(LaLimit.IMPORT_MAX_ROWS);
            if (a.rows() > maxRows)
                throw new ApiException(413, ErrorCodes.PAYLOAD_TOO_LARGE, "'" + name + "' holds " + a.rows()
                        + " rows; this Space's import.max_rows is " + maxRows);
            Map<String, Object> entity = OnboardDraft.entityTypes(a, LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes(), kind);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("draftId", draftId);
            out.put("kind", kind);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", name);
            f.put("bytes", bytes);
            f.put("rows", a.rows());
            out.put("file", f);
            out.put("format", a.format());
            out.put("columns", a.columns());
            out.put("rows", a.preview());
            out.put("entityTypes", entity);
            out.put("limits", limitsShape(writeRoot, lim));

            Map<String, Object> record = new LinkedHashMap<>(out);
            record.remove("rows");
            record.put("type", type);
            record.put("stored", file.getFileName().toString());
            record.put("uploader", ApiContext.actor(ex));
            record.put("createdAt", Instant.now().toString());
            record.put("plan", a.cols().stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.name());
                m.put("header", c.header());
                m.put("selector", c.selector());
                m.put("type", c.type());
                m.put("format", c.format());
                return m;
            }).toList());
            AtomicFiles.write(dir.resolve("draft.json"), JSON.writeValueAsBytes(record), ".draft-");
            kept = true;
            return out;
        } finally {
            if (!kept) deleteTree(dir);
        }
    }

    static String kind(String raw) {
        String k = raw == null || raw.isBlank() ? "stream" : raw.trim().toLowerCase(Locale.ROOT);
        if (!k.equals("stream") && !k.equals("reference"))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "kind must be stream (events, links) or reference (details about entities)");
        return k;
    }

    /** The user's own delimited-format choices, from the query (re-draft after a low-confidence proposal). */
    private static Map<String, String> overrides(HttpExchange ex, String type) {
        Map<String, String> o = new LinkedHashMap<>();
        for (String k : List.of("delimiter", "header", "quote")) {
            String v = ApiContext.query(ex, k);
            if (v == null) continue;
            if (!type.equals("csv") && !type.equals("tsv"))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + k + "' applies to delimited text only");
            if (k.equals("delimiter") && (v.length() != 1 || v.charAt(0) == '\n' || v.charAt(0) == '\r'))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "delimiter must be one character");
            if (k.equals("quote") && v.length() > 1)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "quote must be one character, or empty for none");
            if (k.equals("header") && !v.equals("true") && !v.equals("false"))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "header must be true or false");
            o.put(k, v);
        }
        return o;
    }

    /** The draft's {@code limits} block: what this Space allows and how much of it is used. */
    static Map<String, Object> limitsShape(Path writeRoot, LaLimitValues lim) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxBytes", lim.get(LaLimit.IMPORT_MAX_BYTES));
        m.put("maxRows", lim.get(LaLimit.IMPORT_MAX_ROWS));
        m.put("maxImports", lim.get(LaLimit.IMPORT_MAX_DATASETS));
        m.put("imports", importCount(writeRoot));
        m.put("inlineMaxBytes", lim.get(LaLimit.IMPORT_INLINE_MAX_BYTES));
        m.put("excel", OnboardDraft.excelAvailable());
        return m;
    }

    /** Imported Datasets in this Space (registry entries marked {@code origin: import}). */
    static int importCount(Path writeRoot) {
        int n = 0;
        try {
            for (ComponentRegistry.Component c : new ComponentStore(writeRoot.resolve("registry")).list("dataset"))
                if ("import".equals(String.valueOf(c.content().get("origin")))) n++;
        } catch (RuntimeException unreadable) {
            // an unreadable registry counts nothing; the write below meets the same registry
        }
        return n;
    }

    /** The stored name of an upload: spaces become {@code _}, so the file name is safe in a glob and a TOON value. */
    static String storedName(String name) {
        return name.replace(' ', '_');
    }

    /** The draft {@code draftId}, or 404 (unknown, malformed, expired, or someone else's while signed in). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readDraft(Path dataRoot, String draftId, HttpExchange ex) throws IOException {
        if (!DRAFT_ID.matcher(draftId).matches()) throw noDraft(draftId);
        Path f = dataRoot.resolve(DRAFTS).resolve(draftId).resolve("draft.json");
        if (!Files.isRegularFile(f)) throw noDraft(draftId);
        Map<String, Object> d = JSON.readValue(Files.readAllBytes(f), LinkedHashMap.class);
        if (signedIn(ex) && !isAdmin(ex) && !String.valueOf(d.get("uploader")).equals(ApiContext.actor(ex))) throw noDraft(draftId);
        return d;
    }

    private static ApiException noDraft(String draftId) {
        return new ApiException(404, ErrorCodes.NOT_FOUND, "no draft '" + (draftId.length() > 40 ? draftId.substring(0, 40) : draftId)
                + "' (drafts expire after " + DRAFT_TTL_HOURS + " hours; add the file again)");
    }

    /** Remove drafts older than {@link #DRAFT_TTL_HOURS} (best effort). */
    static void prune(Path drafts) {
        if (!Files.isDirectory(drafts)) return;
        Instant cutoff = Instant.now().minus(java.time.Duration.ofHours(DRAFT_TTL_HOURS));
        try (Stream<Path> s = Files.list(drafts)) {
            for (Path d : s.toList())
                if (Files.getLastModifiedTime(d).toInstant().isBefore(cutoff)) deleteTree(d);
        } catch (IOException | RuntimeException ignored) {
            // housekeeping; the next upload retries
        }
    }

    static void deleteTree(Path p) {
        if (p == null || !Files.exists(p)) return;
        try (Stream<Path> w = Files.walk(p)) {
            for (Path x : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        } catch (IOException | RuntimeException ignored) {
            // best effort; a leftover is reported by the caller where it matters
        }
    }

    /** Edit access to a Dataset entry (owner, edit share, or unrestricted): 404 when it cannot be seen, 403 when view-only. */
    static void requireEdit(HttpExchange ex, String id, Map<String, Object> content) {
        ComponentAccess.onUpdate(ex, "dataset", id, content, content);
    }
}

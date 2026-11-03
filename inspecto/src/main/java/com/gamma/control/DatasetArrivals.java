package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.WriteGates;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.DuckDbUtil;
import com.gamma.config.safety.PathJail;
import com.gamma.util.SqlIdent;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LA-ARRIVAL-LEDGER-1 (plan {@code docs/superpower/la-data-arrival-plan.md} §5a): the arrivals of an Add data Dataset - every
 * file its import Pipeline took, an upload or a file a foreign process put in its inbox - newest first.
 *
 * <p>A READ over what the engine already records: the per-file audit ledger ({@code StatusStore.files}: file, times,
 * rows read / rejected, output Parquet), plus the covered period from the arrival's own Parquet output (min / max of the
 * Dataset's {@code eventDate} column), plus one small sidecar line per upload ({@code <dataRoot>/<id>/arrivals.jsonl}:
 * the file's own name, replace or append, its sha256, the import-time index outcome) that the ledger cannot know. A file
 * with no sidecar line came from a system (a drop in the inbox). Every edition: it is the explorer's own data.
 */
final class DatasetArrivals {

    private DatasetArrivals() {
    }

    static final String SIDECAR = "arrivals.jsonl";
    /** Newest arrivals listed; older ones stay in the ledger. */
    static final int MAX_LISTED = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    // ── the sidecar, written by an upload (OnboardImport) ───────────────────────────────────────────────────

    /** sha256 of a file, hex; null when it cannot be read (the arrival is still recorded without it). */
    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException unreadable) {
            return null;
        }
    }

    /**
     * Record one upload's arrival: the ledger batch and landed name it matches, the user's file name, the mode and the
     * import-time index outcome. Best effort - a failed write loses only these extras, never the import.
     */
    static void recordUpload(Path dataRoot, String pipeline, String batch, Path landed, String fileName, boolean replace,
                             String sha256, Map<String, Object> summary) {
        if (batch == null || landed == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batch", batch);
        m.put("landed", landed.getFileName().toString());
        m.put("file", fileName);
        m.put("mode", replace ? "replace" : "append");
        if (sha256 != null) m.put("sha256", sha256);
        if (summary.get("index") instanceof Map<?, ?> ix && ix.get("result") != null) m.put("index", String.valueOf(ix.get("result")));
        try {
            Path f = dataRoot.resolve(pipeline).resolve(SIDECAR);
            Files.createDirectories(f.getParent());
            Files.writeString(f, JSON.writeValueAsString(m) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best effort: the ledger still lists the arrival, as one from a system
        }
    }

    private static Map<String, Map<String, Object>> sidecar(Path dataRoot, String pipeline) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Path f = dataRoot.resolve(pipeline).resolve(SIDECAR);
        if (!Files.isRegularFile(f)) return out;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = JSON.readValue(line, Map.class);
                    out.put(String.valueOf(m.get("landed")), m);   // the landed name is unique per upload (add_<stamp>_…)
                } catch (IOException torn) {
                    // a torn line (a crash mid-write) is skipped, not fatal
                }
            }
        } catch (IOException unreadable) {
            // no extras: every arrival reads as one from a system
        }
        return out;
    }

    // ── GET /datasets/{id}/arrivals ────────────────────────────────────────────────────────────────────────

    /**
     * {@code GET /datasets/{id}/arrivals}: {@code {dataset, eventDateColumn, arrivals[], truncated, reason}}. A READ gate
     * only (no capability), the Dataset's view permission as {@code GET /datasets/{id}/freshness}; unlike freshness a host
     * with no write root answers 503 (there is no ledger to read). An id the store refuses → 400 · unknown or shared-away
     * Dataset → 404. A Dataset no import made - or an entry whose import fields do not name ITS OWN Add data Pipeline -
     * answers 200 with no arrivals and the reason. The whole per-file ledger is read, then the newest 200 are listed.
     */
    static Object list(ApiContext api, HttpExchange ex, String id) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "dataset arrivals");
        Map<String, Object> dataset;
        try {
            dataset = new ComponentStore(writeRoot.resolve("registry")).get("dataset", id)
                    .map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + id + "'"));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, bad.getMessage());
        }
        if (!ComponentAccess.canView(ex, dataset))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + id + "'");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", id);
        Object col = dataset.get(DatasetRoutes.EVENT_DATE_KEY);
        String eventDate = col instanceof String c && !c.isBlank() ? c.trim() : null;
        out.put("eventDateColumn", eventDate);
        Path dataRoot = api.dataRoot() == null ? null : api.dataRoot().toAbsolutePath().normalize();
        CollectorService service = HostContext.of(api).service();
        // ⚠ origin / importPipeline are editable (PUT /components/dataset/{id}), so never trusted alone - as OnboardImport's
        // importDataset: the Pipeline must be the one Add data wrote for THIS id, or a forged entry would read any Pipeline's
        // ledger and Parquet through this route.
        Path expected = writeRoot.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon").toAbsolutePath().normalize();
        boolean ours = service != null && "import".equals(String.valueOf(dataset.get("origin")))
                && id.equals(dataset.get("importPipeline"))
                && service.pathFor(id).map(p -> p.toAbsolutePath().normalize().equals(expected)).orElse(false);
        PipelineConfig cfg = ours ? service.configFor(id).orElse(null) : null;
        if (cfg == null || dataRoot == null) {
            out.put("arrivals", List.of());
            out.put("truncated", false);
            out.put("reason", dataRoot == null ? "this Space has no data directory"
                    : "'" + id + "' was not made with Add data, so its arrivals are its Pipeline's runs");
            return out;
        }
        String pipeline = id;
        Map<String, Map<String, Object>> extras = sidecar(dataRoot, pipeline);
        List<Map<String, String>> rows = new ArrayList<>(service.statusStore().files(cfg));
        java.util.Collections.reverse(rows);   // the ledger is oldest first
        List<Map<String, Object>> arrivals = new ArrayList<>();
        try (Connection duck = periodReader(dataRoot, pipeline, eventDate)) {
            for (Map<String, String> r : rows) {
                if (arrivals.size() == MAX_LISTED) break;
                arrivals.add(arrival(r, extras, dataRoot, pipeline, eventDate, duck));
            }
        } catch (java.sql.SQLException noReader) {
            // closing failed: the list is already built
        }
        out.put("arrivals", arrivals);
        out.put("truncated", rows.size() > MAX_LISTED);
        out.put("reason", null);
        return out;
    }

    private static Map<String, Object> arrival(Map<String, String> r, Map<String, Map<String, Object>> extras, Path dataRoot,
                                               String pipeline, String eventDate, Connection duck) {
        String landed = r.getOrDefault("filename", "");
        String batch = r.getOrDefault("consignment_id", "");
        Map<String, Object> extra = extras.get(landed);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("file", extra != null && extra.get("file") != null ? extra.get("file")
                : nonBlank(r.get("origin")) ? r.get("origin") : landed);
        a.put("sender", extra != null ? "upload" : "system");
        a.put("received", nonBlank(r.get("end_time")) ? r.get("end_time") : r.get("start_time"));
        a.put("status", r.get("status"));
        a.put("rowsRead", number(r.get("parsed_rows")));
        a.put("rowsRejected", number(r.get("error_rows")));
        a.put("mode", extra == null ? null : extra.get("mode"));
        a.put("sha256", extra == null ? null : extra.get("sha256"));
        a.put("index", extra == null ? null : extra.get("index"));
        a.put("consignment", batch);
        List<Path> outputs = new ArrayList<>();
        Path base = dataRoot.resolve(pipeline);
        for (String p : r.getOrDefault("output_paths", "").split(";")) {
            if (p.isBlank()) continue;
            Path o = Path.of(p.trim()).toAbsolutePath().normalize();
            if (PathJail.contains(base, o) && Files.isRegularFile(o)) outputs.add(o);   // never read outside the Dataset
        }
        boolean wrote = !r.getOrDefault("output_paths", "").isBlank();
        a.put("kept", !wrote || !outputs.isEmpty());   // false: a later Replace everything set its rows aside
        String[] period = period(duck, outputs, eventDate);
        a.put("coversFrom", period[0]);
        a.put("coversTo", period[1]);
        return a;
    }

    /** One in-memory DuckDB sealed to the Dataset's directory, or null when there is no period to read. */
    private static Connection periodReader(Path dataRoot, String pipeline, String eventDate) {
        if (eventDate == null) return null;
        try {
            DuckDbUtil.loadDriver();
            return DuckDbUtil.openInMemory(null, List.of(dataRoot.resolve(pipeline)));
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** {min, max} of the event-date column over these Parquet files, as text; {null, null} when it cannot be read. */
    private static String[] period(Connection duck, List<Path> files, String eventDate) {
        if (duck == null || files.isEmpty() || eventDate == null) return new String[] {null, null};
        String source = com.gamma.sql.SqlViews.pathList(files.stream().map(Path::toString).toList());
        String c = SqlIdent.q(eventDate);
        try (Statement st = duck.createStatement();
             ResultSet rs = st.executeQuery("SELECT CAST(min(" + c + ") AS VARCHAR), CAST(max(" + c + ") AS VARCHAR) FROM "
                     + com.gamma.sql.SqlViews.parquet(source, true, com.gamma.sql.SqlViews.Hive.OFF))) {
            return rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : new String[] {null, null};
        } catch (java.sql.SQLException unreadable) {
            return new String[] {null, null};
        }
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static Long number(String s) {
        try {
            return s == null || s.isBlank() ? null : Long.parseLong(s.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}

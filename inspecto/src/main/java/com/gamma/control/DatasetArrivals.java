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
 * with no sidecar line came from a system (a drop in the inbox) - unless Add data landed it ({@code add_<stamp>_<name>},
 * {@link #ADD_DATA_NAME}): an import made before the sidecar existed, or whose best-effort line was lost, was still added
 * by a person. Every edition: it is the explorer's own data.
 */
final class DatasetArrivals {

    private DatasetArrivals() {
    }

    static final String SIDECAR = "arrivals.jsonl";
    /** Newest arrivals listed; older ones stay in the ledger. */
    static final int MAX_LISTED = 200;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The inbox name only Add data gives a file it lands ({@code OnboardImport.Job#landFromDraft}): {@code add_<stamp>_<stem>.<ext>}. */
    static final java.util.regex.Pattern ADD_DATA_NAME = java.util.regex.Pattern.compile("add_[a-z]+_(.+)");

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
     * Record what one run took, one sidecar line per file (the ledger rows {@code taken} the run added): the upload's own
     * line (the user's file name, the mode, its sha256) for {@code landed}, and a {@code sender: system} line for every
     * other file - a drop Run now (or the upload's run) took from the inbox. Every line carries the run's index outcome
     * ({@code result} and {@code mode}: append / full / compact). Best effort - a failed write loses only these extras,
     * never the import.
     */
    static void recordRun(Path dataRoot, String pipeline, List<Map<String, String>> taken, Path landed, String fileName,
                          String mode, String sha256, Map<String, List<String>> newColumns, Map<String, Object> summary) {
        String landedName = landed == null ? null : landed.getFileName().toString();
        String at = java.time.Instant.now().toString();
        StringBuilder lines = new StringBuilder();
        try {
            for (Map<String, String> r : taken) {
                String name = java.util.Objects.toString(r.get("filename"), "");
                if (name.isBlank()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("batch", r.get("consignment_id"));
                m.put("landed", name);
                m.put("at", at);
                if (name.equals(landedName)) {
                    m.put("sender", "upload");
                    m.put("file", fileName);
                    m.put("mode", mode);
                    if (sha256 != null) m.put("sha256", sha256);
                } else {
                    m.put("sender", "system");
                    m.put("mode", "append");   // Run now adds what it takes
                }
                List<String> fresh = newColumns.get(name);
                if (fresh != null && !fresh.isEmpty()) m.put("newColumns", fresh);
                if (summary.get("index") instanceof Map<?, ?> ix && ix.get("result") != null) {
                    m.put("index", String.valueOf(ix.get("result")));
                    if (ix.get("mode") != null) m.put("indexMode", String.valueOf(ix.get("mode")));
                }
                lines.append(JSON.writeValueAsString(m)).append('\n');
            }
            if (lines.isEmpty()) return;
            Path f = dataRoot.resolve(pipeline).resolve(SIDECAR);
            Files.createDirectories(f.getParent());
            Files.writeString(f, lines.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best effort: the ledger still lists the arrivals, as ones from a system with no index outcome
        }
    }

    /**
     * LA-ARRIVAL-MODES-1: a waiting file Run now refused by the columns policy and set aside ({@code setAside}, relative to
     * the Dataset's directory). It never reached the ledger, so this line is its only record. Best effort.
     */
    static void recordRefused(Path dataRoot, String pipeline, String file, String reason, String setAside) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("landed", file);
        m.put("at", java.time.Instant.now().toString());
        m.put("received", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(java.time.LocalDateTime.now()));
        m.put("sender", "system");
        m.put("status", REFUSED);
        m.put("reason", reason);
        m.put("setAside", setAside);
        try {
            Path f = dataRoot.resolve(pipeline).resolve(SIDECAR);
            Files.createDirectories(f.getParent());
            Files.writeString(f, JSON.writeValueAsString(m) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best effort: the file is still set aside, and the run's answer named it
        }
    }

    static final String REFUSED = "REFUSED";

    /**
     * D-3 (LA-ARRIVAL-MODES-1): the earlier upload of the same bytes whose rows the Dataset still holds - the newest line
     * with this sha256 that no later <i>Replace everything</i> set aside - or null. A refused or system line never counts.
     */
    static Map<String, Object> alreadyImported(Path dataRoot, String pipeline, String sha256) {
        Map<String, Object> hit = null;
        for (Map<String, Object> m : sidecarLines(dataRoot, pipeline)) {
            if (isSystem(m) || REFUSED.equals(m.get("status"))) continue;
            if (sha256.equals(m.get("sha256"))) hit = m;
            else if ("replace".equals(m.get("mode"))) hit = null;   // a later Replace everything set the earlier rows aside
        }
        return hit;
    }

    /** The sidecar's lines, oldest first; torn lines skipped. */
    static List<Map<String, Object>> sidecarLines(Path dataRoot, String pipeline) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path f = dataRoot.resolve(pipeline).resolve(SIDECAR);
        if (!Files.isRegularFile(f)) return out;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = JSON.readValue(line, Map.class);
                    out.add(m);
                } catch (IOException torn) {
                    // a torn line (a crash mid-write) is skipped, not fatal
                }
            }
        } catch (IOException unreadable) {
            // no extras: every arrival reads as one from a system
        }
        return out;
    }

    /**
     * The extras by ledger row: an upload's line under its landed name (unique per upload, {@code add_<stamp>_…}); a
     * system line under {@code landed|batch}, since a foreign process may send the same name twice.
     */
    private static Map<String, Map<String, Object>> sidecar(Path dataRoot, String pipeline) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> m : sidecarLines(dataRoot, pipeline))
            if (!REFUSED.equals(m.get("status")))
                out.put(isSystem(m) ? m.get("landed") + "|" + m.get("batch") : String.valueOf(m.get("landed")), m);
        return out;
    }

    private static boolean isSystem(Map<String, Object> line) {
        return "system".equals(line.get("sender"));
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
        // LA-DATASET-COMPACT-1 (D-8): arrivals a compaction merged keep their period through the merge map
        Map<String, DatasetCompaction.Segment> merged = DatasetCompaction.merged(dataRoot, pipeline);
        try (Connection duck = periodReader(dataRoot, pipeline, eventDate)) {
            for (Map<String, String> r : rows) {
                if (arrivals.size() == MAX_LISTED) break;
                arrivals.add(arrival(r, extras, dataRoot, pipeline, eventDate, duck, merged));
            }
        } catch (java.sql.SQLException noReader) {
            // closing failed: the list is already built
        }
        // LA-ARRIVAL-MODES-1: files Run now refused never reached the ledger; their sidecar line is the arrival
        int refused = 0;
        for (Map<String, Object> m : sidecarLines(dataRoot, pipeline)) {
            if (!REFUSED.equals(m.get("status"))) continue;
            refused++;
            arrivals.add(refusedArrival(m));
        }
        if (refused > 0) {
            // the ledger's times are to the second: a tie is broken by the sidecar's own instant of each line
            arrivals.sort(java.util.Comparator.comparing((Map<String, Object> a) -> String.valueOf(a.get("received")))
                    .thenComparing(a -> String.valueOf(a.getOrDefault(AT, ""))).reversed());
            while (arrivals.size() > MAX_LISTED) arrivals.remove(arrivals.size() - 1);
        }
        for (Map<String, Object> a : arrivals) a.remove(AT);
        out.put("arrivals", arrivals);
        out.put("truncated", rows.size() + refused > MAX_LISTED);
        out.put("reason", null);
        // one read for every Data status line: how files come (LA-ARRIVAL-FEEDS-1) and the storage (LA-DATASET-COMPACT-1)
        out.put("feed", DatasetFeed.summary(cfg, dataRoot, id, dataset));
        try {
            out.put("storage", DatasetCompaction.describe(dataRoot, id, dataset));
        } catch (java.io.IOException unreadable) {
            out.put("storage", null);
        }
        return out;
    }

    private static Map<String, Object> arrival(Map<String, String> r, Map<String, Map<String, Object>> extras, Path dataRoot,
                                               String pipeline, String eventDate, Connection duck,
                                               Map<String, DatasetCompaction.Segment> merged) {
        String landed = r.getOrDefault("filename", "");
        String batch = r.getOrDefault("consignment_id", "");
        Map<String, Object> extra = extras.get(landed + "|" + batch);
        Map<String, Object> byName = extras.get(landed);
        if (extra == null && byName != null && !isSystem(byName)) extra = byName;
        java.util.regex.Matcher added = ADD_DATA_NAME.matcher(landed);
        // no line at all: Add data's own name still says a person added it (a system line, when there is one, wins)
        boolean upload = extra != null ? !isSystem(extra) : added.matches();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("file", upload && extra != null && extra.get("file") != null ? extra.get("file")
                : upload && extra == null ? added.group(1)
                : nonBlank(r.get("origin")) ? r.get("origin") : landed);
        a.put("sender", upload ? "upload" : "system");
        a.put("received", nonBlank(r.get("end_time")) ? r.get("end_time") : r.get("start_time"));
        a.put("status", r.get("status"));
        a.put("rowsRead", number(r.get("parsed_rows")));
        a.put("rowsRejected", number(r.get("error_rows")));
        a.put("mode", extra == null ? null : extra.get("mode"));
        a.put("sha256", extra == null ? null : extra.get("sha256"));
        a.put("index", extra == null ? null : extra.get("index"));
        a.put("indexMode", extra == null ? null : extra.get("indexMode"));
        a.put("consignment", batch);
        List<Path> outputs = new ArrayList<>();
        List<DatasetCompaction.Segment> inMerge = new ArrayList<>();
        Path base = dataRoot.resolve(pipeline);
        for (String p : r.getOrDefault("output_paths", "").split(";")) {
            if (p.isBlank()) continue;
            Path o = Path.of(p.trim()).toAbsolutePath().normalize();
            if (PathJail.contains(base, o) && Files.isRegularFile(o)) outputs.add(o);   // never read outside the Dataset
            else if (PathJail.contains(base, o)) {   // merged by a compaction: the map names it, with its period
                String rel = base.resolve("database").relativize(o).toString().replace(java.io.File.separatorChar, '/');
                DatasetCompaction.Segment s = merged.get(rel);
                if (s != null) inMerge.add(s);
            }
        }
        boolean wrote = !r.getOrDefault("output_paths", "").isBlank();
        // false: a later Replace everything set its rows aside
        a.put("kept", !wrote || !outputs.isEmpty() || !inMerge.isEmpty());
        String[] period = period(duck, outputs, eventDate);
        for (DatasetCompaction.Segment s : inMerge) {   // a rewritten merged file knows no period (null): left unsaid
            if (s.coversFrom() != null && (period[0] == null || s.coversFrom().compareTo(period[0]) < 0)) period[0] = s.coversFrom();
            if (s.coversTo() != null && (period[1] == null || s.coversTo().compareTo(period[1]) > 0)) period[1] = s.coversTo();
        }
        a.put("coversFrom", period[0]);
        a.put("coversTo", period[1]);
        a.put("newColumns", extra == null ? null : extra.get("newColumns"));
        a.put("reason", null);
        if (extra != null && extra.get("at") != null) a.put(AT, extra.get("at"));   // a sort key only, never answered
        return a;
    }

    /** The internal sort key: the sidecar line's instant. */
    private static final String AT = "_at";

    /** A refused file's arrival: the shape of every other, with its reason and where it was set aside. */
    private static Map<String, Object> refusedArrival(Map<String, Object> m) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("file", m.get("landed"));
        a.put("sender", "system");
        a.put("received", m.get("received"));
        a.put("status", REFUSED);
        a.put("rowsRead", null);
        a.put("rowsRejected", null);
        a.put("mode", null);
        a.put("sha256", null);
        a.put("index", null);
        a.put("indexMode", null);
        a.put("consignment", "");
        a.put("kept", false);
        a.put("coversFrom", null);
        a.put("coversTo", null);
        a.put("newColumns", null);
        a.put("reason", m.get("reason"));
        a.put("setAside", m.get("setAside"));
        if (m.get("at") != null) a.put(AT, m.get("at"));
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

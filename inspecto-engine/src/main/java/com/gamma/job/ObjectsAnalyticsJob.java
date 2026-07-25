package com.gamma.job;

import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectType;
import com.gamma.signal.Severity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code objects.analytics} Job Type — periodically materializes {@link ObjectService#analytics(ObjectType)}
 * as tall Parquet rows under {@code <dataDir>/ops_analytics/} and result-stamps an {@code ops_analytics}
 * Dataset, so Alert/Incident/Case/Task rollups become bindable in Studio/BI (widgets, dashboards, queries,
 * Alert Rules) instead of being readable only through {@code GET /objects/analytics}.
 *
 * <p>Why a materialization job rather than a view over the live table: {@code OperationalObject}s live only in
 * the JDBC {@code inspecto_ops_objects} table (single-writer {@code inspecto-ops.db} by default), so there is
 * no Parquet/view surface a {@code dataset} component's {@code physicalRef} could bind to, and opening a second
 * connection to that DB is not allowed. The analytics are therefore computed <em>in-process</em> through the
 * space {@link ObjectService} and written out as an aggregate sample — which also buys the time dimension the
 * live endpoint lacks (backlog / aging trends), the whole point of the binding.
 *
 * <p>Write style mirrors {@code storage_report}'s catalog append ({@link MaintenanceJob}): one timestamped
 * Parquet per run, readers glob the directory, rows union across runs. Current-state-only consumers filter
 * {@code sampled_at = (SELECT max(sampled_at) …)}; trends group by a time bucket. The read path needs no new
 * code — {@code DatasetRelation} already resolves {@code physicalRef → read_parquet('<dataRoot>/ops_analytics/**')}.
 *
 * <p>The Object Engine <em>is</em> the work here, so a missing {@link ObjectService} fails the Run closed
 * (like {@code caserule.evaluate}, and unlike {@code recon.run} where it only adds an optional promotion).
 * It is resolved through a supplier because it is wired onto the {@link JobService} after this built-in is
 * constructed.
 */
final class ObjectsAnalyticsJob implements Job {

    /** Dataset id, {@code physicalRef}, and the sample sub-directory under the space data dir. */
    static final String CATALOG = "ops_analytics";

    private static final Logger log = LoggerFactory.getLogger(ObjectsAnalyticsJob.class);

    private final JobConfig cfg;
    private final String dataDir;
    /** Live view of this space's {@link ObjectService} (wired post-construction); {@code null} until wired
     *  and on the bare-JobService test constructors — then the Run fails closed. */
    private final Supplier<ObjectService> objects;

    ObjectsAnalyticsJob(JobConfig cfg, String dataDir, Supplier<ObjectService> objects) {
        this.cfg = cfg;
        this.dataDir = dataDir;
        this.objects = objects;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "objects.analytics"; }

    /** {@code objects.analytics} always runs with a {@link JobContext} (it emits a Signal + an artifact). */
    @Override public JobResult run() {
        throw new UnsupportedOperationException("objects.analytics requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        long t0 = System.nanoTime();
        ObjectService svc = objects == null ? null : objects.get();
        if (svc == null)
            throw new IllegalStateException("objects.analytics needs the space Object Engine (JobService.objects not wired)");
        String writeRoot = System.getProperty("assist.write.root");
        if (dataDir == null || dataDir.isBlank())
            throw new IllegalStateException("objects.analytics needs a space data directory");
        if (writeRoot == null || writeRoot.isBlank())
            throw new IllegalStateException("objects.analytics needs -Dassist.write.root (the component registry root)");

        List<ObjectType> types = types(cfg.opt("types", null));
        int retentionDays = retentionDays();
        Instant now = Instant.now();

        List<Object[]> rows = new ArrayList<>();
        for (ObjectType type : types) rows.addAll(flatten(type, svc.analytics(type)));

        if (ctx.dryRun())
            return JobResult.ok("objects.analytics (dry run): " + rows.size() + " row(s) over " + types.size()
                    + " type(s), nothing written", (System.nanoTime() - t0) / 1_000_000L);

        Path parquet;
        int purged;
        try {
            Path storeDir = Path.of(dataDir).resolve(CATALOG);
            Files.createDirectories(storeDir);
            parquet = storeDir.resolve("analytics_" + now.toEpochMilli() + "_out.parquet");
            writeParquet(parquet, now, rows);
            com.gamma.pipeline.ComponentStore store =
                    new com.gamma.pipeline.ComponentStore(Path.of(writeRoot).resolve("registry"));
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("name", CATALOG);
            content.put("physicalRef", CATALOG);
            content.put("description", "Operational-object analytics samples (one row per type/axis/key per run)");
            store.write("dataset", CATALOG, content, false);   // result-stamp write, no version churn
            purged = purge(storeDir, retentionDays, now);
        } catch (Exception e) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("rows", rows.size());
            failure.put("error", e.getMessage());
            ctx.signals().emit("objects.analytics.completed", Severity.WARN, failure);
            log.warn("objects.analytics write failed: {}", e.getMessage());
            throw e;   // the write IS the work — never report a silent no-op success
        }

        long ms = (System.nanoTime() - t0) / 1_000_000L;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataset", CATALOG);
        payload.put("rows", rows.size());
        payload.put("types", types.stream().map(Enum::name).toList());
        payload.put("durationMs", ms);
        if (purged > 0) payload.put("purged", purged);
        ctx.signals().emit("objects.analytics.completed", Severity.INFO, payload);
        ctx.log().info("object analytics sampled", "dataset", CATALOG, "rows", rows.size(),
                "types", types.size(), "purged", purged);
        ctx.artifacts().dataset(CATALOG, CATALOG, null, rows.size(), now);

        return JobResult.ok("objects.analytics: " + rows.size() + " row(s) over " + types.size()
                + " type(s) → " + parquet.getFileName()
                + (purged > 0 ? " (purged " + purged + " old sample(s))" : ""), ms);
    }

    // ── flatten ───────────────────────────────────────────────────────────────────────

    /**
     * Fold one {@link ObjectService#analytics(ObjectType)} rollup map into tall
     * {@code (object_type, axis, key, value)} triples — the {@code storageCatalog} row-per-axis idiom.
     * Tall, not wide, because the breakdown keys (status / L1-category / priority) are open-ended rather
     * than a fixed enum, so wide columns would be unstable across runs and across spaces.
     */
    static List<Object[]> flatten(ObjectType type, Map<String, Object> rollup) {
        List<Object[]> out = new ArrayList<>();
        String t = type.name();
        out.add(new Object[]{t, "scalar", "total", num(rollup.get("total"))});
        out.add(new Object[]{t, "scalar", "backlog", num(rollup.get("backlog"))});
        breakdown(out, t, "status", rollup.get("byStatus"));
        breakdown(out, t, "category", rollup.get("byCategory"));
        breakdown(out, t, "priority", rollup.get("byPriority"));
        nested(out, t, "cycle_time", rollup.get("cycleTime"), Map.of("count", "count", "avgMs", "avg_ms"));
        nested(out, t, "impact", rollup.get("impact"),
                Map.of("impactAmount", "impact_amount", "recordsAffected", "records_affected"));
        return out;
    }

    private static void breakdown(List<Object[]> out, String type, String axis, Object value) {
        if (!(value instanceof Map<?, ?> m)) return;
        m.forEach((k, v) -> out.add(new Object[]{type, axis, String.valueOf(k), num(v)}));
    }

    /** Like {@link #breakdown} but with a fixed camelCase → snake_case key mapping (a stable inner shape). */
    private static void nested(List<Object[]> out, String type, String axis, Object value, Map<String, String> keys) {
        if (!(value instanceof Map<?, ?> m)) return;
        keys.forEach((from, to) -> {
            Object v = m.get(from);
            if (v != null) out.add(new Object[]{type, axis, to, num(v)});
        });
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0d;
    }

    // ── write / retention ─────────────────────────────────────────────────────────────

    /** {@code key} is quoted: it is a column name we deliberately keep (the authored row contract), and
     *  quoting keeps it safe whatever the SQL dialect's reserved-word list does. */
    private static void writeParquet(Path parquet, Instant sampledAt, List<Object[]> rows) throws Exception {
        com.gamma.util.DuckDbUtil.loadDriver();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE analytics_sample (sampled_at TIMESTAMP, object_type VARCHAR, "
                        + "axis VARCHAR, \"key\" VARCHAR, value DOUBLE)");
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO analytics_sample VALUES (?,?,?,?,?)")) {
                Timestamp ts = Timestamp.from(sampledAt);
                for (Object[] r : rows) {
                    ps.setTimestamp(1, ts);
                    ps.setString(2, (String) r[0]);
                    ps.setString(3, (String) r[1]);
                    ps.setString(4, (String) r[2]);
                    ps.setDouble(5, (Double) r[3]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (Statement st = conn.createStatement()) {
                st.execute("COPY analytics_sample TO '"
                        + parquet.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                        + "' (FORMAT PARQUET)");
            }
        }
    }

    /**
     * Inline retention: forget {@code analytics_<epochMs>_out.parquet} samples older than
     * {@code retention_days}, keyed on the epoch embedded in the filename (the same sortable key
     * {@code storage_report} chose over an ISO string). {@code 0} keeps forever. A run is a handful of
     * rows, so this is hygiene that bounds the read glob, not a necessity.
     */
    private static int purge(Path storeDir, int retentionDays, Instant now) throws IOException {
        if (retentionDays <= 0) return 0;
        long cutoff = now.minusSeconds(retentionDays * 86_400L).toEpochMilli();
        int purged = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(storeDir, "analytics_*_out.parquet")) {
            for (Path p : files) {
                String stem = p.getFileName().toString();
                long stamp;
                try {
                    stamp = Long.parseLong(stem.substring("analytics_".length(), stem.length() - "_out.parquet".length()));
                } catch (NumberFormatException e) {
                    continue;   // not one of ours — leave it alone
                }
                if (stamp < cutoff && Files.deleteIfExists(p)) purged++;
            }
        }
        return purged;
    }

    // ── params ────────────────────────────────────────────────────────────────────────

    /** {@code types}: optional CSV filter, default all four. An unknown name fails the Run closed
     *  ({@link ObjectType#of} throws) rather than silently sampling a subset. */
    private static List<ObjectType> types(String csv) {
        if (csv == null || csv.isBlank()) return List.of(ObjectType.values());
        List<ObjectType> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            ObjectType t = ObjectType.of(s);
            if (!out.contains(t)) out.add(t);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("objects.analytics: 'types' listed no usable type");
        return out;
    }

    private int retentionDays() {
        String raw = cfg.opt("retention_days", "0");
        try {
            int days = Integer.parseInt(raw.trim());
            if (days < 0) throw new IllegalArgumentException(
                    "objects.analytics: retention_days must be >= 0 (0 = keep forever), got " + days);
            return days;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("objects.analytics: retention_days is not an integer: '"
                    + raw.trim().toLowerCase(Locale.ROOT) + "'");
        }
    }
}

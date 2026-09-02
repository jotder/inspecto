package com.gamma.job;

import com.gamma.consignment.ConsignmentSource;
import com.gamma.api.PublicApi;
import com.gamma.util.JdbcDrivers;
import com.gamma.util.JdbcRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Database-backed projection of {@link JobRun}s into a DuckDB table for job-execution reporting (T27,
 * §3.8). Mirrors {@link com.gamma.service.DbStatusStore}: plain JDBC over the bundled DuckDB engine
 * (no new dependency), a single shared {@link Connection} (job traffic is low-volume and a JDBC
 * connection is not thread-safe), schema created on open.
 *
 * <p>The durable {@code jobs_runs.csv} audit remains the write-time record; this store is the
 * <em>queryable</em> projection {@link JobService} writes through to when a backend is configured
 * ({@code -Djobs.backend=duckdb}). It answers the analytical questions the CSV can't cheaply serve —
 * success rate, p50/p95 duration, and failure trend over time — and backs the Jobs reporting pane.
 *
 * <p>Default-off: when no backend is configured the store is absent and the {@code /jobs/metrics}
 * endpoints 404; nothing about the existing in-memory history / CSV audit changes.
 */
@PublicApi(since = "4.0.0")
public final class DbJobRunStore implements AutoCloseable, com.gamma.util.BrowsableStore {

    private static final Logger log = LoggerFactory.getLogger(DbJobRunStore.class);
    private static final String T_RUNS = "inspecto_job_runs";
    private static final String T_SOURCES = "inspecto_job_run_sources";

    private final Connection conn;

    // ── raw table browser seam (BrowsableStore) — read-only, synchronized(this) ──
    @Override public String browseId() { return "jobs"; }
    @Override public String browseLabel() { return "Job Runs"; }
    @Override public java.util.List<String> browseTables() { return java.util.List.of(T_RUNS); }
    @Override public Connection browseConnection() { return conn; }
    /**
     * True when the connection is PostgreSQL. Percentiles are the one non-portable bit of SQL here:
     * DuckDB spells them {@code quantile_cont(col, p)} while PostgreSQL uses the SQL-standard ordered-set
     * aggregate {@code percentile_cont(p) WITHIN GROUP (ORDER BY col)}. Detected once at construction.
     */
    private final boolean postgres;

    /** Wrap an already-open JDBC connection; the schema is created if absent. Takes ownership (closed in {@link #close()}). */
    public DbJobRunStore(Connection conn) {
        this.conn = conn;
        this.postgres = JdbcDrivers.isPostgres(conn);
        initSchema();
    }

    /** Dialect-correct continuous-percentile expression for {@code duration_ms}, aliased to {@code alias}. */
    private String percentile(double p, String alias) {
        return postgres
                ? "percentile_cont(" + p + ") WITHIN GROUP (ORDER BY duration_ms) " + alias
                : "quantile_cont(duration_ms, " + p + ") " + alias;
    }

    /**
     * Open a job-run DB by JDBC URL (DuckDB primary, e.g. {@code jdbc:duckdb:jobs.db}; a self-registering
     * driver otherwise). Postgres is supported by the same code if its driver is on the classpath.
     */
    public static DbJobRunStore open(String url) throws SQLException {
        return new DbJobRunStore(JdbcDrivers.connect(url));
    }

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + T_RUNS + " ("
                    + "run_id VARCHAR, job VARCHAR, type VARCHAR, \"trigger\" VARCHAR, "
                    + "start_time VARCHAR, end_time VARCHAR, status VARCHAR, "
                    + "duration_ms BIGINT, message VARCHAR)");
            // X2 cross-lane provenance: which Consignments an at-rest run READ. A child table rather than a
            // column on the run row — the linkage is a LIST, the run record is nine scalars built at seven
            // call sites, and the reverse question ("which runs consumed consignment X") is one indexed
            // lookup here instead of a LIKE over a joined string.
            st.execute("CREATE TABLE IF NOT EXISTS " + T_SOURCES + " ("
                    + "run_id VARCHAR, consignment_id VARCHAR, pipeline VARCHAR, table_name VARCHAR)");
            st.execute("CREATE INDEX IF NOT EXISTS " + T_SOURCES + "_by_consignment ON " + T_SOURCES + " (consignment_id)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise job-run DB schema", e);
        }
    }

    /**
     * Record the Consignments {@code runId} read (X2). Best-effort like {@link #record}: the run row is the
     * record of the run, this is its provenance — a failure here is logged, never thrown into the job.
     */
    public synchronized void recordSources(String runId, List<ConsignmentSource> sources) {
        if (runId == null || sources == null || sources.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + T_SOURCES
                + " (run_id, consignment_id, pipeline, table_name) VALUES (?,?,?,?)")) {
            for (ConsignmentSource src : sources) {
                ps.setString(1, runId);
                ps.setString(2, src.consignmentId());
                ps.setString(3, src.pipeline());
                ps.setString(4, src.tableName());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warn("Could not record {} source consignment(s) for run {}: {}", sources.size(), runId, e.getMessage());
        }
    }

    /** The Consignments {@code runId} read, in recorded order — empty when none were recorded. */
    public synchronized List<ConsignmentSource> sources(String runId) {
        List<ConsignmentSource> out = new ArrayList<>();
        if (runId == null) return out;
        try (PreparedStatement ps = conn.prepareStatement("SELECT consignment_id, pipeline, table_name FROM "
                + T_SOURCES + " WHERE run_id = ? ORDER BY rowid")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new ConsignmentSource(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            log.warn("run sources query failed for {}: {}", runId, e.getMessage());
        }
        return out;
    }

    /** The reverse link: every run that read {@code consignmentId}, newest first — the same projection as {@link #recentRuns}. */
    public synchronized List<Map<String, Object>> runsDerivedFrom(String consignmentId) {
        if (consignmentId == null || consignmentId.isBlank()) return new ArrayList<>();
        String sql = "SELECT r.run_id AS \"runId\", r.job, r.type, r.\"trigger\", r.start_time AS \"startTime\","
                + " r.end_time AS \"endTime\", r.status, r.duration_ms AS \"durationMs\", r.message"
                + " FROM " + T_SOURCES + " s JOIN " + T_RUNS + " r ON r.run_id = s.run_id"
                + " WHERE s.consignment_id = ? ORDER BY r.start_time DESC, r.run_id DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, consignmentId);
            return JdbcRows.query(ps);
        } catch (SQLException e) {
            log.warn("runs-derived-from query failed for {}: {}", consignmentId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Append one job run. Best-effort: a write failure is logged, never thrown (the CSV audit is the record). */
    public synchronized void record(JobRun r) {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + T_RUNS
                + " (run_id, job, type, \"trigger\", start_time, end_time, status, duration_ms, message)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, r.runId());
            ps.setString(2, r.job());
            ps.setString(3, r.type());
            ps.setString(4, r.trigger());
            ps.setString(5, r.startTime());
            ps.setString(6, r.endTime());
            ps.setString(7, r.status());
            ps.setLong(8, r.durationMs());
            ps.setString(9, r.message());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not project job run {} to the job-run DB: {}", r.runId(), e.getMessage());
        }
    }

    /**
     * Aggregate execution metrics, optionally for one {@code job} (null/blank = all jobs): total runs,
     * success / failed counts, success rate (0..1), and p50 / p95 / mean duration in ms.
     */
    public synchronized Map<String, Object> metrics(String job) {
        boolean filtered = job != null && !job.isBlank();
        String sql = "SELECT count(*) total,"
                + " count(*) FILTER (WHERE status='SUCCESS') success,"
                + " count(*) FILTER (WHERE status<>'SUCCESS') failed,"
                + " " + percentile(0.5, "p50") + ","
                + " " + percentile(0.95, "p95") + ","
                + " avg(duration_ms) mean"
                + " FROM " + T_RUNS + (filtered ? " WHERE job = ?" : "");
        Map<String, Object> m = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filtered) ps.setString(1, job);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long total = rs.getLong("total");
                    long success = rs.getLong("success");
                    m.put("total", total);
                    m.put("success", success);
                    m.put("failed", rs.getLong("failed"));
                    m.put("successRate", total == 0 ? 0.0 : (double) success / total);
                    m.put("p50Ms", total == 0 ? 0.0 : rs.getDouble("p50"));
                    m.put("p95Ms", total == 0 ? 0.0 : rs.getDouble("p95"));
                    m.put("meanMs", total == 0 ? 0.0 : rs.getDouble("mean"));
                }
            }
        } catch (SQLException e) {
            log.warn("job metrics query failed: {}", e.getMessage());
        }
        return m;
    }

    /**
     * The most recent runs (newest first), optionally for one {@code job}; durable across restarts. Columns
     * are aliased to camelCase to match the rest of the JSON API (the frontend consumes these verbatim).
     */
    public synchronized List<Map<String, Object>> recentRuns(int limit, String job) {
        return recentRuns(limit, job, null, null);
    }

    /**
     * A page of recent runs (newest first), optionally for one {@code job}, resuming strictly after the
     * keyset marker {@code (afterStartTime, afterRunId)} — the same {@code (start_time DESC, run_id DESC)}
     * order as {@link #recentRuns(int, String)}. Both marker parts {@code null} ⇒ the first page (identical
     * to the two-arg form). Cursor pagination (api-contract-design §7); the marker is dialect-neutral SQL
     * (the explicit disjunction, not a row-value comparison) so it holds on DuckDB and PostgreSQL alike.
     */
    public synchronized List<Map<String, Object>> recentRuns(int limit, String job, String afterStartTime, String afterRunId) {
        boolean filtered = job != null && !job.isBlank();
        boolean keyset = afterStartTime != null && afterRunId != null;
        String sql = "SELECT run_id AS \"runId\", job, type, \"trigger\", start_time AS \"startTime\","
                + " end_time AS \"endTime\", status, duration_ms AS \"durationMs\", message"
                + " FROM " + T_RUNS + " WHERE 1=1"
                + (filtered ? " AND job = ?" : "")
                + (keyset ? " AND (start_time < ? OR (start_time = ? AND run_id < ?))" : "")
                + " ORDER BY start_time DESC, run_id DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (filtered) ps.setString(i++, job);
            if (keyset) { ps.setString(i++, afterStartTime); ps.setString(i++, afterStartTime); ps.setString(i++, afterRunId); }
            ps.setInt(i, Math.max(1, limit));
            return JdbcRows.query(ps);
        } catch (SQLException e) {
            log.warn("recent job runs query failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Total run count, optionally for one {@code job} — the {@code metadata.pagination.total}. */
    public synchronized long countRuns(String job) {
        boolean filtered = job != null && !job.isBlank();
        String sql = "SELECT count(*) FROM " + T_RUNS + (filtered ? " WHERE job = ?" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filtered) ps.setString(1, job);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            log.warn("job run count query failed: {}", e.getMessage());
            return 0L;
        }
    }

    /**
     * Failure trend by calendar day (newest first, up to {@code days} distinct days): each entry carries
     * {@code day}, {@code total} and {@code failed}. The day is the {@code start_time} date prefix (the
     * SQL alias is quoted because {@code day} is a DuckDB keyword; the JSON key is the plain {@code day}).
     */
    public synchronized List<Map<String, Object>> failureTrend(int days) {
        String sql = "SELECT substr(start_time,1,10) AS \"day\", count(*) AS total,"
                + " count(*) FILTER (WHERE status<>'SUCCESS') AS failed"
                + " FROM " + T_RUNS + " GROUP BY \"day\" ORDER BY \"day\" DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, days));
            return JdbcRows.query(ps);
        } catch (SQLException e) {
            log.warn("job failure-trend query failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** CHECKPOINT + VACUUM over the live connection, each best-effort — the {@code db_maintenance}
     *  task (System Maintenance MNT-9). DuckDB is single-writer, so maintenance must ride this
     *  store's own connection, never a second one. */
    public synchronized void maintenance() {
        for (String stmt : new String[]{"CHECKPOINT", "VACUUM"}) {
            try (Statement st = conn.createStatement()) {
                st.execute(stmt);
            } catch (SQLException e) {
                log.warn("job-run store maintenance: {} failed (continuing): {}", stmt, e.getMessage());
            }
        }
    }

    /**
     * Delete projected runs started before {@code cutoff} ({@code yyyy-MM-dd HH:mm:ss} — lexicographic
     * on the VARCHAR {@code start_time}) — the {@code runlog_prune} maintenance task (MNT-2a).
     * Returns the number of rows removed.
     */
    public synchronized int prune(String cutoff) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM " + T_RUNS + " WHERE start_time < ?")) {
            ps.setString(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("job-run prune failed: " + e.getMessage(), e);
        }
    }

    /** Count the rows {@link #prune(String)} would delete, deleting nothing — the dry-run estimate (MNT-1). */
    public synchronized int countPrunable(String cutoff) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + T_RUNS + " WHERE start_time < ?")) {
            ps.setString(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("job-run prune count failed: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing job-run DB: {}", e.getMessage());
        }
    }
}

package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.util.ConnectionSource;
import com.gamma.util.JdbcDrivers;
import com.gamma.util.JdbcRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>T21 — durable, queryable projection of the data-plane provenance matrix.</b> Persists the per-(node,
 * relationship) record counts a pipeline run emits ({@link ProvenanceRow}) into a DuckDB table so a
 * {@code GET /provenance} query (T22) can paint counts onto the {@link com.gamma.pipeline.PipelineGraph} edges of a
 * past run. Mirrors {@link com.gamma.job.DbJobRunStore}: plain JDBC over the bundled DuckDB engine (no new
 * dependency), a {@link ConnectionSource} each operation borrows a {@link Connection} from and returns
 * (low-volume, JDBC connections aren't thread-safe), schema created on open.
 *
 * <p>Default-off: activated only when {@code -Dprovenance.backend=duckdb} is set ({@link #close()} owned by the
 * {@link com.gamma.job.JobService} that holds it). When absent, the executor still runs with a {@code NONE}
 * collector and the {@code /provenance} endpoint 404s — nothing about the live path changes.
 */
@PublicApi(since = "4.0.0")
public final class DbProvenanceStore implements AutoCloseable, com.gamma.util.BrowsableStore {

    private static final Logger log = LoggerFactory.getLogger(DbProvenanceStore.class);
    private static final String T = "inspecto_pipeline_provenance";

    private final ConnectionSource src;

    // ── raw table browser seam (BrowsableStore) — read-only, one borrow per read ──
    @Override public String browseId() { return "provenance"; }
    @Override public String browseLabel() { return "Pipeline Provenance"; }
    @Override public java.util.List<String> browseTables() { return java.util.List.of(T); }
    @Override public ConnectionSource browseSource() { return src; }

    /** Wrap an already-open JDBC connection; the schema is created if absent. Takes ownership (closed in {@link #close()}). */
    public DbProvenanceStore(Connection conn) {
        this(JdbcDrivers.source(conn));
    }

    /** Borrow from {@code src} per operation; the schema is created if absent. Takes ownership (closed in {@link #close()}). */
    public DbProvenanceStore(ConnectionSource src) {
        this.src = src;
        initSchema();
    }

    /** Open a provenance DB by JDBC URL (DuckDB primary, e.g. {@code jdbc:duckdb:provenance.duckdb}). */
    public static DbProvenanceStore open(String url) throws SQLException {
        return new DbProvenanceStore(JdbcDrivers.source(url, null, null, "provenance"));
    }

    private void initSchema() {
        try {
            src.run(conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS " + T + " ("
                            + "pipeline_id VARCHAR, batch_id VARCHAR, node_id VARCHAR, rel VARCHAR, "
                            + "row_count BIGINT, run_ts VARCHAR)");
                    // PIPELINE-DRYRUN-1: additive migration, same rule as consignment_outputs' own columns —
                    // CREATE TABLE IF NOT EXISTS never widens a pre-existing table, and a row written before
                    // this column existed reads back NULL/false (a real run, not a dry one).
                    st.execute("ALTER TABLE " + T + " ADD COLUMN IF NOT EXISTS simulated BOOLEAN DEFAULT FALSE");
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise provenance DB schema", e);
        }
    }

    /** CHECKPOINT + VACUUM over one borrowed connection, each best-effort — the {@code db_maintenance}
     *  task (System Maintenance MNT-9). DuckDB is single-writer, so maintenance must ride this
     *  store's own source, never a second connection — and both statements share ONE borrow. */
    public void maintenance() {
        try {
            src.run(conn -> {
                for (String stmt : new String[]{"CHECKPOINT", "VACUUM"}) {
                    try (Statement st = conn.createStatement()) {
                        st.execute(stmt);
                    } catch (SQLException e) {
                        log.warn("provenance store maintenance: {} failed (continuing): {}", stmt, e.getMessage());
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("provenance store maintenance: borrow failed (continuing): {}", e.getMessage());
        }
    }

    /** Append all rows of one pipeline run. Best-effort: a write failure is logged, never thrown. */
    public void record(List<ProvenanceRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        try {
            src.run(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + T
                        + " (pipeline_id, batch_id, node_id, rel, row_count, run_ts, simulated) VALUES (?,?,?,?,?,?,?)")) {
                    for (ProvenanceRow r : rows) {
                        ps.setString(1, r.pipelineId());
                        ps.setString(2, r.batchId());
                        ps.setString(3, r.nodeId());
                        ps.setString(4, r.rel());
                        ps.setLong(5, r.rowCount());
                        ps.setString(6, r.runTs());
                        ps.setBoolean(7, r.simulated());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            });
        } catch (SQLException e) {
            log.warn("Could not project provenance for pipeline {} batch {}: {}",
                    rows.get(0).pipelineId(), rows.get(0).batchId(), e.getMessage());
        }
    }

    /**
     * The per-(node, relationship) counts of one run, keyed by {@code (pipelineId, batchId)}. Column labels are
     * camelCase to match the rest of the JSON API (the frontend consumes them verbatim, mapping each
     * {@code (nodeId, rel)} onto its outgoing {@code PipelineGraph} edge as the Sankey weight).
     */
    public List<Map<String, Object>> query(String pipelineId, String batchId) {
        String sql = "SELECT node_id AS \"nodeId\", rel, row_count AS \"rowCount\", "
                + "coalesce(simulated, FALSE) AS \"simulated\""
                + " FROM " + T + " WHERE pipeline_id = ? AND batch_id = ? ORDER BY node_id, rel";
        try {
            return src.with(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, pipelineId);
                    ps.setString(2, batchId);
                    return JdbcRows.query(ps);
                }
            });
        } catch (SQLException e) {
            log.warn("provenance query failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** The most recent runs of a pipeline (distinct {@code batchId}, newest first) — for picking a run to inspect. */
    public List<Map<String, Object>> batches(String pipelineId, int limit) {
        String sql = "SELECT batch_id AS \"batchId\", max(run_ts) AS \"runTs\", sum(row_count) AS \"totalRows\""
                + " FROM " + T + " WHERE pipeline_id = ? GROUP BY batch_id ORDER BY \"runTs\" DESC LIMIT ?";
        try {
            return src.with(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, pipelineId);
                    ps.setInt(2, Math.max(1, limit));
                    return JdbcRows.query(ps);
                }
            });
        } catch (SQLException e) {
            log.warn("provenance batches query failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void close() {
        try {
            src.close();
        } catch (RuntimeException e) {
            log.warn("Error closing provenance DB: {}", e.getMessage());
        }
    }
}

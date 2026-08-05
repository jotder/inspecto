package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.util.JdbcDrivers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Phase 4 Slice 2 — the durable per-file stage-progression registry.</b> Persists one row per
 * (file, {@link FileStage}) transition {@code BatchProcessor.finalizeSource} crosses, so "where is
 * file X right now" is an append-only, queryable fact instead of a re-read of the manifest and a
 * guess about how far a crashed commit got.
 *
 * <p>Mirrors {@link DbConsignmentOutputStore} exactly: plain JDBC over the bundled DuckDB engine, a
 * single shared {@link Connection}, schema created on open, every mutator {@code synchronized},
 * default-off behind {@code -Dfile.stages.backend}. <b>Insert-only</b> — a stage is a fact about a
 * point in time, never updated or superseded; the history for one file is its own progression.
 *
 * <p><b>Absence is not degraded correctness.</b> With no store registered, {@link FileStages#record}
 * is a no-op and the crash-safe ordering {@code finalizeSource} already enforces is unchanged — this
 * table only adds the queryable index, exactly the existence/state split
 * {@link DbConsignmentOutputStore} documents.
 */
@PublicApi(since = "5.1.0")
public final class DbFileStageStore implements AutoCloseable, com.gamma.util.BrowsableStore {

    private static final Logger log = LoggerFactory.getLogger(DbFileStageStore.class);
    private static final String T = "file_stages";
    private static final String COLS = "source_id, relative_path, batch_id, stage, recorded_at";

    private final Connection conn;

    @Override public String browseId() { return "file-stages"; }
    @Override public String browseLabel() { return "File Stages"; }
    @Override public List<String> browseTables() { return List.of(T); }
    @Override public Connection browseConnection() { return conn; }

    /** Wrap an already-open JDBC connection; the schema is created if absent. Takes ownership. */
    public DbFileStageStore(Connection conn) {
        this.conn = conn;
        initSchema();
    }

    /** Open a registry by JDBC URL (DuckDB primary, e.g. {@code jdbc:duckdb:file-stages.duckdb}). */
    public static DbFileStageStore open(String url) throws SQLException {
        return new DbFileStageStore(JdbcDrivers.connect(url));
    }

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + T + " ("
                    + "source_id VARCHAR, relative_path VARCHAR, batch_id VARCHAR, "
                    + "stage VARCHAR, recorded_at VARCHAR)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise file-stages schema", e);
        }
    }

    /**
     * Append one stage transition per file. <b>Best-effort: a write failure is logged, never
     * thrown</b> — this is an index beside the manifest, so losing a row must not fail a batch that
     * has already committed its data.
     */
    public synchronized void record(List<FileStageRecord> records) {
        if (records == null || records.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + T + " (" + COLS + ") VALUES (?,?,?,?,?)")) {
            for (FileStageRecord r : records) {
                ps.setString(1, r.sourceId());
                ps.setString(2, r.relativePath());
                ps.setString(3, r.batchId());
                ps.setString(4, r.stage().name());
                ps.setString(5, r.recordedAt());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warn("Could not record {} file-stage row(s): {}", records.size(), e.getMessage());
        }
    }

    /**
     * Every stage recorded for one file, oldest first — the "where is file X" lookup, keyed by the
     * same {@code (sourceId, relativePath)} pair {@code AcquisitionLedger} uses.
     */
    public synchronized List<FileStageRecord> stages(String sourceId, String relativePath) {
        String sql = "SELECT " + COLS + " FROM " + T
                + " WHERE source_id = ? AND relative_path = ? ORDER BY recorded_at";
        List<FileStageRecord> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceId);
            ps.setString(2, relativePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            log.warn("file-stages query failed for {}/{}: {}", sourceId, relativePath, e.getMessage());
        }
        return out;
    }

    private static FileStageRecord map(ResultSet rs) throws SQLException {
        return new FileStageRecord(
                rs.getString("source_id"), rs.getString("relative_path"), rs.getString("batch_id"),
                FileStage.valueOf(rs.getString("stage")), rs.getString("recorded_at"));
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing file-stages DB: {}", e.getMessage());
        }
    }
}

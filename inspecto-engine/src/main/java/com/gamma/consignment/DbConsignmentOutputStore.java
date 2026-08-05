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
 * <b>§11.3 — the durable output-file registry.</b> Persists one row per output file a Consignment writes, so
 * "every file C wrote, across all partitions" is answerable with lifecycle state attached. This is the
 * catalog substitute the no-catalog decision (§5) implies.
 *
 * <p>Mirrors {@link com.gamma.pipeline.exec.DbProvenanceStore} and {@link com.gamma.job.DbJobRunStore}: plain
 * JDBC over the bundled DuckDB engine (no new dependency), a single shared {@link Connection} (low-volume,
 * JDBC connections aren't thread-safe), schema created on open, every mutator {@code synchronized}.
 *
 * <p><b>Default-off</b>, activated by {@code -Dconsignment.outputs.backend}. When absent, nothing about the
 * live write path changes — outputs are still revealed and still recorded in the JSON manifest, exactly as
 * today. That fail-open contract is why the manifest stays authoritative for <em>existence</em> while this
 * table is authoritative for <em>state</em>: a store that can legitimately be absent must never be the only
 * record that a file exists.
 *
 * <p><b>State transitions.</b> {@link #supersede} and {@link #markCompactedAway} arrived with their call sites
 * ({@code ReprocessCommand}, {@code PartitionCompactor}) rather than ahead of them. Both are <b>path- or
 * id-keyed {@code UPDATE}s that never insert</b>: a row is only ever created by {@link #record} at the moment a
 * file is revealed, so a state flip cannot resurrect a file the registry never saw. Neither transition is
 * reversible, and neither is a substitute for the JSON manifest — see the existence/state split above.
 */
@PublicApi(since = "5.0.0")
public final class DbConsignmentOutputStore implements AutoCloseable, com.gamma.util.BrowsableStore {

    private static final Logger log = LoggerFactory.getLogger(DbConsignmentOutputStore.class);
    private static final String T = "consignment_outputs";

    /** §11.3's sketch names the count column {@code rows}; this uses {@code row_count} instead — {@code ROWS}
     *  is a SQL keyword (window frames) that would need quoting at every use, and {@code row_count} is already
     *  the spelling in {@code inspecto_pipeline_provenance} and the {@code lineage} CSV. */
    private static final String COLS =
            "consignment_id, run_id, table_name, partition_key, record_day, "
                    + "path, row_count, bytes, written_at, generation, state, schema_fingerprint";

    private final Connection conn;

    // ── raw table browser seam (BrowsableStore) — read-only, synchronized(this) ──
    @Override public String browseId() { return "consignment-outputs"; }
    @Override public String browseLabel() { return "Consignment Outputs"; }
    @Override public List<String> browseTables() { return List.of(T); }
    @Override public Connection browseConnection() { return conn; }

    /** Wrap an already-open JDBC connection; the schema is created if absent. Takes ownership (closed in {@link #close()}). */
    public DbConsignmentOutputStore(Connection conn) {
        this.conn = conn;
        initSchema();
    }

    /** Open a registry by JDBC URL (DuckDB primary, e.g. {@code jdbc:duckdb:consignment-outputs.duckdb}). */
    public static DbConsignmentOutputStore open(String url) throws SQLException {
        return new DbConsignmentOutputStore(JdbcDrivers.connect(url));
    }

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + T + " ("
                    + "consignment_id VARCHAR, run_id VARCHAR, table_name VARCHAR, "
                    + "partition_key VARCHAR, record_day VARCHAR, path VARCHAR, "
                    + "row_count BIGINT, bytes BIGINT, written_at VARCHAR, "
                    + "generation INTEGER, state VARCHAR, schema_fingerprint VARCHAR)");
            // §3.4.3 additive migration: CREATE TABLE IF NOT EXISTS never widens a pre-existing table, so a
            // registry created before the column existed gets it added here; existing rows read back NULL.
            st.execute("ALTER TABLE " + T + " ADD COLUMN IF NOT EXISTS schema_fingerprint VARCHAR");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise consignment-outputs schema", e);
        }
    }

    /** CHECKPOINT + VACUUM over the live connection, each best-effort — the {@code db_maintenance} task.
     *  DuckDB is single-writer, so maintenance must ride this store's own connection, never a second one. */
    public synchronized void maintenance() {
        for (String stmt : new String[]{"CHECKPOINT", "VACUUM"}) {
            try (Statement st = conn.createStatement()) {
                st.execute(stmt);
            } catch (SQLException e) {
                log.warn("consignment-outputs maintenance: {} failed (continuing): {}", stmt, e.getMessage());
            }
        }
    }

    /**
     * Append the output files of one Consignment. <b>Best-effort: a write failure is logged, never thrown</b> —
     * the registry is an index beside the manifest, so losing a row must not fail a Consignment that has
     * already landed its data. §5.4's commit discipline stays the thing that guarantees visibility.
     */
    public synchronized void record(List<ConsignmentOutput> outputs) {
        if (outputs == null || outputs.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + T + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (ConsignmentOutput o : outputs) {
                ps.setString(1, o.consignmentId());
                ps.setString(2, o.runId());
                ps.setString(3, o.tableName());
                ps.setString(4, o.partitionKey());
                ps.setString(5, o.recordDay());
                ps.setString(6, o.path());
                ps.setLong(7, o.rows());
                ps.setLong(8, o.bytes());
                ps.setString(9, o.writtenAt());
                ps.setInt(10, o.generation());
                ps.setString(11, o.state().name());
                ps.setString(12, o.schemaFingerprint());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warn("Could not register {} output file(s) for consignment {}: {}",
                    outputs.size(), outputs.get(0).consignmentId(), e.getMessage());
        }
    }

    /**
     * Every file one Consignment wrote, across all partitions, newest first — the §5.3 lookup.
     *
     * <p>Returns <b>all</b> states, not just {@code LIVE}: reprocessing has to know a file was compacted away
     * (so it can take the partition-rewrite path of §6.2 rather than a no-op unlink), and filtering that out
     * here would hide exactly the case the registry exists to expose.
     */
    public synchronized List<ConsignmentOutput> outputs(String consignmentId) {
        String sql = "SELECT " + COLS + " FROM " + T
                + " WHERE consignment_id = ? ORDER BY written_at DESC, path";
        List<ConsignmentOutput> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, consignmentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            log.warn("consignment-outputs query failed for {}: {}", consignmentId, e.getMessage());
        }
        return out;
    }

    /**
     * Mark every still-{@code LIVE} file of one Consignment {@code SUPERSEDED} — the state flip that belongs
     * beside {@code ManifestStore.supersede}, for when a Consignment's output is replaced by a reprocess.
     *
     * <p>Only {@code LIVE} rows move. A {@code COMPACTED_AWAY} row must keep that state: it is the evidence
     * that the file's rows now live inside a merged file, which is precisely what a reprocess needs to know to
     * take the §6.2 partition-rewrite path instead of a no-op unlink.
     *
     * @return how many rows changed state; {@code 0} is normal when the registry is default-off or the
     *         Consignment predates it, and is never an error.
     */
    public synchronized int supersede(String consignmentId) {
        return update("UPDATE " + T + " SET state = 'SUPERSEDED' WHERE consignment_id = ? AND state = 'LIVE'",
                consignmentId, "supersede consignment " + consignmentId);
    }

    /**
     * Mark the given output paths {@code COMPACTED_AWAY} — the files a compaction merged and unlinked.
     *
     * <p><b>No replacement row is inserted, deliberately.</b> A merged file holds rows from many Consignments,
     * so no single {@code consignment_id} owns it and any row claiming one would be a fiction; per-Consignment
     * {@code row_count}s inside it are unknowable without re-reading the file. The pair
     * {@code (state=COMPACTED_AWAY, partition_key)} is already sufficient for the only consumer that cares —
     * §6.2 rewrites the whole partition — so the schema needs no {@code replaced_by} column for this.
     *
     * @param paths the exact revealed paths as recorded by {@link #record}; unmatched paths are silently
     *              ignored, since compaction legitimately merges files older than the registry itself.
     * @return how many rows changed state.
     */
    public synchronized int markCompactedAway(List<String> paths) {
        if (paths == null || paths.isEmpty()) return 0;
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (String p : paths) wanted.add(norm(p));

        int changed = 0;
        try {
            // Resolve stored spellings to the same normalised form before comparing — see norm(). Matching in
            // SQL cannot work in both directions, because the column would have to be normalised too.
            List<String> matches = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT DISTINCT path FROM " + T + " WHERE state <> 'COMPACTED_AWAY'")) {
                while (rs.next()) {
                    String stored = rs.getString("path");
                    if (stored != null && wanted.contains(norm(stored))) matches.add(stored);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE " + T
                    + " SET state = 'COMPACTED_AWAY' WHERE path = ? AND state <> 'COMPACTED_AWAY'")) {
                for (String stored : matches) {
                    ps.setString(1, stored);
                    changed += ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.warn("Could not mark {} path(s) compacted away: {}", paths.size(), e.getMessage());
        }
        return changed;
    }

    /**
     * A path's absolute, normalised form — used <b>only to widen a match</b>, never to rewrite what is stored.
     *
     * <p>The two sides cannot be relied on to agree on spelling: {@code PartitionWriter.reveal} derives its path
     * from the configured output directory while {@code PartitionCompactor} derives its from its own {@code dir}
     * parameter, and either may be relative. Two spellings of one file would make a state flip match zero rows
     * and still report success — exactly the silent failure this table exists to prevent, so
     * {@link #markCompactedAway} compares <em>normalised forms on both sides</em>. Binding two spellings into
     * one {@code WHERE} clause does not achieve that: an already-absolute probe normalises to itself, so a row
     * stored relative would still never match.
     *
     * <p><b>Why {@link #record} does not normalise.</b> Absolutising at write time would make the stored value
     * depend on the writing process's working directory, so a registry read after a restart under a different
     * cwd would resolve to a different file. Storing the caller's own spelling keeps the row durable; widening
     * happens at compare time, where being wrong costs a missed match rather than a bad path.
     */
    private static String norm(String path) {
        if (path == null) return null;
        try {
            return java.nio.file.Path.of(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException invalid) {
            return path;
        }
    }

    /** Best-effort single-parameter {@code UPDATE}: a failed state flip is logged, never thrown — same
     *  fail-open contract as {@link #record}, since this table is an index and not the record of existence. */
    private int update(String sql, String param, String what) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not {}: {}", what, e.getMessage());
            return 0;
        }
    }

    private static ConsignmentOutput map(ResultSet rs) throws SQLException {
        return new ConsignmentOutput(
                rs.getString("consignment_id"),
                rs.getString("run_id"),
                rs.getString("table_name"),
                rs.getString("partition_key"),
                rs.getString("record_day"),
                rs.getString("path"),
                rs.getLong("row_count"),
                rs.getLong("bytes"),
                rs.getString("written_at"),
                rs.getInt("generation"),
                state(rs.getString("state")),
                rs.getString("schema_fingerprint"));
    }

    /** Unknown state text degrades to {@code LIVE} rather than throwing: a row written by a newer build must
     *  not make an older one unable to list a Consignment's files at all. */
    private static ConsignmentOutput.State state(String raw) {
        if (raw == null) return ConsignmentOutput.State.LIVE;
        try {
            return ConsignmentOutput.State.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("unknown consignment-output state '{}' — treating as LIVE", raw);
            return ConsignmentOutput.State.LIVE;
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing consignment-outputs DB: {}", e.getMessage());
        }
    }
}

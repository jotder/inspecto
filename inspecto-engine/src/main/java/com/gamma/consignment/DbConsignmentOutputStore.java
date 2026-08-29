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
import java.util.Optional;

/**
 * <b>§11.3 — the durable output-file registry.</b> Persists one row per output file a Consignment writes, so
 * "every file C wrote, across all partitions" is answerable with lifecycle state attached. This is the
 * catalog substitute the no-catalog decision (§5) implies.
 *
 * <p>Mirrors {@link com.gamma.pipeline.exec.DbProvenanceStore} and {@link com.gamma.job.DbJobRunStore}: plain
 * JDBC over the bundled DuckDB engine (no new dependency), a single shared {@link Connection} (low-volume,
 * JDBC connections aren't thread-safe), schema created on open, every mutator {@code synchronized}.
 *
 * <p><b>On by default since 2026-08-10</b> (addressing plan D1) — {@code -Dconsignment.outputs.backend=duckdb}
 * unless set otherwise, and {@code none} still turns it off. It was flipped for a shipped bug, not for the
 * addressing work: {@code ReprocessCommand}'s refusal to reprocess a Consignment whose output was compacted
 * away — the alternative being silent row duplication — is decidable only from this table's
 * {@code COMPACTED_AWAY} rows, so default-off meant that fix was switched off everywhere.
 *
 * <p><b>Being optional is still part of the contract.</b> A configured {@code none}, and any failed open,
 * degrade to no registry — and when absent nothing about the live write path changes: outputs are still
 * revealed and still recorded in the JSON manifest. That fail-open contract is why the manifest stays
 * authoritative for <em>existence</em> while this table is authoritative for <em>state</em>: a store that can
 * legitimately be absent must never be the only record that a file exists. Any future reader must therefore
 * <b>filter</b> a list it obtained elsewhere, never <em>produce</em> the list — a file with no row here is
 * unknown, never absent.
 *
 * <p><b>State transitions.</b> {@link #supersede} and {@link #markCompactedAway} arrived with their call sites
 * ({@code ReprocessCommand}, {@code PartitionCompactor}) rather than ahead of them. Both are <b>path- or
 * id-keyed {@code UPDATE}s that never insert</b>: a row is only ever created by {@link #record} at the moment a
 * file is revealed, so a state flip cannot resurrect a file the registry never saw. Neither transition is
 * reversible, and neither is a substitute for the JSON manifest — see the existence/state split above.
 */
@PublicApi(since = "4.0.0")
public final class DbConsignmentOutputStore implements AutoCloseable, com.gamma.util.BrowsableStore {

    private static final Logger log = LoggerFactory.getLogger(DbConsignmentOutputStore.class);
    private static final String T = "consignment_outputs";

    /** §11.3's sketch names the count column {@code rows}; this uses {@code row_count} instead — {@code ROWS}
     *  is a SQL keyword (window frames) that would need quoting at every use, and {@code row_count} is already
     *  the spelling in {@code inspecto_pipeline_provenance} and the {@code lineage} CSV. */
    private static final String COLS =
            "consignment_id, run_id, table_name, partition_key, record_day, "
                    + "path, row_count, bytes, written_at, generation, state, schema_fingerprint, "
                    + "event_time_min, event_time_max, event_time_spread_ms, producer";

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
                    + "generation INTEGER, state VARCHAR, schema_fingerprint VARCHAR, "
                    + "event_time_min VARCHAR, event_time_max VARCHAR, "
                    + "event_time_spread_ms BIGINT, producer VARCHAR)");
            // §3.4.3 additive migration: CREATE TABLE IF NOT EXISTS never widens a pre-existing table, so a
            // registry created before the column existed gets it added here; existing rows read back NULL.
            st.execute("ALTER TABLE " + T + " ADD COLUMN IF NOT EXISTS schema_fingerprint VARCHAR");
            // §3.1 addressing columns, same additive rule. A pre-existing registry keeps every row it has and
            // reads NULL bounds for them — which the Selector must read as "unknown, cannot prune", never as
            // "no rows in range" (see ConsignmentOutput#bounds).
            st.execute("ALTER TABLE " + T + " ADD COLUMN IF NOT EXISTS event_time_min VARCHAR");
            st.execute("ALTER TABLE " + T + " ADD COLUMN IF NOT EXISTS event_time_max VARCHAR");
            st.execute("ALTER TABLE " + T + " ADD COLUMN IF NOT EXISTS event_time_spread_ms BIGINT");
            st.execute("ALTER TABLE " + T + " ADD COLUMN IF NOT EXISTS producer VARCHAR");
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
                "INSERT INTO " + T + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (ConsignmentOutput o : outputs) {
                EventTimeBounds b = o.bounds();
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
                ps.setString(13, b == null ? null : b.min());
                ps.setString(14, b == null ? null : b.max());
                // null, not 0: a spread of 0 is a real value (one event time in the file), so an absent
                // bound has to read back as absent rather than as an instantaneous file.
                if (b == null) ps.setNull(15, java.sql.Types.BIGINT); else ps.setLong(15, b.spreadMs());
                ps.setString(16, o.producer());
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
     * Every path this registry says is <b>no longer readable</b> — {@code SUPERSEDED} or {@code COMPACTED_AWAY},
     * across every stream — for {@link ConsignmentSelector} to subtract from a glob (addressing §7-A).
     *
     * <p><b>A path with any {@code LIVE} row is never returned, whatever else it carries.</b> Output naming is
     * not one-file-per-Consignment (§2.4): a full recompute rewrites a stable path in place, so the same path
     * legitimately owns an old {@code SUPERSEDED} row and a current {@code LIVE} one. Returning it because a
     * dead row mentions it would exclude live data from every read — the failure this method exists to prevent,
     * inverted. Row state is per-registration; readability is per-path, and only the latter belongs here.
     *
     * <p>Not filtered by stream or by directory, deliberately: the caller intersects these against the files a
     * glob actually matched, so a path that belongs to a different store simply never meets one. Adding a
     * {@code table_name} filter would mean mapping a glob root back to a logical table, which nothing can do
     * reliably.
     */
    public synchronized List<String> unreadablePaths() {
        String sql = "SELECT DISTINCT t.path FROM " + T + " t WHERE coalesce(t.state, 'LIVE') <> 'LIVE' "
                + "AND NOT EXISTS (SELECT 1 FROM " + T + " l WHERE l.path = t.path "
                + "AND coalesce(l.state, 'LIVE') = 'LIVE')";
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String path = rs.getString(1);
                if (path != null) out.add(path);
            }
        } catch (SQLException e) {
            log.warn("consignment-outputs unreadable-path query failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Per-producer high water for one stream — the aggregation {@link StreamWatermark} folds into a watermark
     * (consignment addressing §3.6). One row per distinct {@code producer} that has written to {@code tableName},
     * including the unattributed group (see {@link ProducerHighWater#producer()}).
     *
     * <p><b>{@code SUPERSEDED} rows are excluded; {@code COMPACTED_AWAY} rows are not.</b> The plan's sketch says
     * "over COMMITTED rows", but there is no such state here (the enum is {@code LIVE}/{@code SUPERSEDED}/
     * {@code COMPACTED_AWAY}) — and the two non-live states are opposites for this purpose. Compacted rows were
     * genuinely delivered and their data still exists inside a merged file, so their event time must keep
     * counting or a compaction would make the watermark travel <em>backwards</em>. Superseded rows were replaced
     * by a reprocess that wrote its own rows; counting them could claim delivery the current data does not
     * support, which is the one direction a watermark must never err in.
     *
     * <p>Neither aggregate can be a plain {@code max()} over the stored text. {@code written_at} is
     * {@code Instant.toString()}, whose fractional-second digits vary, so its lexicographic order is not its
     * chronological one ({@code …33.1Z} sorts after {@code …33.12Z}) — hence the cast to a real timestamp and the
     * epoch-millis projection, which crosses JDBC with no format or zone left to reinterpret. {@code TRY_CAST}
     * keeps one malformed value from failing the whole query; it reads back as an unknown last-seen, which the
     * fold treats as in-horizon. {@code event_time_max} is safe to {@code max()} as text only because §3.1 writes
     * it in a fixed-width format where the two orders coincide.
     */
    public synchronized List<ProducerHighWater> producerHighWater(String tableName) {
        String sql = "SELECT producer, max(event_time_max) AS event_time_max, "
                + "epoch_ms(max(TRY_CAST(written_at AS TIMESTAMPTZ))) AS last_seen_ms FROM " + T
                + " WHERE table_name = ? AND coalesce(state, 'LIVE') <> 'SUPERSEDED' GROUP BY producer";
        List<ProducerHighWater> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // wasNull() reports on the most recent get*, so it has to be read immediately — asking after
                    // the other columns would answer for one of those and turn an absent instant into the epoch.
                    long ms = rs.getLong("last_seen_ms");
                    java.time.Instant lastSeen = rs.wasNull() ? null : java.time.Instant.ofEpochMilli(ms);
                    out.add(new ProducerHighWater(rs.getString("producer"), rs.getString("event_time_max"),
                            lastSeen));
                }
            }
        } catch (SQLException e) {
            log.warn("consignment-outputs producer high water failed for {}: {}", tableName, e.getMessage());
        }
        return out;
    }

    /**
     * The event-time range of everything currently live in one stream — the {@code min} half
     * {@link #producerHighWater} does not expose, folded across every file of {@code tableName} rather than
     * grouped by producer. Backs {@code $upstream(<job>).artifact(<name>).event_time_min|event_time_max}
     * (job-parameter-contract §5-B), which a downstream Job binds as an incremental window.
     *
     * <p><b>Derived on read, never stored</b>, and that is the whole point of the accessor. §4's revisions mean
     * a full recompute writes a new revision and supersedes the old one, so any range copied into a Run
     * Artifact at write time would go on describing a superseded revision. Reading it here means the answer
     * moves when the data does.
     *
     * <p>Row selection is {@link #producerHighWater}'s predicate verbatim — {@code SUPERSEDED} excluded,
     * {@code COMPACTED_AWAY} included — for the same reasons documented there: compacted rows were genuinely
     * delivered and still exist inside a merged file, while superseded rows were replaced by a reprocess that
     * wrote its own.
     *
     * <p>Empty when nothing is live, when no file carries bounds, or when the query fails — never a partial
     * range. A half-known window is worse than none: the caller would silently scan from the epoch or to the
     * end of time. {@code spreadMs} is computed rather than summed from the stored per-file spreads, which
     * measure single files and do not compose.
     */
    public synchronized Optional<EventTimeBounds> bounds(String tableName) {
        String sql = "SELECT min(event_time_min) AS lo, max(event_time_max) AS hi, "
                + "epoch_ms(TRY_CAST(max(event_time_max) AS TIMESTAMP)) "
                + "- epoch_ms(TRY_CAST(min(event_time_min) AS TIMESTAMP)) AS spread_ms FROM " + T
                + " WHERE table_name = ? AND coalesce(state, 'LIVE') <> 'SUPERSEDED'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String lo = rs.getString("lo");
                String hi = rs.getString("hi");
                // wasNull() answers for the most recent get*, so the spread has to be read and tested here —
                // asking after another column would report on that one and turn an unknown spread into 0.
                long spread = rs.getLong("spread_ms");
                long spreadMs = rs.wasNull() ? 0L : spread;
                if (lo == null || hi == null) return Optional.empty();
                return Optional.of(new EventTimeBounds(lo, hi, spreadMs));
            }
        } catch (SQLException e) {
            log.warn("consignment-outputs bounds query failed for {}: {}", tableName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Mark every still-{@code LIVE} file of one Consignment {@code SUPERSEDED} — the state flip that belongs
     * beside {@code ManifestStore.supersede}, for when a Consignment's output is replaced by a reprocess.
     *
     * <p>Only {@code LIVE} rows move. A {@code COMPACTED_AWAY} row must keep that state: it is the evidence
     * that the file's rows now live inside a merged file, which is precisely what a reprocess needs to know.
     *
     * <p>⚠ <b>What it does with that knowledge today is REFUSE</b>, not rewrite:
     * {@code ReprocessCommand.refuseIfCompacted} throws <i>"Refusing to reprocess … output file(s) were merged
     * away by compaction"</i>, because step 1's {@code deleteIfExists} no-ops on a path compaction already
     * unlinked while the members are restored — so re-ingest would <b>duplicate</b> the rows. The §6.2
     * partition-rewrite path is the design intent, not the shipped behaviour; do not read this state as
     * meaning a compacted Consignment can be reprocessed.
     *
     * @return how many rows changed state; {@code 0} is normal when the registry is default-off or the
     *         Consignment predates it, and is never an error.
     */
    public synchronized int supersede(String consignmentId) {
        return update("UPDATE " + T + " SET state = 'SUPERSEDED' WHERE consignment_id = ? AND state = 'LIVE'",
                consignmentId, "supersede consignment " + consignmentId);
    }

    /**
     * Mark every still-{@code LIVE} row for {@code tableName} that does <b>not</b> belong to
     * {@code keepConsignmentId} as {@code SUPERSEDED} — the state flip a full recompute needs (addressing
     * step 6). The recompute has just rewritten the whole table under its own path, so every earlier
     * revision's files are stale the moment it lands, and {@link ConsignmentSelector} then excludes them.
     *
     * <p><b>Nothing is deleted.</b> The files stay on disk until a retirement pass removes them, precisely so a
     * read that was already open finishes on the revision it started with. Deleting here would reintroduce the
     * hazard superseding exists to close, and on Windows the reader's open handle would fail the <em>writer</em>
     * instead.
     *
     * <p><b>Scoped to one table, not one consignment</b>, which is the opposite of {@link #supersede} — and it
     * has to be: a recompute supersedes work it did not do, spread across however many earlier runs wrote this
     * store. {@code keepConsignmentId} is required rather than optional, because a call that forgot it would
     * mark the recompute's own freshly written files stale and empty every read of the table.
     *
     * @return how many rows changed state; {@code 0} is normal for a table's first recompute under the registry.
     */
    public synchronized int supersedeOtherRevisions(String tableName, String keepConsignmentId) {
        if (tableName == null || keepConsignmentId == null)
            throw new IllegalArgumentException("supersedeOtherRevisions needs both a table and the "
                    + "consignment to keep — a null keep would supersede the revision that just landed");
        try (PreparedStatement ps = conn.prepareStatement("UPDATE " + T + " SET state = 'SUPERSEDED' "
                + "WHERE table_name = ? AND consignment_id <> ? AND coalesce(state, 'LIVE') = 'LIVE'")) {
            ps.setString(1, tableName);
            ps.setString(2, keepConsignmentId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not supersede earlier revisions of {}: {}", tableName, e.getMessage());
            return 0;
        }
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
    static String norm(String path) {
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
                rs.getString("schema_fingerprint"),
                bounds(rs),
                rs.getString("producer"));
    }

    /** §3.1 bounds, or {@code null} when this row carries none — a row written before the columns existed, or a
     *  write path that established no event time. Keyed off {@code event_time_min}: the three are written
     *  together, so a null there means the whole triple is absent rather than partially populated. */
    private static EventTimeBounds bounds(ResultSet rs) throws SQLException {
        String min = rs.getString("event_time_min");
        if (min == null) return null;
        return new EventTimeBounds(min, rs.getString("event_time_max"), rs.getLong("event_time_spread_ms"));
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

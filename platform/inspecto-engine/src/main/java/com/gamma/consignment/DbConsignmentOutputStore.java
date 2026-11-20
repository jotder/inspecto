package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.util.ConnectionSource;
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
import java.util.Map;
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
 *
 * <p><b>Identity (run-model plan slice 4, 2026-09-13).</b> {@code UNIQUE (consignment_id, path, run_id)} —
 * one Consignment writing one path in one Run is <em>one</em> file, and re-recording it is
 * <b>{@code ON CONFLICT DO UPDATE}</b>, not {@code DO NOTHING}. 🔴 That is a write-semantics decision, not a
 * dedupe detail: one Run can legitimately write the same path twice, because two sinks may target one store
 * ({@code PartitionSinkWriter} sums {@code rowsByStore}) and {@code PartitionWriter} reveals each partition
 * under a stable {@code <baseName>_out.<ext>} with {@code OVERWRITE_OR_IGNORE}. The second write carries a
 * <b>different {@code row_count} for the same path</b> — and the file on disk really was overwritten, so
 * last-writer-wins matches the filesystem while {@code DO NOTHING} would keep a count for content that no
 * longer exists.
 *
 * <p>⚠ <b>{@code run_id} is deliberately still nullable</b>, though every production path has supplied one
 * since slice 3. ✅ <b>Re-verified 2026-09-15</b>, because the opposite was asserted and filed as a P1: the
 * ingest lane registers through {@code ConsignmentIngestor.finalizeSource} with the id
 * {@code ConsignmentIngestor.process} mints, the pipeline-job lane through {@code PipelineJobRunner}'s
 * {@code RunContext}, and the summary/derived lanes through {@code ConsignmentProcessJobType}. The one
 * null-supplying construction — {@code ConsignmentGraphRunner}'s two-arg {@code run} overload — has no
 * production caller, and now says so at its own declaration. {@code NULL ≠ NULL} in a UNIQUE constraint on both DuckDB and Postgres, so a null-run row is
 * exempt from the key — but {@code NOT NULL} would be strictly worse than that exemption: {@link #record} is
 * fail-open, so a violation would drop a <em>landed</em> file's row into a WARN, and a registry written before
 * slice 3 could not be rebuilt at all (its legacy rows have no run identity, and inventing one would report a
 * Run that never existed).
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
                    + "path, row_count, bytes, written_at, state, schema_fingerprint, "
                    + "event_time_min, event_time_max, event_time_spread_ms, producer";

    /** Every column except the three key columns, rewritten from the incoming row — last-writer-wins, because
     *  the file on disk was genuinely overwritten (see the class javadoc). ⚠ {@code state} is in here: a
     *  re-revealed file is live again, and leaving a {@code COMPACTED_AWAY} flag standing over content that
     *  now exists would be the same stale-row defect from the other direction. */
    private static final String UPSERT_SET =
            "table_name = excluded.table_name, partition_key = excluded.partition_key, "
                    + "record_day = excluded.record_day, row_count = excluded.row_count, "
                    + "bytes = excluded.bytes, written_at = excluded.written_at, "
                    + "state = excluded.state, "
                    + "schema_fingerprint = excluded.schema_fingerprint, "
                    + "event_time_min = excluded.event_time_min, event_time_max = excluded.event_time_max, "
                    + "event_time_spread_ms = excluded.event_time_spread_ms, producer = excluded.producer";

    /** The states whose path a reader may read: {@code LIVE}, and {@code REPLACED} (B4; see {@link #unreadablePaths}). */
    private static final String READABLE = "('LIVE', 'REPLACED')";

    private final ConnectionSource src;

    // ── raw table browser seam (BrowsableStore) — read-only, borrowed per operation ──
    @Override public String browseId() { return "consignment-outputs"; }
    @Override public String browseLabel() { return "Consignment Outputs"; }
    @Override public List<String> browseTables() { return List.of(T); }
    @Override public ConnectionSource browseSource() { return src; }

    /** Wrap an already-open JDBC connection; the schema is created if absent. Takes ownership (closed in {@link #close()}). */
    public DbConsignmentOutputStore(Connection conn) {
        this(JdbcDrivers.source(conn));
    }

    /** Borrow from {@code src} per operation; the schema is created if absent. */
    public DbConsignmentOutputStore(ConnectionSource src) {
        this.src = src;
        initSchema();
    }

    /** Open a registry by JDBC URL (DuckDB primary, e.g. {@code jdbc:duckdb:consignment-outputs.duckdb}). */
    public static DbConsignmentOutputStore open(String url) throws SQLException {
        return new DbConsignmentOutputStore(JdbcDrivers.source(url, null, null, "consignment-outputs"));
    }

    private static final String CREATE = "CREATE TABLE IF NOT EXISTS " + T + " ("
            + "consignment_id VARCHAR, run_id VARCHAR, table_name VARCHAR, "
            + "partition_key VARCHAR, record_day VARCHAR, path VARCHAR, "
            + "row_count BIGINT, bytes BIGINT, written_at VARCHAR, "
            + "state VARCHAR, schema_fingerprint VARCHAR, "
            + "event_time_min VARCHAR, event_time_max VARCHAR, "
            + "event_time_spread_ms BIGINT, producer VARCHAR, "
            + "UNIQUE (consignment_id, path, run_id))";

    /** X5 Step Receipts (plan §3.6): one row per (Consignment, run, lane, Step). {@code run_id} is {@code ''} on
     *  the ingest lanes, never {@code NULL} — a {@code NULL} in the UNIQUE key would exempt the row from it. */
    private static final String STEPS = "consignment_steps";
    private static final String CREATE_STEPS = "CREATE TABLE IF NOT EXISTS " + STEPS + " ("
            + "consignment_id VARCHAR, run_id VARCHAR, pipeline VARCHAR, lane VARCHAR, seq INTEGER, "
            + "step VARCHAR, kind VARCHAR, outcome VARCHAR, rows_in BIGINT, receipt VARCHAR, "
            + "recorded_at VARCHAR, simulated BOOLEAN, "
            + "UNIQUE (consignment_id, run_id, lane, seq))";

    /** {@code recorded_at}'s fixed-width UTC form: its text order IS its time order, so retention compares text on
     *  every backend ({@code Instant.toString()} varies its fraction digits, so its text order is not). */
    private static final java.time.format.DateTimeFormatter RECORDED_AT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

    /** Default retention of Step Receipts, in days ({@code -Dconsignment.steps.retentionDays}). */
    public static final int DEFAULT_STEPS_RETENTION_DAYS = 30;

    private void initSchema() {
        try {
            // ⚠ The whole DDL + migration is ONE borrow: rebuildWithConstraint runs a transaction, so every
            // statement here has to land on the same connection.
            src.run(conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute(CREATE_STEPS);
                    st.execute("CREATE INDEX IF NOT EXISTS consignment_steps_by_consignment ON " + STEPS
                            + " (consignment_id)");
                    st.execute(CREATE);
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
                    // Subtractive migration (2026-09-24): `generation` was a literal 0 on every write path and
                    // nothing ever read it — revisions are superseded by `state`, compaction by COMPACTED_AWAY, and
                    // reads pin file lists (ConsignmentSelector). A registry created before the drop loses the column
                    // here; every other column and row survives.
                    st.execute("ALTER TABLE " + T + " DROP COLUMN IF EXISTS generation");
                    // ⚠ The constraint is added LAST, after the widening above: the rebuild copies every column by
                    // name, so it can only run once a pre-constraint registry has them all.
                    if (!hasUniqueConstraint(st)) rebuildWithConstraint(conn, st);
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise consignment-outputs schema", e);
        }
    }

    private static boolean hasUniqueConstraint(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT 1 FROM information_schema.table_constraints "
                + "WHERE table_name = '" + T + "' AND constraint_type = 'UNIQUE'")) {
            return rs.next();
        }
    }

    /**
     * Legacy (unconstrained) table → constrained copy, duplicates collapsed, atomically.
     *
     * <p>⚠ The collapse is {@code DO NOTHING}, not the {@code DO UPDATE} of {@link #record}: at migration time
     * there is no "later" writer — the rows are already interleaved and the table carries no column that
     * orders two rows for the same key. Keeping the first arrival is the only choice that fabricates nothing.
     *
     * <p>🔴 <b>The NULL-{@code run_id} rows are copied by a SECOND statement, and that is load-bearing.</b>
     * Probed on DuckDB 1.5.2.1: a UNIQUE constraint really does treat NULLs as distinct <em>between</em>
     * statements (two single-row inserts of the same null-run key both land), but
     * {@code INSERT … SELECT … ON CONFLICT DO NOTHING} <b>also de-duplicates the incoming rows against each
     * other, and there NULL matches NULL</b> — so folding the legacy rows into one statement silently drops
     * every repeat of a null-run key. That is the exact silent data loss the nullable-{@code run_id} decision
     * exists to avoid, arriving through the migration instead of the constraint. ⛔ Do not merge these two
     * inserts back together.
     */
    private void rebuildWithConstraint(Connection conn, Statement st) throws SQLException {
        String legacy = T + "_v1";
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            st.execute("ALTER TABLE " + T + " RENAME TO " + legacy);
            st.execute(CREATE);
            st.execute("INSERT INTO " + T + " (" + COLS + ") SELECT " + COLS + " FROM " + legacy
                    + " WHERE run_id IS NOT NULL ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO " + T + " (" + COLS + ") SELECT " + COLS + " FROM " + legacy
                    + " WHERE run_id IS NULL");
            st.execute("DROP TABLE " + legacy);
            conn.commit();
            log.info("consignment-outputs: rebuilt {} with its unique constraint", T);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    /** Step-Receipt retention then CHECKPOINT + VACUUM over the live connection, each best-effort — the
     *  {@code db_maintenance} task. DuckDB is single-writer, so maintenance must ride this store's own connection,
     *  never a second one. Retention is {@code -Dconsignment.steps.retentionDays} (default
     *  {@value #DEFAULT_STEPS_RETENTION_DAYS}); a value below 1 keeps every receipt. */
    public void maintenance() {
        int days = Integer.getInteger("consignment.steps.retentionDays", DEFAULT_STEPS_RETENTION_DAYS);
        if (days > 0) pruneSteps(java.time.Instant.now().minus(java.time.Duration.ofDays(days)));
        for (String stmt : new String[]{"CHECKPOINT", "VACUUM"}) {
            try {
                src.run(conn -> {
                    try (Statement st = conn.createStatement()) {
                        st.execute(stmt);
                    }
                });
            } catch (SQLException e) {
                log.warn("consignment-outputs maintenance: {} failed (continuing): {}", stmt, e.getMessage());
            }
        }
    }

    /**
     * Record one walk's Step Receipts (X5, plan §3.6) — one batched insert per Consignment, never one per Step.
     * A receipt is immutable: a row already held for its {@code (consignment_id, run_id, lane, seq)} is kept
     * ({@code DO NOTHING}). <b>Best-effort, like {@link #record}</b>: a failure is logged, never thrown, because a
     * receipt is a record of the run, never a reason to fail it.
     */
    public void recordSteps(List<com.gamma.pipeline.exec.StepReceipt> receipts) {
        if (receipts == null || receipts.isEmpty()) return;
        String now = RECORDED_AT.format(java.time.Instant.now());
        try {
            src.run(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + STEPS
                        + " (consignment_id, run_id, pipeline, lane, seq, step, kind, outcome, rows_in, receipt, "
                        + "recorded_at, simulated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (consignment_id, run_id, lane, seq) DO NOTHING")) {
                    for (com.gamma.pipeline.exec.StepReceipt r : receipts) {
                        ps.setString(1, r.consignmentId());
                        ps.setString(2, r.runId());
                        ps.setString(3, r.pipeline());
                        ps.setString(4, r.lane());
                        ps.setInt(5, r.seq());
                        ps.setString(6, r.step());
                        ps.setString(7, r.kind());
                        ps.setString(8, r.outcome().name());
                        ps.setLong(9, r.in());
                        ps.setString(10, r.toJson());
                        ps.setString(11, now);
                        ps.setBoolean(12, r.simulated());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            });
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not record {} Step Receipt(s) for consignment {}: {}",
                    receipts.size(), receipts.get(0).consignmentId(), e.getMessage());
        }
    }


    /**
     * One Consignment's Step Receipts across every lane and run, oldest flush first, then by run, lane and
     * {@code seq}. A row whose receipt this build cannot read is skipped with a warning.
     *
     * @throws IllegalStateException when the read fails — ⛔ never an empty list for "could not read", which a
     *                               caller would report as "no Steps"
     */
    public List<com.gamma.pipeline.exec.StepReceipt> steps(String consignmentId) {
        return steps(consignmentId, Integer.MAX_VALUE);
    }

    /**
     * As {@link #steps(String)}, at most {@code limit} rows, cut in the database ({@code LIMIT}) — a bounded read for
     * a route. {@link #stepCount} gives the true total.
     *
     * @throws IllegalStateException when the read fails
     */
    public List<com.gamma.pipeline.exec.StepReceipt> steps(String consignmentId, int limit) {
        List<com.gamma.pipeline.exec.StepReceipt> out = new ArrayList<>();
        try {
            src.run(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("SELECT receipt FROM " + STEPS
                        + " WHERE consignment_id = ? ORDER BY recorded_at, run_id, lane, seq LIMIT ?")) {
                    ps.setString(1, consignmentId);
                    ps.setInt(2, Math.max(0, limit));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try {
                                out.add(com.gamma.pipeline.exec.StepReceipt.fromJson(rs.getString(1)));
                            } catch (RuntimeException unreadable) {
                                log.warn("consignment {}: skipping an unreadable Step Receipt: {}",
                                        consignmentId, unreadable.getMessage());
                            }
                        }
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("consignment-steps query failed for " + consignmentId + ": "
                    + e.getMessage(), e);
        }
        return out;
    }

    /** How many Step Receipts one Consignment has. @throws IllegalStateException when the read fails */
    public long stepCount(String consignmentId) {
        return count("SELECT count(*) FROM " + STEPS + " WHERE consignment_id = ?", consignmentId);
    }

    /** How many output rows one Consignment has registered. @throws IllegalStateException when the read fails */
    public long outputCount(String consignmentId) {
        return count("SELECT count(*) FROM " + T + " WHERE consignment_id = ?", consignmentId);
    }

    private long count(String sql, String param) {
        try {
            return src.with(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, param);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("consignment count failed for " + param + ": " + e.getMessage(), e);
        }
    }

    /** Run ids bound per {@code IN} list in {@link #consignmentsOfRuns}: well under PostgreSQL's 65 535 bind
     *  parameters per statement. */
    static final int IN_CHUNK = 1000;

    /**
     * The Consignment each of {@code runIds} wrote — the {@code run_id} join of X5's {@code GET /consignments/{id}}
     * (plan §3.7), which closes the gap that a job run row does not name its own Consignment. One query per table and
     * per {@value #IN_CHUNK} runs, never one per run: the runs' Step Receipts answer first ({@code consignment_steps.run_id}); a run that left none
     * is answered by {@code consignment_outputs.run_id}. When a run wrote more than one, the oldest wins. A run that
     * wrote nothing has no entry; {@code null} and blank ids (an ingest lane's {@code ''} is not a run) are ignored.
     *
     * @return run id → Consignment id, in the order of {@code runIds}
     * @throws IllegalStateException when the read fails — never an empty map for "could not read"
     */
    public Map<String, String> consignmentsOfRuns(java.util.Collection<String> runIds) {
        List<String> wanted = new ArrayList<>(new java.util.LinkedHashSet<>(runIds == null ? List.<String>of() : runIds));
        wanted.removeIf(r -> r == null || r.isBlank());
        Map<String, String> found = new java.util.HashMap<>();
        if (wanted.isEmpty()) return new java.util.LinkedHashMap<>();
        try {
            src.run(conn -> {
                for (String[] q : new String[][]{{STEPS, "recorded_at"}, {T, "written_at"}}) {
                    List<String> open = wanted.stream().filter(r -> !found.containsKey(r)).toList();
                    if (open.isEmpty()) return;
                    // Chunked: PostgreSQL caps one statement at 65 535 bind parameters.
                    for (int from = 0; from < open.size(); from += IN_CHUNK) {
                        List<String> chunk = open.subList(from, Math.min(open.size(), from + IN_CHUNK));
                        String sql = "SELECT run_id, consignment_id FROM " + q[0] + " WHERE run_id IN ("
                                + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")"
                                + " AND consignment_id IS NOT NULL GROUP BY run_id, consignment_id"
                                + " ORDER BY run_id, min(" + q[1] + "), consignment_id";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            for (int i = 0; i < chunk.size(); i++) ps.setString(i + 1, chunk.get(i));
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) found.putIfAbsent(rs.getString(1), rs.getString(2));
                            }
                        }
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("consignment lookup failed for " + wanted.size() + " run(s): "
                    + e.getMessage(), e);
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String r : wanted) if (found.containsKey(r)) out.put(r, found.get(r));
        return out;
    }


    /** Delete the Step Receipts recorded before {@code cutoff}; returns how many went. Best-effort. */
    public int pruneSteps(java.time.Instant cutoff) {
        return update("DELETE FROM " + STEPS + " WHERE recorded_at < ?", RECORDED_AT.format(cutoff),
                "prune Step Receipts before " + cutoff);
    }

    /**
     * Record the output files of one Consignment — an insert, or, for a path this Consignment already wrote in
     * this Run, an <b>overwrite of the earlier row</b> (see the class javadoc on identity).
     * <b>Best-effort: a write failure is logged, never thrown</b> —
     * the registry is an index beside the manifest, so losing a row must not fail a Consignment that has
     * already landed its data. §5.4's commit discipline stays the thing that guarantees visibility.
     */
    public void record(List<ConsignmentOutput> outputs) {
        if (outputs == null || outputs.isEmpty()) return;
        try {
            src.run(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + T + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                                + "ON CONFLICT (consignment_id, path, run_id) DO UPDATE SET " + UPSERT_SET)) {
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
                        ps.setString(10, o.state().name());
                        ps.setString(11, o.schemaFingerprint());
                        ps.setString(12, b == null ? null : b.min());
                        ps.setString(13, b == null ? null : b.max());
                        // null, not 0: a spread of 0 is a real value (one event time in the file), so an absent
                        // bound has to read back as absent rather than as an instantaneous file.
                        if (b == null) ps.setNull(14, java.sql.Types.BIGINT); else ps.setLong(14, b.spreadMs());
                        ps.setString(15, o.producer());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            });
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
    /**
     * One day's received volume for one pipeline, as the completeness KPI reads it
     * (completeness-KPI K1; design of record:
     * docs/okf/product/module/observability/plan-observability.md §3.9 — the plan was archived 2026-09-09).
     *
     * @param recordDay the event-time day, or {@code null} for the <b>unknown-day</b> bucket — files whose
     *                  {@code record_day} was never established because the write path materialises no event
     *                  time (enrichment and Pipeline sinks), the schema declares no date partition, or every
     *                  row's event time failed to parse. 🔴 <b>Unknown is not zero and not empty.</b> Folding
     *                  it into a day would attribute rows to a date nothing claimed; dropping it would hide
     *                  rows that genuinely arrived. A KPI that cannot tell "no data" from "date unknown"
     *                  reports a confident deviation that is an artifact of its own bookkeeping.
     * @param files     distinct output files — {@code COUNT(DISTINCT path)}, never a row count.
     * @param rows      rows across those files.
     */
    public record DailyVolume(String recordDay, long files, long rows) {}

    /**
     * Received volume per record-day for one producing pipeline, over {@code [fromDay, toDay]} inclusive,
     * plus the unknown-day bucket (a {@code null} {@code recordDay}, sorted last).
     *
     * <p>🔴 <b>LIVE rows only, and files counted DISTINCT.</b> Row state is per-<em>registration</em> while a
     * file is one <em>path</em>: output naming is not one-file-per-Consignment, so a full recompute rewrites a
     * stable path in place and that path legitimately owns an old {@code SUPERSEDED} row beside its current
     * {@code LIVE} one (see {@link #unreadablePaths}). Summing every row would count a recomputed day twice
     * and report a volume <em>increase</em> caused purely by reprocessing — the exact false signal a
     * completeness KPI exists to rule out. {@code COMPACTED_AWAY} is excluded for the same reason: its rows
     * live on inside the compacted file, which carries its own LIVE row. {@code REPLACED} is excluded too: its
     * {@code row_count} still counts the rows a later arrival replaced, which that arrival's own row counts.
     *
     * <p>⚠ Days with no registered output are <b>absent from the result, not zero</b>. Only the caller knows
     * which days were expected; this method reports what the registry holds and does not invent a calendar.
     *
     * @throws IllegalArgumentException if {@code producer} is blank — a KPI must never silently aggregate
     *                                  across every pipeline when it meant to scope to one.
     */
    public List<DailyVolume> dailyVolume(String producer, String fromDay, String toDay) {
        if (producer == null || producer.isBlank())
            throw new IllegalArgumentException("dailyVolume requires a producer (pipeline) — refusing to "
                    + "aggregate across every pipeline");
        String sql = "SELECT record_day, COUNT(DISTINCT path) AS files, "
                + "COALESCE(SUM(row_count), 0) AS rows_total FROM " + T
                + " WHERE producer = ? AND state = 'LIVE'"
                // The unknown bucket must survive the range filter: it has no day to compare.
                + "   AND (record_day IS NULL OR (record_day >= ? AND record_day <= ?))"
                + " GROUP BY record_day ORDER BY record_day NULLS LAST";
        List<DailyVolume> out = new ArrayList<>();
        try {
            src.run(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, producer);
                    ps.setString(2, fromDay);
                    ps.setString(3, toDay);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next())
                            out.add(new DailyVolume(rs.getString("record_day"), rs.getLong("files"), rs.getLong("rows_total")));
                    }
                }
            });
        } catch (SQLException e) {
            // ⛔ Deliberately NOT the fail-open warn-and-return-empty of record(): an empty list here is
            // indistinguishable from "the pipeline received nothing", which is a breach the KPI would report
            // as fact. A read that failed must not be mistaken for a day that was empty.
            throw new IllegalStateException("consignment-outputs daily volume query failed for producer '"
                    + producer + "': " + e.getMessage(), e);
        }
        return out;
    }

    public List<ConsignmentOutput> outputs(String consignmentId) {
        return outputs(consignmentId, Integer.MAX_VALUE);
    }

    /** As {@link #outputs(String)}, at most {@code limit} rows, cut in the database ({@code LIMIT}); best-effort like
     *  it. {@link #outputCount} gives the true total. */
    public List<ConsignmentOutput> outputs(String consignmentId, int limit) {
        String sql = "SELECT " + COLS + " FROM " + T
                + " WHERE consignment_id = ? ORDER BY written_at DESC, path LIMIT ?";
        List<ConsignmentOutput> out = new ArrayList<>();
        try {
            src.run(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, consignmentId);
                    ps.setInt(2, Math.max(0, limit));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(map(rs));
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("consignment-outputs query failed for {}: {}", consignmentId, e.getMessage());
        }
        return out;
    }

    /**
     * Every path this registry says is <b>no longer readable</b> — {@code SUPERSEDED} or {@code COMPACTED_AWAY},
     * across every stream — for {@link ConsignmentSelector} to subtract from a glob (addressing §7-A).
     * {@code REPLACED} is readable (B4): a file <i>Replace the period</i> rewrote keeps its path and the rows it
     * kept, so subtracting it would hide them from every read - and {@code retire_superseded} would delete it.
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
    /**
     * Whether {@code path} is a file this registry says is <b>readable</b>: registered, and carrying at least
     * one {@code LIVE} (or {@code REPLACED}, see {@link #unreadablePaths}) row.
     *
     * <p>The inverse of {@link #unreadablePaths}, and it follows the same rule for the same reason:
     * <b>readability is per-PATH, row state is per-registration</b>. Output naming is not
     * one-file-per-Consignment — a full recompute rewrites a stable path in place — so a path legitimately
     * owns an old {@code SUPERSEDED} row beside a current {@code LIVE} one, and it is readable.
     *
     * <p>Its first caller is the derived-table seam, where it decides whether author SQL may name a file
     * directly: reading a registered path keeps the addressing invariant (everything downstream keys off
     * registered outputs), while reading an arbitrary path would not.
     *
     * <p>⚠ Compares the path <b>as stored</b>. A caller holding a path in another spelling (a different
     * separator, a relative form) must normalise before asking; this method does not guess.
     */
    /**
     * The Consignments behind {@code readPaths} under {@code tableName} — readable ({@code LIVE} or
     * {@code REPLACED}) rows only, one entry per
     * consignment (X2 cross-lane provenance: the at-rest run records what it READ, mapped from the files
     * the selector kept, not every Consignment ever written to the store).
     *
     * <p>Paths are matched through {@link #norm}, on BOTH sides: the selector hands back DuckDB's glob
     * spelling and the registry stores whatever the writer passed, so a raw string compare would miss on
     * a separator or a relative form and silently under-report the sources — which reads exactly like
     * "this run derived from nothing". {@code null}/empty {@code readPaths} ⇒ empty result; the caller
     * decides what an unknown file set means (it must not be recorded as "no sources").
     */
    public List<ConsignmentSource> sourcesForPaths(String tableName, java.util.Collection<String> readPaths) {
        List<ConsignmentSource> out = new ArrayList<>();
        if (tableName == null || readPaths == null || readPaths.isEmpty()) return out;
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (String p : readPaths) if (p != null) wanted.add(norm(p));
        String sql = "SELECT consignment_id, producer, path FROM " + T
                + " WHERE table_name = ? AND coalesce(state, 'LIVE') IN " + READABLE;
        java.util.LinkedHashMap<String, ConsignmentSource> byId = new java.util.LinkedHashMap<>();
        try {
            src.run(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, tableName);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            if (!wanted.contains(norm(rs.getString(3)))) continue;
                            byId.putIfAbsent(rs.getString(1),
                                    new ConsignmentSource(rs.getString(1), rs.getString(2), tableName));
                        }
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("[CONSIGNMENT-OUTPUTS] source lookup failed for {}: {}", tableName, e.getMessage());
        }
        out.addAll(byId.values());
        return out;
    }

    public boolean isReadable(String path) {
        if (path == null || path.isBlank()) return false;
        String sql = "SELECT 1 FROM " + T + " WHERE path = ? AND coalesce(state, 'LIVE') IN " + READABLE + " LIMIT 1";
        try {
            return src.with(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, path);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next();
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("[CONSIGNMENT-OUTPUTS] readability check failed for {}: {}", path, e.getMessage());
            return false;   // fail closed: an unanswerable question is not a yes
        }
    }

    public List<String> unreadablePaths() {
        String sql = "SELECT DISTINCT t.path FROM " + T + " t WHERE coalesce(t.state, 'LIVE') NOT IN " + READABLE
                + " AND NOT EXISTS (SELECT 1 FROM " + T + " l WHERE l.path = t.path "
                + "AND coalesce(l.state, 'LIVE') IN " + READABLE + ")";
        List<String> out = new ArrayList<>();
        try {
            src.run(conn -> {
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        String path = rs.getString(1);
                        if (path != null) out.add(path);
                    }
                }
            });
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
    public List<ProducerHighWater> producerHighWater(String tableName) {
        String sql = "SELECT producer, max(event_time_max) AS event_time_max, "
                + "epoch_ms(max(TRY_CAST(written_at AS TIMESTAMPTZ))) AS last_seen_ms FROM " + T
                + " WHERE table_name = ? AND coalesce(state, 'LIVE') <> 'SUPERSEDED' GROUP BY producer";
        List<ProducerHighWater> out = new ArrayList<>();
        try {
            src.run(conn -> {
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
                }
            });
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
    public Optional<EventTimeBounds> bounds(String tableName) {
        String sql = "SELECT min(event_time_min) AS lo, max(event_time_max) AS hi, "
                + "epoch_ms(TRY_CAST(max(event_time_max) AS TIMESTAMP)) "
                + "- epoch_ms(TRY_CAST(min(event_time_min) AS TIMESTAMP)) AS spread_ms FROM " + T
                + " WHERE table_name = ? AND coalesce(state, 'LIVE') <> 'SUPERSEDED'";
        try {
            return src.with(conn -> {
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
                }
            });
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
    public int supersede(String consignmentId) {
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
    public int supersedeOtherRevisions(String tableName, String keepConsignmentId) {
        if (tableName == null || keepConsignmentId == null)
            throw new IllegalArgumentException("supersedeOtherRevisions needs both a table and the "
                    + "consignment to keep — a null keep would supersede the revision that just landed");
        try {
            return src.with(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE " + T + " SET state = 'SUPERSEDED' "
                        + "WHERE table_name = ? AND consignment_id <> ? AND coalesce(state, 'LIVE') = 'LIVE'")) {
                    ps.setString(1, tableName);
                    ps.setString(2, keepConsignmentId);
                    return ps.executeUpdate();
                }
            });
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
    public int markCompactedAway(List<String> paths) {
        return flip(paths, "state <> 'COMPACTED_AWAY'", "COMPACTED_AWAY", "compacted away");
    }

    /**
     * Mark the given output paths {@code REPLACED} ({@code BASIC-ELT-PROFILE-1} B4) — the files a later arrival
     * replaced rows of: <i>Replace the period</i> rewrote them without the days it covers (or removed them), or
     * <i>Replace everything</i> set them aside. Only {@code LIVE} rows move: a {@code COMPACTED_AWAY} or
     * {@code SUPERSEDED} row already refuses or retired what this would. The path stays readable (see
     * {@link #unreadablePaths}); a reprocess of its Consignment is refused, because re-reading the backup would bring
     * the replaced rows back. Matched like {@link #markCompactedAway}; unmatched paths are ignored.
     *
     * @return how many rows changed state.
     */
    public int markReplaced(List<String> paths) {
        return flip(paths, "coalesce(state, 'LIVE') = 'LIVE'", "REPLACED", "replaced");
    }

    /** Set {@code to} on every row of {@code paths} (normalised on both sides) that {@code from} selects. */
    private int flip(List<String> paths, String from, String to, String what) {
        if (paths == null || paths.isEmpty()) return 0;
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (String p : paths) wanted.add(norm(p));

        // ⚠ A holder, not a local, so a partial count survives a failure half-way through the UPDATEs —
        // the pre-borrow code returned what it had already changed, and so does this.
        int[] changed = {0};
        try {
            // ⚠ The SELECT of stored spellings and the UPDATEs that act on them share ONE borrow: on a pool
            // they would otherwise land on different connections, and a path revealed between them would be
            // read as unmatched while the UPDATE could still touch it.
            src.run(conn -> {
                // Resolve stored spellings to the same normalised form before comparing — see norm(). Matching in
                // SQL cannot work in both directions, because the column would have to be normalised too.
                List<String> matches = new ArrayList<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT DISTINCT path FROM " + T + " WHERE " + from)) {
                    while (rs.next()) {
                        String stored = rs.getString("path");
                        if (stored != null && wanted.contains(norm(stored))) matches.add(stored);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE " + T
                        + " SET state = '" + to + "' WHERE path = ? AND " + from)) {
                    for (String stored : matches) {
                        ps.setString(1, stored);
                        changed[0] += ps.executeUpdate();
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("Could not mark {} path(s) {}: {}", paths.size(), what, e.getMessage());
        }
        return changed[0];
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
        try {
            return src.with(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, param);
                    return ps.executeUpdate();
                }
            });
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
    public void close() {
        try {
            src.close();
        } catch (RuntimeException e) {
            log.warn("Error closing consignment-outputs DB: {}", e.getMessage());
        }
    }
}

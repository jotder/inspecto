package com.gamma.consignment;

import com.gamma.consignment.ConsignmentOutput.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DbConsignmentOutputStore} (consignment-elt plan §11.3): the durable per-output-file registry.
 * Mostly in-memory DuckDB ({@code jdbc:duckdb:}) like {@link com.gamma.job.DbJobRunStoreTest}, except the
 * durability test, which needs a real file to close and reopen.
 */
class DbConsignmentOutputStoreTest {

    private static ConsignmentOutput out(String consignment, String path, long rows, State state) {
        return new ConsignmentOutput(consignment, "run-1", "cdr", "dt=2026-08-04", "2026-08-04",
                path, rows, rows * 100, "2026-08-04T10:00:00Z", 1, state);
    }

    @Test
    void registersEveryFieldAndReadsItBack() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(out("c1", "/w/cdr/dt=2026-08-04/c1.parquet", 42, State.LIVE)));

            List<ConsignmentOutput> rows = db.outputs("c1");
            assertEquals(1, rows.size());
            ConsignmentOutput o = rows.get(0);
            assertEquals("c1", o.consignmentId());
            assertEquals("run-1", o.runId());
            assertEquals("cdr", o.tableName());
            assertEquals("dt=2026-08-04", o.partitionKey());
            assertEquals("2026-08-04", o.recordDay());
            assertEquals("/w/cdr/dt=2026-08-04/c1.parquet", o.path());
            assertEquals(42L, o.rows(), "row_count must survive — it is the field PartitionOutput cannot supply");
            assertEquals(4200L, o.bytes());
            assertEquals("2026-08-04T10:00:00Z", o.writtenAt());
            assertEquals(1, o.generation());
            assertEquals(State.LIVE, o.state());
        }
    }

    /** §5.3: the registry's whole point is answering "every file C wrote, across ALL partitions". */
    @Test
    void returnsEveryPartitionOfOneConsignmentAndNothingFromAnother() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    out("c1", "/w/cdr/dt=2026-08-03/c1.parquet", 5, State.LIVE),
                    out("c1", "/w/cdr/dt=2026-08-04/c1.parquet", 7, State.LIVE),
                    out("c2", "/w/cdr/dt=2026-08-04/c2.parquet", 9, State.LIVE)));

            assertEquals(2, db.outputs("c1").size(), "a multi-day Consignment must return both partitions");
            assertEquals(1, db.outputs("c2").size());
            assertTrue(db.outputs("nope").isEmpty());
        }
    }

    /**
     * Non-LIVE rows must come back too. Filtering them would hide the exact case the registry exists to
     * expose: reprocessing a Consignment whose output was compacted away has to take the partition-rewrite
     * path (§6.2), not a no-op unlink that silently duplicates rows.
     */
    @Test
    void listsSupersededAndCompactedRowsNotJustLive() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    out("c1", "/w/cdr/dt=2026-08-04/live.parquet", 1, State.LIVE),
                    out("c1", "/w/cdr/dt=2026-08-04/old.parquet", 2, State.SUPERSEDED),
                    out("c1", "/w/cdr/dt=2026-08-04/gone.parquet", 3, State.COMPACTED_AWAY)));

            List<State> states = db.outputs("c1").stream().map(ConsignmentOutput::state).toList();
            assertEquals(3, states.size());
            assertTrue(states.containsAll(List.of(State.LIVE, State.SUPERSEDED, State.COMPACTED_AWAY)));
        }
    }

    @Test
    void survivesCloseAndReopen(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("outputs.duckdb");
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open(url)) {
            db.record(List.of(out("c1", "/w/cdr/dt=2026-08-04/c1.parquet", 42, State.LIVE)));
        }
        try (DbConsignmentOutputStore reopened = DbConsignmentOutputStore.open(url)) {
            List<ConsignmentOutput> rows = reopened.outputs("c1");
            assertEquals(1, rows.size(), "the registry must be durable — an in-memory one cannot replace the manifest");
            assertEquals(42L, rows.get(0).rows());
        }
    }

    /** initSchema is re-run on every open, so a second open of the same file must not duplicate or fail. */
    @Test
    void schemaCreationIsIdempotent(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("outputs.duckdb");
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open(url)) {
            db.record(List.of(out("c1", "/w/a.parquet", 1, State.LIVE)));
        }
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open(url)) {
            db.record(List.of(out("c1", "/w/b.parquet", 2, State.LIVE)));
            assertEquals(2, db.outputs("c1").size());
        }
    }

    /**
     * A row written by a newer build carrying an unknown state must not make an older build unable to list a
     * Consignment's files at all — it degrades to LIVE. Written via raw SQL because the enum cannot express it.
     */
    @Test
    void unknownStateDegradesToLiveRatherThanThrowing() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            Connection raw = db.browseConnection();   // borrowed, NOT owned — closing it would close the store
            try (Statement st = raw.createStatement()) {
                // Columns named, not positional: this row exists to exercise `state`, and an unrelated
                // additive migration must not turn it into an arity error.
                st.execute("INSERT INTO consignment_outputs (consignment_id, run_id, table_name, "
                        + "partition_key, record_day, path, row_count, bytes, written_at, generation, "
                        + "state, schema_fingerprint) VALUES "
                        + "('c1','run-1','cdr','dt=2026-08-04','2026-08-04','/w/x.parquet',1,100,"
                        + "'2026-08-04T10:00:00Z',1,'QUARANTINED_BY_A_FUTURE_BUILD',NULL)");
            } catch (SQLException e) {
                fail("raw insert should succeed: " + e.getMessage());
            }
            List<ConsignmentOutput> rows = db.outputs("c1");
            assertEquals(1, rows.size());
            assertEquals(State.LIVE, rows.get(0).state());
        }
    }

    /** A write failure must be logged, never thrown — the data has already landed. */
    @Test
    void recordIsBestEffortAndNeverThrows() throws Exception {
        DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:");
        db.close();
        assertDoesNotThrow(() -> db.record(List.of(out("c1", "/w/a.parquet", 1, State.LIVE))));
        assertDoesNotThrow(() -> db.outputs("c1"));
    }

    @Test
    void emptyAndNullRecordAreNoOps() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            assertDoesNotThrow(() -> db.record(List.of()));
            assertDoesNotThrow(() -> db.record(null));
            assertTrue(db.outputs("c1").isEmpty());
        }
    }

    // ── schema fingerprint (ELT amendment §3.4.3) ─────────────────────────────────

    @Test
    void schemaFingerprintRoundTripsAndAbsentStaysNull() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    new ConsignmentOutput("c1", "run-1", "cdr", "dt=2026-08-04", "2026-08-04",
                            "/w/a.parquet", 1, 100, "2026-08-04T10:00:00Z", 1, State.LIVE, "abc123"),
                    out("c1", "/w/b.parquet", 2, State.LIVE)));   // fingerprint-less form → null

            List<ConsignmentOutput> rows = db.outputs("c1");
            assertEquals("abc123", rows.stream().filter(o -> o.path().endsWith("a.parquet"))
                    .findFirst().orElseThrow().schemaFingerprint());
            assertNull(rows.stream().filter(o -> o.path().endsWith("b.parquet"))
                    .findFirst().orElseThrow().schemaFingerprint(),
                    "write paths carrying no pipeline schema record null, never a fabricated hash");
        }
    }

    /**
     * A registry file created before the column existed must gain it on reopen — CREATE TABLE IF NOT EXISTS
     * never widens an existing table, so initSchema's ADD COLUMN IF NOT EXISTS is the migration. Pre-migration
     * rows read back with a null fingerprint (the "old row ⇒ unknown, don't throw" rule the state column set).
     */
    @Test
    void preFingerprintRegistryGainsTheColumnOnReopen(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("outputs.duckdb");
        try (Connection legacy = com.gamma.util.JdbcDrivers.connect(url);
             Statement st = legacy.createStatement()) {
            st.execute("CREATE TABLE consignment_outputs ("
                    + "consignment_id VARCHAR, run_id VARCHAR, table_name VARCHAR, "
                    + "partition_key VARCHAR, record_day VARCHAR, path VARCHAR, "
                    + "row_count BIGINT, bytes BIGINT, written_at VARCHAR, "
                    + "generation INTEGER, state VARCHAR)");
            st.execute("INSERT INTO consignment_outputs VALUES "
                    + "('c0','run-0','cdr','dt=2026-08-01','2026-08-01','/w/old.parquet',9,900,"
                    + "'2026-08-01T10:00:00Z',0,'LIVE')");
        }
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open(url)) {
            List<ConsignmentOutput> old = db.outputs("c0");
            assertEquals(1, old.size(), "pre-migration rows stay readable");
            assertNull(old.get(0).schemaFingerprint());

            db.record(List.of(new ConsignmentOutput("c1", "run-1", "cdr", "dt=2026-08-04", "2026-08-04",
                    "/w/new.parquet", 1, 100, "2026-08-04T10:00:00Z", 1, State.LIVE, "fp-1")));
            assertEquals("fp-1", db.outputs("c1").get(0).schemaFingerprint());
        }
    }

    // ── state transitions (§6.3 / §5.3) ──────────────────────────────────────────

    @Test
    void supersedeMovesOnlyThisConsignmentsLiveRows() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    out("c1", "/w/a.parquet", 1, State.LIVE),
                    out("c1", "/w/b.parquet", 2, State.LIVE),
                    out("c2", "/w/c.parquet", 3, State.LIVE)));

            assertEquals(2, db.supersede("c1"));
            assertEquals(List.of(State.SUPERSEDED, State.SUPERSEDED),
                    db.outputs("c1").stream().map(ConsignmentOutput::state).toList());
            assertEquals(State.LIVE, db.outputs("c2").get(0).state(), "another Consignment is untouched");
        }
    }

    /**
     * The transition that must NOT happen. A {@code COMPACTED_AWAY} row is the evidence that this file's rows
     * now live inside a merged file; overwriting it with {@code SUPERSEDED} would lose the one fact that tells
     * a reprocess to rewrite the partition (§6.2) instead of unlinking a path that is already gone.
     */
    @Test
    void supersedeLeavesCompactedAwayRowsAlone() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    out("c1", "/w/live.parquet", 1, State.LIVE),
                    out("c1", "/w/gone.parquet", 2, State.COMPACTED_AWAY)));

            assertEquals(1, db.supersede("c1"), "only the LIVE row moves");
            assertEquals(State.COMPACTED_AWAY, db.outputs("c1").stream()
                    .filter(o -> o.path().endsWith("gone.parquet")).findFirst().orElseThrow().state());
        }
    }

    @Test
    void markCompactedAwayFlipsExactlyTheGivenPaths() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    out("c1", "/w/dt=1/a.parquet", 1, State.LIVE),
                    out("c2", "/w/dt=1/b.parquet", 2, State.LIVE),
                    out("c3", "/w/dt=2/c.parquet", 3, State.LIVE)));

            // A merged file spans Consignments — which is why this is keyed by path, not by consignment_id.
            assertEquals(2, db.markCompactedAway(List.of("/w/dt=1/a.parquet", "/w/dt=1/b.parquet")));
            assertEquals(State.COMPACTED_AWAY, db.outputs("c1").get(0).state());
            assertEquals(State.COMPACTED_AWAY, db.outputs("c2").get(0).state());
            assertEquals(State.LIVE, db.outputs("c3").get(0).state(), "a different partition is untouched");
        }
    }

    /** Compaction legitimately merges files older than the registry, so an unmatched path is not an error. */
    @Test
    void markCompactedAwayIgnoresPathsItDoesNotKnow() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(out("c1", "/w/a.parquet", 1, State.LIVE)));
            assertEquals(0, db.markCompactedAway(List.of("/w/never-registered.parquet")));
            assertEquals(State.LIVE, db.outputs("c1").get(0).state());
        }
    }

    /**
     * The silent-failure guard: a relative spelling on the write side and an absolute one on the compaction side
     * (or the reverse) must still match, or a state flip would report success having changed nothing.
     */
    @Test
    void markCompactedAwayMatchesAcrossRelativeAndAbsoluteSpellings() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(out("c1", "work/cdr/dt=1/a.parquet", 1, State.LIVE)));

            String absolute = Path.of("work/cdr/dt=1/a.parquet").toAbsolutePath().normalize().toString();
            assertEquals(1, db.markCompactedAway(List.of(absolute)),
                    "an absolute probe must find a row stored relative");
            assertEquals(State.COMPACTED_AWAY, db.outputs("c1").get(0).state());
            assertEquals("work/cdr/dt=1/a.parquet", db.outputs("c1").get(0).path(),
                    "and the stored spelling is left alone — normalising it would make the row cwd-dependent");
        }
    }

    /** Idempotent: re-running compaction over the same directory must not report phantom changes. */
    @Test
    void markCompactedAwayIsIdempotent() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(out("c1", "/w/a.parquet", 1, State.LIVE)));
            assertEquals(1, db.markCompactedAway(List.of("/w/a.parquet")));
            assertEquals(0, db.markCompactedAway(List.of("/w/a.parquet")), "already gone — nothing to change");
        }
    }

    /** Same fail-open contract as record(): a failed state flip is logged, never thrown. */
    @Test
    void stateTransitionsAreBestEffortAndNeverThrow() throws Exception {
        DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:");
        db.close();
        assertEquals(0, db.supersede("c1"));
        assertEquals(0, db.markCompactedAway(List.of("/w/a.parquet")));
        assertEquals(0, db.markCompactedAway(List.of()));
        assertEquals(0, db.markCompactedAway(null));
    }
}

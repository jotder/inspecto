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
                st.execute("INSERT INTO consignment_outputs VALUES "
                        + "('c1','run-1','cdr','dt=2026-08-04','2026-08-04','/w/x.parquet',1,100,"
                        + "'2026-08-04T10:00:00Z',1,'QUARANTINED_BY_A_FUTURE_BUILD')");
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
}

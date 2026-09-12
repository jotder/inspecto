package com.gamma.consignment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DbFileStageStore} (Phase 4 §2.4): the durable per-file stage-progression registry.
 * Mostly in-memory DuckDB, like {@link DbConsignmentOutputStoreTest}.
 */
class DbFileStageStoreTest {

    private static FileStageRecord row(String path, FileStage stage, String at) {
        return new FileStageRecord("src-1", path, "batch-1", stage, at);
    }

    @Test
    void recordsEveryFieldAndReadsItBackInOrder() throws Exception {
        try (DbFileStageStore db = DbFileStageStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    row("a.csv", FileStage.REGISTERED, "2026-08-06T10:00:00"),
                    row("a.csv", FileStage.MANIFESTED, "2026-08-06T10:00:01")));

            List<FileStageRecord> stages = db.stages("src-1", "a.csv");
            assertEquals(2, stages.size());
            assertEquals(FileStage.REGISTERED, stages.get(0).stage(), "oldest first");
            assertEquals(FileStage.MANIFESTED, stages.get(1).stage());
            assertEquals("batch-1", stages.get(0).batchId());
        }
    }

    @Test
    void aDifferentFileOrSourceIsNotConfused() throws Exception {
        try (DbFileStageStore db = DbFileStageStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    row("a.csv", FileStage.MANIFESTED, "t1"),
                    new FileStageRecord("src-2", "a.csv", "batch-1", FileStage.MANIFESTED, "t1")));

            assertEquals(1, db.stages("src-1", "a.csv").size());
            assertTrue(db.stages("src-1", "b.csv").isEmpty());
        }
    }

    @Test
    void survivesCloseAndReopen(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("stages.duckdb");
        try (DbFileStageStore db = DbFileStageStore.open(url)) {
            db.record(List.of(row("a.csv", FileStage.MARKED, "t1")));
        }
        try (DbFileStageStore reopened = DbFileStageStore.open(url)) {
            assertEquals(1, reopened.stages("src-1", "a.csv").size());
        }
    }

    @Test
    void recordIsBestEffortAndNeverThrows() throws Exception {
        DbFileStageStore db = DbFileStageStore.open("jdbc:duckdb:");
        db.close();
        assertDoesNotThrow(() -> db.record(List.of(row("a.csv", FileStage.MARKED, "t1"))));
        assertDoesNotThrow(() -> db.stages("src-1", "a.csv"));
    }

    // ── CONSIGNMENT-ID-DETERMINISTIC-1, constraints half ─────────────────────────────────────────
    //
    // Without the UNIQUE key + ON CONFLICT DO NOTHING every one of these first two tests reads back
    // two rows — the probe would SUCCEED, which is what makes the negative assertion trustworthy.

    @Test
    void theSameTransitionRecordedTwiceIsOneRow() throws Exception {
        try (DbFileStageStore db = DbFileStageStore.open("jdbc:duckdb:")) {
            db.record(List.of(row("a.csv", FileStage.REGISTERED, "t1")));
            db.record(List.of(row("a.csv", FileStage.REGISTERED, "t2")));   // a retry / a second executor
            db.record(List.of(row("a.csv", FileStage.MANIFESTED, "t3"), row("a.csv", FileStage.MANIFESTED, "t3")));

            List<FileStageRecord> stages = db.stages("src-1", "a.csv");
            assertEquals(2, stages.size(), "one row per (batch, file, stage), whatever the timestamp");
            assertEquals("t1", stages.get(0).recordedAt(), "the FIRST write wins; the retry is a no-op");
        }
    }

    @Test
    void aDifferentBatchOrStageStaysDistinct() throws Exception {
        try (DbFileStageStore db = DbFileStageStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    row("a.csv", FileStage.REGISTERED, "t1"),
                    new FileStageRecord("src-1", "a.csv", "batch-2", FileStage.REGISTERED, "t1")));
            assertEquals(2, db.stages("src-1", "a.csv").size(), "same file, two batches — both kept");
        }
    }

    @Test
    void aLegacyUnconstrainedTableIsRebuiltWithDuplicatesCollapsed(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("legacy.duckdb");
        try (Connection c = com.gamma.util.JdbcDrivers.connect(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE file_stages (source_id VARCHAR, relative_path VARCHAR, "
                    + "batch_id VARCHAR, stage VARCHAR, recorded_at VARCHAR)");   // the pre-constraint DDL
            st.execute("INSERT INTO file_stages VALUES "
                    + "('src-1','a.csv','batch-1','REGISTERED','t1'),"
                    + "('src-1','a.csv','batch-1','REGISTERED','t2'),"      // the duplicate the old schema allowed
                    + "('src-1','a.csv','batch-1','MANIFESTED','t3')");
        }
        try (DbFileStageStore db = DbFileStageStore.open(url)) {
            assertEquals(2, db.stages("src-1", "a.csv").size(), "the duplicate collapsed on rebuild");
            db.record(List.of(row("a.csv", FileStage.MANIFESTED, "t9")));
            assertEquals(2, db.stages("src-1", "a.csv").size(), "and the rebuilt table enforces the key");
        }
        try (Connection c = com.gamma.util.JdbcDrivers.connect(url); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables ORDER BY 1")) {
            assertTrue(rs.next());
            assertEquals("file_stages", rs.getString(1));
            assertFalse(rs.next(), "the renamed legacy table is dropped, not left behind");
        }
        try (DbFileStageStore reopened = DbFileStageStore.open(url)) {
            assertEquals(2, reopened.stages("src-1", "a.csv").size(), "a second open does not migrate again");
        }
    }

    @Test
    void emptyAndNullRecordAreNoOps() throws Exception {
        try (DbFileStageStore db = DbFileStageStore.open("jdbc:duckdb:")) {
            assertDoesNotThrow(() -> db.record(List.of()));
            assertDoesNotThrow(() -> db.record(null));
            assertTrue(db.stages("src-1", "a.csv").isEmpty());
        }
    }
}

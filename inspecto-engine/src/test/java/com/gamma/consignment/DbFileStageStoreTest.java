package com.gamma.consignment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

    @Test
    void emptyAndNullRecordAreNoOps() throws Exception {
        try (DbFileStageStore db = DbFileStageStore.open("jdbc:duckdb:")) {
            assertDoesNotThrow(() -> db.record(List.of()));
            assertDoesNotThrow(() -> db.record(null));
            assertTrue(db.stages("src-1", "a.csv").isEmpty());
        }
    }
}

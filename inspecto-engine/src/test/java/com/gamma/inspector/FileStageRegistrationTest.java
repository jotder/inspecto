package com.gamma.inspector;

import com.gamma.consignment.DbFileStageStore;
import com.gamma.consignment.FileStage;
import com.gamma.consignment.FileStageRecord;
import com.gamma.consignment.FileStages;
import com.gamma.etl.Batch;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 §2.4 — the per-file stage-progression registry's production caller on the ingest path.
 * {@code finalizeSource} records a {@link FileStage} row at each boundary it genuinely crosses;
 * this is the "where is file X" answer becoming durable instead of a re-read of the manifest.
 */
class FileStageRegistrationTest {

    @AfterEach
    void clearRegistry() {
        FileStages.use(null);   // the registry is process-wide static — never leak into another test
    }

    private Batch.Member member(PipelineConfig cfg, File f, int id) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        return new Batch.Member(f, id, f.length(), sel);
    }

    private Connection openWithTwoPartitions(File db) throws Exception {
        DuckDbUtil.loadDriver();
        Connection conn = DuckDbUtil.openConnection(db);
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE transformed AS SELECT * FROM (VALUES
                      ('alice', 250.0, '2026', '07', '01', 1),
                      ('bob',    50.0, '2026', '07', '01', 2)
                    ) v(name, cost, year, month, day, __src_id)""");
        }
        return conn;
    }

    @Test
    void recordsTheCrashSafeOrderingAsDurableStages(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path solo = inbox.resolve("solo.csv");
        Files.writeString(solo, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        List<Batch.Member> survivors = List.of(member(cfg, solo.toFile(), 0));
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_stg_0001", "stg", null, survivors);

        try (DbFileStageStore store = DbFileStageStore.open("jdbc:duckdb:")) {
            FileStages.use(store);

            File db = DuckDbUtil.tempDbFile("stg_ingest_");
            try (Connection conn = openWithTwoPartitions(db)) {
                BatchIngestStrategy.Written written = BatchIngestStrategy.writeAndTrace(
                        conn, "transformed", List.of("year", "month", "day"), cfg,
                        cfg.dirs().database(), "b1", batch.batchId(), Map.of(1, "a.csv", 2, "b.csv"));
                BatchProcessor.finalizeSource(batch, cfg, survivors, written.outputs(), written.lineage());
            } finally {
                DuckDbUtil.deleteTempDb(db);
            }

            List<FileStageRecord> stages = store.stages(cfg.collector().id(), "solo.csv");
            List<FileStage> order = stages.stream().map(FileStageRecord::stage).toList();
            assertEquals(List.of(FileStage.REGISTERED, FileStage.MANIFESTED, FileStage.OUTPUT_REGISTERED,
                    FileStage.BACKED_UP, FileStage.MARKED), order,
                    "the durable stage rows mirror finalizeSource's own crash-safe ordering comment");
            for (FileStageRecord r : stages) {
                assertEquals(batch.batchId(), r.batchId());
                assertNotNull(r.recordedAt());
            }
        }
    }

    /** Default-off must stay a true no-op: the commit still succeeds with no registry registered. */
    @Test
    void commitsNormallyWhenNoRegistryIsRegistered(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path solo = inbox.resolve("solo.csv");
        Files.writeString(solo, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        List<Batch.Member> survivors = List.of(member(cfg, solo.toFile(), 0));
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_stg_0002", "stg", null, survivors);

        assertNull(FileStages.shared(), "precondition: no registry for the default space");

        File db = DuckDbUtil.tempDbFile("stg_off_");
        try (Connection conn = openWithTwoPartitions(db)) {
            BatchIngestStrategy.Written written = BatchIngestStrategy.writeAndTrace(
                    conn, "transformed", List.of("year", "month", "day"), cfg,
                    cfg.dirs().database(), "b2", batch.batchId(), Map.of(1, "a.csv", 2, "b.csv"));
            assertDoesNotThrow(() -> BatchProcessor.finalizeSource(
                    batch, cfg, survivors, written.outputs(), written.lineage()));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}

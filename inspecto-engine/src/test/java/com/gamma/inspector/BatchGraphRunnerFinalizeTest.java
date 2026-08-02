package com.gamma.inspector;

import com.gamma.etl.Batch;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.PipelineStores;
import com.gamma.pipeline.exec.BatchGraphRunner;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage A step 2 — the re-homed {@link BatchProcessor#finalizeSource} drives the identical crash-ordered
 * source finalisation (register → manifest → backup → markers LAST → ledger) when it is the
 * {@link BatchGraphRunner}'s {@code SourceFinalizer}. A two-sink route batch writes both sinks, then the
 * source is finalised exactly once over the real inbox; a replay over the same {@code BranchCommitLog} does
 * <em>not</em> re-finalise — which is what keeps the marker-creating step from throwing on the second pass.
 */
class BatchGraphRunnerFinalizeTest {

    private Batch.Member member(PipelineConfig cfg, File f, int id) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        return new Batch.Member(f, id, f.length(), sel);
    }

    @Test
    void finalizeSourceRunsMarkersLastOnceAndIsSkippedOnReplay(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path solo = inbox.resolve("solo.csv");
        Files.writeString(solo, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");

        List<Batch.Member> survivors = List.of(member(cfg, solo.toFile(), 0));
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_mini_0001", "mini", null, survivors);

        String store = PipelineStores.CONFIG_STORE;
        PipelineGraph g = new PipelineGraph("ROUTE_ETL", true,
                List.of(
                        PipelineNode.of("parse", "parser"),
                        PipelineNode.of("r", "transform.route", Map.of(
                                "mode", "case",
                                "branches", List.of(
                                        Map.of("key", "hi", "where", "amt >= 200"),
                                        Map.of("key", "lo", "where", "amt < 200")))),
                        PipelineNode.of("sink_hi", "sink.persistent", Map.of(store, "hi")),
                        PipelineNode.of("sink_lo", "sink.persistent", Map.of(store, "lo"))),
                List.of(
                        PipelineEdge.data("parse", "r"),
                        new PipelineEdge("r", PipelineRel.route("hi"), "sink_hi"),
                        new PipelineEdge("r", PipelineRel.route("lo"), "sink_lo")));

        Path branchLog = dir.resolve("branch_commit.csv");
        int[] finalised = {0};
        BatchGraphRunner.SourceFinalizer finalizer = sinkOutputs -> {
            finalised[0]++;
            BatchProcessor.finalizeSource(batch, cfg, survivors, sinkOutputs);
        };

        // run 1 — a poll cycle over a fresh per-batch connection: both sinks written, source finalised once
        BatchGraphRunner.Result res = runCycle(cfg, g, batch, branchLog, finalizer, "1");
        assertEquals(java.util.Set.of("sink_hi", "sink_lo"), res.exec().sinkInputs().keySet());
        assertTrue(res.exec().commit().sourceFinalized());
        assertEquals(1, finalised[0], "source finalised once, after both branches committed");

        // the re-homed finalize did the real inbox side effects — backup, markers LAST, manifest
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "solo.csv")), "survivor backed up");
        assertTrue(Files.exists(Path.of(cfg.dirs().markers(), "solo.csv.processed")), "marker created (LAST)");
        assertTrue(Files.exists(Path.of(cfg.dirs().manifestsDir(), batch.batchId() + ".json")), "manifest written");
        assertFalse(Files.exists(solo), "survivor moved out of the inbox by backup");

        // replay — a fresh poll cycle (new connection) over the SAME durable BranchCommitLog: the
        // coordinator sees the batch already finalised and skips it, so the marker-creating step never
        // runs twice (which would throw FileAlreadyExists on the already-created marker).
        BatchGraphRunner.Result replay = runCycle(cfg, g, batch, branchLog, finalizer, "2");
        assertFalse(replay.exec().commit().sourceFinalized(), "source not re-finalised on replay");
        assertEquals(1, finalised[0], "finalize invoked exactly once across both cycles");
    }

    /** One poll cycle: a fresh per-batch DuckDB connection holding the parsed seed, run through the graph. */
    private BatchGraphRunner.Result runCycle(PipelineConfig cfg, PipelineGraph g, Batch batch,
                                             Path branchLog, BatchGraphRunner.SourceFinalizer finalizer,
                                             String tag) throws Exception {
        File db = DuckDbUtil.tempDbFile("bgrf_" + tag + "_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            sql(conn, "CREATE TABLE parsed AS SELECT * FROM (VALUES (1,150),(3,200)) t(id,amt)");
            return BatchGraphRunner.run(new BatchGraphRunner.Input(conn, g, "parse", "parsed",
                    batch.batchId(), cfg.dirs().database(), "solo", branchLog), finalizer);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static void sql(Connection conn, String s) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute(s); }
    }
}

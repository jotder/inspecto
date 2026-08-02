package com.gamma.pipeline.exec;

import com.gamma.etl.PartitionOutput;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.PipelineStores;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Stage A step 1 — {@link BatchGraphRunner} drives a {@code parse → filter → route → 2 sinks} batch through
 * the real {@link PartitionSinkWriter} and {@link BranchCommitCoordinator}: both sink branches are written
 * exactly once (each store gets a file on disk) and the injected {@code SourceFinalize} runs exactly once.
 */
class BatchGraphRunnerTest {

    @TempDir Path dir;
    private File db;
    private Connection conn;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("bgr_");
        conn = DuckDbUtil.openConnection(db);
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    @Test
    void runsTwoSinkGraphWritingEachSinkOnceAndFinalisingOnce() throws Exception {
        // the parse stage already produced this relation; the runner executes the downstream subgraph
        sql("CREATE TABLE parsed AS SELECT * FROM (VALUES (1,150),(2,50),(3,200)) t(id,amt)");

        String store = PipelineStores.CONFIG_STORE;
        PipelineGraph g = new PipelineGraph("ROUTE_ETL", true,
                List.of(
                        PipelineNode.of("parse", "parser"),
                        PipelineNode.of("f", "transform.filter", Map.of("where", "amt >= 100")),
                        PipelineNode.of("r", "transform.route", Map.of(
                                "mode", "case",
                                "branches", List.of(
                                        Map.of("key", "hi", "where", "amt >= 200"),
                                        Map.of("key", "lo", "where", "amt < 200")))),
                        PipelineNode.of("sink_hi", "sink.persistent", Map.of(store, "hi")),
                        PipelineNode.of("sink_lo", "sink.persistent", Map.of(store, "lo"))),
                List.of(
                        PipelineEdge.data("parse", "f"),
                        PipelineEdge.data("f", "r"),
                        new PipelineEdge("r", PipelineRel.route("hi"), "sink_hi"),
                        new PipelineEdge("r", PipelineRel.route("lo"), "sink_lo")));

        String dataDir = dir.resolve("data").toString();
        int[] finalised = {0};

        BatchGraphRunner.Result res = BatchGraphRunner.run(
                new BatchGraphRunner.Input(conn, g, "parse", "parsed", "batch1",
                        dataDir, "route_etl", dir.resolve("branch_commit.csv")),
                sinkOutputs -> finalised[0]++);

        // both sinks are branches, fed the routed relations; source finalised exactly once
        assertEquals(java.util.Set.of("sink_hi", "sink_lo"), res.exec().sinkInputs().keySet());
        assertTrue(res.exec().commit().sourceFinalized());
        assertEquals(1, finalised[0], "source finalised exactly once, after both branches committed");

        // each sink branch was written once — one unpartitioned output file per store, both on disk
        assertEquals(2, res.outputs().size(), "one file written per sink branch");
        for (PartitionOutput o : res.outputs())
            assertTrue(Files.exists(Path.of(o.outputFile())), "sink output file exists on disk: " + o.outputFile());
        assertTrue(Files.exists(Path.of(dataDir, "hi")), "store 'hi' directory written");
        assertTrue(Files.exists(Path.of(dataDir, "lo")), "store 'lo' directory written");
        assertEquals(2L, res.totalRows(), "one row routed to each sink (id3→hi, id1→lo)");
    }

    @Test
    void engagementPredicateExcludesQuarantineSoFlatSingleSinkDoesNotEngage() {
        String store = PipelineStores.CONFIG_STORE;
        // the shape a single-schema *_pipeline.toon lifts to: one data sink + a quarantine sink wired
        // ONLY by the `unmatched` control edge (PipelineLift.addQuarantine)
        PipelineGraph flat = new PipelineGraph("FLAT_ETL", true,
                List.of(
                        PipelineNode.of("parse", "parser"),
                        PipelineNode.of("sink", "sink.persistent", Map.of(store, "out")),
                        PipelineNode.of("quarantine", "sink.persistent", Map.of(store, "q"))),
                List.of(
                        PipelineEdge.data("parse", "sink"),
                        new PipelineEdge("parse", PipelineRel.UNMATCHED, "quarantine")));

        assertEquals(1, BatchGraphRunner.dataFedSinkCount(flat), "quarantine (unmatched) is not a data-fed sink");
        assertFalse(BatchGraphRunner.engages(flat), "flat single-sink config keeps the legacy write path");
    }

    @Test
    void engagementPredicateFiresOnMultipleDataFedSinks() {
        String store = PipelineStores.CONFIG_STORE;
        PipelineGraph multi = new PipelineGraph("ROUTE_ETL", true,
                List.of(
                        PipelineNode.of("parse", "parser"),
                        PipelineNode.of("r", "transform.route", Map.of(
                                "mode", "case",
                                "branches", List.of(
                                        Map.of("key", "hi", "where", "amt >= 200"),
                                        Map.of("key", "lo", "where", "amt < 200")))),
                        PipelineNode.of("sink_hi", "sink.persistent", Map.of(store, "hi")),
                        PipelineNode.of("sink_lo", "sink.persistent", Map.of(store, "lo")),
                        PipelineNode.of("quarantine", "sink.persistent", Map.of(store, "q"))),
                List.of(
                        PipelineEdge.data("parse", "r"),
                        new PipelineEdge("r", PipelineRel.route("hi"), "sink_hi"),
                        new PipelineEdge("r", PipelineRel.route("lo"), "sink_lo"),
                        new PipelineEdge("parse", PipelineRel.UNMATCHED, "quarantine")));

        assertEquals(2, BatchGraphRunner.dataFedSinkCount(multi), "two route-fed sinks; quarantine excluded");
        assertTrue(BatchGraphRunner.engages(multi), "multi-sink fan-out needs the branch executor");
    }

    private void sql(String s) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute(s); }
    }
}

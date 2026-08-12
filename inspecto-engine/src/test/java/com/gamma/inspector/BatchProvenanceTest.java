package com.gamma.inspector;

import com.gamma.etl.Batch;
import com.gamma.etl.LineageRow;
import com.gamma.etl.SchemaSelector;
import com.gamma.pipeline.exec.DbProvenanceStore;
import com.gamma.pipeline.exec.ProvenanceStores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3 of {@code consignment-chain-plan.md}: the ingest lane projects per-step counts into the SAME
 * {@code inspecto_pipeline_provenance} matrix the job lane's {@code PipelineExecutor} writes — before
 * this, the EL lane never wrote provenance at all and the editor's edge weights 404'd for every
 * ingest pipeline. Node ids are the editable lift's ({@code parse}/{@code sink}), which is what the
 * {@code GET /provenance} Sankey keys on.
 */
class BatchProvenanceTest {

    @AfterEach
    void clearRegistry() {
        ProvenanceStores.use(null);
    }

    private static Batch batch(Path dir) throws Exception {
        File f = dir.resolve("in.csv").toFile();
        Files.writeString(f.toPath(), "x");
        return new Batch("TS_t1_0001", "s", "t1", List.of(new Batch.Member(
                f, 0, 1L, new SchemaSelector.Selection(Map.of("raw", Map.of("name", "t1")), "t1"))));
    }

    private static IngestOutcome outcome(Batch batch, String status, long inputRows, List<LineageRow> lineage) {
        return new IngestOutcome(LocalDateTime.now(), status, null,
                batch.members(), List.of(), List.of(), lineage, inputRows, "s");
    }

    @Test
    void aSuccessfulBatchProjectsParseAndSinkCounts(@TempDir Path dir) throws Exception {
        try (DbProvenanceStore store = DbProvenanceStore.open("jdbc:duckdb:")) {
            ProvenanceStores.use(store);
            Batch batch = batch(dir);
            // 13 rows parsed; 12 landed across two partition files (one row filtered en route)
            BatchProcessor.recordProvenance("demo_etl", batch, outcome(batch, "SUCCESS", 13L, List.of(
                    new LineageRow(batch.batchId(), 0, "in.csv", "out1.parquet", "day=1", 7L),
                    new LineageRow(batch.batchId(), 0, "in.csv", "out2.parquet", "day=2", 5L))), "SUCCESS");

            List<Map<String, Object>> rows = store.query("demo_etl", batch.batchId());
            assertEquals(2, rows.size(), rows.toString());
            assertEquals("parse", rows.get(0).get("nodeId"));
            assertEquals(13L, ((Number) rows.get(0).get("rowCount")).longValue(),
                    "parse's outgoing edge carries the accepted input rows");
            assertEquals("sink", rows.get(1).get("nodeId"));
            assertEquals(12L, ((Number) rows.get(1).get("rowCount")).longValue(),
                    "sink carries the lineage sum — what actually landed on disk");
        }
    }

    /** A failed batch wrote nothing durable, so it must not paint counts that suggest it did. */
    @Test
    void aFailedBatchRecordsNothing(@TempDir Path dir) throws Exception {
        try (DbProvenanceStore store = DbProvenanceStore.open("jdbc:duckdb:")) {
            ProvenanceStores.use(store);
            Batch batch = batch(dir);
            BatchProcessor.recordProvenance("demo_etl", batch, outcome(batch, "FAILED", 13L, List.of()), "FAILED");
            assertTrue(store.query("demo_etl", batch.batchId()).isEmpty());
        }
    }

    /** Default-off: with no store registered the call is a map lookup, never a throw into the commit path. */
    @Test
    void noRegisteredStoreIsANoOp(@TempDir Path dir) throws Exception {
        Batch batch = batch(dir);
        assertDoesNotThrow(() -> BatchProcessor.recordProvenance(
                "demo_etl", batch, outcome(batch, "SUCCESS", 1L, List.of()), "SUCCESS"));
    }
}

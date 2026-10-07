package com.gamma.pipeline.exec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /provenance/batches} is the Pipeline editor's run picker (PIPELINE-RUN-HISTORY-OVERLAY-1), so
 * each batch says whether it was a dry run before its counts are fetched (DRYRUN-INVISIBLE-ON-FLAT-LANE-1 b).
 */
class DbProvenanceStoreTest {

    @Test
    void batchesAreNewestFirstAndCarryTheDryRunFlag() throws Exception {
        try (DbProvenanceStore store = DbProvenanceStore.open("jdbc:duckdb:")) {
            store.record(List.of(
                    new ProvenanceRow("p", "real-1", "src", "data", 3, "2026-09-20T10:00:00Z"),
                    new ProvenanceRow("p", "real-1", "out", "data", 2, "2026-09-20T10:00:00Z")));
            store.record(List.of(
                    new ProvenanceRow("p", "dry-1", "src", "data", 4, "2026-09-21T10:00:00Z", true)));

            List<Map<String, Object>> batches = store.batches("p", 10);

            assertEquals(2, batches.size(), batches.toString());
            assertEquals("dry-1", batches.get(0).get("batchId"), "newest first");
            assertEquals(Boolean.TRUE, batches.get(0).get("simulated"), "a dry run's batch is marked");
            assertEquals("real-1", batches.get(1).get("batchId"));
            assertEquals(Boolean.FALSE, batches.get(1).get("simulated"), "a real run's batch is not");
            assertEquals(5L, ((Number) batches.get(1).get("totalRows")).longValue());
        }
    }
}

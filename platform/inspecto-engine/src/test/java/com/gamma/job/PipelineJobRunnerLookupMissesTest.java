package com.gamma.job;

import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.FailureText;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.exec.RowShaper;
import com.gamma.pipeline.exec.StepReceipt;
import com.gamma.pipeline.exec.StepReceiptRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LOOKUP-1 §4.6 — the runner stores each keyed join's unmatched values only as fingerprint, length, shape and count,
 * under the Pipeline's Space salt; never for a dry run.
 */
class PipelineJobRunnerLookupMissesTest {

    @AfterEach
    void tearDown() { ConsignmentOutputStores.use(null); }

    private static StepReceiptRecorder recorder(boolean simulated) {
        StepReceiptRecorder r = new StepReceiptRecorder(new PipelineGraph("calls_stage2", false, List.of(), List.of()),
                "c-1", "calls_stage2", StepReceipt.LANE_JOB, "run-1", simulated, null);
        r.lookupMisses("join", List.of(new RowShaper.LookupMiss("msisdn", "447912345678", 3)));
        return r;
    }

    @Test
    void aRunStoresTheFingerprintedSampleAndADryRunStoresNothing(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                TestConfigs.csv(dir.resolve("cfg"), PipelineConfigBatchTest.miniSchema()).write().toString());
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.use(store);

            PipelineJobRunner.flushLookupMisses(recorder(true), cfg);
            assertTrue(store.lookupMisses("calls_stage2", "join", "c-1").isEmpty(), "a dry run stores no sample");

            PipelineJobRunner.flushLookupMisses(recorder(false), cfg);
            var e = store.lookupMisses("calls_stage2", "join", "c-1");
            assertEquals(1, e.size());
            assertEquals(FailureText.fingerprint(cfg, "447912345678"), e.get(0).fingerprint());
            assertEquals("4479••••••••", e.get(0).shape());
            assertEquals(3, e.get(0).n());
            assertFalse(e.toString().contains("447912345678"), "the raw value is never stored");
        }
    }
}

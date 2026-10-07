package com.gamma.etl;

import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the P2 parse/prepare split: {@code fromMap} is a pure parse (no directory creation), the
 * side-effect lives only in {@code prepare()}, and {@code load} (= fromMap + prepare) is unchanged.
 * The pre-existing {@code PipelineConfigBatchTest} remains the regression proof that {@code load}'s
 * observable behaviour is intact; this class proves the new pure entry points exist and behave.
 * (The analogous {@code EnrichmentConfig}/{@code JobConfig} {@code fromMap} cases live in
 * {@code EnrichmentConfigTest}/{@code JobConfigTest} in their own packages — moved there when `etl`
 * was extracted into its own module, since they don't exercise anything etl-specific.)
 */
class ConfigFromMapTest {

    @Test
    void fromMapParsesButDoesNotCreateStatusDir(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        Path statusDir = dir.resolve("status");
        assertFalse(Files.exists(statusDir), "precondition: status dir not yet created");

        PipelineConfig cfg = PipelineConfig.fromMap(ToonHelper.load(p.toString()));

        // pure parse: paths derived, but no filesystem mutation
        assertEquals("mini_etl", cfg.identity().pipelineName());
        assertNotNull(cfg.dirs().statusFilePath());
        assertTrue(cfg.dirs().statusFilePath().contains("_status_"));
        assertNotNull(cfg.dirs().batchesFilePath());
        assertFalse(Files.exists(statusDir), "fromMap must NOT create the status dir");

        // the one side-effect happens only on prepare()
        cfg.prepare();
        assertTrue(Files.exists(statusDir), "prepare() creates the status dir");
        assertDoesNotThrow(cfg::prepare); // idempotent
    }

    @Test
    void loadStillCreatesStatusDir(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig.load(p.toString());
        assertTrue(Files.exists(dir.resolve("status")), "load (= fromMap + prepare) creates the status dir");
    }

    /** VALIDATE-PREPARE-WRITES-STATUS-DIR-1: the read-shaped entry point writes nothing but still refuses. */
    @Test
    void loadForValidationCreatesNothingButStillRefusesAnUnrunnableShape(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig.loadForValidation(p.toString());
        assertFalse(Files.exists(dir.resolve("status")), "loadForValidation must NOT create the status dir");

        // an ACTIVE summarize without output_store: is a requireRunnable() refusal — the same answer load() gives
        Path bad = PipelineConfigBatchTest.writePipeline(dir, "  summarize:\n    group_by[1]: EVENT_DATE\n    measures[1]: count", true);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.loadForValidation(bad.toString()));
        assertTrue(e.getMessage().contains("summarize"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("status")), "a refused config creates nothing either");
    }
}

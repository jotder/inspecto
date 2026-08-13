package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-flow {@code processing.intake} admission-control override (T15 follow-up). Every case goes
 * through a real TOON file via {@link PipelineConfigBatchTest#writePipeline} — never a hand-built map —
 * per the {@code steps:}/{@code sinks:} lesson that a config-format claim is only proven through the
 * codec the routes actually write with.
 *
 * <p>The load-bearing property is <b>absent ≠ stated</b>: an absent block (or key) must stay {@code null}
 * so the {@code IntakeGovernor} call site can inherit the live {@code -Dingest.*} global, while a stated
 * {@code max_files_per_cycle: 0} means "explicitly unbounded" (exempts the flow from a fleet-wide cap).
 */
class PipelineIntakeConfigTest {

    @Test
    void absentBlockMeansInheritTheGlobalsWhole(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        assertNull(cfg.intake(), "no processing.intake ⇒ null ⇒ the -D globals apply untouched");
    }

    @Test
    void fullBlockParsesEveryField(@TempDir Path dir) throws Exception {
        String intake = """
              intake:
                max_files_per_cycle: 25
                min_files_per_cycle: 4
                adaptive: false
            """;
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, intake).toString());
        assertEquals(25, cfg.intake().maxFilesPerCycle());
        assertEquals(4, cfg.intake().minFilesPerCycle());
        assertEquals(Boolean.FALSE, cfg.intake().adaptive());
    }

    @Test
    void unstatedFieldsStayNullSoTheyInheritTheirGlobalCounterpart(@TempDir Path dir) throws Exception {
        String intake = """
              intake:
                max_files_per_cycle: 25
            """;
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, intake).toString());
        assertEquals(25, cfg.intake().maxFilesPerCycle());
        assertNull(cfg.intake().minFilesPerCycle(), "unset min inherits -Dingest.minFilesPerCycle");
        assertNull(cfg.intake().adaptive(), "unset adaptive inherits -Dingest.backpressure.adaptive");
    }

    @Test
    void zeroCapIsAnExplicitExemptionNotAnAbsence(@TempDir Path dir) throws Exception {
        String intake = """
              intake:
                max_files_per_cycle: 0
            """;
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, intake).toString());
        assertEquals(0, cfg.intake().maxFilesPerCycle(),
                "a stated 0 must survive as 0 — it exempts this flow from a fleet-wide cap");
    }

    @Test
    void garbageThresholdsAreNamedLoadErrorsNeverSilentInheritance(@TempDir Path dir) throws Exception {
        String garbage = """
              intake:
                max_files_per_cycle: lots
            """;
        Path p = PipelineConfigBatchTest.writePipeline(dir, garbage);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.load(p.toString()));
        assertTrue(e.getMessage().contains("processing.intake.max_files_per_cycle"),
                "a mistyped threshold must fail by name, not quietly mean 'inherit the global': " + e.getMessage());
    }

    @Test
    void negativeCapAndZeroFloorAreRefused(@TempDir Path dir) throws Exception {
        Path neg = PipelineConfigBatchTest.writePipeline(dir, """
              intake:
                max_files_per_cycle: -1
            """);
        assertThrows(IllegalArgumentException.class, () -> PipelineConfig.load(neg.toString()));

        Path zeroFloor = PipelineConfigBatchTest.writePipeline(dir, """
              intake:
                min_files_per_cycle: 0
            """);   // overwrites the same mini_pipeline.toon — each load follows its own write
        assertThrows(IllegalArgumentException.class, () -> PipelineConfig.load(zeroFloor.toString()),
                "a floor of 0 would let the controller halve a pipeline to a standstill");
    }
}

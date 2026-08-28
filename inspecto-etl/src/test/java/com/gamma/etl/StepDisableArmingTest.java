package com.gamma.etl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 S4a (elt-s4-park-drain-plan): {@code processing.disabled_steps} — the shape parses and
 * round-trips, and arming REFUSES every non-empty list until park/drain semantics ship (S4b). Same
 * split as {@code RouteArmingTest} vs {@code RecordDedupRouteConfigTest}: the rule set here, the
 * {@code prepare()} throw-first behaviour via {@code PipelineConfig.load}.
 */
class StepDisableArmingTest {

    // ── the rule set ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an empty (or absent) list arms — no refusals")
    void emptyListArms() {
        assertEquals(List.of(), StepDisableArming.refusals(List.of()));
        assertEquals(List.of(), StepDisableArming.refusals(null));
    }

    @Test
    @DisplayName("S4a posture: ANY non-empty list refuses, naming the alternatives")
    void anyEntryRefusesUntilParkShips() {
        List<String> refusals = StepDisableArming.refusals(List.of("dedup"));
        assertEquals(1, refusals.size());
        assertTrue(refusals.get(0).contains("park/drain"), refusals.get(0));
        assertTrue(refusals.get(0).contains("dry-run"), "the refusal names its alternative: " + refusals.get(0));
    }

    @Test
    @DisplayName("the draft-map reader: list parsed, absent means empty")
    void draftReaderParsesTheList() {
        assertEquals(List.of("dedup", "join"),
                StepDisableArming.draftDisabledSteps(Map.of("disabled_steps", List.of("dedup", "join"))));
        assertEquals(List.of(), StepDisableArming.draftDisabledSteps(Map.of()));
        assertEquals(List.of(), StepDisableArming.draftDisabledSteps(null));
    }

    // ── parse + prepare, over a real file ───────────────────────────────────────

    private static Path write(Path dir, boolean active, String processingExtra) throws Exception {
        Files.writeString(dir.resolve("mini_schema.toon"), PipelineConfigBatchTest.miniSchema(),
                StandardCharsets.UTF_8);
        String d = dir.toString().replace('\\', '/');
        Path pipeline = dir.resolve("mini_pipeline.toon");
        Files.writeString(pipeline, """
                name: MINI_ETL
                active: %s
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status
                output:
                  format: PARQUET
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schema_file: mini_schema.toon
                %s""".formatted(active, d, d, d, d, d, d, d, processingExtra), StandardCharsets.UTF_8);
        return pipeline;
    }

    @Test
    @DisplayName("an inactive draft parses and round-trips the list")
    void inactiveDraftParsesTheList(@TempDir Path dir) throws Exception {
        Path p = write(dir, false, """
                  disabled_steps[2]: dedup, join
                """);
        PipelineConfig cfg = PipelineConfig.load(p.toString());
        assertEquals(List.of("dedup", "join"), cfg.disabledSteps());
    }

    @Test
    @DisplayName("an ACTIVE pipeline with a disabled step is refused at prepare() — fail closed")
    void activePipelineWithDisabledStepIsRefused(@TempDir Path dir) throws Exception {
        Path p = write(dir, true, """
                  disabled_steps[1]: dedup
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.load(p.toString()));
        assertTrue(e.getMessage().contains("park/drain"), e.getMessage());
    }

    @Test
    @DisplayName("absent list: everything enabled, active pipeline unaffected")
    void absentListLeavesAnActivePipelineAlone(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(write(dir, true, "").toString());
        assertEquals(List.of(), cfg.disabledSteps());
    }
}

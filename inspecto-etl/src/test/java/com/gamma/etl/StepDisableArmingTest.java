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
 * Phase 4 S4 (elt-s4-park-drain-plan): {@code processing.disabled_steps} — the shape parses and
 * round-trips, and arming allows EXACTLY an armed {@code route:} pipeline's branch sinks (they PARK
 * at rest, S4b); everything else refuses by name. Same split as {@code RouteArmingTest} vs
 * {@code RecordDedupRouteConfigTest}: the rule set here, {@code prepare()}'s throw-first behaviour
 * via {@code PipelineConfig.load}.
 */
class StepDisableArmingTest {

    // ── the rule set ────────────────────────────────────────────────────────────

    private static final Map<String, Object> ROUTE = Map.of(
            "mode", "case", "default", "apac",
            "branches", List.of(
                    Map.of("key", "emea", "database", "emea_db", "where", "ID LIKE 'E%'"),
                    Map.of("key", "apac", "database", "apac_db", "where", "ID LIKE 'A%'")));

    @Test
    @DisplayName("an empty (or absent) list arms — no refusals")
    void emptyListArms() {
        assertEquals(List.of(), StepDisableArming.refusals(List.of(), List.of("sink__d0"), true));
        assertEquals(List.of(), StepDisableArming.refusals(null, List.of(), true));
    }

    @Test
    @DisplayName("a route-branch sink may be disabled — it parks at rest (S4b)")
    void aBranchSinkArms() {
        assertEquals(List.of(), StepDisableArming.refusals(
                List.of("sink__d1"), List.of("sink__d0", "sink__d1"), true));
    }

    @Test
    @DisplayName("no armed route: — nothing can park, the whole list refuses")
    void noRouteRefuses() {
        List<String> refusals = StepDisableArming.refusals(List.of("dedup"), List.of(), true);
        assertEquals(1, refusals.size());
        assertTrue(refusals.get(0).contains("no park boundary"), refusals.get(0));
        assertTrue(refusals.get(0).contains("dry-run"), "names its alternative: " + refusals.get(0));
    }

    @Test
    @DisplayName("an upstream or unknown step refuses by name — a typo must never silently enable")
    void upstreamOrUnknownStepRefuses() {
        List<String> refusals = StepDisableArming.refusals(
                List.of("parse", "sink__d0"), List.of("sink__d0", "sink__d1"), true);
        assertEquals(1, refusals.size(), refusals.toString());
        assertTrue(refusals.get(0).contains("'parse'"), refusals.get(0));
        assertTrue(refusals.get(0).contains("sink__d0"), "the refusal names the parkable set: " + refusals.get(0));
    }

    @Test
    @DisplayName("disabling EVERY branch refuses — pausing the pipeline is spelled active: false")
    void allBranchesRefuse() {
        List<String> refusals = StepDisableArming.refusals(
                List.of("sink__d0", "sink__d1"), List.of("sink__d0", "sink__d1"), true);
        assertEquals(1, refusals.size(), refusals.toString());
        assertTrue(refusals.get(0).contains("active: false"), refusals.get(0));
    }

    @Test
    @DisplayName("no park home refuses — dirs.backup is optional, but a parked batch has nowhere to go")
    void noParkHomeRefuses() {
        List<String> refusals = StepDisableArming.refusals(
                List.of("sink__d1"), List.of("sink__d0", "sink__d1"), false);
        assertEquals(1, refusals.size(), refusals.toString());
        assertTrue(refusals.get(0).contains("dirs.backup"), refusals.get(0));
        assertTrue(refusals.get(0).contains("nowhere to park"), refusals.get(0));
        assertTrue(refusals.get(0).contains("dry-run"), "names its alternative: " + refusals.get(0));
    }

    @Test
    @DisplayName("an EMPTY list still arms without a park home — nothing can park, so nothing needs a home")
    void noParkHomeArmsWhenNothingIsDisabled() {
        assertEquals(List.of(), StepDisableArming.refusals(List.of(), List.of("sink__d0"), false));
        assertEquals(List.of(), StepDisableArming.refusals(null, List.of("sink__d0"), false));
    }

    @Test
    @DisplayName("draftHasParkHome mirrors PipelineConfigParser: absent and blank are both 'no home'")
    void draftParkHomeReader() {
        assertTrue(StepDisableArming.draftHasParkHome(Map.of("backup", "/data/backup")));
        assertFalse(StepDisableArming.draftHasParkHome(Map.of("backup", "   ")));
        assertFalse(StepDisableArming.draftHasParkHome(Map.of("poll", "/data/in")));
        assertFalse(StepDisableArming.draftHasParkHome(null));
    }

    @Test
    @DisplayName("parkableSinkIds mirrors the lift's id grammar over plain config data")
    void parkableIdsDerive() {
        assertEquals(List.of("sink__d0", "sink__d1"),
                StepDisableArming.parkableSinkIds(ROUTE, List.of("emea_db", "apac_db")));
        assertEquals(List.of("sink__d1"),
                StepDisableArming.parkableSinkIds(ROUTE, List.of("other_db", "apac_db")),
                "only sinks a branch pairs with are parkable");
        assertEquals(List.of(), StepDisableArming.parkableSinkIds(null, List.of("emea_db")));
        assertEquals(List.of(), StepDisableArming.parkableSinkIds(ROUTE, List.of()));
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
    @DisplayName("an ACTIVE pipeline disabling a non-parkable step is refused at prepare() — fail closed")
    void activePipelineWithNonParkableStepIsRefused(@TempDir Path dir) throws Exception {
        Path p = write(dir, true, """
                  disabled_steps[1]: dedup
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.load(p.toString()));
        assertTrue(e.getMessage().contains("park"), e.getMessage());
    }

    @Test
    @DisplayName("absent list: everything enabled, active pipeline unaffected")
    void absentListLeavesAnActivePipelineAlone(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(write(dir, true, "").toString());
        assertEquals(List.of(), cfg.disabledSteps());
    }
}

package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ELT amendment Phase 2 (route/dedup lowering) + Phase 3 S1 (summarize lowering): the flat-config
 * homes. {@code processing.dedup} is the record-grain dedup Step's section (keys validated like
 * {@code reference.key}); {@code route:} is carried verbatim and is <b>authoring-only</b> — arming a
 * pipeline with it is refused at {@code prepare()} until the branch-aware executor is wired into the
 * ingest path. {@code processing.summarize} is the group-by rollup Step's section (group_by columns
 * validated the same way) and is likewise authoring-only until a recipe-driven executor replaces
 * {@code MaterializeTask}'s standalone Dataset-relation runtime. {@code processing.join} (Phase 3 S2,
 * D-4's one-verb reference join) follows the same three-part contract: parse + column validation +
 * fail-closed arming, since the join model executes post-commit via {@code EnrichmentEngine}.
 */
class RecordDedupRouteConfigTest {

    private static Path write(Path dir, boolean active, String processingExtra, String topLevelExtra)
            throws Exception {
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
                %s%s""".formatted(active, d, d, d, d, d, d, d,
                        processingExtra, topLevelExtra), StandardCharsets.UTF_8);
        return pipeline;
    }

    @Test
    void processingDedupParsesKeysAndOrderBy(@TempDir Path dir) throws Exception {
        Path p = write(dir, false, """
                  dedup:
                    keys[2]: ID, EVENT_DATE
                    order_by: AMT DESC
                """, "");
        PipelineConfig cfg = PipelineConfig.load(p.toString());
        assertNotNull(cfg.dedup());
        assertEquals(java.util.List.of("ID", "EVENT_DATE"), cfg.dedup().keys());
        assertEquals("AMT DESC", cfg.dedup().orderBy());
    }

    @Test
    void aDedupKeyOutsideTheSchemaIsRefusedAtParse(@TempDir Path dir) throws Exception {
        Path p = write(dir, false, """
                  dedup:
                    keys[1]: NO_SUCH_COLUMN
                """, "");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.load(p.toString()));
        assertTrue(e.getMessage().contains("NO_SUCH_COLUMN"), e.getMessage());
    }

    @Test
    void anActivePipelineWithRouteRefusesToArm(@TempDir Path dir) throws Exception {
        Path p = write(dir, true, "", """
                route:
                  mode: case
                  branches[1]{key,where}:
                    emea,"ID LIKE 'E%'"
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.load(p.toString()),
                "the linear batch path cannot execute a branch tree — arming must fail fast");
        assertTrue(e.getMessage().contains("route"), e.getMessage());
    }

    @Test
    void anInactiveDraftWithRouteParsesAndCarriesTheBlockVerbatim(@TempDir Path dir) throws Exception {
        Path p = write(dir, false, "", """
                route:
                  mode: case
                  branches[1]{key,where}:
                    emea,"ID LIKE 'E%'"
                """);
        PipelineConfig cfg = PipelineConfig.load(p.toString());
        assertNotNull(cfg.routeConfig());
        assertEquals("case", cfg.routeConfig().get("mode"));
    }

    @Test
    void processingSummarizeParsesGroupByAndMeasures(@TempDir Path dir) throws Exception {
        Path p = write(dir, false, """
                  summarize:
                    group_by[1]: EVENT_DATE
                    measures[2]: count, "sum(AMT)"
                """, "");
        PipelineConfig cfg = PipelineConfig.load(p.toString());
        assertNotNull(cfg.summarize());
        assertEquals(java.util.List.of("EVENT_DATE"), cfg.summarize().groupBy());
        assertEquals(java.util.List.of("count", "sum(AMT)"), cfg.summarize().measures());
    }

    @Test
    void aSummarizeGroupByColumnOutsideTheSchemaIsRefusedAtParse(@TempDir Path dir) throws Exception {
        Path p = write(dir, false, """
                  summarize:
                    group_by[1]: NO_SUCH_COLUMN
                    measures[1]: count
                """, "");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.load(p.toString()));
        assertTrue(e.getMessage().contains("NO_SUCH_COLUMN"), e.getMessage());
    }

    @Test
    void processingJoinParsesReferenceAndOnIncludingTheSingleKeyShorthand(@TempDir Path dir) throws Exception {
        Path p = write(dir, false, """
                  join:
                    reference: reference/region_dim
                    on: ID
                """, "");
        PipelineConfig cfg = PipelineConfig.load(p.toString());
        assertNotNull(cfg.join());
        assertEquals("reference/region_dim", cfg.join().reference());
        assertEquals(java.util.List.of("ID"), cfg.join().on(), "on: k is the single-key shorthand (D-4)");
    }

    @Test
    void aJoinOnColumnOutsideTheSchemaIsRefusedAtParse(@TempDir Path dir) throws Exception {
        Path p = write(dir, false, """
                  join:
                    reference: reference/region_dim
                    on[1]: NO_SUCH_COLUMN
                """, "");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.load(p.toString()));
        assertTrue(e.getMessage().contains("NO_SUCH_COLUMN"), e.getMessage());
    }

    // ── the steps: chain arms nothing yet, and the guards above cannot see it ──────────

    /**
     * ⚠ <b>The fourth arming guard, and the one the other three cannot cover.</b> They test the TYPED
     * fields — {@code route}, {@code summarize}, {@code join} — and an explicit {@code steps:} file never
     * populates any of them: the parser refuses the two spellings in one file, so those fields are null
     * no matter what the chain says. Without a guard of its own, a {@code steps:} pipeline carrying a
     * summarize would pass every check above and run on the linear path, which reads {@code dedup()} and
     * {@code csv.rowWhere()} and would therefore apply <em>none</em> of the chain — the config saves,
     * loads, arms, and silently runs a different pipeline than the one authored.
     *
     * <p>That is the multiplicity plan's own failure mode relocated one layer down, so the format slice
     * (A3) that made {@code steps:} writable is the slice that has to fail closed. Lifted by A5.
     */
    @Test
    void anActivePipelineWithAStepsChainRefusesToArm(@TempDir Path dir) throws Exception {
        Path p = write(dir, true, "", """
                steps[2]:
                  - dedup:
                      keys[1]: ID
                  - dedup:
                      keys[1]: EVENT_DATE
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.load(p.toString()),
                "nothing reads steps: yet — arming would run a pipeline that applies none of the chain");
        assertTrue(e.getMessage().contains("steps:"), e.getMessage());
    }

    /**
     * ⚠ <b>A {@code steps:} written without its element count is REFUSED, not ignored.</b> Found by
     * probing the codec rather than by reading it: toon decodes a bare {@code steps:} as a <em>map</em>
     * ({@code {- dedup={…}}}), so the parser's {@code instanceof List} test simply did not match and the
     * entire chain vanished — no error, no warning, a pipeline running none of its authored transforms.
     * The one silent-discard shape this whole format was introduced to remove, recreated in its reader.
     */
    @Test
    void aStepsBlockWithoutItsElementCountIsRefusedRatherThanSilentlyIgnored(@TempDir Path dir)
            throws Exception {
        Path p = write(dir, false, "", """
                steps:
                  - dedup:
                      keys[1]: ID
                """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.load(p.toString()));
        assertTrue(e.getMessage().contains("steps[2]:"),
                "the refusal shows the spelling that works: " + e.getMessage());
    }

    /** …and the same chain in an inactive draft parses and keeps its order, which is the point of A3. */
    @Test
    void anInactiveDraftWithAStepsChainParsesAndKeepsItsOrder(@TempDir Path dir) throws Exception {
        Path p = write(dir, false, "", """
                steps[3]:
                  - dedup:
                      keys[1]: ID
                  - summarize:
                      group_by[1]: EVENT_DATE
                      measures[1]: count
                  - dedup:
                      keys[1]: AMT
                """);
        PipelineConfig cfg = PipelineConfig.load(p.toString());
        assertTrue(cfg.hasExplicitSteps(), "an authored chain is not the legacy projection");
        assertEquals(java.util.List.of("dedup", "summarize", "dedup"),
                cfg.steps().stream().map(PipelineConfig.Step::kind).toList());
        assertNull(cfg.dedup(), "the singular slot stays empty — the chain is the only spelling here");
    }

    @Test
    void anActivePipelineWithJoinRefusesToArm(@TempDir Path dir) throws Exception {
        Path p = write(dir, true, """
                  join:
                    reference: reference/region_dim
                    on[1]: ID
                """, "");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.load(p.toString()),
                "the join model executes post-commit via EnrichmentEngine, never in the linear ingest path — arming must fail fast");
        assertTrue(e.getMessage().contains("join"), e.getMessage());
    }

    /** ⚠ Added 2026-08-11: this refusal is the load-bearing half of dedup's Stage-2 move (`04004af8`,
     *  BREAKING) and had NO pinning test until a falsification of the A5 gate change stayed green. */
    @Test
    void anActivePipelineWithDedupRefusesToArm(@TempDir Path dir) throws Exception {
        Path p = write(dir, true, """
                  dedup:
                    keys[1]: ID
                """, "");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.load(p.toString()),
                "record dedup no longer executes on the linear path — arming would silently keep every duplicate");
        assertTrue(e.getMessage().contains("dedup"), e.getMessage());
    }

    // ── A5-at-rest (2026-08-11): output_store: is the arming condition for the Stage-2 blocks ──

    /** With an authored output_store: the chain has a real route (the at-rest flow job), so arming is
     *  the intended EL/T split — not the silent skip the gates exist to refuse. */
    @Test
    void anAuthoredOutputStoreArmsAStageTwoPipeline(@TempDir Path dir) throws Exception {
        Path p = write(dir, true, """
                  dedup:
                    keys[1]: ID
                """, "output_store: mini_shaped\n");
        PipelineConfig cfg = PipelineConfig.load(p.toString());
        assertNotNull(cfg.dedup());
        assertEquals("mini_shaped", cfg.outputStore());
    }

    /** route: stays refused regardless — the at-rest route refuses it too (one output_store cannot
     *  name N branches), so arming would only defer the refusal to the job's first run. */
    @Test
    void routeStaysRefusedEvenWithAnOutputStore(@TempDir Path dir) throws Exception {
        Path p = write(dir, true, "", """
                route:
                  mode: case
                  branches[1]{key,where}:
                    emea,"ID LIKE 'E%'"
                output_store: mini_shaped
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.load(p.toString()));
        assertTrue(e.getMessage().contains("route"), e.getMessage());
    }

    @Test
    void anAuthoredOutputStoreArmsAnExplicitStepsChain(@TempDir Path dir) throws Exception {
        Path p = write(dir, true, "", """
                steps[2]:
                  - dedup:
                      keys[1]: ID
                  - summarize:
                      group_by[1]: EVENT_DATE
                      measures[1]: count
                output_store: mini_shaped
                """);
        PipelineConfig cfg = PipelineConfig.load(p.toString());
        assertTrue(cfg.hasExplicitSteps());
        assertEquals("mini_shaped", cfg.outputStore());
    }

    @Test
    void aStepsChainCarryingARouteStepRefusesEvenWithAnOutputStore(@TempDir Path dir) throws Exception {
        Path p = write(dir, true, "", """
                steps[1]:
                  - route:
                      mode: case
                output_store: mini_shaped
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.load(p.toString()));
        assertTrue(e.getMessage().contains("branch-aware"), e.getMessage());
    }

    @Test
    void anActivePipelineWithSummarizeRefusesToArm(@TempDir Path dir) throws Exception {
        Path p = write(dir, true, """
                  summarize:
                    measures[1]: count
                """, "");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.load(p.toString()),
                "MaterializeTask, not the linear batch path, is the only executor for this measure grammar — arming must fail fast");
        assertTrue(e.getMessage().contains("summarize"), e.getMessage());
    }
}

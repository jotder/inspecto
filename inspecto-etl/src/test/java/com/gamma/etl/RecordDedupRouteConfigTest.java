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

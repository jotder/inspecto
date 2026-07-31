package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConfigRoutes#schemaFileFindings}: the pre-flight check that a pipeline draft's
 * {@code schema_file} reference(s) resolve on this server before save/registration (v4.1.0).
 */
class SchemaFileFindingsTest {

    @Test
    void missingLegacySchemaFileIsFlaggedAtTheRequestedSeverity() {
        Map<String, Object> draft = Map.of("name", "X",
                "processing", Map.of("schema_file", "no/such/dir/ghost_schema.toon"));

        List<Finding> warn = ConfigRoutes.schemaFileFindings("pipeline", draft, Severity.WARNING);
        assertEquals(1, warn.size());
        assertEquals(Severity.WARNING, warn.get(0).severity());
        assertEquals("processing.schema_file", warn.get(0).fieldPath());
        assertTrue(warn.get(0).message().contains("ghost_schema.toon"));

        assertEquals(Severity.ERROR,
                ConfigRoutes.schemaFileFindings("pipeline", draft, Severity.ERROR).get(0).severity());
    }

    @Test
    void resolvableSchemaFileIsClean(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("ok_schema.toon");
        Files.writeString(schema, "raw:\n  name: ok\n");
        Map<String, Object> draft = Map.of("name", "X",
                "processing", Map.of("schema_file", schema.toString()));
        assertTrue(ConfigRoutes.schemaFileFindings("pipeline", draft, Severity.ERROR).isEmpty());
    }

    @Test
    void multiSchemaEntriesAreCheckedIndividually(@TempDir Path dir) throws Exception {
        Path ok = dir.resolve("ok_schema.toon");
        Files.writeString(ok, "raw:\n  name: ok\n");
        Map<String, Object> draft = Map.of("name", "X", "processing", Map.of("schemas", List.of(
                Map.of("schema_file", ok.toString(), "column_count", 3),
                Map.of("schema_file", dir.resolve("ghost.toon").toString(), "column_count", 5))));

        List<Finding> f = ConfigRoutes.schemaFileFindings("pipeline", draft, Severity.ERROR);
        assertEquals(1, f.size(), "only the unresolvable entry is flagged");
        assertEquals("processing.schemas[1].schema_file", f.get(0).fieldPath());
    }

    @Test
    void nonPipelineTypesAndAbsentOrBlankReferencesAreNoOps() {
        assertTrue(ConfigRoutes.schemaFileFindings("job",
                Map.of("processing", Map.of("schema_file", "ghost.toon")), Severity.ERROR).isEmpty());
        assertTrue(ConfigRoutes.schemaFileFindings("pipeline", Map.of("name", "X"), Severity.ERROR).isEmpty());
        assertTrue(ConfigRoutes.schemaFileFindings("pipeline",
                Map.of("processing", Map.of("schema_file", " ")), Severity.ERROR).isEmpty());
    }

    // ── W1b: the gate must resolve exactly as PipelineConfig.load does ─────────────────

    /**
     * The bug this closes: a portable, config-relative reference (a bare basename beside its pipeline)
     * loads fine in the engine, so a gate that only checked the working-directory form would have
     * ERROR'd at registration on a config that runs — blocking the promotion path W1b exists to enable.
     */
    @Test
    void aConfigRelativeReferenceIsCleanWhenTheConfigDirectoryIsKnown(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("orders_schema.toon"), "raw:\n  name: ok\n");
        Map<String, Object> draft = Map.of("name", "X",
                "processing", Map.of("schema_file", "orders_schema.toon"));

        assertTrue(ConfigRoutes.schemaFileFindings("pipeline", draft, Severity.ERROR, dir).isEmpty(),
                "a bare basename beside the config resolves — the engine would load it");
        assertEquals(1, ConfigRoutes.schemaFileFindings("pipeline", draft, Severity.WARNING).size(),
                "with no config directory there is nothing to be relative to, so it is still flagged");
    }

    @Test
    void aParentEscapingReferenceIsStillFlagged(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("outside_schema.toon"), "raw:\n  name: ok\n");
        Path configDir = Files.createDirectories(tmp.resolve("cfg"));
        Map<String, Object> draft = Map.of("name", "X",
                "processing", Map.of("schema_file", "../outside_schema.toon"));
        assertEquals(1, ConfigRoutes.schemaFileFindings("pipeline", draft, Severity.ERROR, configDir).size(),
                "the config-relative branch is contained, so a ../ escape does not resolve");
    }
}

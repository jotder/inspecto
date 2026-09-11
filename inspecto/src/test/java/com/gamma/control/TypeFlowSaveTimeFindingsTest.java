package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConfigRoutes#routeColumnFindings} and {@link ConfigRoutes#summarizeMeasureFindings} —
 * the two save-time checks `TYPEFLOW-CONSUMERS-1` (a) filled in, wiring `TypeFlow`'s
 * derive-without-executing into the write path that `elt-final-amendment-plan.md` P2 S2 deferred.
 *
 * <p>⚠ The third check that row named — a Mapping over a nonexistent field — is NOT here: it was
 * already built as {@code SchemaMappingDrift.check} and is covered by its own tests. The row claimed
 * all three were missing.
 *
 * <p>🔴 The load-bearing negative case in this class is
 * {@link #anUnresolvableSchemaReferenceSaysNOTHINGRatherThanFlaggingEveryColumn}: these checks must
 * treat "cannot read the schema" as nothing to say. Treating it as "no columns declared" would refuse
 * every save made before the schema file exists, which is the normal authoring order — and is how a
 * check like this turns into a block on ordinary work.
 */
class TypeFlowSaveTimeFindingsTest {

    /** A schema TOON declaring ID VARCHAR + QTY INTEGER, written through the real codec. */
    private static Path schemaFile(Path dir, String name) throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", name);
        raw.put("format", "CSV");
        raw.put("fields", List.of(
                Map.of("name", "ID", "selector", "0", "type", "VARCHAR"),
                Map.of("name", "QTY", "selector", "1", "type", "INTEGER")));
        Path file = dir.resolve(name + ".toon");
        Files.writeString(file, ConfigCodec.toToon(Map.of("raw", raw)), StandardCharsets.UTF_8);
        return file;
    }

    private static Map<String, Object> pipelineWithRoute(Path schema, boolean active, String where) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "P");
        draft.put("active", active);
        draft.put("processing", Map.of("schema_file", schema.toString()));
        draft.put("route", Map.of("branches", List.of(
                Map.of("key", "big", "database", "db1", "where", where))));
        return draft;
    }

    private static Map<String, Object> pipelineWithSummarize(Path schema, boolean active, List<String> measures) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "P");
        draft.put("active", active);
        draft.put("processing", Map.of("schema_file", schema.toString(),
                "summarize", Map.of("group_by", List.of("ID"), "measures", measures)));
        return draft;
    }

    // ── route: predicate columns ─────────────────────────────────────────────────

    @Test
    void aPredicateOverADroppedColumnIsRefusedAndTheColumnIsNAMED(@TempDir Path dir) throws Exception {
        Path schema = schemaFile(dir, "ev");
        List<Finding> out = ConfigRoutes.routeColumnFindings(
                "pipeline", pipelineWithRoute(schema, true, "AMOUNT > 100"), dir);

        assertEquals(1, out.size(), "one branch, one refusal");
        assertEquals(Severity.ERROR, out.get(0).severity(), "the pipeline is active — it would really fail");
        assertEquals(FindingCodes.ERR_ROUTE_PREDICATE_COLUMN, out.get(0).code());
        assertEquals("route.branches[big].where", out.get(0).fieldPath());
        // DuckDB's binder names the column; that is the whole reason this binds instead of regexing.
        assertTrue(out.get(0).message().contains("AMOUNT"),
                "the binder's own message must name the offending column: " + out.get(0).message());
    }

    @Test
    void aPredicateOverADeclaredColumnIsClean(@TempDir Path dir) throws Exception {
        Path schema = schemaFile(dir, "ev");
        assertTrue(ConfigRoutes.routeColumnFindings(
                "pipeline", pipelineWithRoute(schema, true, "QTY > 100"), dir).isEmpty());
    }

    @Test
    void anInactiveDraftOnlyWarnsSoMidAuthoringSavesKeepWorking(@TempDir Path dir) throws Exception {
        Path schema = schemaFile(dir, "ev");
        List<Finding> out = ConfigRoutes.routeColumnFindings(
                "pipeline", pipelineWithRoute(schema, false, "AMOUNT > 100"), dir);
        assertEquals(1, out.size());
        assertEquals(Severity.WARNING, out.get(0).severity());
        assertEquals(FindingCodes.WARN_ROUTE_PREDICATE_COLUMN, out.get(0).code());
    }

    @Test
    void aBlankPredicateIsLeftToRouteArmingRatherThanDoubleReported(@TempDir Path dir) throws Exception {
        Path schema = schemaFile(dir, "ev");
        assertTrue(ConfigRoutes.routeColumnFindings(
                        "pipeline", pipelineWithRoute(schema, true, "   "), dir).isEmpty(),
                "routeArmingFindings owns the missing-predicate refusal; two messages for one fault is noise");
    }

    @Test
    void anUnsafePredicateIsREPORTEDRatherThanSilentlySkipped(@TempDir Path dir) throws Exception {
        // 🔴 The first cut ran SqlGuard over the BARE predicate. SqlGuard requires SQL to begin with
        // SELECT/WITH, so every predicate violated it and every branch was skipped — the check passed
        // everything while looking like it worked. The guard now runs on the assembled statement, and a
        // genuine violation is reported instead of skipped, so neither failure mode is silent.
        Path schema = schemaFile(dir, "ev");
        List<Finding> out = ConfigRoutes.routeColumnFindings(
                "pipeline", pipelineWithRoute(schema, true, "QTY > 1; DROP TABLE input"), dir);
        assertEquals(1, out.size());
        assertTrue(out.get(0).message().contains("not a safe read-only expression"), out.get(0).message());
    }

    @Test
    void aNonPipelineTypeIsNotChecked(@TempDir Path dir) throws Exception {
        Path schema = schemaFile(dir, "ev");
        assertTrue(ConfigRoutes.routeColumnFindings(
                "schema", pipelineWithRoute(schema, true, "AMOUNT > 100"), dir).isEmpty());
    }

    // ── summarize measure types ──────────────────────────────────────────────────

    @Test
    void summingANonNumericDeclaredFieldIsRefused(@TempDir Path dir) throws Exception {
        Path schema = schemaFile(dir, "ev");
        List<Finding> out = ConfigRoutes.summarizeMeasureFindings(
                "pipeline", pipelineWithSummarize(schema, true, List.of("sum(ID)")), dir);

        assertEquals(1, out.size());
        assertEquals(Severity.ERROR, out.get(0).severity());
        assertEquals(FindingCodes.ERR_SUMMARIZE_MEASURE_TYPE, out.get(0).code());
        assertTrue(out.get(0).message().contains("VARCHAR"), out.get(0).message());
    }

    @Test
    void summingANumericDeclaredFieldIsClean(@TempDir Path dir) throws Exception {
        Path schema = schemaFile(dir, "ev");
        assertTrue(ConfigRoutes.summarizeMeasureFindings(
                "pipeline", pipelineWithSummarize(schema, true, List.of("sum(QTY)", "avg(QTY)")), dir).isEmpty());
    }

    @Test
    void countAndMinMaxOverANonNumericFieldAreNOTFlagged(@TempDir Path dir) throws Exception {
        Path schema = schemaFile(dir, "ev");
        // count ignores the value; min/max order text and dates perfectly well. Flagging these would be
        // taste, not a type error — and a check that cries wolf gets switched off.
        assertTrue(ConfigRoutes.summarizeMeasureFindings("pipeline",
                pipelineWithSummarize(schema, true, List.of("count", "min(ID)", "max(ID)")), dir).isEmpty());
    }

    @Test
    void aFieldTheSchemaDoesNotDeclareAtAllIsLeftToTheOtherFault(@TempDir Path dir) throws Exception {
        Path schema = schemaFile(dir, "ev");
        assertTrue(ConfigRoutes.summarizeMeasureFindings("pipeline",
                        pipelineWithSummarize(schema, true, List.of("sum(GHOST)")), dir).isEmpty(),
                "no declared type to judge — this check answers 'is it numeric', not 'does it exist'");
    }

    @Test
    void aMalformedMeasureIsLeftToTheExecutorsRefusal(@TempDir Path dir) throws Exception {
        Path schema = schemaFile(dir, "ev");
        assertTrue(ConfigRoutes.summarizeMeasureFindings("pipeline",
                        pipelineWithSummarize(schema, true, List.of("sum QTY")), dir).isEmpty(),
                "a second, differently-worded syntax error from a TYPE checker is noise");
    }

    // ── the shared column resolution ─────────────────────────────────────────────

    @Test
    void anUnresolvableSchemaReferenceSaysNOTHINGRatherThanFlaggingEveryColumn(@TempDir Path dir) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "P");
        draft.put("active", true);
        draft.put("processing", Map.of("schema_file", "no/such/ghost_schema.toon",
                "summarize", Map.of("measures", List.of("sum(ID)"))));
        draft.put("route", Map.of("branches", List.of(
                Map.of("key", "big", "database", "db1", "where", "AMOUNT > 100"))));

        assertTrue(ConfigRoutes.declaredColumns(draft, dir).isEmpty());
        // 🔴 Both checks must stay silent. A schema file that does not exist YET is the normal authoring
        // order (schemaFileFindings keeps it a WARNING for exactly that reason); treating unknown as
        // "declares nothing" would refuse every such save.
        assertTrue(ConfigRoutes.routeColumnFindings("pipeline", draft, dir).isEmpty());
        assertTrue(ConfigRoutes.summarizeMeasureFindings("pipeline", draft, dir).isEmpty());
    }

    @Test
    void aSPLITSchemasFieldsAreFoundInTheSiblingStructureCsv(@TempDir Path dir) throws Exception {
        // 🔴 STRUCTURE-CSV-1: a split schema's TOON carries NO raw.fields — they live in the sibling CSV.
        // Without merging it, declaredColumns sees zero columns, both checks fall silent, and every
        // predicate and measure passes. A guard that quietly stops looking is worse than no guard.
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "split");
        raw.put("format", "CSV");
        Path toon = dir.resolve("split.toon");
        Files.writeString(toon, ConfigCodec.toToon(Map.of("raw", raw)), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("split_structure.csv"),
                "field,type,selector,unit,description,classification\nQTY,INTEGER,1,,,\n",
                StandardCharsets.UTF_8);

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "P");
        draft.put("active", true);
        draft.put("processing", Map.of("schema_file", toon.toString()));

        assertEquals(1, ConfigRoutes.declaredColumns(draft, dir).size(),
                "the structure sibling must be merged, or the checks silently pass everything");
        assertEquals("QTY", ConfigRoutes.declaredColumns(draft, dir).get(0).name());
    }
}

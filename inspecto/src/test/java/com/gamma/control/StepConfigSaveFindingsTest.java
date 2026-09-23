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
 * {@link ConfigRoutes#stepConfigFindings} — the save-time half of the lookup / profile / dedup / filter
 * refusals {@code RowShaper} otherwise throws only on the first run (`PROCESSOR-RELEASE-READINESS-1` G4).
 * Each fault below saved clean before this check and failed at run.
 */
class StepConfigSaveFindingsTest {

    /** A schema TOON declaring ID VARCHAR + QTY INTEGER, written through the real codec. */
    private static Path schemaFile(Path dir) throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "ev");
        raw.put("format", "CSV");
        raw.put("fields", List.of(
                Map.of("name", "ID", "selector", "0", "type", "VARCHAR"),
                Map.of("name", "QTY", "selector", "1", "type", "INTEGER")));
        Path file = dir.resolve("ev.toon");
        Files.writeString(file, ConfigCodec.toToon(Map.of("raw", raw)), StandardCharsets.UTF_8);
        return file;
    }

    private static Map<String, Object> pipeline(Path schema, boolean active, List<Object> steps) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "P");
        draft.put("active", active);
        if (schema != null) draft.put("processing", Map.of("schema_file", schema.toString()));
        draft.put("steps", steps);
        return draft;
    }

    private static List<Finding> check(Path dir, Map<String, Object> draft) {
        return ConfigRoutes.stepConfigFindings("pipeline", draft, dir);
    }

    private static void assertOneRefusal(List<Finding> out, String field, String mustMention) {
        assertEquals(1, out.size(), "one fault, one finding: " + out);
        Finding f = out.get(0);
        assertEquals(Severity.ERROR, f.severity(), "active — it would really fail at run");
        assertEquals(FindingCodes.ERR_STEP_CONFIG_INVALID, f.code());
        assertEquals(field, f.fieldPath());
        assertTrue(f.message().contains(mustMention), "message must name the fault: " + f.message());
    }

    // ── lookup ───────────────────────────────────────────────────────────────────

    @Test
    void aLookupWithNoColumnIsRefused(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true,
                List.of(Map.of("lookup", Map.of("mappings", List.of("1=one")))))), "steps[0].lookup", "column");
    }

    @Test
    void aLookupWithNoMappingsIsRefused(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true,
                List.of(Map.of("lookup", Map.of("column", "ID"))))), "steps[0].lookup", "mappings");
    }

    @Test
    void aLookupMappingThatIsNotKeyEqualsValueIsRefusedByName(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true, List.of(Map.of("lookup",
                Map.of("column", "ID", "mappings", List.of("1=one", "two")))))), "steps[0].lookup", "'two'");
    }

    @Test
    void aLookupOverAnUndeclaredColumnIsRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(schemaFile(dir), true, List.of(Map.of("lookup",
                Map.of("column", "CODE", "mappings", List.of("1=one")))))), "steps[0].lookup", "CODE");
    }

    // ── filter ───────────────────────────────────────────────────────────────────

    @Test
    void aFilterWithABlankPredicateIsRefused(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true,
                List.of(Map.of("filter", Map.of("where", " "))))), "steps[0].filter", "where");
    }

    @Test
    void aFilterOverAnUndeclaredColumnIsRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(schemaFile(dir), true,
                List.of(Map.of("filter", Map.of("where", "AMOUNT > 0"))))), "steps[0].filter", "AMOUNT");
    }

    @Test
    void aFilterOverALookupTargetIsClean(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true, List.of(
                Map.of("lookup", Map.of("column", "ID", "target", "ID_NAME", "mappings", List.of("1=one"))),
                Map.of("filter", Map.of("where", "ID_NAME <> 'x' AND lower(ID) LIKE 'a%'"))))));
    }

    @Test
    void aFilterAfterAnSqlStepIsNotJudged(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true, List.of(
                Map.of("sql", Map.of("sql", "SELECT ID, QTY * 2 AS DOUBLED FROM input")),
                Map.of("filter", Map.of("where", "DOUBLED > 4"))))));
    }

    /** The shipped filter_step schema: raw fields + a mapping with custom-derived REGION and GROSS. */
    private static Path mappedSchemaFile(Path dir) throws Exception {
        Path file = dir.resolve("filter_step_schema.toon");
        Files.writeString(file, """
                raw:
                  name: ORDERS
                  format: CSV
                  fields[3]{name,selector,type}:
                    QUANTITY,"0",INTEGER
                    UNIT_PRICE,"1",DOUBLE
                    STATUS,"2",VARCHAR
                mapping:
                  canonicalName: filter_step
                  rawName: ORDERS
                  fields[2]:
                    - name: STATUS
                      from: STATUS
                      fn: keep
                    - name: GROSS
                      from: ""
                      fn: custom
                      args:
                        expression: "ROUND(TRY_CAST(QUANTITY AS DOUBLE) * TRY_CAST(UNIT_PRICE AS DOUBLE), 2)"
                """, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void aFilterOverAMappedDerivedColumnIsClean(@TempDir Path dir) throws Exception {
        // Regression: the shipped filter_step pipeline, refused when only the RAW fields were known.
        List<Finding> out = check(dir, pipeline(mappedSchemaFile(dir), true,
                List.of(Map.of("filter", Map.of("where", "STATUS = 'SHIPPED' AND GROSS >= 30")))));
        assertEquals(List.of(), out, () -> out.isEmpty() ? "" : out.get(0).message());
    }

    @Test
    void aFilterOverAColumnNeitherRawNorMappedIsStillRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(mappedSchemaFile(dir), true,
                List.of(Map.of("filter", Map.of("where", "NETT >= 30"))))), "steps[0].filter", "NETT");
    }

    @Test
    void aFilterWhoseBindFailsForAnyOtherReasonFailsOpen(@TempDir Path dir) throws Exception {
        // A type mismatch is not an unknown column; the save-time check must not guess about it.
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true,
                List.of(Map.of("filter", Map.of("where", "no_such_function(QTY) > 0"))))));
    }

    // ── route branch sub-chains ──────────────────────────────────────────────────

    private static Map<String, Object> routeStep(List<Object> branchSteps) {
        return Map.of("route", Map.of("branches", List.of(
                Map.of("key", "all", "where", "true", "steps", branchSteps))));
    }

    @Test
    void aBranchStepIsCheckedLikeATopLevelStep(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true, List.of(routeStep(List.of(
                Map.of("dedup", Map.of("keys", List.of()))))))), "steps[0].route.branches[0].steps[0].dedup", "keys");
    }

    @Test
    void aBranchStepStartsFromTheColumnsKnownAtTheRoutePoint(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(schemaFile(dir), true, List.of(
                Map.of("lookup", Map.of("column", "ID", "target", "ID_NAME", "mappings", List.of("1=one"))),
                routeStep(List.of(
                        Map.of("filter", Map.of("where", "ID_NAME <> 'x'")),   // the lookup target: known
                        Map.of("dedup", Map.of("keys", List.of("ID_NAME", "MSISDN")))))))),
                "steps[1].route.branches[0].steps[1].dedup", "MSISDN");
    }

    @Test
    void aBranchFilterOverAnUndeclaredColumnIsRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(schemaFile(dir), true, List.of(routeStep(List.of(
                Map.of("filter", Map.of("where", "QTY > 0 AND MSISDN = '1'"))))))),
                "steps[0].route.branches[0].steps[0].filter", "MSISDN");
    }

    @Test
    void aLegacyRouteBlockBranchStepIsChecked(@TempDir Path dir) throws Exception {
        Map<String, Object> draft = pipeline(schemaFile(dir), true, List.of());
        draft.remove("steps");
        draft.put("route", Map.of("branches", List.of(Map.of("key", "a", "where", "true",
                "steps", List.of(Map.of("dedup", Map.of("keys", List.of("NOPE"))))))));
        assertOneRefusal(check(dir, draft), "route.branches[0].steps[0].dedup", "NOPE");
    }

    @Test
    void aValidBranchSubChainIsClean(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true, List.of(routeStep(List.of(
                Map.of("filter", Map.of("where", "QTY > 0")),
                Map.of("dedup", Map.of("keys", List.of("ID")))))))));
    }

    @Test
    void aBranchStepAfterAnSqlStepInsideTheBranchIsNotJudged(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true, List.of(routeStep(List.of(
                Map.of("sql", Map.of("sql", "SELECT ID AS K FROM input")),
                Map.of("filter", Map.of("where", "K = 'a'"))))))));
    }

    // ── dedup ────────────────────────────────────────────────────────────────────

    @Test
    void aDedupWithNoKeysIsRefused(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true,
                List.of(Map.of("dedup", Map.of("keys", List.of()))))), "steps[0].dedup", "keys");
    }

    @Test
    void aLegacyDedupBlockWithNoKeysIsRefused(@TempDir Path dir) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "P");
        draft.put("active", true);
        draft.put("processing", Map.of("dedup", Map.of("order_by", "ID")));
        assertOneRefusal(check(dir, draft), "processing.dedup", "keys");
    }

    @Test
    void aDedupKeyTheSchemaDoesNotDeclareIsRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(schemaFile(dir), true,
                List.of(Map.of("dedup", Map.of("keys", List.of("id", "MSISDN")))))), "steps[0].dedup", "MSISDN");
    }

    // ── profile ──────────────────────────────────────────────────────────────────

    @Test
    void aProfileColumnTheSchemaDoesNotDeclareIsRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(schemaFile(dir), true,
                List.of(Map.of("profile", Map.of("columns", List.of("QTY", "AMOUNT")))))), "steps[0].profile", "AMOUNT");
    }

    @Test
    void aLegacyProfileBlockOverAnUndeclaredColumnIsRefused(@TempDir Path dir) throws Exception {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "P");
        draft.put("active", true);
        draft.put("processing", Map.of("schema_file", schemaFile(dir).toString(),
                "profile", Map.of("columns", List.of("AMOUNT"))));
        assertOneRefusal(check(dir, draft), "processing.profile", "AMOUNT");
    }

    // ── severity, silence and the valid shapes ───────────────────────────────────

    @Test
    void anInactiveDraftIsWarnedNotRefused(@TempDir Path dir) {
        List<Finding> out = check(dir, pipeline(null, false,
                List.of(Map.of("dedup", Map.of("keys", List.of())))));
        assertEquals(1, out.size());
        assertEquals(Severity.WARNING, out.get(0).severity());
        assertEquals(FindingCodes.WARN_STEP_CONFIG_INVALID, out.get(0).code());
    }

    @Test
    void aValidChainOfAllFourKindsIsClean(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true, List.of(
                Map.of("filter", Map.of("where", "QTY > 0")),
                Map.of("lookup", Map.of("column", "ID", "target", "ID_NAME", "mappings", List.of("1=one"))),
                Map.of("dedup", Map.of("keys", List.of("id", "ID_NAME"))),
                Map.of("profile", Map.of("columns", List.of("QTY", "ID_NAME")))))));
    }

    @Test
    void anEmptyProfileMeansEveryColumnAndIsClean(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true, List.of(Map.of("profile", Map.of())))));
    }

    @Test
    void columnsAreNotJudgedAfterAStepThatReshapesTheRow(@TempDir Path dir) throws Exception {
        // After an author SELECT the row's columns are no longer the schema's — unknown is not wrong.
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true, List.of(
                Map.of("sql", Map.of("sql", "SELECT ID, QTY * 2 AS DOUBLED FROM input")),
                Map.of("dedup", Map.of("keys", List.of("DOUBLED")))))));
    }

    @Test
    void anUnreadableSchemaSaysNothingAboutColumns(@TempDir Path dir) {
        Map<String, Object> draft = pipeline(null, true, List.of(Map.of("dedup", Map.of("keys", List.of("X")))));
        draft.put("processing", Map.of("schema_file", dir.resolve("missing.toon").toString()));
        assertEquals(List.of(), check(dir, draft));
    }

    @Test
    void theSaveGateRunsTheCheck(@TempDir Path dir) {
        List<Finding> out = SaveGate.check(null, "pipeline",
                pipeline(null, true, List.of(Map.of("lookup", Map.of("column", "ID")))),
                dir, dir, SaveGate.Referents.MAY_ARRIVE_LATER);
        assertTrue(out.stream().anyMatch(f -> FindingCodes.ERR_STEP_CONFIG_INVALID.equals(f.code())), out.toString());
        assertTrue(SaveGate.refuses(out));
    }
}

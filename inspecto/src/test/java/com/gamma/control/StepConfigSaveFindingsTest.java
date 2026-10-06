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

    // ── hash (quality.crypto.hash, 2026-10-06) ──────────────────────────────────

    @Test
    void aHashWithNoColumnsIsRefused(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true,
                List.of(Map.of("hash", Map.of())))), "steps[0].hash", "columns");
    }

    @Test
    void aHashCarryingASaltIsRefusedByName(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true, List.of(Map.of("hash",
                Map.of("columns", List.of("ID"), "salt", "pepper"))))), "steps[0].hash", "'salt'");
    }

    @Test
    void aHashOverAnUndeclaredColumnIsRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(schemaFile(dir), true, List.of(Map.of("hash",
                Map.of("columns", List.of("MSISDN")))))), "steps[0].hash", "MSISDN");
    }

    @Test
    void aFilterOverAKeptHashColumnIsClean(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true, List.of(
                Map.of("hash", Map.of("columns", List.of("ID"), "keep_original", true)),
                Map.of("filter", Map.of("where", "ID_hash IS NOT NULL AND QTY > 0"))))));
    }

    // ── mask (quality.pii.mask, 2026-10-06) ─────────────────────────────────────

    @Test
    void aMaskWithNothingToMaskIsRefused(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true,
                List.of(Map.of("mask", Map.of("mode", "full"))))), "steps[0].mask", "something to mask");
    }

    @Test
    void aMaskByClassificationWithoutADatasetOrWithAnUnknownClassIsRefused(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true, List.of(Map.of("mask",
                Map.of("classifications", List.of("PII")))))), "steps[0].mask", "dataset");
        assertOneRefusal(check(dir, pipeline(null, true, List.of(Map.of("mask",
                Map.of("classifications", List.of("SECRET"), "dataset", "d"))))), "steps[0].mask", "'SECRET'");
    }

    @Test
    void aMaskVaultKeyIsRefusedByName(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true, List.of(Map.of("mask",
                Map.of("columns", List.of("ID"), "vault", "v"))))), "steps[0].mask", "'vault'");
    }

    @Test
    void aMaskOverAnUndeclaredColumnIsRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(schemaFile(dir), true, List.of(Map.of("mask",
                Map.of("columns", List.of("EMAIL"), "mode", "partial"))))), "steps[0].mask", "EMAIL");
    }

    // ── explode (operator 2026-10-06) ────────────────────────────────────────────

    @Test
    void anExplodeWithNoColumnIsRefused(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true,
                List.of(Map.of("explode", Map.of("as", "item"))))), "steps[0].explode", "column");
    }

    @Test
    void anExplodeNewNameThatIsNotAnIdentifierIsRefusedByName(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true, List.of(Map.of("explode",
                Map.of("column", "ID", "index_column", "n; DROP"))))), "steps[0].explode", "'n; DROP'");
    }

    @Test
    void anExplodeWithAnUnknownOnEmptyIsRefused(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true, List.of(Map.of("explode",
                Map.of("column", "ID", "on_empty", "skip"))))), "steps[0].explode", "on_empty");
    }

    @Test
    void anExplodeOverAnUndeclaredColumnIsRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(schemaFile(dir), true, List.of(Map.of("explode",
                Map.of("column", "CHARGES"))))), "steps[0].explode", "CHARGES");
    }

    @Test
    void aWellFormedExplodeIsNotRefused(@TempDir Path dir) throws Exception {
        assertTrue(check(dir, pipeline(schemaFile(dir), true, List.of(Map.of("explode",
                Map.of("column", "ID", "as", "item", "on_empty", "drop"))))).stream()
                .noneMatch(f -> FindingCodes.ERR_STEP_CONFIG_INVALID.equals(f.code())),
                "the positive probe: a valid explode raises no step finding");
    }

    // ── unpivot (operator 2026-10-06) ────────────────────────────────────────────

    @Test
    void anUnpivotWithNeitherOrBothSelectorsIsRefused(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true, List.of(Map.of("unpivot", Map.of("value_type", "BIGINT"))))),
                "steps[0].unpivot", "exactly one");
        assertOneRefusal(check(dir, pipeline(null, true, List.of(Map.of("unpivot",
                Map.of("columns", List.of("ID"), "columns_pattern", "^H"))))), "steps[0].unpivot", "exactly one");
    }

    @Test
    void anUnpivotWithABadPatternNameOrValueTypeIsRefusedByName(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true, List.of(Map.of("unpivot",
                Map.of("columns_pattern", "(["))))), "steps[0].unpivot", "regular expression");
        assertOneRefusal(check(dir, pipeline(null, true, List.of(Map.of("unpivot",
                Map.of("columns", List.of("ID"), "value_column", "v x"))))), "steps[0].unpivot", "'v x'");
        assertOneRefusal(check(dir, pipeline(null, true, List.of(Map.of("unpivot",
                Map.of("columns", List.of("ID"), "value_type", "BLOB"))))), "steps[0].unpivot", "BLOB");
    }

    @Test
    void anUnpivotOverAnUndeclaredColumnIsRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(schemaFile(dir), true, List.of(Map.of("unpivot",
                Map.of("columns", List.of("H07")))))), "steps[0].unpivot", "H07");
    }

    @Test
    void aWellFormedUnpivotIsNotRefused(@TempDir Path dir) throws Exception {
        assertTrue(check(dir, pipeline(schemaFile(dir), true, List.of(Map.of("unpivot",
                Map.of("columns", List.of("ID"), "value_type", "decimal(18,4)"))))).stream()
                .noneMatch(f -> FindingCodes.ERR_STEP_CONFIG_INVALID.equals(f.code())),
                "the positive probe: a valid unpivot raises no step finding");
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

    // ── legacy processing.csv_settings.where filter ──────────────────────────────

    private static Map<String, Object> legacyFilter(Path schema, String where) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "P");
        draft.put("active", true);
        draft.put("processing", Map.of("schema_file", schema.toString(),
                "csv_settings", Map.of("where", where)));
        return draft;
    }

    @Test
    void aLegacyFilterOverAnUndeclaredColumnIsRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, legacyFilter(schemaFile(dir), "AMOUNT > 0")),
                "processing.csv_settings.where", "AMOUNT");
    }

    @Test
    void aLegacyFilterOverAMappedColumnIsClean(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, legacyFilter(mappedSchemaFile(dir), "STATUS = 'SHIPPED' AND GROSS >= 30")));
    }

    @Test
    void aLegacyFilterWhoseBindFailsForAnyOtherReasonFailsOpen(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, legacyFilter(schemaFile(dir), "no_such_function(QTY) > 0")));
    }

    // ── route STEP branch predicates ─────────────────────────────────────────────

    private static Map<String, Object> routeWhere(String where) {
        return Map.of("route", Map.of("branches", List.of(Map.of("key", "hot", "where", where))));
    }

    @Test
    void aRouteStepPredicateOverAnUndeclaredColumnIsRefused(@TempDir Path dir) throws Exception {
        List<Finding> out = check(dir, pipeline(schemaFile(dir), true, List.of(routeWhere("MSISDN = '1'"))));
        assertEquals(1, out.size(), out.toString());
        assertEquals(Severity.ERROR, out.get(0).severity());
        assertEquals(FindingCodes.ERR_ROUTE_PREDICATE_COLUMN, out.get(0).code());
        assertEquals("steps[0].route.branches[hot].where", out.get(0).fieldPath());
        assertTrue(out.get(0).message().contains("MSISDN"), out.get(0).message());
    }

    @Test
    void aRouteStepPredicateOverAMappedColumnIsClean(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, pipeline(mappedSchemaFile(dir), true, List.of(routeWhere("GROSS > 10")))));
    }

    @Test
    void aRouteStepPredicateWhoseBindFailsForAnyOtherReasonFailsOpen(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true,
                List.of(routeWhere("no_such_function(QTY) > 0")))));
    }

    @Test
    void aRouteStepPredicateAfterAnSqlStepIsNotJudged(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true, List.of(
                Map.of("sql", Map.of("sql", "SELECT ID AS K FROM input")),
                routeWhere("K = 'a'")))));
    }

    @Test
    void aBlankRouteStepPredicateIsNotReportedHere(@TempDir Path dir) throws Exception {
        // routeArmingFindings owns the blank-predicate refusal - no double report.
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true, List.of(routeWhere(" ")))));
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

    // ── running (transform.running, 2026-10-06) ──────────────────────────────────

    private static Map<String, Object> running(String partition, String orderBy, String window) {
        return Map.of("running", Map.of("partition_by", List.of(partition), "order_by", orderBy, "window", window,
                "measures", List.of(Map.of("fn", "count", "as", "N"))));
    }

    @Test
    void aRunningKeyTheSchemaDoesNotDeclareIsRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(schemaFile(dir), true,
                List.of(running("MSISDN", "QTY", "3 rows")))), "steps[0].running", "MSISDN");
    }

    @Test
    void aMalformedRunningWindowIsRefusedEvenWithNoSchema(@TempDir Path dir) {
        assertOneRefusal(check(dir, pipeline(null, true, List.of(running("ID", "QTY", "60x")))),
                "steps[0].running", "neither a duration");
    }

    @Test
    void aDurationWindowOverANumericColumnIsRefused(@TempDir Path dir) throws Exception {
        assertOneRefusal(check(dir, pipeline(schemaFile(dir), true, List.of(running("ID", "QTY", "60m")))),
                "steps[0].running", "TIMESTAMP or DATE");
    }

    /** VARCHAR fails OPEN: a mapping-derived column reads VARCHAR here whatever it really computes. */
    @Test
    void aDurationWindowOverAVarcharColumnIsNotJudged(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true, List.of(running("QTY", "ID", "60m")))));
    }

    @Test
    void aValidRunningRowWindowIsClean(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), check(dir, pipeline(schemaFile(dir), true, List.of(running("ID", "QTY", "5 rows")))));
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

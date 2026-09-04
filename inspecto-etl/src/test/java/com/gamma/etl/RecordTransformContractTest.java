package com.gamma.etl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Java Record Transformer catalog vs the committed TS contract
 * ({@code sql-functions.contract.json}) — the same pattern as {@code ProcessorCatalogContractTest}.
 * Regenerate deliberately with {@code -Drecord.transform.write=true}, then check the TS suite agrees.
 *
 * <p>🔴 <b>Why this test carries real weight.</b> {@link RecordTransform} and {@code sql-functions.ts}
 * both compile a field row to SQL — the browser preview renders one, the engine executes the other. A
 * silent divergence would show an author one expression and run a different one, which is the failure
 * this whole seam exists to prevent.
 */
class RecordTransformContractTest {

    private static final String CONTRACT = "inspecto-ui/src/app/inspecto/contracts/sql-functions.contract.json";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Resolve against the REPO ROOT (the ancestor holding {@code inspecto-ui/}) rather than by probing
     * for the contract file itself — the file does not exist on a first generation, and locating by its
     * own presence would make the very run that creates it fail.
     */
    private static Path contractPath() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("inspecto-ui"))) return dir.resolve(CONTRACT);
        }
        throw new AssertionError("cannot locate the repo root from " + Path.of("").toAbsolutePath());
    }

    @Test
    void theJavaCatalogMatchesTheCommittedContract() throws IOException {
        String actual = JSON.writeValueAsString(RecordTransform.toContract()).replace("\r\n", "\n").trim();
        if (Boolean.getBoolean("record.transform.write")) {
            Files.createDirectories(contractPath().getParent());
            Files.writeString(contractPath(), actual + "\n");
            return;
        }
        String expected = Files.readString(contractPath()).replace("\r\n", "\n").trim();
        assertEquals(expected, actual, "the Java function catalog and " + CONTRACT + " disagree — if the "
                + "Java side is right, regenerate with -Drecord.transform.write=true and check the TS suite still passes");
    }

    /** Every parameter placeholder a template mentions is declared, and every declared param is used. */
    @Test
    void everyTemplatePlaceholderIsADeclaredParameter() {
        for (RecordTransform.Fn fn : RecordTransform.SQL_FUNCTIONS) {
            var declared = fn.params().stream().map(RecordTransform.Param::name).toList();
            var matcher = java.util.regex.Pattern.compile("\\{([a-z_]+)}").matcher(fn.template());
            while (matcher.find()) {
                String ph = matcher.group(1);
                if ("source".equals(ph)) continue;
                assertTrue(declared.contains(ph),
                        fn.id() + ": template uses {" + ph + "} but declares no such parameter");
            }
            for (String p : declared)
                assertTrue(fn.template().contains("{" + p + "}"),
                        fn.id() + ": declares parameter '" + p + "' the template never uses");
        }
    }

    /**
     * ⛔ The forgiving-by-construction rule, asserted rather than trusted: a bare {@code CAST} kills the
     * whole batch where {@code TRY_CAST} nulls one cell, and the cast-failure audit counts the latter.
     */
    @Test
    void everyCastIsForgiving() {
        for (RecordTransform.Fn fn : RecordTransform.SQL_FUNCTIONS) {
            assertFalse(fn.template().matches(".*(?<!TRY_)\\bCAST\\s*\\(.*"),
                    fn.id() + ": uses a bare CAST — every cast in this catalog must be TRY_CAST");
            if (fn.template().contains("/"))
                assertTrue(fn.template().contains("NULLIF"),
                        fn.id() + ": divides without guarding the divisor with NULLIF");
        }
    }

    /** A `text` parameter must be quoted as a literal, never spliced — the injection-shaped mistake. */
    @Test
    void aTextParameterRendersAsAQuotedLiteral() {
        RecordTransform.Fn replace = RecordTransform.function("text.replace");
        var r = RecordTransform.renderExpression(replace, "NOTE",
                Map.of("find", "O'Brien", "replacement", "x"));
        assertTrue(r.ok(), r.problem());
        assertTrue(r.expr().contains("'O''Brien'"),
                "an embedded quote must be doubled, got: " + r.expr());
    }

    /** An enum parameter refuses a value outside its options rather than emitting it. */
    @Test
    void anEnumParameterRefusesAnUndeclaredValue() {
        RecordTransform.Fn cast = RecordTransform.function("convert.type");
        var bad = RecordTransform.renderExpression(cast, "AMOUNT", Map.of("type", "DROP TABLE"));
        assertFalse(bad.ok(), "an undeclared enum value must not reach the SQL");
        var good = RecordTransform.renderExpression(cast, "AMOUNT", Map.of("type", "DOUBLE"));
        assertTrue(good.ok());
        assertEquals("TRY_CAST(AMOUNT AS DOUBLE)", good.expr());
    }

    /**
     * The audit's measurability rule, both arms: a {@code custom} row is excluded exactly as {@code EXPR}
     * is (author-owned SQL has no defined "source was non-blank"), and a VARCHAR pass-through is excluded
     * because it cannot null out.
     */
    @Test
    void theAuditSkipsCustomRowsAndVarcharPassThroughs() {
        Map<String, String> types = Map.of("AMOUNT", "DOUBLE", "NOTE", "VARCHAR");

        assertEquals("AMOUNT", RecordTransform.auditedSourceColumn(
                Map.of("name", "amt", "from", "AMOUNT", "fn", "keep"), types),
                "a coercing pass-through is measurable");
        assertNull(RecordTransform.auditedSourceColumn(
                Map.of("name", "n", "from", "NOTE", "fn", "keep"), types),
                "a VARCHAR pass-through cannot null out, so it is not counted");
        assertNull(RecordTransform.auditedSourceColumn(
                Map.of("name", "x", "from", "AMOUNT", "fn", "custom",
                        "args", Map.of("expression", "1+1")), types),
                "a custom expression has no defined denominator — the EXPR rule");
    }

    /**
     * 🔴 The migration's whole safety argument in one assertion: on the RAW (all-VARCHAR) source, a
     * {@code keep} field compiles byte-identically to what a {@code DIRECT} mapping rule emits, so
     * converting a stored schema cannot change the SQL that runs.
     */
    @Test
    void keepOnARawSourceMatchesADirectMappingRuleExactly() {
        Map<String, Object> schema = Map.of("raw", Map.of("fields",
                List.of(Map.of("name", "AMOUNT", "selector", "0", "type", "DOUBLE"))));
        PipelineConfig.CsvSettings csv =
                PipelineConfig.CsvSettings.ofFormats(List.of("%Y-%m-%d"), List.of("%Y-%m-%d %H:%M:%S"));
        SourceZones zones = SourceZones.of(schema, null);
        Map<String, String> types = Map.of("AMOUNT", "DOUBLE");

        String viaRule = TransformCompiler.dataColumn(
                Map.of("targetColumn", "AMOUNT", "sourceExpression", "AMOUNT", "transformType", "DIRECT"),
                types, "raw_input", csv, zones);

        List<Map<String, Object>> viaFields = RecordTransform.compile(
                List.of(Map.of("name", "AMOUNT", "from", "AMOUNT", "fn", "keep")),
                types, csv, zones, "raw_input", false);

        assertEquals(viaRule, viaFields.get(0).get("expr"),
                "a keep field and a DIRECT rule must emit the same SQL, or the migration changes behaviour");
    }
}

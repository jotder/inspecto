package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 — the {@code fields[]} spelling reaching the shared {@code [{name, expr}]} seam that BOTH
 * execution lanes read ({@code DataTransformer.selectFor} on ingest, {@code RowShaper.columnsOf} at
 * rest). These assert the branch itself; the catalog's faithfulness is
 * {@link RecordTransformContractTest}'s job.
 */
class RecordTransformLaneTest {

    private static final PipelineConfig.CsvSettings CSV =
            PipelineConfig.CsvSettings.ofFormats(List.of("%Y-%m-%d"), List.of("%Y-%m-%d %H:%M:%S"));

    private static Map<String, Object> schema(Map<String, Object> mapping) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "orders");
        raw.put("fields", List.of(
                Map.of("name", "ORDER_ID", "selector", "0", "type", "VARCHAR"),
                Map.of("name", "AMOUNT", "selector", "1", "type", "DOUBLE")));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("raw", raw);
        s.put("mapping", mapping);
        return s;
    }

    @Test
    void aFieldsProjectionCompilesThroughTheSharedSeam() {
        Map<String, Object> s = schema(Map.of("fields", List.of(
                Map.of("name", "order_ref", "from", "ORDER_ID", "fn", "text.trim"),
                Map.of("name", "amount_cents", "from", "AMOUNT", "fn", "num.multiply",
                        "args", Map.of("factor", "100")))));

        List<Map<String, Object>> cols = DataTransformer.dataColumns(s, CSV, "raw_input");

        assertEquals(List.of("order_ref", "amount_cents"), cols.stream().map(c -> c.get("name")).toList());
        assertEquals("TRIM(ORDER_ID)", cols.get(0).get("expr"));
        assertEquals("(AMOUNT * 100)", cols.get(1).get("expr"));
    }

    /**
     * ⛔ The legacy spelling must be untouched — every stored pipeline still uses it, and this branch
     * is additive or it is a regression.
     */
    @Test
    void aRulesProjectionStillCompilesExactlyAsBefore() {
        Map<String, Object> s = schema(Map.of("rules", List.of(
                Map.of("targetColumn", "AMOUNT", "sourceExpression", "AMOUNT", "transformType", "DIRECT"))));

        List<Map<String, Object>> cols = DataTransformer.dataColumns(s, CSV, "raw_input");

        assertEquals(1, cols.size());
        assertEquals("AMOUNT", cols.get(0).get("name"));
        assertEquals("TRY_CAST(\"raw_input\".\"AMOUNT\" AS DOUBLE)", cols.get(0).get("expr"));
    }

    /**
     * 🔴 An EMPTY fields[] must not silently project zero columns where a mapping would have projected
     * the schema — empty is treated as absent, so the legacy path still runs.
     */
    @Test
    void anEmptyFieldsListFallsBackToTheRulesRatherThanProjectingNothing() {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("fields", List.of());
        mapping.put("rules", List.of(
                Map.of("targetColumn", "AMOUNT", "sourceExpression", "AMOUNT", "transformType", "DIRECT")));

        List<Map<String, Object>> cols = DataTransformer.dataColumns(schema(mapping), CSV, "raw_input");

        assertEquals(1, cols.size(), "an empty fields[] must not win over the rules");
        assertEquals("AMOUNT", cols.get(0).get("name"));
    }

    /**
     * On the raw ALL-VARCHAR relation a `keep` field casts to its declared type; a column declared
     * VARCHAR passes through unchanged (castSql returns the column for VARCHAR), which is also why the
     * same compile is correct at rest, where no types are declared at all.
     */
    @Test
    void keepCastsADeclaredTypeAndPassesVarcharThrough() {
        Map<String, Object> s = schema(Map.of("fields", List.of(
                Map.of("name", "AMOUNT", "from", "AMOUNT", "fn", "keep"),
                Map.of("name", "ORDER_ID", "from", "ORDER_ID", "fn", "keep"))));

        List<Map<String, Object>> cols = DataTransformer.dataColumns(s, CSV, "raw_input");

        assertEquals("TRY_CAST(\"raw_input\".\"AMOUNT\" AS DOUBLE)", cols.get(0).get("expr"));
        assertEquals("\"raw_input\".\"ORDER_ID\"", cols.get(1).get("expr"),
                "a VARCHAR column is a pass-through, not a cast");
    }

    /**
     * 🔴 The regression this branch actually caused, pinned. A stored {@code sql} step may carry
     * {@code fields[]} rows shaped {@code {name, expr}} — a pre-rendered column list, not Record
     * Transformer rows (see {@code PipelineJobRunnerTest.runsAFlatConfigsSqlStepOverItsLandedStore}).
     * Diverting on the KEY's presence broke it; the diversion is gated on every row naming an {@code fn}.
     */
    @Test
    void aNameExprFieldListIsNotARecordTransformerListAndDoesNotDivert() {
        List<Map<String, Object>> nameExpr = List.of(Map.of("name", "amt", "expr", "amt * 2"));
        assertFalse(RecordTransform.isFieldList(nameExpr),
                "{name, expr} rows are a pre-rendered column list — they must keep running the stored sql");

        assertTrue(RecordTransform.isFieldList(
                List.of(Map.of("name", "amt", "from", "AMOUNT", "fn", "keep"))));
        assertFalse(RecordTransform.isFieldList(List.of()), "empty is absent");
        assertFalse(RecordTransform.isFieldList(
                List.of(Map.of("name", "a", "fn", "keep"), Map.of("name", "b", "expr", "1"))),
                "a MIXED list is not a field list — one un-compilable row must not divert the whole node");
    }

    /** A field naming a function this build does not have is refused, naming the field. */
    @Test
    void anUnknownFunctionIsRefusedNamingTheField() {
        Map<String, Object> s = schema(Map.of("fields", List.of(
                Map.of("name", "x", "from", "AMOUNT", "fn", "no.such.function"))));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DataTransformer.dataColumns(s, CSV, "raw_input"));
        assertTrue(e.getMessage().contains("no.such.function"), e.getMessage());
        assertTrue(e.getMessage().contains("'x'"), e.getMessage());
    }
}

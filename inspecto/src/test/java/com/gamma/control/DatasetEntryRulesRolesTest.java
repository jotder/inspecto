package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code columns[].role} on a Dataset entry (LA-COLUMN-ROLES-1 P0, operator decision B 2026-11-04): one vocabulary,
 * {@code entity | temporal | measure | dimension | detail}, and a {@code unit} on a measure only. An entry without
 * roles, and one carrying the roles a materialization seeds, still passes.
 */
class DatasetEntryRulesRolesTest {

    private static Map<String, Object> col(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> entry(Map<String, Object>... cols) {
        return Map.of("physicalRef", "t", "columns", List.of(cols));
    }

    private static String refusal(Map<String, Object> e) {
        return assertThrows(IllegalArgumentException.class, () -> DatasetEntryRules.validate(e)).getMessage();
    }

    @Test
    void everyRoleWithAUnitOnAMeasurePasses() {
        assertDoesNotThrow(() -> DatasetEntryRules.validate(entry(
                col("name", "caller", "role", "entity", "classification", "MSISDN"),
                col("name", "booked_at", "role", "temporal"),
                col("name", "settled_at", "role", "Temporal"),
                col("name", "amount", "role", "measure", "unit", "USD"),
                col("name", "channel", "role", "dimension"),
                col("name", "note", "role", "detail"))));
    }

    @Test
    void anEntryWithoutRolesStillLoads() {
        assertDoesNotThrow(() -> DatasetEntryRules.validate(entry(
                col("name", "caller", "classification", "MSISDN"), col("name", "amount", "type", "number"))));
    }

    @Test
    void refusesAnUnknownRole() {
        assertTrue(refusal(entry(col("name", "amount", "role", "amount"))).contains("allowed"));
        assertTrue(refusal(entry(col("name", "amount", "role", 3))).contains("allowed"));
    }

    @Test
    void anEntityColumnHoldsAnEntityType() {
        assertTrue(refusal(entry(col("name", "caller", "role", "entity"))).contains("holds no Entity Type"));
    }

    @Test
    void aUnitBelongsToAMeasureAndIsShortText() {
        assertTrue(refusal(entry(col("name", "channel", "role", "dimension", "unit", "USD"))).contains("only a measure"));
        assertTrue(refusal(entry(col("name", "channel", "unit", "USD"))).contains("only a measure"));
        assertTrue(refusal(entry(col("name", "amount", "role", "measure", "unit", " "))).contains("short text"));
        assertTrue(refusal(entry(col("name", "amount", "role", "measure", "unit", "x".repeat(33)))).contains("short text"));
    }

    // ── P1: the primaries and the numeric check ─────────────────────────────────────────────────────────────────────

    private static Map<String, Object> linked(Map<String, Object> mapping, Map<String, Object>... cols) {
        Map<String, Object> e = new LinkedHashMap<>(entry(cols));
        e.put("defaultLinkMapping", mapping);
        return e;
    }

    private static final Map<String, Object> A = col("name", "a", "role", "entity", "classification", "ACCOUNT");
    private static final Map<String, Object> B = col("name", "b", "role", "entity", "classification", "ACCOUNT");

    @Test
    void thePrimariesNameATimeAndAMeasure() {
        assertDoesNotThrow(() -> DatasetEntryRules.validate(linked(
                Map.of("from", "a", "to", "b", "primaryTime", "booked_at", "primaryMeasure", "amount"),
                A, B, col("name", "booked_at", "role", "temporal"), col("name", "settled_at", "role", "temporal"),
                col("name", "amount", "type", "number", "role", "measure"), col("name", "fee", "type", "DECIMAL(18,2)", "role", "measure"))));
        assertTrue(refusal(linked(Map.of("from", "a", "to", "b", "primaryTime", "amount"),
                A, B, col("name", "amount", "type", "number", "role", "measure")))
                .contains("primaryTime names 'amount', which is not a Time column (its role is measure)"));
        assertTrue(refusal(linked(Map.of("from", "a", "to", "b", "primaryMeasure", "booked_at"),
                A, B, col("name", "booked_at", "role", "temporal")))
                .contains("primaryMeasure names 'booked_at', which is not a Measure column"));
    }

    @Test
    void theOldNamesAreReadAndMayNotDisagree() {
        // an entry written before P1 (time / amount, no roles) still loads
        assertDoesNotThrow(() -> DatasetEntryRules.validate(linked(Map.of("from", "a", "to", "b", "time", "ts", "amount", "amt"),
                col("name", "a"), col("name", "b"), col("name", "ts"), col("name", "amt", "type", "number"))));
        // the old name is checked like the new one
        assertTrue(refusal(linked(Map.of("from", "a", "to", "b", "time", "amt"),
                A, B, col("name", "amt", "type", "number", "role", "measure"))).contains("not a Time column"));
        assertTrue(refusal(linked(Map.of("from", "a", "to", "b", "primaryTime", "ts", "time", "ts2"),
                A, B, col("name", "ts"), col("name", "ts2"))).contains("state primaryTime only"));
        assertDoesNotThrow(() -> DatasetEntryRules.validate(linked(Map.of("from", "a", "to", "b", "primaryTime", "ts", "time", "TS"),
                A, B, col("name", "ts"))));
    }

    @Test
    void aMeasureHoldsNumbers() {
        assertTrue(refusal(entry(col("name", "msisdn", "type", "number", "role", "measure", "classification", "MSISDN")))
                .contains("holds an Entity Type (MSISDN), so it is an identifier, not a measure"));
        assertTrue(refusal(entry(col("name", "currency", "type", "string", "role", "measure")))
                .contains("is a measure but holds string values, not numbers"));
        assertTrue(refusal(linked(Map.of("from", "a", "to", "b", "amount", "currency"), A, B, col("name", "currency", "type", "VARCHAR")))
                .contains("holds VARCHAR values, not numbers"));
        // a column without a type is a Dataset read at query time: not checked
        assertDoesNotThrow(() -> DatasetEntryRules.validate(entry(col("name", "amount", "role", "measure"))));
        for (String t : List.of("number", "BIGINT", "double", "DECIMAL(10,2)", "integer"))
            assertDoesNotThrow(() -> DatasetEntryRules.validate(entry(col("name", "amount", "type", t, "role", "measure"))), t);
    }

    // ── P2: what rides on the links ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void everyTimeMeasureAndCategoryIsCarriedUnlessKeptOff() {
        Map<String, Object> e = linked(Map.of("from", "a", "to", "b", "kind", "channel"),
                A, B, col("name", "channel", "role", "dimension"), col("name", "booked_at", "role", "temporal"),
                col("name", "fee", "role", "measure", "carried", false), col("name", "amount", "role", "Measure"),
                col("name", "note", "role", "detail"), col("name", "plain"));
        assertDoesNotThrow(() -> DatasetEntryRules.validate(e));
        assertEquals(List.of("channel", "booked_at", "amount"), DatasetEntryRules.carriedColumns(e));
        // a link end is never an attribute, even when it states a carried role
        assertEquals(List.of(), DatasetEntryRules.carriedColumns(linked(Map.of("from", "a", "to", "b"),
                col("name", "a", "role", "dimension"), col("name", "b", "role", "temporal"))));
        assertEquals(List.of(), DatasetEntryRules.carriedColumns(Map.of("physicalRef", "t")));
        // at most the index build's bound, in column order
        List<Map<String, Object>> wide = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) wide.add(col("name", "m" + i, "role", "measure"));
        List<String> capped = DatasetEntryRules.carriedColumns(Map.of("columns", wide));
        assertEquals(DatasetEntryRules.MAX_CARRIED, capped.size());
        assertEquals("m49", capped.get(capped.size() - 1));
        assertTrue(refusal(entry(col("name", "fee", "role", "measure", "carried", "no"))).contains("true or false"));
    }
}

package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}

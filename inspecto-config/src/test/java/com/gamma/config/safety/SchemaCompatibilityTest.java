package com.gamma.config.safety;

import com.gamma.config.spec.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link SchemaCompatibility} — the BACKWARD save-gate diff (ELT amendment §3.4.2, D-10). */
class SchemaCompatibilityTest {

    private static Map<String, Object> schema(List<Map<String, Object>> fields) {
        return Map.of("raw", Map.of("name", "ev", "fields", fields));
    }

    private static Map<String, Object> field(String name, String selector, String type) {
        return Map.of("name", name, "selector", selector, "type", type);
    }

    private static final Map<String, Object> BASE = schema(List.of(
            field("ID", "0", "VARCHAR"),
            field("QTY", "1", "INTEGER")));

    @Test
    void additiveFieldAndWideningAreCompatible() {
        Map<String, Object> draft = schema(List.of(
                field("ID", "0", "VARCHAR"),
                field("QTY", "1", "DOUBLE"),          // INTEGER → DOUBLE widening
                field("NOTE", "2", "VARCHAR")));      // additive
        assertTrue(SchemaCompatibility.check(BASE, draft).isEmpty());
    }

    @Test
    void anythingToVarcharIsWidening() {
        Map<String, Object> draft = schema(List.of(
                field("ID", "0", "VARCHAR"),
                field("QTY", "1", "VARCHAR")));
        assertTrue(SchemaCompatibility.check(BASE, draft).isEmpty());
    }

    @Test
    void removalIsBreakingWithACellLevelFinding() {
        Map<String, Object> draft = schema(List.of(field("ID", "0", "VARCHAR")));
        List<Finding> f = SchemaCompatibility.check(BASE, draft);
        assertEquals(1, f.size());
        assertEquals("raw.fields[QTY]", f.get(0).fieldPath(), "finding anchors to the removed field");
        assertTrue(f.get(0).message().contains("removed"), f.get(0).message());
    }

    @Test
    void narrowingIsBreaking() {
        Map<String, Object> base = schema(List.of(field("AMT", "0", "DOUBLE")));
        Map<String, Object> draft = schema(List.of(field("AMT", "0", "INTEGER")));
        List<Finding> f = SchemaCompatibility.check(base, draft);
        assertEquals(1, f.size());
        assertEquals("raw.fields[AMT].type", f.get(0).fieldPath());
        assertTrue(f.get(0).message().contains("DOUBLE → INTEGER"), f.get(0).message());
    }

    @Test
    void selectorMoveIsBreaking() {
        Map<String, Object> draft = schema(List.of(
                field("ID", "5", "VARCHAR"),
                field("QTY", "1", "INTEGER")));
        List<Finding> f = SchemaCompatibility.check(BASE, draft);
        assertEquals(1, f.size());
        assertEquals("raw.fields[ID].selector", f.get(0).fieldPath());
    }

    @Test
    void emptyOrMalformedExistingSideGatesNothing() {
        assertTrue(SchemaCompatibility.check(Map.of(), BASE).isEmpty(),
                "nothing to be compatible with");
        assertTrue(SchemaCompatibility.check(Map.of("raw", "not-a-map"), BASE).isEmpty());
    }
}

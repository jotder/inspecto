package com.gamma.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CanonicalHash} — the §3.4.3 schema-fingerprint hash. The contract that matters: key order must
 * not change the hash (two decodes of the same config), any value change must.
 */
class CanonicalHashTest {

    @Test
    void keyOrderDoesNotChangeTheHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("raw", Map.of("name", "orders", "format", "CSV"));
        a.put("mapping", Map.of("rules", List.of(Map.of("targetColumn", "ID", "sourceExpression", "ID"))));

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("mapping", Map.of("rules", List.of(Map.of("sourceExpression", "ID", "targetColumn", "ID"))));
        b.put("raw", Map.of("format", "CSV", "name", "orders"));

        assertEquals(CanonicalHash.sha256(a), CanonicalHash.sha256(b));
    }

    @Test
    void anyValueChangeChangesTheHash() {
        Map<String, Object> base = Map.of("raw", Map.of("name", "orders",
                "fields", List.of(Map.of("name", "ID", "type", "VARCHAR"))));
        Map<String, Object> widened = Map.of("raw", Map.of("name", "orders",
                "fields", List.of(Map.of("name", "ID", "type", "BIGINT"))));
        assertNotEquals(CanonicalHash.sha256(base), CanonicalHash.sha256(widened));
    }

    @Test
    void listOrderIsSignificant() {
        assertNotEquals(
                CanonicalHash.sha256(Map.of("fields", List.of("A", "B"))),
                CanonicalHash.sha256(Map.of("fields", List.of("B", "A"))));
    }

    @Test
    void scalarsAreLengthPrefixedSoConcatenationCannotCollide() {
        // "ab" + "c" vs "a" + "bc" — a naive join would render both as "abc".
        assertNotEquals(
                CanonicalHash.sha256(List.of("ab", "c")),
                CanonicalHash.sha256(List.of("a", "bc")));
    }

    @Test
    void nullsAndNestingHashDeterministically() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", null);
        m.put("b", List.of(Map.of("x", 1)));
        String h = CanonicalHash.sha256(m);
        assertEquals(64, h.length());
        assertEquals(h, CanonicalHash.sha256(m));
        assertNotEquals(h, CanonicalHash.sha256(null));
    }
}

package com.gamma.la.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ReferenceRows#keyPredicate}: the prunable forms and the CAST fallback. */
class ReferenceRowsKeyPredicateTest {

    @Test
    void varcharKeyComparesDirectly() {
        assertEquals("\"k\" IN (?, ?)", ReferenceRows.keyPredicate("\"k\"", "VARCHAR", List.of("a", "12")));
    }

    @Test
    void integerKeyWithCanonicalNumericKeysBindsTyped() {
        assertEquals("\"k\" IN (CAST(? AS BIGINT), CAST(? AS BIGINT))",
                ReferenceRows.keyPredicate("\"k\"", "BIGINT", List.of("27829990001", "-5")));
        assertEquals("\"k\" IN (CAST(? AS INTEGER))", ReferenceRows.keyPredicate("\"k\"", "integer", List.of("0")));
    }

    @Test
    void anyNonCanonicalOrOutOfRangeKeyFallsBackToCast() {
        String cast = "CAST(\"k\" AS VARCHAR) IN (?, ?)";
        assertEquals(cast, ReferenceRows.keyPredicate("\"k\"", "BIGINT", List.of("1", "x1")));
        assertEquals(cast, ReferenceRows.keyPredicate("\"k\"", "BIGINT", List.of("1", "007")), "007 never CAST-matches 7");
        assertEquals(cast, ReferenceRows.keyPredicate("\"k\"", "BIGINT", List.of("1", "+1")));
        assertEquals(cast, ReferenceRows.keyPredicate("\"k\"", "TINYINT", List.of("1", "300")));
        assertEquals(cast, ReferenceRows.keyPredicate("\"k\"", "UBIGINT", List.of("1", "-1")));
        assertEquals(cast, ReferenceRows.keyPredicate("\"k\"", "DOUBLE", List.of("1", "2")));
        assertEquals(cast, ReferenceRows.keyPredicate("\"k\"", null, List.of("1", "2")), "unknown type");
    }
}

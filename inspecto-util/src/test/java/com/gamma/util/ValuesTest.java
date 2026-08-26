package com.gamma.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the exact semantics of each consolidated helper — the historical per-file copies differed
 * on precisely these edges (trim vs no-trim, null vs "" fallback), so every edge is asserted.
 */
class ValuesTest {

    @Test
    void strKeepsNullAndSkipsTrim() {
        assertNull(Values.str(null));
        assertEquals(" a ", Values.str(" a "));
        assertEquals("42", Values.str(42));
    }

    @Test
    void strOrEmptyMapsNullToEmptyWithoutTrim() {
        assertEquals("", Values.strOrEmpty(null));
        assertEquals(" a ", Values.strOrEmpty(" a "));
    }

    @Test
    void trimOrEmptyMapsNullToEmptyAndTrims() {
        assertEquals("", Values.trimOrEmpty(null));
        assertEquals("a", Values.trimOrEmpty(" a "));
    }

    @Test
    void trimToNullCollapsesBlankAndTrims() {
        assertNull(Values.trimToNull(null));
        assertNull(Values.trimToNull("   "));
        assertEquals("a", Values.trimToNull(" a "));
        assertEquals("42", Values.trimToNull(42));
    }

    @Test
    void blankToNullCollapsesBlankButDoesNotTrim() {
        assertNull(Values.blankToNull(null));
        assertNull(Values.blankToNull("  "));
        assertEquals(" a ", Values.blankToNull(" a "));
    }

    @Test
    void intOrNarrowsNumbersParsesStringsAndFallsBack() {
        assertEquals(3, Values.intOr(3L, 9));
        assertEquals(3, Values.intOr(3.7d, 9));
        assertEquals(4, Values.intOr(" 4 ", 9));
        assertEquals(9, Values.intOr("x", 9));
        assertEquals(9, Values.intOr(null, 9));
    }

    @Test
    void putIfPresentSkipsOnlyNull() {
        Map<String, Object> m = new HashMap<>();
        Values.putIfPresent(m, "a", null);
        Values.putIfPresent(m, "b", "");
        Values.putIfPresent(m, "c", 1);
        assertFalse(m.containsKey("a"));
        assertEquals("", m.get("b"));
        assertEquals(1, m.get("c"));
    }

    @Test
    void mapAtIsNullTolerantOnEveryEdge() {
        assertNull(Values.mapAt(null, "k"));
        assertNull(Values.mapAt(Map.of(), "k"));
        assertNull(Values.mapAt(Map.of("k", "not a map"), "k"));
        Map<String, Object> nested = Map.of("x", 1);
        assertSame(nested, Values.mapAt(Map.of("k", nested), "k"));
    }

    @Test
    void castMapAtPreservesBareCastSemantics() {
        assertNull(Values.castMapAt(Map.of(), "k"));
        Map<String, Object> nested = Map.of("x", 1);
        assertSame(nested, Values.castMapAt(Map.of("k", nested), "k"));
        // exact-preserving: a non-map value fails like the historical bare cast did
        Map<String, Object> bad = Map.of("k", "not a map");
        assertThrows(ClassCastException.class, () -> {
            Map<String, Object> m = Values.castMapAt(bad, "k");
            m.isEmpty();   // the cast surfaces at use, as with the bare-cast idiom
        });
    }

    @Test
    void listAtPreservesBareCastSemantics() {
        assertNull(Values.listAt(Map.of(), "k"));
        List<Object> l = List.of("a");
        assertSame(l, Values.listAt(Map.of("k", l), "k"));
        Map<String, Object> bad = Map.of("k", "not a list");
        assertThrows(ClassCastException.class, () -> {
            List<Object> v = Values.listAt(bad, "k");
            v.isEmpty();
        });
    }

    @Test
    void listOfMapsAtIsEmptyWhenAbsentAndFailsFastOnShape() {
        assertEquals(List.of(), Values.listOfMapsAt(Map.of(), "k", "here"));
        Map<String, Object> ok = Map.of("k", List.of(Map.of("a", 1), Map.of("b", 2)));
        assertEquals(2, Values.listOfMapsAt(ok, "k", "here").size());

        IllegalArgumentException notList = assertThrows(IllegalArgumentException.class,
                () -> Values.listOfMapsAt(Map.of("k", "scalar"), "k", "here"));
        assertEquals("here must be a list of objects", notList.getMessage());

        IllegalArgumentException badEntry = assertThrows(IllegalArgumentException.class,
                () -> Values.listOfMapsAt(Map.of("k", List.of("x")), "k", "here"));
        assertEquals("here entries must be objects, got: x", badEntry.getMessage());
    }

    @Test
    void fileSafeReplacesUnsafeCharsAndNull() {
        assertEquals("_", Values.fileSafe(null));
        assertEquals("a_b.c-d_1", Values.fileSafe("a b.c-d/1"));
        assertEquals("run_2026_08", Values.fileSafe("run:2026/08"));
    }
}

package com.gamma.la.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link IndexedExpand#notIndexable}: every rung the index must NOT answer, each against a simple twin (D-3 step 6). */
class IndexedExpandTest {

    private static IndexedExpand.Request rung(List<String> frontier, List<String> kinds, Integer fanOut,
                                              boolean windowed, boolean minDays, boolean degree, boolean merged) {
        return new IndexedExpand.Request("ds", "s", "t", "kind", frontier, List.of(), kinds, "both", 1, fanOut, 100,
                windowed, minDays, degree, merged);
    }

    private static List<String> ids(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add("e" + i);
        return out;
    }

    @Test
    void aSimpleRungIsIndexable() {
        assertNull(IndexedExpand.notIndexable(rung(ids(20), List.of("call"), 3, false, false, false, false)));
        assertNull(IndexedExpand.notIndexable(rung(ids(1), null, null, false, false, false, false)));
    }

    @Test
    void eachNonSimpleFeatureNamesItself() {
        assertTrue(IndexedExpand.notIndexable(rung(ids(2), null, null, true, false, false, false)).contains("window"));
        assertTrue(IndexedExpand.notIndexable(rung(ids(2), null, null, false, true, false, false)).contains("minDistinctDays"));
        assertTrue(IndexedExpand.notIndexable(rung(ids(2), null, null, false, false, true, false)).contains("degree"));
        assertTrue(IndexedExpand.notIndexable(rung(ids(2), null, null, false, false, false, true)).contains("merged"));
        assertTrue(IndexedExpand.notIndexable(rung(ids(21), null, null, false, false, false, false)).contains("21"));
        assertNotNull(IndexedExpand.notIndexable(rung(List.of("a", "a"), null, null, false, false, false, false)));
        assertNotNull(IndexedExpand.notIndexable(rung(ids(2), List.of(), null, false, false, false, false)));
        assertNotNull(IndexedExpand.notIndexable(rung(ids(2), null, 0, false, false, false, false)));
    }
}

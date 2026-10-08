package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins BOTH sides of the opt-in strict mode: every authoring-time leniency of the default API stays exactly as
 * it is (so nobody "fixes" it by accident — Expectation / Alert / Decision rule authoring relies on it), and
 * {@code *Strict} refuses each with a path-pointing message.
 */
class ConditionStrictTest {

    private static final List<Map<String, Object>> ROWS = List.of(row("a", 1), row("a", 7));

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> group(Object... items) {
        return row("kind", "group", "op", "AND", "items", List.of(items));
    }

    private static Map<String, Object> leaf(Object... kv) {
        Map<String, Object> m = row("kind", "condition");
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String refusal(Object tree) {
        return assertThrows(IllegalArgumentException.class, () -> ConditionTree.matchedStrict(tree, ROWS)).getMessage();
    }

    @Test
    void emptyGroupLenientMatchesAllStrictRefuses() {
        Map<String, Object> g = group();
        assertEquals(2, ConditionTree.matched(g, ROWS));
        assertEquals(2, ConditionTree.matched(Map.of(), ROWS));
        assertTrue(refusal(g).startsWith("items: empty group"));
        assertTrue(refusal(Map.of()).startsWith("root:"));
        assertEquals("TRUE", ConditionSql.predicate(g));
        assertThrows(IllegalArgumentException.class, () -> ConditionSql.predicateStrict(g));
    }

    @Test
    void incompleteLeafLenientIgnoredStrictRefusesWithPath() {
        Map<String, Object> g = group(leaf("field", "a", "operator", "=", "value", "1"), leaf("field", "a", "operator", "="));
        assertEquals(1, ConditionTree.matched(g, ROWS));
        assertEquals("items[1].value: incomplete condition", refusal(g));
        assertThrows(IllegalArgumentException.class, () -> ConditionSql.predicateStrict(g));
        assertEquals("items[0].field: incomplete condition", refusal(group(leaf("operator", "=", "value", "1"))));
        assertEquals("items[0].operator: incomplete condition", refusal(group(leaf("field", "a", "value", "1"))));
        assertEquals("items[0].value: incomplete condition",
                refusal(group(leaf("field", "a", "operator", "between", "value", "1"))));
    }

    @Test
    void nestedPathPointsIntoTheSubGroup() {
        Map<String, Object> g = group(leaf("field", "a", "operator", "=", "value", "1"),
                group(leaf("field", "a", "operator", "="), leaf("field", "a", "operator", "=", "value", "1")));
        assertEquals("items[1].items[0].value: incomplete condition", refusal(g));
    }

    @Test
    void unknownOperatorLenientFalseStrictRefuses() {
        Map<String, Object> g = group(leaf("field", "a", "operator", "approx", "value", "1"));
        assertEquals(0, ConditionTree.matched(g, ROWS));
        assertEquals("items[0].operator: unknown operator 'approx'", refusal(g));
    }

    @Test
    void unknownGroupOpLenientAndStrictRefuses() {
        Map<String, Object> g = row("kind", "group", "op", "XOR", "items",
                List.of(leaf("field", "a", "operator", "=", "value", "1")));
        assertEquals(1, ConditionTree.matched(g, ROWS));
        assertTrue(refusal(g).startsWith("op: unknown group operator"));
    }

    @Test
    void nonObjectAndBareLeafRoots() {
        Map<String, Object> bare = leaf("field", "a", "operator", "=", "value", "1");
        assertThrows(IllegalArgumentException.class, () -> ConditionTree.matched(bare, ROWS));
        assertEquals(2, ConditionTree.matched(null, ROWS)); // lenient: absent tree = no constraint
        assertTrue(refusal(null).startsWith("root:"));
        assertTrue(refusal("a = 1").startsWith("root:"));
        assertTrue(refusal(List.of()).startsWith("root:"));
        assertTrue(refusal(bare).startsWith("root:"));
    }

    @Test
    void nonMapItemLenientSkippedStrictRefuses() {
        Map<String, Object> g = group(leaf("field", "a", "operator", "=", "value", "1"), "junk");
        assertEquals(1, ConditionTree.matched(g, ROWS));
        assertEquals("items[1]: not a condition or group object", refusal(g));
    }

    @Test
    void emptyInListLenientNeverMatchesStrictRefuses() {
        Map<String, Object> g = group(leaf("field", "a", "operator", "in", "value", " , "));
        assertEquals(0, ConditionTree.matched(g, ROWS));
        assertEquals("items[0].value: 'in' with an empty list", refusal(g));
        assertThrows(IllegalArgumentException.class, () -> ConditionSql.predicateStrict(g));
    }

    @Test
    void unparseableOrderingOperandLenientCompares_StrictRefuses() {
        Map<String, Object> g = group(leaf("field", "a", "operator", ">", "value", "abc"));
        ConditionTree.matched(g, ROWS); // lenient never throws
        assertTrue(refusal(g).startsWith("items[0].value: operand 'abc'"));
        assertTrue(refusal(group(leaf("field", "a", "operator", "between", "value", "1", "value2", "x")))
                .startsWith("items[0].value2:"));
        assertEquals(1, ConditionTree.matchedStrict(group(leaf("field", "a", "operator", ">", "value", "5")), ROWS));
    }

    @Test
    void valueFieldWithValueAndBadPatternStayRefusedInBothModes() {
        Map<String, Object> both = group(leaf("field", "a", "operator", "=", "value", "1", "valueField", "a"));
        assertThrows(IllegalArgumentException.class, () -> ConditionTree.matched(both, ROWS));
        assertThrows(IllegalArgumentException.class, () -> ConditionTree.matchedStrict(both, ROWS));
        Map<String, Object> bad = group(leaf("field", "a", "operator", "matches", "value", "(["));
        assertThrows(IllegalArgumentException.class, () -> ConditionTree.matched(bad, ROWS));
        assertThrows(IllegalArgumentException.class, () -> ConditionTree.matchedStrict(bad, ROWS));
    }

    @Test
    void missingFieldLeafIsFalseInStrictEvenForIsNull() {
        List<Map<String, Object>> rows = List.of(row("a", 1), row("a", 2, "b", 3));
        // lenient: absent ⇒ null ⇒ isNull true (row 0 only)
        assertEquals(1, ConditionTree.matched(group(leaf("field", "b", "operator", "isNull")), rows));
        assertEquals(0, ConditionTree.matchedStrict(group(leaf("field", "b", "operator", "isNull")), rows.subList(0, 1)));
        assertEquals(1, ConditionTree.matchedStrict(group(leaf("field", "b", "operator", "=", "value", "3")), rows));
        // present-but-null is still null data, not "missing"
        List<Map<String, Object>> withNull = List.of(row("b", null));
        assertEquals(1, ConditionTree.matchedStrict(group(leaf("field", "b", "operator", "isNull")), withNull));
    }

    @Test
    void negateOverMissingFieldNotsTheFalseLeaf() {
        Map<String, Object> g = row("kind", "group", "op", "AND", "negate", true,
                "items", List.of(leaf("field", "zzz", "operator", "=", "value", "1")));
        assertEquals(2, ConditionTree.matchedStrict(g, ROWS)); // leaf false, negated true; never skipped
    }

    @Test
    void presentDataSemanticsIdenticalToLenient() {
        Map<String, Object> g = row("kind", "group", "op", "OR", "items", List.of(
                leaf("field", "a", "operator", "<", "value", "3"),
                group(leaf("field", "a", "operator", "in", "value", "7,8"))));
        assertEquals(ConditionTree.matched(g, ROWS), ConditionTree.matchedStrict(g, ROWS));
        assertEquals(2, ConditionTree.matchedStrict(g, ROWS));
        assertEquals(ConditionSql.predicate(g), ConditionSql.predicateStrict(g));
    }
}

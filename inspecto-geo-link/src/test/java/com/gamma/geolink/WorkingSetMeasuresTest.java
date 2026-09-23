package com.gamma.geolink;

import com.gamma.alert.AlertRule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-23 — a Measure over one Working Set relation, in the BI Measure shorthand, evaluated in-JVM with SQL's null
 * rules; and the one hand-kept mirror this change introduces ({@code AlertRule.INVESTIGATION_RELATIONS}).
 */
class WorkingSetMeasuresTest {

    private static Map<String, Object> link(String s, String t, String kind, long count) {
        Map<String, Object> m = new HashMap<>();
        m.put("source", s);
        m.put("target", t);
        m.put("kind", kind);
        m.put("count", count);
        m.put("opSeq", 2);
        return m;
    }

    private static final List<Map<String, Object>> LINKS = List.of(
            link("a", "b", "sms", 2), link("a", "c", "call", 1), link("c", "d", null, 4));

    private static double v(String measure) {
        return WorkingSetMeasures.compute(LINKS, "links", measure).orElseThrow();
    }

    @Test
    void theMeasureShorthandEvaluatesOverTheRowsWithSqlNullRules() {
        assertEquals(3, v("count"));
        assertEquals(2, v("count(kind)"), "count(field) ignores nulls, as SQL does");
        assertEquals(2, v("countDistinct(kind)"));
        assertEquals(7, v("sum(count)"));
        assertEquals(7.0 / 3, v("avg(count)"), 1e-9);
        assertEquals(1, v("min(count)"));
        assertEquals(4, v("max(count)"));
        assertEquals(OptionalDouble.of(0), WorkingSetMeasures.compute(List.of(), "links", "count"));
        assertTrue(WorkingSetMeasures.compute(List.of(), "links", "sum(count)").isEmpty(),
                "an aggregate over no values is NULL — empty, never a made-up zero an alert could compare");
    }

    @Test
    void aMeasureOutsideTheGrammarOrTheRelationIsRefused() {
        List<Map<String, Object>> none = new ArrayList<>();
        for (String[] bad : new String[][]{
                {"links", "median(count)"}, {"links", "sum(nope)"}, {"links", "sum(kind)"},
                {"entities", "sum(count)"}, {"edges", "count"}})
            assertThrows(IllegalArgumentException.class, () -> WorkingSetMeasures.compute(none, bad[0], bad[1]),
                    String.join(" ", bad));
    }

    /** The engine mirrors the relation names it cannot see; a relation added to LA-20 must be added there too. */
    @Test
    void theAlertRulesRelationListIsTheWorkingSetsRelations() {
        assertEquals(WorkingSetRoutes.COLUMNS.keySet(), AlertRule.INVESTIGATION_RELATIONS);
    }
}

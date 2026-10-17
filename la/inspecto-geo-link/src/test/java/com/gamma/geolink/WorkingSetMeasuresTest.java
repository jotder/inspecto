package com.gamma.geolink;

import com.gamma.la.api.WorkingSetRoutes;
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

    private static Map<String, Object> entity(String id, Object hop, Object identity, Boolean hidden) {
        Map<String, Object> m = new HashMap<>();
        m.put("entityId", id);
        m.put("type", "msisdn");
        m.put("hop", hop);
        m.put("identity", identity);
        m.put("hidden", hidden);
        return m;
    }

    /** ANALYTICS-CORE-1 M9 pin: the values the in-JVM evaluator gave, held while the grammar moves to MeasureCompiler. */
    @Test
    void distinctNullAndEmptyCasesKeepTheirValues() {
        List<Map<String, Object>> es = List.of(entity("a", 0, "x", false), entity("b", 1, "x", true),
                entity("c", 1, null, null), entity("d", 2, "y", false));
        assertEquals(OptionalDouble.of(2), WorkingSetMeasures.compute(es, "entities", "countDistinct(identity)"));
        assertEquals(OptionalDouble.of(3), WorkingSetMeasures.compute(es, "entities", "countDistinct(hop)"));
        assertEquals(OptionalDouble.of(2), WorkingSetMeasures.compute(es, "entities", "countDistinct(hidden)"));
        assertEquals(OptionalDouble.of(3), WorkingSetMeasures.compute(es, "entities", "count(hidden)"));
        assertEquals(OptionalDouble.of(4), WorkingSetMeasures.compute(es, "entities", "sum(hop)"));
        assertEquals(OptionalDouble.of(1), WorkingSetMeasures.compute(es, "entities", "avg(hop)"));
        assertEquals(OptionalDouble.of(0), WorkingSetMeasures.compute(es, "entities", "min(hop)"));
        assertEquals(OptionalDouble.of(2), WorkingSetMeasures.compute(es, "entities", "max(hop)"));
        assertEquals(OptionalDouble.of(0), WorkingSetMeasures.compute(es, "entities", "count(contacts)"),
                "a column no row carries counts 0");
        assertEquals(OptionalDouble.of(0), WorkingSetMeasures.compute(es, "entities", "countDistinct(contacts)"));
        assertTrue(WorkingSetMeasures.compute(es, "entities", "sum(contacts)").isEmpty(), "all-null sum is NULL");
        assertEquals(OptionalDouble.of(0), WorkingSetMeasures.compute(List.of(), "links", "count(kind)"));
        assertEquals(OptionalDouble.of(0), WorkingSetMeasures.compute(List.of(), "links", "countDistinct(kind)"));
        assertEquals(0.1 + 0.2 + 0.3, WorkingSetMeasures.compute(List.of(Map.of("count", 0.1), Map.of("count", 0.2),
                Map.of("count", 0.3)), "links", "sum(count)").orElseThrow(), 1e-12);
    }

    @Test
    void theDeclaredSetKeepsItsShape() {
        List<Map<String, Object>> es = List.of(entity("a", 0, "x", false), entity("b", 1, "x", true),
                entity("c", 2, null, null));
        Map<String, Object> out = WorkingSetMeasures.declared(new WorkingSetRoutes.Relation("k", 3, "h",
                Map.of("entities", es, "links", LINKS, "excluded", List.of())));
        assertEquals("{measures=[{name=entities, relation=entities, measure=count, value=3.0}, "
                + "{name=links, relation=links, measure=count, value=3.0}, "
                + "{name=events, relation=links, measure=sum(count), value=7.0}, "
                + "{name=excluded, relation=excluded, measure=count, value=0.0}, "
                + "{name=maxHop, relation=entities, measure=max(hop), value=2.0}, "
                + "{name=identities, relation=entities, measure=countDistinct(identity), value=1.0}], "
                + "byKind=[{kind=null, links=1, events=4}, {kind=call, links=1, events=1}, {kind=sms, links=1, events=2}]}",
                out.toString());
    }

    @Test
    void aMeasureOutsideTheGrammarOrTheRelationIsRefused() {
        List<Map<String, Object>> none = new ArrayList<>();
        for (String[] bad : new String[][]{
                {"links", "median(count)"}, {"links", "sum(nope)"}, {"links", "sum(kind)"},
                {"entities", "sum(count)"}, {"edges", "count"}})
            assertThrows(IllegalArgumentException.class, () -> WorkingSetMeasures.compute(none, bad[0], bad[1]),
                    String.join(" ", bad));
        // The statistical aggregates are valid Measure grammar (D-AN5) but evaluated only in SQL: over real rows
        // each must be refused, never fall through to the numeric switch's last arm (max).
        List<Map<String, Object>> rows = List.of(Map.of("count", 1), Map.of("count", 9));
        for (String agg : List.of("median", "stddev", "variance", "p05", "p25", "p75", "p90", "p95", "p99"))
            assertThrows(IllegalArgumentException.class, () -> WorkingSetMeasures.compute(rows, "links", agg + "(count)"),
                    agg);
    }

    /** The engine mirrors the relation names it cannot see; a relation added to LA-20 must be added there too. */
    @Test
    void theAlertRulesRelationListIsTheWorkingSetsRelations() {
        assertEquals(WorkingSetRoutes.COLUMNS.keySet(), AlertRule.INVESTIGATION_RELATIONS);
    }

    private static AlertRule valueRule(Object minInbound) {
        Map<String, Object> vm = new java.util.LinkedHashMap<>();
        vm.put("name", "passThrough");
        vm.put("valueCol", "AMOUNT");
        vm.put("minInbound", minInbound);
        vm.put("minRatio", 0.9);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", "r1");
        m.put("investigation", "inv1");
        m.put("valueMeasure", vm);
        m.put("comparator", "gte");
        m.put("threshold", 1);
        m.put("severity", "CRITICAL");
        return AlertRule.fromMap(m);
    }

    @Test
    void aBindingHashSurvivesTheRuleBeingReadBackWithAWholeNumberAsAnInteger() {
        // the bind path hands the Measure's numbers over as doubles; a rule re-read from its TOON file brings 10000 back as an Integer
        assertEquals(WorkingSetMeasures.ruleHash(valueRule(10000.0)), WorkingSetMeasures.ruleHash(valueRule(10000)),
                "a restart must not make the bound rule read as 'changed since it was bound'");
    }
}

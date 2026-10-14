package com.gamma.ops.cases;

import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.tag.TagRule;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A Case Rule with {@code groupBy} groups ONE offender's Incidents into a Case; two offenders never merge. */
class CaseRuleGroupByTest {

    private final ObjectService objects = new ObjectService(new InMemoryObjectStore());
    private final CaseOperations cases = CaseOperations.of(objects);

    private void incident(String msisdn) {
        objects.open(ObjectType.INCIDENT, "Fraud on " + msisdn, "d", null, null, null, null, null,
                msisdn == null ? Map.of() : Map.of("key.msisdn", msisdn));
    }

    private CaseRule rule(int threshold, long windowMinutes, String groupBy) {
        return new CaseRule("per-offender", "Offender Incidents", new TagRule.Filter("INCIDENT", null, null, null, null, null),
                threshold, windowMinutes, null, null, System.currentTimeMillis(), groupBy, Map.of());
    }

    private List<OperationalObject> allCases() {
        return objects.query(ObjectQuery.builder().objectType(ObjectType.CASE).build());
    }

    @Test
    void twoOffendersNeverMergeAndOneOffendersIncidentsDo() {
        cases.registerCaseRule(rule(2, 0, "msisdn"));
        incident("A"); incident("A"); incident("A"); incident("B"); incident("B");
        CaseOperations.CaseRuleEvaluation e = cases.evaluateCaseRule("per-offender");
        assertEquals(5, e.grouped());
        assertEquals(2, allCases().size(), "one Case per offender");
        for (OperationalObject c : allCases()) {
            String group = c.attributes().get(CaseOperations.ATTR_CASE_GROUP);
            assertTrue(group.equals("A") || group.equals("B"));
            assertFalse(c.title().contains("A ") && c.title().contains(group + ")"), "title carries no raw key");
            assertFalse(c.title().contains("msisdn " + group), "title carries no raw key");
        }
        assertEquals(0, cases.evaluateCaseRule("per-offender").grouped(), "idempotent");
    }

    @Test
    void minCountAppliesPerOffenderNotInTotal() {
        cases.registerCaseRule(rule(2, 0, "msisdn"));
        incident("A"); incident("B");   // 2 Incidents in total, but only one each
        assertEquals(0, cases.evaluateCaseRule("per-offender").grouped());
        assertTrue(allCases().isEmpty());
        incident("A");
        assertEquals(2, cases.evaluateCaseRule("per-offender").grouped());
        assertEquals(1, allCases().size());
    }

    @Test
    void aLateIncidentJoinsTheOffendersOpenCaseOnly() {
        cases.registerCaseRule(rule(2, 0, "msisdn"));
        incident("A"); incident("A"); incident("B"); incident("B");
        cases.evaluateCaseRule("per-offender");
        incident("B");
        CaseOperations.CaseRuleEvaluation e = cases.evaluateCaseRule("per-offender");
        assertEquals(1, e.grouped());
        assertFalse(e.opened());
        assertEquals(2, allCases().size());
    }

    @Test
    void incidentsWithoutTheKeyAreNeverGrouped() {
        cases.registerCaseRule(rule(1, 0, "msisdn"));
        incident(null);
        assertEquals(0, cases.evaluateCaseRule("per-offender").matched());
    }

    @Test
    void windowStillApplies() {
        cases.registerCaseRule(rule(1, 1, "msisdn"));
        incident("A");
        assertEquals(1, cases.evaluateCaseRule("per-offender").grouped());
        CaseRule old = rule(1, 1, "msisdn");
        assertEquals("msisdn", old.groupBy());
    }

    @Test
    void groupByMustBeAPlainColumnAndRoundTrips() {
        assertThrows(IllegalArgumentException.class, () -> rule(1, 0, "msisdn; x"));
        CaseRule r = CaseRule.fromMap(Map.of("name", "n", "title", "t", "type", "INCIDENT", "groupBy", "msisdn"));
        assertEquals("msisdn", r.groupBy());
        assertEquals("msisdn", r.toMap().get("groupBy"));
        assertFalse(r.extra().containsKey("groupBy"));
    }
}

package com.gamma.ops;

import com.gamma.ops.link.LinkRelationship;
import com.gamma.ops.tag.CaseRule;
import com.gamma.ops.tag.TagRule;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P7 Case Management characterisation (MODULE-REORG-P7, step 0): pins the Case behaviour that
 * {@code ObjectService} owns today and that the Case extraction must keep byte-for-byte — the Case Rule
 * registry contract, the "closed rule-raised case is not re-used" branch of rule evaluation, and the
 * audit trail a merge leaves. (The rest is pinned by ObjectServiceCaseRuleTest, ObjectServiceCaseGroupTest,
 * ObjectServiceEntityCaseTest, ControlApiCase*Test, CaseRuleEvalJobTest and GovernanceSweepTest.)
 */
class ObjectServiceCaseParityTest {

    private static CaseRule rule(String name, int threshold) {
        return new CaseRule(name, "Cluster " + name, new TagRule.Filter("INCIDENT", null, null, "CRITICAL", null, null),
                threshold, 1440, null, null, 1);
    }

    private static OperationalObject incident(ObjectService svc, String title) {
        return svc.open(ObjectType.INCIDENT, title, "d", "HIGH", "CRITICAL", null, null, "corr", Map.of());
    }

    @Test
    void caseRuleRegistryRegistersReplacesListsSortedAndRemoves() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        assertTrue(svc.caseRules().isEmpty());
        assertTrue(svc.caseRule(null).isEmpty(), "a null name is simply absent");
        assertFalse(svc.removeCaseRule(null));
        assertFalse(svc.removeCaseRule("ghost"));

        svc.registerCaseRule(rule("zeta", 2));
        svc.registerCaseRule(rule("alpha", 2));
        assertEquals(List.of("alpha", "zeta"), svc.caseRules().stream().map(CaseRule::name).toList(), "sorted by name");
        assertEquals("alpha", svc.caseRule("  alpha ").orElseThrow().name(), "lookup trims the name");

        svc.registerCaseRule(rule("alpha", 9));   // same name replaces
        assertEquals(2, svc.caseRules().size());
        assertEquals(9, svc.caseRule("alpha").orElseThrow().threshold());

        assertTrue(svc.removeCaseRule(" zeta"));
        assertFalse(svc.removeCaseRule("zeta"), "removing twice reports false");
        assertEquals(List.of("alpha"), svc.caseRules().stream().map(CaseRule::name).toList());
    }

    @Test
    void aClosedRuleRaisedCaseIsNotReusedSoANewCaseOpensForNewMatches() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.registerCaseRule(rule("burst", 1));
        incident(svc, "one");
        ObjectService.CaseRuleEvaluation first = svc.evaluateCaseRule("burst");
        assertTrue(first.opened());

        for (String a : List.of("investigate", "resolve", "close")) svc.transition(first.caseId(), a, "op");   // terminal: no longer an open Case raised by the rule
        incident(svc, "two");
        ObjectService.CaseRuleEvaluation second = svc.evaluateCaseRule("burst");
        assertEquals(1, second.matched(), "the first incident is already a member of a Case; only the new one matches");
        assertTrue(second.opened(), "a terminal rule-raised Case is not attached to");
        assertNotEquals(first.caseId(), second.caseId());
    }

    @Test
    void mergeLeavesTraceLinkMarkerAndCommentsOnBothSides() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject survivor = svc.open(ObjectType.CASE, "A", "d", "HIGH", null, "ops", null, "c", Map.of());
        OperationalObject source = svc.open(ObjectType.CASE, "B", "d", "HIGH", null, "ops", null, "c", Map.of());

        svc.mergeCases(survivor.id(), List.of(source.id()), "op");

        OperationalObject closed = svc.get(source.id()).orElseThrow();
        assertEquals("CLOSED", closed.status());
        assertEquals(survivor.id(), closed.attributes().get(ObjectService.ATTR_MERGED_INTO));
        assertTrue(svc.linksOf(source.id()).stream().anyMatch(l -> LinkRelationship.MERGED_INTO.equalsIgnoreCase(l.relationship())
                && l.fromId().equals(source.id()) && l.toId().equals(survivor.id())), "MERGED_INTO trace link");
        assertTrue(svc.notesOf(source.id(), null).stream().anyMatch(n -> n.body().contains("Merged into " + survivor.id() + " by op")));
        assertTrue(svc.notesOf(survivor.id(), null).stream().anyMatch(n -> n.body().contains("Absorbed " + source.id())));
    }
}

package com.gamma.objects;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ASSURE-WORKFLOW-SLA-1 — an authored workflow is refused fail-closed ({@link Workflow#problems}). */
class WorkflowValidationTest {

    private static Map<String, Object> t(String from, String to, String action) {
        return Map.of("from", from, "to", to, "action", action);
    }

    private static Map<String, Object> wf(String type, Object initial, List<String> terminal, List<Map<String, Object>> moves) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("objectType", type);
        m.put("initial", initial);
        m.put("terminal", terminal);
        m.put("transitions", moves);
        return m;
    }

    private static String refusal(String id, Map<String, Object> content) {
        return assertThrows(IllegalArgumentException.class, () -> Workflow.fromComponent(id, content)).getMessage();
    }

    @Test
    void everyBuiltInWorkflowPassesTheValidator() {
        for (ObjectType type : ObjectType.values())
            assertEquals(List.of(), Workflow.defaultFor(type).problems(), type.name());
    }

    @Test
    void aCustomIncidentWorkflowThatKeepsTheGatesIsAccepted() {
        Workflow w = Workflow.fromComponent("incident", wf("INCIDENT", "NEW", List.of("CLOSED", "ARCHIVED"), List.of(
                t("NEW", "TRIAGED", "triage"), t("TRIAGED", "RESOLVED", "resolve"), t("RESOLVED", "CLOSED", "close"),
                t("NEW", "ARCHIVED", "archive"), t("TRIAGED", "ARCHIVED", "archive"), t("RESOLVED", "TRIAGED", "reopen"))));
        assertEquals("NEW", w.initialState());
        assertTrue(w.isTerminal("CLOSED"));
    }

    @Test
    void anUnreachableStateIsRefused() {
        String why = refusal("case", wf("CASE", "OPEN", List.of("CLOSED"), List.of(
                t("OPEN", "CLOSED", "close"), t("LIMBO", "CLOSED", "close"))));
        assertTrue(why.contains("LIMBO is unreachable"), why);
    }

    @Test
    void aDeadEndNobodyDeclaredTerminalIsRefused() {
        String why = refusal("case", wf("CASE", "OPEN", List.of("CLOSED"), List.of(
                t("OPEN", "CLOSED", "close"), t("OPEN", "PARKED", "park"))));
        assertTrue(why.contains("PARKED has no way out"), why);
    }

    @Test
    void aWorkflowWithNoTerminalStateIsRefused() {
        String why = refusal("case", wf("CASE", "OPEN", List.of(), List.of(t("OPEN", "WORK", "go"), t("WORK", "OPEN", "back"))));
        assertTrue(why.contains("at least one terminal"), why);
    }

    @Test
    void exactlyOneInitialStateIsRequired() {
        assertTrue(refusal("case", wf("CASE", List.of("OPEN", "NEW"), List.of("CLOSED"), List.of(t("OPEN", "CLOSED", "close"))))
                .contains("exactly one state"));
        assertThrows(IllegalArgumentException.class, () -> Workflow.fromComponent("case",
                wf("CASE", null, List.of("CLOSED"), List.of(t("OPEN", "CLOSED", "close")))));
    }

    @Test
    void anAmbiguousActionIsRefused() {
        String why = refusal("case", wf("CASE", "OPEN", List.of("CLOSED", "DONE"), List.of(
                t("OPEN", "CLOSED", "close"), t("OPEN", "DONE", "close"))));
        assertTrue(why.contains("two transitions leave OPEN by action 'close'"), why);
    }

    @Test
    void theComponentIdMustBeTheObjectType() {
        assertTrue(refusal("case", wf("INCIDENT", "IDENTIFIED", List.of("ARCHIVED"), List.of(
                t("IDENTIFIED", "RESOLVED", "resolve"), t("RESOLVED", "ARCHIVED", "archive"))))
                .contains("must match the component id"));
    }

    /** The gate-bypass check: a terminal Incident state reached other than from RESOLVED is refused. */
    @Test
    void anIncidentWorkflowThatFinishesAroundResolvedIsRefused() {
        String why = refusal("incident", wf("INCIDENT", "IDENTIFIED", List.of("CLOSED", "ARCHIVED"), List.of(
                t("IDENTIFIED", "RESOLVED", "resolve"), t("RESOLVED", "ARCHIVED", "archive"),
                t("IDENTIFIED", "CLOSED", "close"))));
        assertTrue(why.contains("IDENTIFIED -close-> CLOSED finishes an Incident around RESOLVED"), why);
    }

    @Test
    void anIncidentWorkflowWithoutResolvedOrWithANonTerminalArchivedIsRefused() {
        assertTrue(refusal("incident", wf("INCIDENT", "IDENTIFIED", List.of("ARCHIVED"), List.of(
                t("IDENTIFIED", "ARCHIVED", "archive")))).contains("needs a RESOLVED state"));
        assertTrue(refusal("incident", wf("INCIDENT", "IDENTIFIED", List.of("CLOSED"), List.of(
                t("IDENTIFIED", "RESOLVED", "resolve"), t("RESOLVED", "CLOSED", "close"),
                t("IDENTIFIED", "ARCHIVED", "archive"), t("ARCHIVED", "IDENTIFIED", "reopen"))))
                .contains("ARCHIVED must be terminal"));
    }

    @Test
    void theSameShapeIsFineForACaseBecauseOnlyIncidentsCarryTheResolutionGate() {
        Workflow.fromComponent("case", wf("CASE", "OPEN", List.of("CLOSED"), List.of(t("OPEN", "CLOSED", "close"))));
    }
}

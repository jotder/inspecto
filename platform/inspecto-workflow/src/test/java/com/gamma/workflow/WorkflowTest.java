package com.gamma.workflow;

import com.gamma.workflow.ObjectType;

import com.gamma.workflow.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The Workflow Engine: the built-in lifecycles, case-insensitive matching, and {@code .toon} authoring. */
class WorkflowTest {

    @Test
    void anAuthoredWorkflowForTheRetiredAlertTypeIsRefusedNamingTheKnownTypes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Workflow.fromMap(Map.of(
                "object_type", "ALERT", "initial", "OPEN", "terminal", List.of("RESOLVED"),
                "transitions", List.of(Map.of("from", "OPEN", "to", "RESOLVED", "action", "resolve")))));
        assertTrue(e.getMessage().contains("unknown object type 'ALERT'") && e.getMessage().contains("INCIDENT"), e.getMessage());
    }

    @Test
    void defaultIncidentLifecycle() {
        // The mail lifecycle (GLOSSARY §9): IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED (+ reopen).
        Workflow wf = Workflow.defaultFor(ObjectType.INCIDENT);
        assertEquals("IDENTIFIED", wf.initialState());
        assertEquals("DIAGNOSING", wf.apply("IDENTIFIED", "accept").orElseThrow());
        assertEquals("RESOLVED", wf.apply("DIAGNOSING", "resolve").orElseThrow());
        assertEquals("RESOLVED", wf.apply("IDENTIFIED", "resolve").orElseThrow(), "resolve without accepting");
        assertEquals("ARCHIVED", wf.apply("RESOLVED", "archive").orElseThrow());
        assertEquals("ARCHIVED", wf.apply("IDENTIFIED", "archive").orElseThrow(), "archive from anywhere (the mail Trash)");
        assertEquals("DIAGNOSING", wf.apply("RESOLVED", "reopen").orElseThrow());
        assertEquals("DIAGNOSING", wf.apply("ARCHIVED", "reopen").orElseThrow(), "reopen leaves the terminal state");
        assertTrue(wf.isTerminal("ARCHIVED"));
        assertFalse(wf.isTerminal("RESOLVED"), "RESOLVED is not terminal — an incident can still be archived");
        assertTrue(wf.apply("DIAGNOSING", "accept").isEmpty(), "accept is only legal from IDENTIFIED");
        assertTrue(wf.apply("IDENTIFIED", "bogus").isEmpty());
    }

    @Test
    void defaultCaseLifecycle() {
        Workflow wf = Workflow.defaultFor(ObjectType.CASE);
        assertEquals("OPEN", wf.initialState());
        assertEquals("INVESTIGATING", wf.apply("OPEN", "investigate").orElseThrow());
        assertEquals("ESCALATED", wf.apply("INVESTIGATING", "escalate").orElseThrow());
        assertEquals("RESOLVED", wf.apply("ESCALATED", "resolve").orElseThrow());
        assertEquals("RESOLVED", wf.apply("INVESTIGATING", "resolve").orElseThrow(), "resolve without escalating");
        assertEquals("CLOSED", wf.apply("RESOLVED", "close").orElseThrow());
        assertTrue(wf.isTerminal("CLOSED"));
        assertFalse(wf.isTerminal("RESOLVED"));
        assertTrue(wf.apply("OPEN", "escalate").isEmpty(), "cannot escalate before investigating");
    }

    @Test
    void matchingIsCaseInsensitive() {
        Workflow wf = Workflow.defaultFor(ObjectType.CASE);
        assertEquals("INVESTIGATING", wf.apply("open", "INVESTIGATE").orElseThrow());
        assertTrue(wf.allows("open", "investigating"));
        assertFalse(wf.allows("INVESTIGATING", "OPEN"), "no backward transition");
    }

    @Test
    void fromMapParsesAndValidates() {
        Workflow wf = Workflow.fromMap(Map.of(
                "object_type", "INCIDENT",
                "initial", "OPEN",
                "terminal", List.of("CLOSED"),
                "transitions", List.of(
                        Map.of("from", "OPEN", "to", "ASSIGNED", "action", "assign"),
                        Map.of("from", "ASSIGNED", "to", "CLOSED", "action", "close"))));
        assertEquals(ObjectType.INCIDENT, wf.objectType());
        assertEquals("ASSIGNED", wf.apply("OPEN", "assign").orElseThrow());
        assertTrue(wf.isTerminal("CLOSED"));
        assertThrows(IllegalArgumentException.class, () -> Workflow.fromMap(Map.of("initial", "OPEN")),
                "object_type is required");
    }

    @Test
    void loadFromToonFile(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("incident_workflow.toon");
        Files.writeString(p, """
                workflow:
                  object_type: INCIDENT
                  initial: OPEN
                  terminal[1]: "CLOSED"
                  transitions[2]{from,to,action}:
                    OPEN,ASSIGNED,assign
                    ASSIGNED,CLOSED,close
                """);
        Workflow wf = Workflow.load(p);
        assertEquals(ObjectType.INCIDENT, wf.objectType());
        assertEquals("OPEN", wf.initialState());
        assertEquals("ASSIGNED", wf.apply("OPEN", "assign").orElseThrow());
        assertEquals("CLOSED", wf.apply("ASSIGNED", "close").orElseThrow());
        assertTrue(wf.isTerminal("CLOSED"));
        assertEquals(2, wf.transitions().size());
    }
}

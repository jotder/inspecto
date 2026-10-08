package com.gamma.alert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The Alert lifecycle that was {@code Workflow.defaultFor(ALERT)}: unchanged move for move. */
class AlertLifecycleTest {

    @Test
    void openAcknowledgeResolve() {
        assertEquals("OPEN", AlertLifecycle.initialState());
        assertEquals("ACKNOWLEDGED", AlertLifecycle.apply("OPEN", "ack").orElseThrow());
        assertEquals("RESOLVED", AlertLifecycle.apply("ACKNOWLEDGED", "resolve").orElseThrow());
        assertEquals("RESOLVED", AlertLifecycle.apply("OPEN", "resolve").orElseThrow(), "resolve without ack allowed");
        assertTrue(AlertLifecycle.apply("RESOLVED", "ack").isEmpty(), "terminal state has no outgoing transitions");
        assertTrue(AlertLifecycle.apply("RESOLVED", "resolve").isEmpty());
        assertTrue(AlertLifecycle.apply("ACKNOWLEDGED", "ack").isEmpty());
        assertTrue(AlertLifecycle.apply("OPEN", "bogus").isEmpty());
        assertTrue(AlertLifecycle.isTerminal("RESOLVED"));
        assertFalse(AlertLifecycle.isTerminal("OPEN"));
        assertFalse(AlertLifecycle.isTerminal("ACKNOWLEDGED"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertEquals("ACKNOWLEDGED", AlertLifecycle.apply("open", "ACK").orElseThrow());
        assertTrue(AlertLifecycle.isTerminal("resolved"));
    }
}

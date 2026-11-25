package com.gamma.control;

import com.gamma.spi.auth.Subject;

import java.util.Map;
import java.util.Set;

/**
 * Test-only door to the package-private {@link AgentGate} for the MCP module's tests (AK-2 widening plan W10): the tool
 * table lives here and the gate in {@code inspecto-processor}, which this module has on its test classpath only, so a
 * public test helper in the gate's package keeps the gate's main-code visibility unchanged.
 */
public final class AgentGateProbe {

    private AgentGateProbe() {}

    /** A person whose request carries an allowlisted agent client's token — not a machine. */
    private static final Subject PERSON_AGENT =
            new Subject("person-1", Set.of(), null, Map.of(), null, null, null, "agent-client");

    /** Why the host refuses a person's agent the route registered as {@code method pattern}, or {@code null} when it may call it. */
    public static String refusalForPersonsAgent(String method, String pattern) {
        return AgentGate.refusal(PERSON_AGENT, method, pattern);
    }

    /** Whether a write to {@code method path} (a concrete path) can be held as a Pending Change ({@code PendingChanges.REPLAYABLE}). */
    public static boolean reachesTheHold(String method, String path) {
        return PendingChanges.replayable(method, path);
    }
}

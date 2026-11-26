package com.gamma.util;

/**
 * The agent data budget as a route handler sees it (AGENT-KIT-1 W6 group E, D-AK24). The host charges a data route's
 * weight before the handler runs; a route whose cost depends on its body (a screening check: 2 per started 10 subjects)
 * spends the rest itself, before any work, through the charger the host leaves in the request attribute {@link #ATTR}: a
 * {@link java.util.function.IntConsumer} that spends that many MORE tokens of the caller's agent budget or throws the
 * host's 429. The host sets it only for an agent's call to an agent data route, on every door.
 */
public final class AgentBudget {

    private AgentBudget() {}

    /** The request attribute holding this request's charger ({@link java.util.function.IntConsumer}). */
    public static final String ATTR = "inspecto.agentBudget.charge";
}

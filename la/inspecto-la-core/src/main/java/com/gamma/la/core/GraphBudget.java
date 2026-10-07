package com.gamma.la.core;

/**
 * The stated limits of one graph run (D-4 design §3.1): how many nodes and edges it may be asked to hold and how long
 * it may compute. {@code timeoutMs} counts from the moment the run starts executing, not from submission — time spent
 * queued is not the algorithm's. A non-positive field means "not stated" until {@link #resolve} fills it.
 */
public record GraphBudget(int maxNodes, int maxEdges, long timeoutMs) {

    /** All three unset: the caller states nothing and takes the defaults. */
    public static final GraphBudget UNSTATED = new GraphBudget(0, 0, 0);

    /** True when every field is positive. */
    public boolean stated() {
        return maxNodes > 0 && maxEdges > 0 && timeoutMs > 0;
    }

    /**
     * The effective budget: each unset field takes {@code defaults}' value, then every field is capped at
     * {@code ceilings}. The caller echoes the result, so a request above a ceiling is visibly clamped, never silently.
     */
    public GraphBudget resolve(GraphBudget defaults, GraphBudget ceilings) {
        return new GraphBudget(
                Math.min(maxNodes > 0 ? maxNodes : defaults.maxNodes, ceilings.maxNodes),
                Math.min(maxEdges > 0 ? maxEdges : defaults.maxEdges, ceilings.maxEdges),
                Math.min(timeoutMs > 0 ? timeoutMs : defaults.timeoutMs, ceilings.timeoutMs));
    }

    /** True when {@code requested} asked for more than this (the ceilings) allows on any field. */
    public boolean clamps(GraphBudget requested) {
        return requested.maxNodes > maxNodes || requested.maxEdges > maxEdges || requested.timeoutMs > timeoutMs;
    }
}

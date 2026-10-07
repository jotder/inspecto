package com.gamma.la.core;

/**
 * Thrown by an index-backed engine when a walk reaches past the index's fixed cap (design 4.2): a frontier of more than
 * {@code ceiling} keys at one level. Carries no partial result - the run ends {@code BUDGET_EXCEEDED} naming the cap and the
 * measured figure, never a smaller answer shaped like a whole one, and the request is NEVER rerouted to another engine.
 */
public final class IndexCapExceeded extends RuntimeException {

    private final String cap;
    private final long reached;
    private final long ceiling;

    public IndexCapExceeded(String cap, long reached, long ceiling) {
        super(cap + ": " + reached + " exceeds the cap of " + ceiling, null, false, false);
        this.cap = cap;
        this.reached = reached;
        this.ceiling = ceiling;
    }

    /** The cap's name, e.g. {@code frontier keys per level}. */
    public String cap() { return cap; }

    public long reached() { return reached; }

    public long ceiling() { return ceiling; }
}

package com.gamma.la.graph;

/**
 * Thrown by {@link RunControl#checkpoint()} to stop a running algorithm. Unchecked, carries no partial result
 * (design D-4 Decision 6): the caller reports the reason and the numbers, never a half-computed ranking.
 */
public final class GraphAborted extends RuntimeException {

    public enum Reason { CANCELLED, DEADLINE, BUDGET }

    private final Reason reason;
    private final long reached;
    private final long ceiling;

    /**
     * @param reached checkpoints passed when the abort fired
     * @param ceiling the work ceiling for {@link Reason#BUDGET}; 0 for the other reasons
     */
    public GraphAborted(Reason reason, long reached, long ceiling) {
        // no stack trace: thrown from deep recursion on a hot path
        super(reason + " after " + reached + " work units" + (reason == Reason.BUDGET ? " (ceiling " + ceiling + ")" : ""),
                null, false, false);
        this.reason = reason;
        this.reached = reached;
        this.ceiling = ceiling;
    }

    public Reason reason() { return reason; }

    public long reached() { return reached; }

    public long ceiling() { return ceiling; }
}

package com.gamma.la.graph;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Cancellation, wall-clock deadline, work budget and progress for one algorithm run. The algorithm calls
 * {@link #checkpoint()} once per bounded unit of work (a Brandes source, an iteration sweep, a DFS step); another
 * thread may {@link #cancel()} and read {@link #work()} / {@link #fraction()} at any time. One instance per run.
 *
 * <p>Cost: a live checkpoint is one volatile read plus one atomic increment; the clock is read only on the first
 * checkpoint and then every {@value #CLOCK_EVERY}th (a {@code nanoTime} is ~25 ns, so the clock adds ~0.1 ns per
 * checkpoint while a deadline still fires within 256 units of work — each unit is bounded, so that is milliseconds).
 * {@link #NONE} is a no-op: the existing public methods pass it and behave exactly as before.
 */
public final class RunControl {

    /** Clock-read period; a power of two so the test is a mask. */
    static final long CLOCK_EVERY = 256;

    /** The no-op control: never aborts, records nothing. */
    public static final RunControl NONE = new RunControl(false, false, 0, 0);

    private final boolean live;
    private final boolean hasDeadline;
    private final long deadlineNanos; // System.nanoTime() basis
    private final long ceiling;       // 0 = unbounded
    private final AtomicLong work = new AtomicLong();
    private volatile boolean cancelled;
    private volatile double fraction;

    private RunControl(boolean live, boolean hasDeadline, long deadlineNanos, long ceiling) {
        this.live = live;
        this.hasDeadline = hasDeadline;
        this.deadlineNanos = deadlineNanos;
        this.ceiling = ceiling;
    }

    /** A live control with neither deadline nor budget: cancellable and observable only. */
    public static RunControl create() {
        return new RunControl(true, false, 0, 0);
    }

    /** A live control with a work ceiling (checkpoints); {@code <= 0} means unbounded. */
    public static RunControl withBudget(long workCeiling) {
        return new RunControl(true, false, 0, Math.max(workCeiling, 0));
    }

    /** A live control with a wall-clock deadline {@code timeoutMillis} from now; {@code <= 0} is already past. */
    public static RunControl withTimeout(long timeoutMillis) {
        return of(timeoutMillis, 0);
    }

    /** Deadline and work ceiling together. */
    public static RunControl of(long timeoutMillis, long workCeiling) {
        return new RunControl(true, true, System.nanoTime() + timeoutMillis * 1_000_000L, Math.max(workCeiling, 0));
    }

    /** Ask the run to stop at its next checkpoint. Safe from any thread. */
    public void cancel() {
        if (live) cancelled = true;
    }

    /** Checkpoints passed so far; monotone, readable from any thread. */
    public long work() {
        return work.get();
    }

    /** The caller's progress hint in [0,1] (0 until set). */
    public double fraction() {
        return fraction;
    }

    public void setFraction(double f) {
        if (live) fraction = f;
    }

    /** Throws {@link GraphAborted} when cancelled, past the deadline, or over the work ceiling; else counts one unit. */
    public void checkpoint() {
        if (!live) return;
        if (cancelled) throw new GraphAborted(GraphAborted.Reason.CANCELLED, work.get(), 0);
        long n = work.incrementAndGet();
        if (ceiling != 0 && n > ceiling) throw new GraphAborted(GraphAborted.Reason.BUDGET, n, ceiling);
        if (hasDeadline && (n & (CLOCK_EVERY - 1)) == 1 && System.nanoTime() - deadlineNanos >= 0) {
            throw new GraphAborted(GraphAborted.Reason.DEADLINE, n, 0);
        }
    }
}

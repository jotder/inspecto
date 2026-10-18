package com.gamma.analytics.model;

import com.gamma.analytics.score.EntityCap;

/**
 * The declared ceiling on what a model kernel will fit or score in one call (D-AD10, plan §6): past it the call
 * <b>fails</b> rather than working on a silent subset. The same numbers as {@link EntityCap} — a model's own cap
 * (1..{@link EntityCap#CEILING}) overrides the default {@link EntityCap#DEFAULT}; the caller passes it in, the kernels
 * never read a system property.
 */
public final class InputCap {
    private InputCap() {}

    /** Thrown when an input is bigger than its cap; the message names what was capped and by how much. */
    public static final class Exceeded extends IllegalArgumentException {
        public Exceeded(String what, long actual, int cap) {
            super(what + " has " + actual + " rows, over the cap of " + cap
                    + ": pre-aggregate (one row per entity) or raise the cap (at most " + EntityCap.CEILING + ")");
        }
    }

    /** {@code cap} when within 1..{@link EntityCap#CEILING}, else refused. */
    public static int resolve(int cap) {
        if (cap < 1 || cap > EntityCap.CEILING) throw new IllegalArgumentException("cap must be within 1.." + EntityCap.CEILING + ": " + cap);
        return cap;
    }

    /** Fails with {@link Exceeded} when {@code rows} is over {@code cap}. */
    public static void require(String what, long rows, int cap) {
        if (rows > cap) throw new Exceeded(what, rows, cap);
    }
}

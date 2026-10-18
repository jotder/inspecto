package com.gamma.analytics.model;

/**
 * A seeded pseudo-random stream with a <b>pinned algorithm</b> (SplitMix64, Steele/Lea/Flood 2014) — the model kernels'
 * only source of randomness (ANALYTICS-CORE-1 C2–C4). The JDK's {@code SplittableRandom} implements the same generator
 * but promises no algorithm across releases; a fitted model's "same seed, same fit" guarantee must not depend on the
 * JDK, so the few lines are written out here. Not thread-safe: one stream per fit.
 */
public final class SeededRandom {

    private long state;

    public SeededRandom(long seed) {
        this.state = seed;
    }

    /** The next 64 random bits. */
    public long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A double in [0, 1) with 53 random bits. */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** An int in [0, bound): the high 32 bits scaled (multiply-shift); the bias is below 2^-32 per value at these sizes. */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive: " + bound);
        return (int) (((nextLong() >>> 32) * bound) >>> 32);
    }
}

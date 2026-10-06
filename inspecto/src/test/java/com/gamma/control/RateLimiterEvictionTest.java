package com.gamma.control;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Deterministic refill and idle-bucket eviction: memory stays bounded, and eviction never resets a budget early. */
class RateLimiterEvictionTest {

    private static final long S = 1_000_000_000L;
    private final AtomicLong now = new AtomicLong(0);

    private RateLimiter limiter(double cap, double perSec) {
        RateLimiter l = new RateLimiter(cap, perSec);
        l.useClock(now::get);
        return l;
    }

    private static int drain(RateLimiter l, String key) {
        int n = 0;
        while (l.tryConsume(key)) n++;
        return n;
    }

    @Test
    void aFrozenClockGivesExactlyTheBurstAndRefillIsExact() {
        RateLimiter l = limiter(20, 1.0 / 3.0);
        assertEquals(20, drain(l, "a"));
        now.addAndGet(3 * S - 1);
        assertFalse(l.tryConsume("a"), "one nanosecond short of a token");
        now.addAndGet(1);
        assertTrue(l.tryConsume("a"));
        assertFalse(l.tryConsume("a"));
    }

    @Test
    void anIdlePartlyRefilledBucketIsNotEvictedSoWaitingLessThanAFullRefillDoesNotResetIt() {
        RateLimiter l = limiter(20, 0.1);            // full refill = 200 s, longer than the sweep interval
        assertEquals(20, drain(l, "a"));
        now.addAndGet(RateLimiter.SWEEP_INTERVAL_NANOS + S);   // 61 s idle: 6 tokens back, sweep is due
        l.tryConsume("other");                       // triggers the sweep
        assertEquals(2, l.size(), "a non-full bucket must survive the sweep");
        assertEquals(6, drain(l, "a"), "only the refilled tokens, not a fresh burst of 20");
    }

    @Test
    void idleFullBucketsAreEvictedSoRotatingKeysStayBounded() {
        RateLimiter l = limiter(20, 1.0 / 3.0);      // full refill = 60 s
        for (int i = 0; i < 1000; i++) l.tryConsume("ip-" + i);
        assertEquals(1000, l.size());
        now.addAndGet(RateLimiter.SWEEP_INTERVAL_NANOS);   // every bucket is back to full
        l.tryConsume("fresh");
        assertEquals(1, l.size(), "only the caller that triggered the sweep remains");
        assertEquals(20, drain(l, "ip-7"), "an evicted bucket comes back exactly as full as it was");
    }

    @Test
    void noSweepBeforeTheIntervalEvenForFullBuckets() {
        RateLimiter l = limiter(20, 1.0 / 3.0);
        for (int i = 0; i < 10; i++) l.tryConsume("ip-" + i);
        now.addAndGet(RateLimiter.SWEEP_INTERVAL_NANOS - 1);
        l.tryConsume("x");
        assertEquals(11, l.size());
    }
}

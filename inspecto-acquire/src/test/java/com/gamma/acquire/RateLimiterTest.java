package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Phase F — {@link RateLimiter} token-bucket throttling with an injected nano clock + sleeper. */
class RateLimiterTest {

    private final AtomicLong nanos = new AtomicLong(0);
    private final List<Long> slept = new ArrayList<>();

    /** A sleeper that "advances time" so the refill loop makes progress without real waiting. */
    private final RateLimiter.Sleeper clockSleeper = ms -> {
        slept.add(ms);
        nanos.addAndGet(ms * 1_000_000L);
    };

    @Test
    void unlimitedReturnsImmediately() throws Exception {
        RateLimiter rl = RateLimiter.forTest(0, nanos::get, clockSleeper);
        assertFalse(rl.active());
        rl.acquire(10_000_000L);
        assertTrue(slept.isEmpty(), "an unlimited limiter never sleeps");
    }

    @Test
    void firstBurstIsFreeThenItThrottles() throws Exception {
        RateLimiter rl = RateLimiter.forTest(1000, nanos::get, clockSleeper);   // 1000 B/s, 1s burst
        rl.acquire(1000);                  // consumes the initial full bucket — no wait
        assertTrue(slept.isEmpty());

        rl.acquire(1000);                  // bucket empty ⇒ must wait ~1s for a refill
        assertEquals(1, slept.size());
        assertEquals(1000L, slept.get(0), "waited one second to earn 1000 bytes at 1000 B/s");
    }

    @Test
    void refillAccruesOverElapsedTime() throws Exception {
        RateLimiter rl = RateLimiter.forTest(1000, nanos::get, clockSleeper);
        rl.acquire(1000);                  // drain the initial bucket
        nanos.addAndGet(500_000_000L);     // 0.5s passes ⇒ 500 bytes refilled
        rl.acquire(500);                   // exactly affordable — no sleep
        assertTrue(slept.isEmpty(), "accrued enough tokens during the elapsed half-second");
    }

    private static final Duration HANG_GUARD = Duration.ofSeconds(5);

    @Test
    void oversizeRequestWaitsProportionallyNotForever() {
        RateLimiter rl = RateLimiter.forTest(1000, nanos::get, clockSleeper);   // burst = 1000 B
        assertTimeoutPreemptively(HANG_GUARD, () -> rl.acquire(3000));   // 1000 available, 2000 owed
        assertEquals(List.of(2000L), slept, "one proportional wait of (3000 - 1000) / 1000 B/s = 2 s");
    }

    @Test
    void throughputOverSeveralOversizeRequestsMatchesTheRate() {
        RateLimiter rl = RateLimiter.forTest(1000, nanos::get, clockSleeper);
        assertTimeoutPreemptively(HANG_GUARD, () -> {
            for (int i = 0; i < 5; i++) rl.acquire(2500);
        });
        // 12 500 B at 1000 B/s with a 1000 B initial burst => 11.5 s of virtual time.
        assertEquals(11_500_000_000L, nanos.get(), "long-run rate honoured: (total - burst) / rate");
    }

    @Test
    void aSmallRequestAfterAnOversizeOneIsNotPenalisedBeyondNormalRefill() throws Exception {
        RateLimiter rl = RateLimiter.forTest(1000, nanos::get, clockSleeper);
        assertTimeoutPreemptively(HANG_GUARD, () -> rl.acquire(5000));
        slept.clear();
        rl.acquire(100);                   // empty bucket, no debt => just the 0.1 s it takes to earn 100 B
        assertEquals(List.of(100L), slept, "no debt carried over from the oversize request");
        slept.clear();
        nanos.addAndGet(1_000_000_000L);   // a full idle second refills to the burst cap
        rl.acquire(1000);
        assertTrue(slept.isEmpty(), "the bucket refills normally after an oversize request");
    }

    @Test
    void negativeRateIsUnlimitedAndNonPositiveRequestsAreFree() throws Exception {
        RateLimiter unlimited = RateLimiter.forTest(-5, nanos::get, clockSleeper);
        assertFalse(unlimited.active());
        unlimited.acquire(Long.MAX_VALUE);
        RateLimiter rl = RateLimiter.forTest(1000, nanos::get, clockSleeper);
        rl.acquire(1000);
        rl.acquire(0);
        rl.acquire(-10);
        assertTrue(slept.isEmpty(), "zero / negative requests never wait");
    }
}

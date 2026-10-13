package com.gamma.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The Link Analysis budget is operator-tunable, and a bad value never disables the limiter. */
class RateLimiterBudgetTest {

    private static final String KEY = "control.rateLimit.linkAnalysis.capacity";

    @AfterEach
    void clear() {
        System.clearProperty(KEY);
    }

    @Test
    void unsetFallsBackToTheDefault() {
        assertEquals(20.0, RateLimiter.positive(KEY, 20.0));
    }

    @Test
    void aPositiveValueIsHonoured() {
        System.setProperty(KEY, " 250 ");
        assertEquals(250.0, RateLimiter.positive(KEY, 20.0));
    }

    @Test
    void aMalformedZeroNegativeOrInfiniteValueKeepsTheDefault() {
        for (String bad : new String[] {"abc", "0", "-5", "Infinity", "NaN", ""}) {
            System.setProperty(KEY, bad);
            assertEquals(20.0, RateLimiter.positive(KEY, 20.0), bad);
        }
    }

    /** LA-DAILY-INGEST-1 (measured 2026-10-14): opening an Investigation spends 0 tokens, every expensive gesture 1.
     *  The default budget carries a hard drill-down (12 gestures at once, then one every 3 s for a minute) and still
     *  refuses a runaway loop. */
    @Test
    void anAnalystSessionStaysUnderTheDefaultBudgetAndARunawayLoopIsRefused() {
        long[] now = {0L};
        RateLimiter l = RateLimiter.linkAnalysis();
        l.useClock(() -> now[0]);
        for (int i = 0; i < 12; i++) assertTrue(l.tryConsume("analyst"), "burst " + i);
        for (int s = 0; s < 20; s++) {
            now[0] += 3_000_000_000L;
            assertTrue(l.tryConsume("analyst"), "gesture every 3 s " + s);
        }
        int refused = 0;
        for (int i = 0; i < 200; i++) if (!l.tryConsume("loop")) refused++;
        assertEquals(170, refused, "a loop at the same instant gets the 30-token burst and nothing more");
        assertFalse(l.tryConsume("loop"));
    }
}

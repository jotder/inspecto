package com.gamma.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

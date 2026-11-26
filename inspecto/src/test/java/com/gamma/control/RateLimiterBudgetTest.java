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
        System.clearProperty(DASHBOARD);
    }

    private static final String DASHBOARD = "control.rateLimit.dashboard.capacity";
    private static final String AGENT_CAPACITY = "control.rateLimit.agent.capacity";

    /** The /bi/query bucket keeps burst 120 by default and honours its override (MixedLoadBenchmark raises it so a
     *  fast runner measures load, not the throttle). */
    /** AGENT-KIT-1 W6 S0 (D-AK17): the agent budget is burst 60 by default, and a weighted call spends its whole weight or nothing. */
    @Test
    void theAgentBudgetSpendsByWeightAllOrNothing() {
        assertEquals(60, burst(RateLimiter.agent()));
        RateLimiter l = new RateLimiter(10.0, 0.5);
        l.useClock(() -> 0L);
        assertTrue(l.tryConsume("k", 5));
        assertTrue(l.tryConsume("k", 5));
        assertFalse(l.tryConsume("k", 1), "the burst is spent");
        RateLimiter m = new RateLimiter(10.0, 0.5);
        m.useClock(() -> 0L);
        assertTrue(m.tryConsume("k", 8));
        assertFalse(m.tryConsume("k", 5), "two tokens left: a weight of 5 is refused");
        assertTrue(m.tryConsume("k", 2), "and spent none of them");
        assertFalse(m.tryConsume("k", 1));
    }

    /** W6 S0: a weighted 429 says when the route's whole weight is back, not when the next token is. */
    @Test
    void aWeightedRetryAfterWaitsForTheWholeWeight() {
        RateLimiter l = new RateLimiter(20.0, 0.5);
        l.useClock(() -> 0L);
        assertTrue(l.tryConsume("k", 20));
        assertFalse(l.tryConsume("k", 20));
        assertEquals(40, l.retryAfterSeconds("k", 20), "20 tokens at 0.5 a second");
        assertEquals(2, l.retryAfterSeconds("k"), "one token: the unweighted answer is unchanged");
    }

    /** W6 S0: no agent burst below the heaviest agent data route's weight - refused on PUT, raised when read from elsewhere. */
    @Test
    void anAgentBurstBelowARoutesWeightIsRefusedOrRaised() {
        java.util.Map<String, Integer> routes = java.util.Map.of("POST /db/query", 1, "POST /lookups/test", 20);
        String problem = AgentGate.burstProblem(19, routes);
        assertTrue(problem != null && problem.contains("rate.agent_burst 19") && problem.contains("POST /lookups/test")
                && problem.contains("at least 20"), String.valueOf(problem));
        assertEquals(null, AgentGate.burstProblem(20, routes));
        assertEquals(null, AgentGate.burstProblem(1, AgentGate.DATA_ROUTES), "today's routes weigh 1: every legal burst pays");

        System.setProperty(AGENT_CAPACITY, "0.5");   // below the heaviest weight (1): the bucket still holds a whole call
        try {
            assertEquals(AgentGate.heaviestDataWeight(), burst(RateLimiter.agent()));
        } finally {
            System.clearProperty(AGENT_CAPACITY);
        }
    }

    @Test
    void theDashboardBurstIsTunable() {
        assertEquals(120, burst(RateLimiter.dashboard()));
        System.setProperty(DASHBOARD, "500");
        assertEquals(500, burst(RateLimiter.dashboard()));
        System.setProperty(DASHBOARD, "0");
        assertEquals(120, burst(RateLimiter.dashboard()), "a non-positive value never disables the limiter");
    }

    private static int burst(RateLimiter l) {
        l.useClock(() -> 0L);
        int n = 0;
        while (l.tryConsume("k") && n < 10_000) n++;
        return n;
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

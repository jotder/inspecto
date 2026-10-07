package com.gamma.acquire;

import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Phase F — {@link CircuitBreaker} trip / cooldown / half-open behaviour with an injected clock. */
class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);
    private final CircuitBreaker cb = new CircuitBreaker(now::get);

    @Test
    void closedAllowsAndTripsOnThreshold() {
        assertTrue(cb.allow("s", 1000));
        assertFalse(cb.recordFailure("s", 3));    // 1
        assertFalse(cb.recordFailure("s", 3));    // 2
        assertTrue(cb.recordFailure("s", 3), "third consecutive failure trips OPEN");
        assertEquals(CircuitBreaker.State.OPEN, cb.state("s"));
        assertFalse(cb.allow("s", 1000), "OPEN within cooldown ⇒ skip");
    }

    @Test
    void successResetsTheFailureCount() {
        cb.recordFailure("s", 3);
        cb.recordFailure("s", 3);
        cb.recordSuccess("s");
        assertFalse(cb.recordFailure("s", 3), "count was reset — one failure doesn't trip");
        assertEquals(CircuitBreaker.State.CLOSED, cb.state("s"));
    }

    @Test
    void halfOpensAfterCooldownThenClosesOnSuccess() {
        cb.recordFailure("s", 1);                 // trips immediately (threshold 1)
        assertEquals(CircuitBreaker.State.OPEN, cb.state("s"));
        assertFalse(cb.allow("s", 1000));

        now.set(1000);                            // cooldown elapsed
        assertTrue(cb.allow("s", 1000), "half-opens for one trial");
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state("s"));
        cb.recordSuccess("s");
        assertEquals(CircuitBreaker.State.CLOSED, cb.state("s"));
    }

    @Test
    void halfOpenFailureReopensImmediately() {
        cb.recordFailure("s", 1);
        now.set(1000);
        assertTrue(cb.allow("s", 1000));          // HALF_OPEN
        assertTrue(cb.recordFailure("s", 1), "a failed trial re-opens");
        assertEquals(CircuitBreaker.State.OPEN, cb.state("s"));
        assertFalse(cb.allow("s", 1000), "re-opened, still cooling down");
    }

    @Test
    void perSourceIsolation() {
        cb.recordFailure("a", 1);
        assertEquals(CircuitBreaker.State.OPEN, cb.state("a"));
        assertEquals(CircuitBreaker.State.CLOSED, cb.state("b"));
        assertTrue(cb.allow("b", 1000));
    }

    /** Run {@code body} as a thread of Space {@code space} — the binding CollectorService.underSpace and ControlApi make. */
    private static void inSpace(String space, Runnable body) {
        String prev = MDC.get(EventLog.SPACE_MDC_KEY);
        MDC.put(EventLog.SPACE_MDC_KEY, space);
        try { body.run(); }
        finally { if (prev == null) MDC.remove(EventLog.SPACE_MDC_KEY); else MDC.put(EventLog.SPACE_MDC_KEY, prev); }
    }

    // ── SPACE-UNKEYED-STATICS-1: shared() is per Space, never one process-wide instance ───────────────

    /**
     * The defect: two Spaces polling a same-named collector shared ONE breaker, so one tenant's dead
     * endpoint OPENED the circuit for the other and skipped its acquisition. Applying one Space template
     * twice is enough to collide, because the shipped template names its pipeline.
     */
    @Test
    void aTrippedBreakerInOneSpaceDoesNotOpenAnotherSpacesSameNamedCollector() {
        inSpace("tenant-a", () -> {
            assertTrue(CircuitBreaker.shared().recordFailure("orders", 1), "one failure at threshold 1 trips it");
            assertEquals(CircuitBreaker.State.OPEN, CircuitBreaker.shared().state("orders"), "tenant-a is open");
        });
        inSpace("tenant-b", () -> assertEquals(CircuitBreaker.State.CLOSED,
                CircuitBreaker.shared().state("orders"),
                "tenant-b never failed — its breaker for the same collector id must still be closed"));
        assertEquals(CircuitBreaker.State.CLOSED, CircuitBreaker.shared().state("orders"),
                "the default Space (no MDC) is a third, untouched key");
        CircuitBreaker.forgetSpace("tenant-a");
        CircuitBreaker.forgetSpace("tenant-b");
    }

    /** An OPEN breaker skips its own Space's source only — the other Space still gets to poll. */
    @Test
    void anOpenBreakerDoesNotSuppressAnotherSpacesPoll() {
        inSpace("tenant-a", () -> {
            CircuitBreaker.shared().recordFailure("sftp", 1);
            assertFalse(CircuitBreaker.shared().allow("sftp", 60_000), "tenant-a is in cooldown");
        });
        inSpace("tenant-b", () -> assertTrue(CircuitBreaker.shared().allow("sftp", 60_000),
                "tenant-b must be allowed to poll — it has no failures of its own"));
        CircuitBreaker.forgetSpace("tenant-a");
        CircuitBreaker.forgetSpace("tenant-b");
    }

    /** forgetSpace releases exactly one Space's state, so a deleted Space cannot leak or take a sibling with it. */
    @Test
    void forgetSpaceDropsOnlyThatSpacesBreaker() {
        inSpace("gone", () -> CircuitBreaker.shared().recordFailure("c", 1));
        inSpace("stays", () -> CircuitBreaker.shared().recordFailure("c", 1));
        CircuitBreaker.forgetSpace("gone");
        inSpace("gone", () -> assertEquals(CircuitBreaker.State.CLOSED, CircuitBreaker.shared().state("c"),
                "a forgotten Space starts clean"));
        inSpace("stays", () -> assertEquals(CircuitBreaker.State.OPEN, CircuitBreaker.shared().state("c"),
                "the surviving Space keeps its own breaker"));
        CircuitBreaker.forgetSpace("gone");
        CircuitBreaker.forgetSpace("stays");
    }
}

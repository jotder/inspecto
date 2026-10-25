package com.gamma.la.api;

import com.gamma.control.testkit.FakeApiContext;
import com.gamma.la.core.InvestigationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-P4-3 review hardening of the Working Set conditional read: (1) the change token is re-checked AFTER the log read, so a
 * log that moved during the read is served uncached and untagged; (2) the trust window runs on a MONOTONIC clock; (4) an
 * error response never carries the ETag. The store is a test double, so the "mutation of the log between the two token
 * captures" is deterministic.
 */
class WorkingSetConditionalReadTest {

    private static final String PATH = "/inv/investigations/inv-a/working-set?of=entities";

    @AfterEach
    void reset() {
        WorkingSetRoutes.clockMs = WorkingSetRoutes.DEFAULT_CLOCK;
        WorkingSetRoutes.trustMs = WorkingSetRoutes.WORKING_SET_TRUST_MS;
    }

    /** A store whose token is {@code t1} until the first log read, then {@code t2} (when {@code moves}): the log is rewritten WHILE it is read. */
    private static InvestigationRoutes.Inv racing(Path root, boolean moves) {
        return racing(root, moves ? 1 : Integer.MAX_VALUE);
    }

    /** The token becomes {@code t2} once the log has been read {@code flipAfterReads} times. */
    private static InvestigationRoutes.Inv racing(Path root, int flipAfterReads) {
        String cacheKey = "k-" + UUID.randomUUID();   // the relation cache is static: a fresh key per test
        AtomicInteger logReads = new AtomicInteger();
        var store = (InvestigationStore) Proxy.newProxyInstance(InvestigationStore.class.getClassLoader(), new Class<?>[] {InvestigationStore.class},
                (p, m, a) -> switch (m.getName()) {
                    case "cacheKey" -> cacheKey;
                    case "logToken" -> logReads.get() >= flipAfterReads ? "t2" : "t1";
                    case "log" -> {
                        logReads.incrementAndGet();
                        yield List.of();
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return new InvestigationRoutes.Inv(store, root, "inv-a", Map.of("dataset", "d"), null);
    }

    @Test
    void aTokenThatMovedDuringTheReadIsNeverCachedAndNeverTagged(@TempDir Path root) throws Exception {
        InvestigationRoutes.Inv inv = racing(root, true);
        String before = WorkingSetEtag.logToken(inv);   // the pre-read capture: t1
        boolean[] cached = {false}, stable = {true};
        WorkingSetRoutes.relation(inv, -1, cached, before, stable);
        assertFalse(stable[0], "the post-read capture (t2) differs from the pre-read one (t1)");
        assertFalse(cached[0]);
        // not cached under the OLD token: the same pre-read token read again must read the log again
        long full = WorkingSetRoutes.FULL_READS.sum();
        stable[0] = true;
        WorkingSetRoutes.relation(inv, -1, cached, before, stable);
        assertEquals(1, WorkingSetRoutes.FULL_READS.sum() - full, "the old token was not cached against the newer content");
        assertFalse(cached[0]);
    }

    @Test
    void aStableTokenIsCachedAndHitsWithinTheWindow(@TempDir Path root) throws Exception {
        InvestigationRoutes.Inv inv = racing(root, false);
        String token = WorkingSetEtag.logToken(inv);
        boolean[] cached = {false}, stable = {false};
        WorkingSetRoutes.relation(inv, -1, cached, token, stable);
        assertTrue(stable[0]);
        long full = WorkingSetRoutes.FULL_READS.sum();
        WorkingSetRoutes.relation(inv, -1, cached, token, stable);
        assertTrue(cached[0], "a hit");
        assertEquals(full, WorkingSetRoutes.FULL_READS.sum());
    }

    @Test
    void aRacedAnswerCarriesNoEtagAndTheFollowUpReReads(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
        InvestigationRoutes.Inv inv = racing(root, true);

        Probe first = new Probe(PATH, null);
        assertEquals(200, answer(first, WorkingSetRoutes.serve(first, inv)), "served, not a 304");
        assertNull(first.getResponseHeaders().getFirst("ETag"), "raced: a correct 200, no tag");

        // the token is t2 from now on (stable): the raced answer was never cached, so the follow-up reads the log again
        long full = WorkingSetRoutes.FULL_READS.sum();
        Probe second = new Probe(PATH, null);
        assertEquals(200, answer(second, WorkingSetRoutes.serve(second, inv)));
        assertEquals(1, WorkingSetRoutes.FULL_READS.sum() - full, "the follow-up re-read the log");
        String tag = second.getResponseHeaders().getFirst("ETag");
        assertNotNull(tag, "a stable answer is tagged");

        full = WorkingSetRoutes.FULL_READS.sum();
        Probe third = new Probe(PATH, tag);
        assertEquals(304, answer(third, WorkingSetRoutes.serve(third, inv)), "and only a stable answer is a 304 source");
        assertEquals(full, WorkingSetRoutes.FULL_READS.sum(), "the 304 read nothing");
    }

    @Test
    void aSerialisationFailureAfterTheTagWasSetLeavesNoEtagOnTheError() {
        Probe ex = new Probe(PATH, null);
        Object unserialisable = new Object() {
            @SuppressWarnings("unused")
            public String getBoom() {
                throw new IllegalStateException("cannot serialise");
            }
        };
        assertThrows(IOException.class, () -> WorkingSetRoutes.sendTagged(ex, "\"abc\"", Map.of("x", unserialisable)));
        assertNull(ex.getResponseHeaders().getFirst("ETag"), "the tag is withdrawn before the error escapes");
        assertEquals(-1, ex.status, "nothing was committed");

        Probe ok = new Probe(PATH, null);
        assertDoesNotThrow(() -> WorkingSetRoutes.sendTagged(ok, "\"abc\"", Map.of("x", 1)));
        assertEquals("\"abc\"", ok.getResponseHeaders().getFirst("ETag"));
        assertEquals(200, ok.status);
    }

    @Test
    void theTrustWindowClockIsMonotonicNotTheWallClock() {
        long mono = WorkingSetRoutes.monotonicMs();
        assertTrue(Math.abs(mono - System.nanoTime() / 1_000_000L) < 1_000, "tracks System.nanoTime");
        assertTrue(Math.abs(mono - System.currentTimeMillis()) > 100_000_000_000L, "is not on the epoch of the wall clock");
        // the PRODUCTION default, read from a constant no test reassigns - independent of test order and of the seam
        long def = WorkingSetRoutes.DEFAULT_CLOCK.getAsLong();
        assertTrue(Math.abs(def - System.nanoTime() / 1_000_000L) < 1_000, "the default clock tracks System.nanoTime");
        assertTrue(Math.abs(def - System.currentTimeMillis()) > 100_000_000_000L, "the default clock is not the wall clock");
    }

    @Test
    void aWallClockStepBackwardsDoesNotExtendTheWindow(@TempDir Path root) throws Exception {
        InvestigationRoutes.Inv inv = racing(root, false);
        AtomicLong mono = new AtomicLong(5_000);
        AtomicLong wall = new AtomicLong(1_900_000_000_000L);   // the simulated WALL clock: production never reads it
        WorkingSetRoutes.clockMs = mono::get;
        String token = WorkingSetEtag.logToken(inv);
        boolean[] cached = {false}, stable = {true};
        WorkingSetRoutes.relation(inv, -1, cached, token, stable);
        long full = WorkingSetRoutes.FULL_READS.sum();
        wall.addAndGet(-3_600_000L);                                  // NTP / VM resume: the wall clock jumps back an hour
        mono.addAndGet(WorkingSetRoutes.WORKING_SET_TRUST_MS - 1);    // 29.999 s of monotonic time: still trusted
        WorkingSetRoutes.relation(inv, -1, cached, token, stable);
        assertEquals(full, WorkingSetRoutes.FULL_READS.sum(), "inside the window");
        mono.addAndGet(2);                                            // 30.001 s: the full SHA-256 revalidation happens
        WorkingSetRoutes.relation(inv, -1, cached, token, stable);
        assertEquals(1, WorkingSetRoutes.FULL_READS.sum() - full, "after 30 s of monotonic time, whatever the wall clock did");
    }

    @Test
    void aTokenThatMovesDuringARevalidationDoesNotRefreshTheStampAndIsUncachedUntagged(@TempDir Path root) throws Exception {
        InvestigationRoutes.Inv inv = racing(root, 2);   // read #1 stable; the log moves during read #2 (the re-validation)
        AtomicLong mono = new AtomicLong(5_000);
        WorkingSetRoutes.clockMs = mono::get;
        String token = WorkingSetEtag.logToken(inv);   // t1
        boolean[] cached = {false}, stable = {true};
        WorkingSetRoutes.relation(inv, -1, cached, token, stable);
        assertTrue(stable[0]);
        mono.addAndGet(WorkingSetRoutes.WORKING_SET_TRUST_MS + 1);   // the window ran out: the next call re-validates by content
        long full = WorkingSetRoutes.FULL_READS.sum();
        stable[0] = true;
        WorkingSetRoutes.relation(inv, -1, cached, token, stable);
        assertEquals(1, WorkingSetRoutes.FULL_READS.sum() - full, "re-validation read the log");
        assertFalse(stable[0], "the token moved between the pre-read and post-read capture: no ETag for this answer");
        // the entry's stamp was NOT refreshed: the very next request (same instant) must re-read, not hit the cache
        stable[0] = true;
        WorkingSetRoutes.relation(inv, -1, cached, token, stable);
        assertEquals(2, WorkingSetRoutes.FULL_READS.sum() - full, "the next request re-reads");
        assertFalse(stable[0]);
    }

    /** The status the framework would answer with: a handler that returns a body (not HANDLED) is answered 200. */
    private static int answer(Probe ex, Object result) {
        return result == com.gamma.spi.http.ApiContext.HANDLED ? ex.status : 200;
    }

    /** A request with no network behind it that records the status it was answered with. */
    private static final class Probe extends FakeApiContext.StubExchange {
        int status = -1;

        Probe(String path, String ifNoneMatch) {
            super("GET", path, null);
            if (ifNoneMatch != null) getRequestHeaders().add("If-None-Match", ifNoneMatch);
        }

        @Override
        public void sendResponseHeaders(int code, long length) {
            status = code;
        }
    }
}

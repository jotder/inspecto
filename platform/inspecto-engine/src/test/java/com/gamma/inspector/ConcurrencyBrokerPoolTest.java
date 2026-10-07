package com.gamma.inspector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Named execution pools on {@link ConcurrencyBroker} ({@code DUCKLE-C10-ADMISSION-POOLS-1}, scale-out
 * plan §4.1): a pool is admission only (it refuses, never widens), an unknown pool is {@code default},
 * a queued admission is a recorded state with an id and a {@code queueReason} that becomes
 * {@code running} with {@code queueMs}, a supervisor takes no slot, and the metric is free permits.
 */
class ConcurrencyBrokerPoolTest {

    private final ConcurrencyBroker broker = new ConcurrencyBroker();

    @AfterEach
    void restoreShared() {
        ConcurrencyBroker.use(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> admissions() {
        return (List<Map<String, Object>>) broker.snapshot().get("admissions");
    }

    private Map<String, Object> admission(String id) {
        return admissions().stream().filter(r -> id.equals(r.get("id"))).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> poolRow(String pool) {
        return ((Map<String, Map<String, Object>>) broker.snapshot().get("pools")).get(pool);
    }

    private void awaitState(String id, String state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            Map<String, Object> row = admission(id);
            if (row != null && state.equals(row.get("state"))) return;
            if (System.nanoTime() > deadline) fail("admission " + id + " never reached " + state + ": " + row);
            Thread.sleep(1);
        }
    }

    // ── admission: a pool bounds its own admissions ───────────────────────────

    @Test
    void aFullPoolQueuesTheNextAdmissionUntilAPermitFrees() throws Exception {
        broker.setPools(Map.of("heavy", 1));
        ConcurrencyBroker.Permit first = broker.admit("s", "a", "heavy", 10, 1, "c-1");
        assertEquals("heavy", first.pool());

        AtomicReference<ConcurrencyBroker.Permit> second = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                second.set(broker.admit("s", "b", "heavy", 10, 1, "c-2"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        awaitState("c-2", "queued");
        // A different pipeline in another pool is unaffected: the pool gates only its own members.
        try (ConcurrencyBroker.Permit other = broker.admit("s", "c", 10, 1)) {
            assertEquals(ConcurrencyBroker.DEFAULT_POOL, other.pool());
        }
        assertNull(second.get(), "admitted past a full pool");

        first.close();
        t.join(TimeUnit.SECONDS.toMillis(5));
        assertNotNull(second.get(), "a freed pool permit must admit the waiter");
        second.get().close();
    }

    @Test
    void aPoolNeverWidensThePipelineOrServerCap() throws Exception {
        broker.setPools(Map.of("wide", 10));
        broker.setSystemCap(2);
        ConcurrencyBroker.Permit a = broker.admit("s", "p", "wide", 1, 1, "a");
        Thread t = Thread.ofVirtual().start(() -> {
            try (ConcurrencyBroker.Permit b = broker.admit("s", "p", "wide", 1, 1, "b")) {
                // granted only once `a` releases
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        awaitState("b", "queued");
        assertTrue(String.valueOf(admission("b").get("queueReason")).contains("pipeline cap"),
                "the pipeline cap, not the roomy pool, holds it: " + admission("b"));
        assertEquals(9, poolRow("wide").get("free"));
        a.close();
        t.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(t.isAlive());

        ConcurrencyBroker.Permit x = broker.admit("s", "x", "wide", 10, 1, "x");
        ConcurrencyBroker.Permit y = broker.admit("s", "y", "wide", 10, 1, "y");
        Thread z = Thread.ofVirtual().start(() -> {
            try (ConcurrencyBroker.Permit p = broker.admit("s", "z", "wide", 10, 1, "z")) {
                // granted only once the server cap has room
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        awaitState("z", "queued");
        assertTrue(String.valueOf(admission("z").get("queueReason")).contains("server cap"),
                "a pool of 10 must not lift a server cap of 2: " + admission("z"));
        x.close();
        y.close();
        z.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(z.isAlive());
    }

    // ── queued → running, with the id known from the start ───────────────────

    @Test
    void aQueuedAdmissionHasItsIdAndReasonImmediatelyThenRunsWithQueueMs() throws Exception {
        broker.setPools(Map.of("heavy", 1));
        ConcurrencyBroker.Permit plug = broker.admit("s", "p", "heavy", 10, 1, "plug");
        assertNull(plug.queueReason(), "an immediate grant never queued");
        assertEquals(0, plug.queueMs());

        AtomicReference<ConcurrencyBroker.Permit> granted = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                granted.set(broker.admit("s", "q", "heavy", 10, 1, "consignment-42"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        awaitState("consignment-42", "queued");
        Map<String, Object> queued = admission("consignment-42");
        assertEquals("heavy", queued.get("pool"));
        assertEquals("pool 'heavy' full (1 in use)", queued.get("queueReason"));

        Thread.sleep(40);
        plug.close();
        t.join(TimeUnit.SECONDS.toMillis(5));
        ConcurrencyBroker.Permit p = granted.get();
        assertNotNull(p);
        assertEquals("consignment-42", p.id());
        assertEquals("pool 'heavy' full (1 in use)", p.queueReason());
        assertTrue(p.queueMs() >= 40, "queueMs must cover the wait, got " + p.queueMs());
        Map<String, Object> running = admission("consignment-42");
        assertEquals("running", running.get("state"));
        assertEquals(p.queueMs(), running.get("queueMs"));

        p.close();
        assertNull(admission("consignment-42"), "a released admission leaves the ticket list");
    }

    // ── unknown pool ⇒ default ────────────────────────────────────────────────

    @Test
    void anUnknownOrBlankPoolIsAdmittedInDefaultNeverAsANewPool() throws Exception {
        broker.setPools(Map.of("heavy", 1));
        assertEquals("heavy", broker.resolvePool("heavy"));
        assertEquals(ConcurrencyBroker.DEFAULT_POOL, broker.resolvePool("nope"));
        assertEquals(ConcurrencyBroker.DEFAULT_POOL, broker.resolvePool(null));
        assertEquals(ConcurrencyBroker.DEFAULT_POOL, broker.resolvePool("  "));

        try (ConcurrencyBroker.Permit p = broker.admit("s", "p", "nope", 5, 1, "u")) {
            assertEquals(ConcurrencyBroker.DEFAULT_POOL, p.pool());
            @SuppressWarnings("unchecked")
            Map<String, Object> pools = (Map<String, Object>) broker.snapshot().get("pools");
            assertFalse(pools.containsKey("nope"), "an unknown name must not become a pool: " + pools);
            assertEquals(1, poolRow(ConcurrencyBroker.DEFAULT_POOL).get("in_flight"));
        }
        assertThrows(IllegalArgumentException.class, () -> broker.setPools(Map.of("Bad-Name", 1)));
    }

    // ── a supervisor takes no slot (the deadlock rule) ────────────────────────

    /**
     * A supervisor that fans out children into its own pool and waits on them must complete, with a
     * pool of ONE and more children than permits — because the supervisor holds no permit. The
     * counterfactual (the supervisor holding the pool's only permit) is shown to wedge, so this test
     * would fail if the broker ever admitted the dispatcher.
     */
    @Test
    void aSupervisorHoldingNoSlotCompletesItsChildrenInAPoolOfOne() {
        broker.setPools(Map.of("tight", 1));
        AtomicInteger done = new AtomicInteger();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (ExecutorService children = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> fs = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    String id = "child-" + i;
                    fs.add(children.submit(() -> {
                        try (ConcurrencyBroker.Permit p = broker.admit("s", "p", "tight", 4, 1, id)) {
                            done.incrementAndGet();
                        }
                        return null;
                    }));
                }
                for (Future<?> f : fs) f.get();
            }
        }, "supervisor deadlocked on a pool of one");
        assertEquals(4, done.get());
        assertEquals(1, broker.freePermits("tight"));
    }

    @Test
    void theCounterfactualSupervisorHoldingASlotWedgesItsChild() throws Exception {
        broker.setPools(Map.of("tight", 1));
        try (ConcurrencyBroker.Permit supervisorSlot = broker.admit("s", "sup", "tight", 1, 1, "supervisor")) {
            Thread child = Thread.ofVirtual().start(() -> {
                try (ConcurrencyBroker.Permit p = broker.admit("s", "p", "tight", 4, 1, "child")) {
                    // never reached while the supervisor holds the slot
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            awaitState("child", "queued");
            child.join(200);
            assertTrue(child.isAlive(), "the child must be wedged behind the supervisor's permit");
            child.interrupt();
            child.join(TimeUnit.SECONDS.toMillis(5));
            assertNull(admission("child"), "an interrupted waiter must leave the ticket list");
        }
    }

    // ── metric: free permits per pool ─────────────────────────────────────────

    @Test
    void freePermitsPerPoolTrackGrantsAndReleasesAndUnboundedIsNull() throws Exception {
        broker.setPools(Map.of("heavy", 3));
        assertEquals(3, broker.freePermits("heavy"));
        assertNull(broker.freePermits(ConcurrencyBroker.DEFAULT_POOL), "unbounded default has no count");

        ConcurrencyBroker.Permit a = broker.admit("s", "p", "heavy", 5, 1, "a");
        ConcurrencyBroker.Permit b = broker.admit("s", "p", "heavy", 5, 1, "b");
        assertEquals(1, broker.freePermits("heavy"));
        assertEquals(1, poolRow("heavy").get("free"));
        assertEquals(2, poolRow("heavy").get("in_flight"));

        broker.setPools(Map.of("heavy", 1));                 // shrink under load drains, never goes negative
        assertEquals(0, broker.freePermits("heavy"));
        a.close();
        assertEquals(0, broker.freePermits("heavy"));
        b.close();
        assertEquals(1, broker.freePermits("heavy"));
    }
}

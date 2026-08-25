package com.gamma.inspector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Part B verification gates for {@link ConcurrencyBroker}: caps hold jointly across the three
 * tiers, FIFO within a pipeline, the 3:1 weighted share with a hard no-starvation floor, hot shrink
 * drains without interrupting, and no banked credit for a pipeline returning from idle.
 *
 * <p>Determinism idiom: a "plug" permit occupies the constrained tier first, waiters are enqueued
 * one at a time (confirmed via {@link ConcurrencyBroker#snapshot()}), then the plug is released —
 * so every grant decision happens over a fully-known queue state.
 */
class ConcurrencyBrokerTest {

    private final ConcurrencyBroker broker = new ConcurrencyBroker();

    @AfterEach
    void restoreShared() {
        ConcurrencyBroker.use(null);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Admit on a virtual thread; on grant, append {@code label} to {@code order} and immediately release. */
    private Thread waiter(String space, String pipeline, int cap, int priority,
                          String label, List<String> order) {
        Thread t = Thread.ofVirtual().unstarted(() -> {
            try (ConcurrencyBroker.Permit p = broker.admit(space, pipeline, cap, priority)) {
                order.add(label);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();
        return t;
    }

    private int waitingTotal() {
        int total = 0;
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> pipelines =
                (Map<String, Map<String, Object>>) broker.snapshot().get("pipelines");
        for (Map<String, Object> row : pipelines.values()) total += (int) row.get("waiting");
        return total;
    }

    private void awaitWaiting(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (waitingTotal() != expected) {
            if (System.nanoTime() > deadline)
                fail("timed out waiting for " + expected + " queued waiters, have " + waitingTotal());
            Thread.sleep(1);
        }
    }

    private static void join(List<Thread> threads) throws InterruptedException {
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(t.isAlive(), "waiter did not finish");
        }
    }

    // ── gate 1: weighted share ≈ 3:1 with a hard no-starvation floor ──────────

    @Test
    void weightedShareIsThreeToOneAndLowPriorityNeverStarves() throws Exception {
        broker.setSystemCap(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ConcurrencyBroker.Permit plug = broker.admit("s", "plug", 1, 1);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            threads.add(waiter("s", "hi", 100, 3, "hi", order));
            awaitWaiting(i + 1);
        }
        for (int i = 0; i < 6; i++) {
            threads.add(waiter("s", "lo", 100, 1, "lo", order));
            awaitWaiting(19 + i);
        }
        plug.close();
        join(threads);

        long hi = order.stream().filter("hi"::equals).count();
        long lo = order.stream().filter("lo"::equals).count();
        assertEquals(18, hi);
        assertEquals(6, lo);
        // No starvation: the low-priority pipeline appears within every stride window (LCM = 6/1 +
        // 3×6/3 = 4 grants per window), never pushed to the tail.
        for (int w = 0; w + 4 <= order.size(); w += 4) {
            assertTrue(order.subList(w, w + 4).contains("lo"),
                    "low-priority starved in window " + w + ": " + order);
        }
    }

    // ── gate 2: FIFO within one pipeline ──────────────────────────────────────

    @Test
    void grantsWithinOnePipelineAreFifo() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ConcurrencyBroker.Permit plug = broker.admit("s", "p", 1, 1);   // pipeline cap 1: plug holds the slot

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            threads.add(waiter("s", "p", 1, 1, "w" + i, order));
            awaitWaiting(i + 1);
        }
        plug.close();
        join(threads);
        assertEquals(List.of("w0", "w1", "w2", "w3", "w4"), order);
    }

    // ── gate 3: the three caps hold jointly ───────────────────────────────────

    @Test
    void systemSpaceAndPipelineCapsHoldJointly() throws Exception {
        broker.setSystemCap(2);
        broker.setSpaceCap("s1", 1);
        AtomicInteger granted = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        List<ConcurrencyBroker.Permit> permits = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 2; i++) {
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    permits.add(broker.admit("s1", "p1", 5, 1));
                    granted.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(t);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (granted.get() < 1 && System.nanoTime() < deadline) Thread.sleep(1);
        Thread.sleep(50);                                    // let a second (wrong) grant surface
        assertEquals(1, granted.get(), "space cap 1 must gate the second s1 admission");

        // s2 is uncapped, but the system cap (2) has one slot left: exactly one more grant.
        Thread t3 = Thread.ofVirtual().start(() -> {
            try {
                permits.add(broker.admit("s2", "q", 5, 1));
                granted.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread t4 = Thread.ofVirtual().start(() -> {
            try {
                permits.add(broker.admit("s2", "q", 5, 1));
                granted.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (granted.get() < 2 && System.nanoTime() < deadline) Thread.sleep(1);
        Thread.sleep(50);
        assertEquals(2, granted.get(), "system cap 2 must gate the fourth admission");

        // Drain: release everything; every waiter eventually completes.
        while (granted.get() < 4) {
            ConcurrencyBroker.Permit p;
            synchronized (permits) {
                p = permits.isEmpty() ? null : permits.remove(0);
            }
            if (p != null) p.close();
            Thread.sleep(1);
            if (System.nanoTime() > deadline + TimeUnit.SECONDS.toNanos(10)) fail("drain stalled");
        }
        for (ConcurrencyBroker.Permit p : new ArrayList<>(permits)) p.close();
        join(threads);
        t3.join(TimeUnit.SECONDS.toMillis(10));
        t4.join(TimeUnit.SECONDS.toMillis(10));
    }

    // ── gate 4: a shrink drains — in-flight permits survive, admissions stop ─

    @Test
    void shrinkDrainsWithoutInterrupting() throws Exception {
        broker.setSystemCap(2);
        ConcurrencyBroker.Permit a = broker.admit("s", "p", 5, 1);
        ConcurrencyBroker.Permit b = broker.admit("s", "p", 5, 1);

        broker.setSystemCap(1);                              // shrink under load: nothing is interrupted
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Thread w = waiter("s", "p", 5, 1, "late", order);
        awaitWaiting(1);

        a.close();                                           // 1 in flight = new cap: still gated
        Thread.sleep(50);
        assertTrue(order.isEmpty(), "admission above the shrunk cap");
        b.close();                                           // 0 in flight: now admitted
        w.join(TimeUnit.SECONDS.toMillis(10));
        assertEquals(List.of("late"), order);
    }

    // ── gate 5: no banked credit for a pipeline returning from idle ──────────

    @Test
    void idlePipelineJoinsAtCurrentVirtualTimeNotZero() throws Exception {
        broker.setSystemCap(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ConcurrencyBroker.Permit plug = broker.admit("s", "plug", 1, 1);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {                        // "busy" runs alone first, advancing vtime
            threads.add(waiter("s", "busy", 100, 3, "busy", order));
            awaitWaiting(i + 1);
        }
        for (int i = 0; i < 2; i++) {                        // "idle" arrives late at weight 1
            threads.add(waiter("s", "idle", 100, 1, "idle", order));
            awaitWaiting(7 + i);
        }
        plug.close();
        join(threads);
        // Joined at vtime: "idle" must not burst ahead of the queue it just joined — at weight 1 it
        // gets at most its 1-in-4 share of the early grants, never the first slot.
        assertNotEquals("idle", order.get(0), "idle pipeline banked credit and jumped the queue");
        long idleInFirstFour = order.subList(0, 4).stream().filter("idle"::equals).count();
        assertTrue(idleInFirstFour <= 1, "idle pipeline over-drew its share: " + order);
    }

    // ── priority restated on admit is visible immediately (hot change) ───────

    @Test
    void priorityChangeIsVisibleOnNextAdmit() throws Exception {
        ConcurrencyBroker.Permit p = broker.admit("s", "p", 5, 1);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> before =
                (Map<String, Map<String, Object>>) broker.snapshot().get("pipelines");
        assertEquals(1, before.get("p").get("priority"));
        ConcurrencyBroker.Permit q = broker.admit("s", "p", 5, 3);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> after =
                (Map<String, Map<String, Object>>) broker.snapshot().get("pipelines");
        assertEquals(3, after.get("p").get("priority"));
        p.close();
        q.close();
    }
}

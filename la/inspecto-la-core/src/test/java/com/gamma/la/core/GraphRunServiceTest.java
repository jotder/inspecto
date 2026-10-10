package com.gamma.la.core;

import com.gamma.la.core.GraphRunException.Kind;
import com.gamma.la.core.GraphRunService.Exceeded;
import com.gamma.la.core.GraphRunService.Limits;
import com.gamma.la.core.GraphRunService.Request;
import com.gamma.la.core.GraphRunService.RunView;
import com.gamma.la.core.GraphRunService.Status;
import com.gamma.la.graph.RunControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-4 step 5: the {@link GraphRunService} lifecycle - budget before work, deadline, cancel latency, the four terminal
 * states, retention and the cache. Engines here are PROBES that behave as a real algorithm might (spin on the control,
 * ignore it, throw); the real engine is used once, for the happy path.
 */
@Timeout(30)
class GraphRunServiceTest {

    private static final Limits TIGHT = new Limits(new GraphBudget(1_000, 10_000, 5_000), new GraphBudget(2_000, 20_000, 10_000),
            1, 1, 60_000L, 50, 60_000L, 8);

    private GraphRunService svc;

    @AfterEach
    void close() {
        if (svc != null) svc.close();
    }

    /** A directed ring of n nodes. */
    private static GraphInput ring(int n) {
        List<GraphInput.Node> nodes = new ArrayList<>();
        List<GraphInput.Edge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) nodes.add(new GraphInput.Node("n" + i, "n" + i));
        for (int i = 0; i < n; i++) edges.add(new GraphInput.Edge("e" + i, "n" + i, "n" + ((i + 1) % n)));
        return GraphInput.of(nodes, edges, Map.of());
    }

    private static Request req(String owner, GraphInput in) {
        return new Request(owner, "inv-1", "rel-1", "scope-A", Algorithm.DEGREE_CENTRALITY, Map.of(), "none", in, GraphBudget.UNSTATED);
    }

    private static Request withBudget(Request r, GraphBudget b) {
        return new Request(r.owner(), r.investigationId(), r.relationKey(), r.scopeFingerprint(), r.algorithm(), r.params(),
                r.weightsSpec(), r.input(), b);
    }

    private static Request withAlgorithm(Request r, Algorithm a, Map<String, ?> p) {
        return new Request(r.owner(), r.investigationId(), r.relationKey(), r.scopeFingerprint(), a, p, r.weightsSpec(), r.input(), r.budget());
    }

    /** An engine whose behaviour is a lambda; counts its invocations. */
    private static final class Probe implements GraphEngine {
        interface Body { GraphResult run(RunControl ctl) throws Exception; }

        final AtomicInteger calls = new AtomicInteger();
        final Body body;

        Probe(Body body) {
            this.body = body;
        }

        @Override public String engineId() { return "probe"; }
        @Override public Set<Algorithm> supported() { return EnumSet.allOf(Algorithm.class); }

        @Override
        public GraphResult run(Algorithm a, Map<String, Object> p, GraphInput in, RunControl ctl) {
            calls.incrementAndGet();
            try {
                return body.run(ctl);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static GraphResult answer() {
        return new GraphResult(Algorithm.DEGREE_CENTRALITY, new GraphResult.Flag(true), 0, 1);
    }

    private RunView settle(RunView v) throws InterruptedException {
        return svc.await(v.id(), 10_000);
    }

    private static void until(java.util.function.BooleanSupplier c) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("condition not reached in 10 s");
            Thread.sleep(2);
        }
    }

    // ── the happy path, on the real engine ──────────────────────────────────────────────────────────────────────

    @Test
    void aRealRunCompletesWithTheEnginesOwnAnswerAndTheConsumedSize() throws Exception {
        svc = new GraphRunService(new InMemoryGraphEngine(), TIGHT);
        GraphInput in = ring(6);
        RunView v = settle(svc.submit(req("alice", in)));
        assertEquals(Status.COMPLETED, v.status());
        assertEquals(new InMemoryGraphEngine().run(Algorithm.DEGREE_CENTRALITY, Map.of(), in, null).payload(), v.result().payload());
        assertEquals(6, v.consumed().nodes());
        assertEquals(6, v.consumed().edges());
        assertEquals("memory", v.engine());
        assertFalse(v.cached());
        assertEquals(new GraphBudget(1_000, 10_000, 5_000), v.budget(), "the stated budget is echoed");
    }

    // ── budget ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** The negative test the design asks for: the probe WOULD succeed, so a result can only be absent because the budget held. */
    @Test
    void anInputOverTheBudgetNeverReachesTheEngineAndCarriesNoResult() throws Exception {
        Probe probe = new Probe(ctl -> answer());
        svc = new GraphRunService(probe, TIGHT);
        RunView v = svc.submit(withBudget(req("alice", ring(50)), new GraphBudget(10, 0, 0)));
        assertEquals(Status.BUDGET_EXCEEDED, v.status(), "terminal at submit, without a wait");
        assertEquals(Exceeded.NODES, v.exceeded());
        assertNull(v.result());
        assertEquals(50, v.consumed().nodes(), "the measured size is reported");
        assertEquals(10, v.budget().maxNodes());
        assertEquals(0, probe.calls.get(), "the engine was never asked");

        RunView e = svc.submit(withBudget(req("alice", ring(50)), new GraphBudget(0, 20, 0)));
        assertEquals(Exceeded.EDGES, e.exceeded());
        assertNull(e.result());
        assertEquals(0, probe.calls.get());
    }

    @Test
    void aRunThatFinishesPastItsDeadlineIsOverBudgetAndItsAnswerIsDiscarded() throws Exception {
        // an algorithm with no checkpoint: it cannot be stopped, but it can still be held to the budget it was given
        Probe probe = new Probe(ctl -> {
            Thread.sleep(150);
            return answer();                                  // a perfectly good answer - delivered late
        });
        svc = new GraphRunService(probe, TIGHT);
        Request r = withBudget(req("alice", ring(4)), new GraphBudget(0, 0, 50));
        RunView v = settle(svc.submit(r));
        assertEquals(Status.BUDGET_EXCEEDED, v.status());
        assertEquals(Exceeded.TIMEOUT, v.exceeded());
        assertNull(v.result(), "never a late answer shaped like a complete one");
        assertTrue(v.consumed().elapsedMs() >= 50);
        // and it was not cached: asking again runs the engine again
        settle(svc.submit(r));
        assertEquals(2, probe.calls.get());
    }

    @Test
    void theDeadlineStopsARunningAlgorithmAtItsCheckpoint() throws Exception {
        Probe probe = new Probe(ctl -> {
            while (true) ctl.checkpoint();                    // spins until the control aborts it
        });
        svc = new GraphRunService(probe, TIGHT);
        long t0 = System.nanoTime();
        RunView v = settle(svc.submit(withBudget(req("alice", ring(4)), new GraphBudget(0, 0, 60))));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(Status.BUDGET_EXCEEDED, v.status());
        assertEquals(Exceeded.TIMEOUT, v.exceeded());
        assertNull(v.result());
        assertTrue(ms < 5_000, "stopped near its deadline, took " + ms + " ms");
    }

    @Test
    void aRequestAboveACeilingIsClampedAndTheClampIsSaid() throws Exception {
        svc = new GraphRunService(new InMemoryGraphEngine(), TIGHT);
        RunView over = svc.submit(withBudget(req("alice", ring(4)), new GraphBudget(1_000_000, 0, 999_999)));
        assertEquals(new GraphBudget(2_000, 10_000, 10_000), over.budget(), "capped at the ceilings, unset field takes the default");
        assertTrue(over.budgetClamped());
        assertFalse(svc.submit(req("alice", ring(4))).budgetClamped());
    }

    // ── cancel ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void cancellingARunningRunStopsItAtTheNextCheckpointWithNoResult() throws Exception {
        Probe probe = new Probe(ctl -> {
            while (true) ctl.checkpoint();
        });
        svc = new GraphRunService(probe, TIGHT);
        RunView started = svc.submit(req("alice", ring(4)));
        until(() -> svc.get(started.id()).status() == Status.RUNNING);

        long t0 = System.nanoTime();
        RunView asked = svc.cancel(started.id(), "alice", false);
        assertTrue(asked.cancelRequested());
        RunView v = settle(started);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(Status.CANCELLED, v.status());
        assertNull(v.result());
        assertTrue(ms < 2_000, "cancel latency was " + ms + " ms");
        assertEquals(Kind.TERMINAL, assertThrows(GraphRunException.class, () -> svc.cancel(started.id(), "alice", false)).kind());
    }

    @Test
    void cancelIsIdempotentWhileRunningAndAnAnswerThatArrivesAfterItIsDiscarded() throws Exception {
        CountDownLatch release = new CountDownLatch(1), inside = new CountDownLatch(1);
        Probe probe = new Probe(ctl -> {                       // ignores the control, as an uncancellable algorithm does
            inside.countDown();
            release.await();
            return answer();
        });
        svc = new GraphRunService(probe, TIGHT);
        RunView started = svc.submit(req("alice", ring(4)));
        inside.await();
        assertTrue(svc.cancel(started.id(), "alice", false).cancelRequested());
        assertTrue(svc.cancel(started.id(), "alice", false).cancelRequested(), "asking twice is fine");
        assertEquals(Status.RUNNING, svc.get(started.id()).status());
        release.countDown();
        RunView v = settle(started);
        assertEquals(Status.CANCELLED, v.status(), "the caller asked to stop; the late answer is not delivered");
        assertNull(v.result());
    }

    @Test
    void aQueuedRunIsCancelledAtOnceAndNeverReachesTheEngine() throws Exception {
        CountDownLatch release = new CountDownLatch(1), inside = new CountDownLatch(1);
        Probe probe = new Probe(ctl -> {
            inside.countDown();
            release.await();
            return answer();
        });
        svc = new GraphRunService(probe, TIGHT);                // 1 worker, 1 queue slot
        RunView first = svc.submit(req("alice", ring(4)));
        inside.await();
        RunView queued = svc.submit(withAlgorithm(req("alice", ring(4)), Algorithm.CONNECTED_COMPONENTS, Map.of()));
        assertEquals(Status.QUEUED, queued.status());
        assertEquals(Status.CANCELLED, svc.cancel(queued.id(), "alice", false).status());
        release.countDown();
        settle(first);
        assertEquals(1, probe.calls.get(), "only the first run was ever executed");
        assertNull(svc.get(queued.id()).result());
    }

    @Test
    void onlyTheStarterOrAnAdministratorMayCancel() throws Exception {
        CountDownLatch release = new CountDownLatch(1), inside = new CountDownLatch(1);
        svc = new GraphRunService(new Probe(ctl -> {
            inside.countDown();
            release.await();
            return answer();
        }), TIGHT);
        RunView v = svc.submit(req("alice", ring(4)));
        inside.await();
        assertEquals(Kind.FORBIDDEN, assertThrows(GraphRunException.class, () -> svc.cancel(v.id(), "mallory", false)).kind());
        assertFalse(svc.get(v.id()).cancelRequested(), "a refused cancel changes nothing");
        assertTrue(svc.cancel(v.id(), "root", true).cancelRequested());
        release.countDown();
        settle(v);
    }

    // ── terminal states, queue, validation ──────────────────────────────────────────────────────────────────────

    @Test
    void anEngineFailureEndsFailedNamingTheClassNeverTheMessage() throws Exception {
        svc = new GraphRunService(new Probe(ctl -> {
            throw new IllegalStateException("boom on entity msisdn-0771234567");
        }), TIGHT);
        RunView v = settle(svc.submit(req("alice", ring(4))));
        assertEquals(Status.FAILED, v.status());
        assertEquals("IllegalStateException", v.failure());
        assertNull(v.result());
        assertFalse(v.toString().contains("0771234567"), "an entity id in the exception message must not travel");
    }

    @Test
    void aFullQueueRefusesTheSubmitAndLeavesNoRunBehind() throws Exception {
        CountDownLatch release = new CountDownLatch(1), inside = new CountDownLatch(1);
        svc = new GraphRunService(new Probe(ctl -> {
            inside.countDown();
            release.await();
            return answer();
        }), TIGHT);                                             // 1 running + 1 waiting
        RunView a = svc.submit(req("alice", ring(4)));
        inside.await();
        RunView b = svc.submit(withAlgorithm(req("alice", ring(4)), Algorithm.IS_FOREST, Map.of()));
        GraphRunException full = assertThrows(GraphRunException.class,
                () -> svc.submit(withAlgorithm(req("alice", ring(4)), Algorithm.BRIDGES, Map.of())));
        assertEquals(Kind.REJECTED, full.kind());
        assertEquals(2, svc.list("alice", null).size(), "the refused submit is not in the table");
        release.countDown();
        settle(a);
        settle(b);
    }

    @Test
    void aBadRequestIsRefusedBeforeAnyRunExists() {
        svc = new GraphRunService(new Probe(ctl -> answer()), TIGHT);
        assertThrows(InvalidGraphRequest.class, () -> svc.submit(withAlgorithm(req("alice", ring(4)), Algorithm.PAGE_RANK, Map.of("nope", 1))));
        assertThrows(InvalidGraphRequest.class, () -> svc.submit(withAlgorithm(req("alice", ring(4)), Algorithm.SHORTEST_PATH, Map.of())));
        assertTrue(svc.list("alice", null).isEmpty());
    }

    @Test
    void anUnknownRunIs404() {
        svc = new GraphRunService(new Probe(ctl -> answer()), TIGHT);
        assertEquals(Kind.NOT_FOUND, assertThrows(GraphRunException.class, () -> svc.get("gr-nope")).kind());
    }

    @Test
    void awaitReturnsAnUnfinishedViewWhenTheWaitRunsOut() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        svc = new GraphRunService(new Probe(ctl -> {
            release.await();
            return answer();
        }), TIGHT);
        RunView v = svc.submit(req("alice", ring(4)));
        assertFalse(svc.await(v.id(), 30).status().terminal());
        release.countDown();
        assertEquals(Status.COMPLETED, settle(v).status());
    }

    @Test
    void listShowsOnlyTheCallersRunsNewestFirstAndFiltersByInvestigation() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        svc = new GraphRunService(new InMemoryGraphEngine(), new Limits(TIGHT.defaults(), TIGHT.ceilings(), 1, 8, 60_000, 50, 60_000, 8), now::get);
        RunView a = svc.submit(req("alice", ring(4)));
        settle(a);
        now.set(2_000);
        Request other = new Request("alice", "inv-2", "rel-2", "scope-A", Algorithm.IS_FOREST, Map.of(), "none", ring(4), GraphBudget.UNSTATED);
        RunView b = svc.submit(other);
        settle(b);
        svc.submit(req("bob", ring(4)));
        assertEquals(List.of(b.id(), a.id()), svc.list("alice", null).stream().map(RunView::id).toList());
        assertEquals(List.of(b.id()), svc.list("alice", "inv-2").stream().map(RunView::id).toList());
    }

    // ── retention + cache ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aFinishedRunIsDroppedAfterItsRetentionAndTheTableIsBounded() throws Exception {
        AtomicLong now = new AtomicLong(0);
        Limits lim = new Limits(TIGHT.defaults(), TIGHT.ceilings(), 1, 8, 1_000, 3, 60_000, 8);
        svc = new GraphRunService(new InMemoryGraphEngine(), lim, now::get);
        RunView v = settle(svc.submit(req("alice", ring(4))));
        assertEquals(Status.COMPLETED, svc.get(v.id()).status());
        now.set(1_001);
        assertEquals(Kind.NOT_FOUND, assertThrows(GraphRunException.class, () -> svc.get(v.id())).kind(), "retention dropped it");

        List<String> ids = new ArrayList<>();
        for (Algorithm a : List.of(Algorithm.DEGREE_CENTRALITY, Algorithm.IS_FOREST, Algorithm.BRIDGES, Algorithm.CONNECTED_COMPONENTS)) {
            ids.add(settle(svc.submit(withAlgorithm(req("alice", ring(4)), a, Map.of()))).id());
        }
        assertTrue(svc.list("alice", null).size() <= 3, "bounded by maxRuns");
        assertEquals(Kind.NOT_FOUND, assertThrows(GraphRunException.class, () -> svc.get(ids.get(0))).kind(), "the oldest finished went first");
        assertNotNull(svc.get(ids.get(3)));
    }

    @Test
    void theSameRequestIsACacheHitAndAnyDifferenceInTheKeyIsNot() throws Exception {
        Probe probe = new Probe(ctl -> answer());
        svc = new GraphRunService(probe, TIGHT);
        Request base = req("alice", ring(4));
        settle(svc.submit(base));
        RunView hit = svc.submit(base);
        assertEquals(Status.COMPLETED, hit.status(), "a hit is terminal at submit");
        assertTrue(hit.cached());
        assertNotNull(hit.result());
        assertEquals(1, probe.calls.get());

        // each part of the key, changed alone, misses
        settle(svc.submit(new Request("alice", "inv-1", "rel-OTHER", "scope-A", base.algorithm(), Map.of(), "none", base.input(), GraphBudget.UNSTATED)));
        settle(svc.submit(new Request("alice", "inv-1", "rel-1", "scope-B", base.algorithm(), Map.of(), "none", base.input(), GraphBudget.UNSTATED)));
        settle(svc.submit(new Request("alice", "inv-1", "rel-1", "scope-A", base.algorithm(), Map.of(), "count", base.input(), GraphBudget.UNSTATED)));
        settle(svc.submit(withAlgorithm(base, Algorithm.PAGE_RANK, Map.of("iterations", 10))));
        settle(svc.submit(withAlgorithm(base, Algorithm.PAGE_RANK, Map.of("iterations", 11))));
        assertEquals(6, probe.calls.get(), "relation key, row scope, weights and params each separate the entries");
        // the same explicit parameters hit the entry the first one stored
        settle(svc.submit(withAlgorithm(base, Algorithm.PAGE_RANK, Map.of("iterations", 10))));
        assertEquals(6, probe.calls.get());
    }

    @Test
    void aCachedResultOutlivesNeitherItsTtlNorTheBound() throws Exception {
        AtomicLong now = new AtomicLong(0);
        Probe probe = new Probe(ctl -> answer());
        svc = new GraphRunService(probe, new Limits(TIGHT.defaults(), TIGHT.ceilings(), 1, 8, 60_000, 50, 1_000, 2), now::get);
        Request r = req("alice", ring(4));
        settle(svc.submit(r));
        now.set(1_001);
        settle(svc.submit(r));
        assertEquals(2, probe.calls.get(), "expired");

        settle(svc.submit(withAlgorithm(r, Algorithm.IS_FOREST, Map.of())));
        settle(svc.submit(withAlgorithm(r, Algorithm.BRIDGES, Map.of())));       // evicts the oldest of the two cap-2 entries
        int before = probe.calls.get();
        settle(svc.submit(r));
        assertEquals(before + 1, probe.calls.get(), "evicted by size");
    }

    @Test
    void aFailedOrCancelledRunIsNeverCached() throws Exception {
        AtomicInteger n = new AtomicInteger();
        Probe probe = new Probe(ctl -> {
            if (n.incrementAndGet() == 1) throw new IllegalStateException("first call fails");
            return answer();
        });
        svc = new GraphRunService(probe, TIGHT);
        Request r = req("alice", ring(4));
        assertEquals(Status.FAILED, settle(svc.submit(r)).status());
        RunView again = settle(svc.submit(r));
        assertEquals(Status.COMPLETED, again.status());
        assertFalse(again.cached());
        assertNotEquals(0, probe.calls.get());
    }

    // ── the terminal hook (D-4 step 6: the route layer audits the END of an asynchronous run) ──────────────────

    private GraphRunService withHook(GraphEngine e, List<RunView> seen) {
        return new GraphRunService(e, TIGHT, System::currentTimeMillis, v -> {
            synchronized (seen) {
                seen.add(v);
            }
        });
    }

    @Test
    void theTerminalHookFiresExactlyOncePerRunForEveryTerminalState() throws Exception {
        List<RunView> seen = new ArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger n = new AtomicInteger();
        svc = withHook(new Probe(ctl -> {
            int call = n.incrementAndGet();
            if (call == 1) return answer();                                  // COMPLETED
            if (call == 2) throw new IllegalStateException("boom");          // FAILED
            gate.await();                                                    // call 3 blocks until cancelled
            ctl.checkpoint();
            return answer();
        }), seen);

        RunView done = settle(svc.submit(req("alice", ring(4))));
        assertEquals(Status.COMPLETED, done.status());
        RunView failed = settle(svc.submit(withAlgorithm(req("alice", ring(5)), Algorithm.BRIDGES, Map.of())));
        assertEquals(Status.FAILED, failed.status());
        RunView over = svc.submit(withBudget(req("alice", ring(50)), new GraphBudget(10, 0, 0)));   // terminal at submit
        assertEquals(Status.BUDGET_EXCEEDED, over.status());
        RunView cached = svc.submit(req("alice", ring(4)));                                          // terminal at submit (cache hit)
        assertTrue(cached.cached());
        RunView running = svc.submit(withAlgorithm(req("alice", ring(6)), Algorithm.IS_FOREST, Map.of()));
        until(() -> svc.get(running.id()).status() == Status.RUNNING);
        svc.cancel(running.id(), "alice", false);
        gate.countDown();
        assertEquals(Status.CANCELLED, settle(running).status());
        until(() -> { synchronized (seen) { return seen.size() == 5; } });

        synchronized (seen) {
            assertEquals(5, seen.size(), "one call per run");
            assertEquals(5, seen.stream().map(RunView::id).distinct().count(), "never twice for one run");
            for (RunView v : seen) assertTrue(v.status().terminal(), "only the final view is handed over");
            assertEquals(Set.of(Status.COMPLETED, Status.FAILED, Status.BUDGET_EXCEEDED, Status.CANCELLED),
                    seen.stream().map(RunView::status).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void aRunCancelledWhileQueuedFiresTheHookOnceAndAHookThatThrowsChangesNothing() throws Exception {
        List<RunView> seen = new ArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        svc = new GraphRunService(new Probe(ctl -> {
            gate.await();
            return answer();
        }), TIGHT, System::currentTimeMillis, v -> {
            synchronized (seen) {
                seen.add(v);
            }
            throw new IllegalStateException("a broken audit sink");
        });
        RunView first = svc.submit(req("alice", ring(4)));                       // occupies the one worker
        until(() -> svc.get(first.id()).status() == Status.RUNNING);
        RunView queued = svc.submit(withAlgorithm(req("alice", ring(5)), Algorithm.BRIDGES, Map.of()));
        assertEquals(Status.QUEUED, queued.status());
        assertEquals(Status.CANCELLED, svc.cancel(queued.id(), "alice", false).status(), "the throwing hook did not break cancel");
        gate.countDown();
        assertEquals(Status.COMPLETED, settle(first).status(), "nor the worker's own run");
        until(() -> { synchronized (seen) { return seen.size() == 2; } });
        synchronized (seen) {
            assertEquals(1, seen.stream().filter(v -> v.id().equals(queued.id())).count());
        }
    }

    @Test
    void closingTheServiceEndsTheRunsStillWaitingInTheQueue() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        svc = new GraphRunService(new Probe(ctl -> {
            inside.countDown();
            while (true) ctl.checkpoint();
        }), TIGHT);                                            // 1 worker, 1 queue slot
        svc.submit(req("alice", ring(4)));
        inside.await();
        RunView queued = svc.submit(withAlgorithm(req("alice", ring(4)), Algorithm.IS_FOREST, Map.of()));
        assertEquals(Status.QUEUED, queued.status());
        svc.close();
        assertEquals(Status.CANCELLED, svc.get(queued.id()).status(), "a dropped task must not stay QUEUED forever");
        assertTrue(svc.await(queued.id(), 50).status().terminal());
    }

    // ── live progress (LA-GRAPH-RUN-PROGRESS-1) ─────────────────────────────────────────────────────────────────

    @Test
    void aRunningRunReportsItsFractionAndElapsedTimeLiveAndTheFinalOnesAtTheEnd() throws Exception {
        CountDownLatch half = new CountDownLatch(1), release = new CountDownLatch(1);
        svc = new GraphRunService(new Probe(ctl -> {
            ctl.progress(1, 4);
            half.countDown();
            release.await();
            return answer();
        }), TIGHT);
        RunView queued = svc.submit(req("alice", ring(4)));
        half.await();
        RunView live = svc.get(queued.id());
        assertEquals(Status.RUNNING, live.status());
        assertTrue(live.progress().known());
        assertEquals(0.25, live.progress().fraction(), 1e-9, "the algorithm's own fraction, live");
        Thread.sleep(60);
        long mid = svc.get(queued.id()).consumed().elapsedMs();
        assertTrue(mid >= 50, "elapsedMs is live while RUNNING, was " + mid);
        release.countDown();
        RunView done = settle(queued);
        assertEquals(Status.COMPLETED, done.status());
        assertEquals(1.0, done.progress().fraction(), 1e-9, "a finished run whose algorithm reports shows 1");
        assertTrue(done.consumed().elapsedMs() >= mid, "the final elapsed is not smaller than a live one");
        long fixed = done.consumed().elapsedMs();
        Thread.sleep(30);
        assertEquals(fixed, svc.get(done.id()).consumed().elapsedMs(), "a terminal run's elapsedMs no longer moves");
    }

    @Test
    void anAlgorithmThatReportsNoFractionIsKnownFalseNotAnInventedZeroPercent() throws Exception {
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        svc = new GraphRunService(new Probe(ctl -> {
            ctl.checkpoint();
            inside.countDown();
            release.await();
            return answer();
        }), TIGHT);
        RunView started = svc.submit(req("alice", ring(4)));
        inside.await();
        RunView live = svc.get(started.id());
        assertFalse(live.progress().known());
        assertEquals(0.0, live.progress().fraction());
        assertEquals(1, live.progress().work(), "the checkpoint count is still live");
        release.countDown();
        RunView done = settle(started);
        assertFalse(done.progress().known(), "no fraction was ever reported, so none is claimed at the end either");
        assertEquals(0.0, done.progress().fraction());
    }

    @Test
    void aQueuedRunHasZeroElapsedTime() throws Exception {
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        svc = new GraphRunService(new Probe(ctl -> {
            inside.countDown();
            release.await();
            return answer();
        }), TIGHT);
        RunView a = svc.submit(req("alice", ring(4)));
        inside.await();
        RunView b = svc.submit(withAlgorithm(req("alice", ring(4)), Algorithm.IS_FOREST, Map.of()));
        Thread.sleep(40);
        assertEquals(Status.QUEUED, svc.get(b.id()).status());
        assertEquals(0, svc.get(b.id()).consumed().elapsedMs(), "elapsed counts from execution start, not from queueing");
        release.countDown();
        settle(a);
        settle(b);
    }

    // ── run table bound (LA-GRAPH-RUN-POOL-LIFECYCLE-1) ─────────────────────────────────────────────────────────

    private static long workerThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("la-graph-run-") && t.isAlive()).count();
    }

    /** An unused service holds no thread: workers time out when idle, with no timer and no sweep thread, and a new run restarts one. */
    @Test
    void anIdleServiceHoldsNoWorkerThreadAndARunStartsOneAgain() throws Exception {
        long before = workerThreads();
        svc = new GraphRunService(new InMemoryGraphEngine(), TIGHT, System::currentTimeMillis, null, 40);
        assertEquals(Status.COMPLETED, settle(svc.submit(req("alice", ring(4)))).status());
        assertTrue(workerThreads() > before, "the run started a worker");
        until(() -> workerThreads() == before);                                         // idle for the 40 ms keep-alive: it ended
        assertEquals(Status.COMPLETED, settle(svc.submit(withAlgorithm(req("alice", ring(4)), Algorithm.IS_FOREST, Map.of()))).status(),
                "the pool is still usable: a fresh worker took the next run");
        until(() -> workerThreads() == before);
    }

    /** maxRuns is soft (live runs are never dropped) yet the table stays bounded: live runs are capped by threads + queue. */
    @Test
    void maxRunsIsSoftForLiveRunsButTheTableStaysBoundedByThePool() throws Exception {
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        Limits lim = new Limits(TIGHT.defaults(), TIGHT.ceilings(), 1, 1, 60_000L, 1, 60_000L, 8);   // maxRuns 1, 1 running + 1 waiting
        svc = new GraphRunService(new Probe(ctl -> {
            inside.countDown();
            release.await();
            return answer();
        }), lim);
        RunView a = svc.submit(req("alice", ring(4)));
        inside.await();
        RunView b = svc.submit(withAlgorithm(req("alice", ring(4)), Algorithm.IS_FOREST, Map.of()));
        assertEquals(2, svc.list("alice", null).size(), "two live runs exceed maxRuns = 1: live runs are never dropped");
        assertEquals(2, svc.active());
        assertEquals(Kind.REJECTED, assertThrows(GraphRunException.class,
                () -> svc.submit(withAlgorithm(req("alice", ring(4)), Algorithm.BRIDGES, Map.of()))).kind(), "but a third cannot join");
        assertEquals(2, svc.list("alice", null).size());
        release.countDown();
        until(() -> svc.active() == 0);          // (not settle(): with maxRuns = 1 a finished run is dropped as soon as the next is stored)
        assertTrue(svc.list("alice", null).size() <= 1, "finished runs are held to maxRuns");
    }

    /**
     * The terminal hook fires right after COMPLETED is published. A caller that reacts to COMPLETED by resubmitting at once
     * (here: inside the hook, deterministically) must already find the cache entry, so the cache write is part of the
     * completion commit - the old write-after-publish order came back QUEUED on a slow CI runner.
     */
    @Test
    void aResubmitRightAfterCompletionIsAlwaysACacheHit() throws Exception {
        java.util.concurrent.atomic.AtomicReference<GraphRunService> self = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Request> request = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<RunView> resubmitted = new java.util.concurrent.atomic.AtomicReference<>();
        GraphRunService s = new GraphRunService(new Probe(ctl -> answer()), TIGHT, System::currentTimeMillis, v -> {
            if (v.status() == Status.COMPLETED && !v.cached() && resubmitted.get() == null)
                resubmitted.set(self.get().submit(request.get()));
        });
        self.set(s);
        svc = s;
        Request r = req("alice", ring(4));
        request.set(r);
        settle(s.submit(r));
        until(() -> resubmitted.get() != null);
        assertEquals(Status.COMPLETED, resubmitted.get().status(), "a hit is terminal at submit");
        assertTrue(resubmitted.get().cached());
    }

    // ── an index reference (D-3 step 7): estimate before the walk, measurement after it ──────────────────────────

    private static GraphInput.IndexRef indexRef(long version, int estNodes, int estEdges) {
        return new GraphInput.IndexRef(java.nio.file.Path.of("v" + version), version, "ds:map", List.of("A"), List.of(), estNodes, estEdges);
    }

    private static GraphResult subGraph(int nodes, int edges) {
        List<com.gamma.la.graph.GraphAlgorithms.Node> ns = new ArrayList<>();
        List<com.gamma.la.graph.GraphAlgorithms.Edge> es = new ArrayList<>();
        for (int i = 0; i < nodes; i++) ns.add(new com.gamma.la.graph.GraphAlgorithms.Node("n" + i, "n" + i));
        for (int i = 0; i < edges; i++) es.add(new com.gamma.la.graph.GraphAlgorithms.Edge("e" + i, "n0", "n0"));
        return new GraphResult(Algorithm.NEIGHBORHOOD, new GraphResult.SubGraph(new com.gamma.la.graph.GraphAlgorithms.Graph(ns, es)), 0, 1);
    }

    private static Request indexReq(GraphInput in) {
        return new Request("alice", "inv-1", "index:ds:map", "scope-A", Algorithm.NEIGHBORHOOD, Map.of("node", "A"), "none", in, GraphBudget.UNSTATED);
    }

    @Test
    void anIndexReferenceWhoseEstimateIsOverTheBudgetNeverReachesTheEngine() throws Exception {
        Probe probe = new Probe(ctl -> subGraph(1, 0));
        svc = new GraphRunService(probe, TIGHT);
        RunView v = svc.submit(indexReq(indexRef(1, 1_001, 5)));                         // the probe would have answered
        assertEquals(Status.BUDGET_EXCEEDED, v.status());
        assertEquals(Exceeded.NODES, v.exceeded());
        assertEquals(0, probe.calls.get(), "refused on the estimate, before the engine");
        assertNull(v.result());
        assertEquals(1_001, v.consumed().nodes(), "the estimate is what was consumed");
        assertEquals(Exceeded.EDGES, svc.submit(indexReq(indexRef(1, 5, 10_001))).exceeded());
    }

    @Test
    void anIndexRunWhoseMeasuredAnswerIsOverTheBudgetIsDiscardedWithTheMeasuredSize() throws Exception {
        Probe probe = new Probe(ctl -> subGraph(1_500, 3));                              // the estimate said 10 - the walk found 1 500
        svc = new GraphRunService(probe, TIGHT);
        RunView v = settle(svc.submit(indexReq(indexRef(1, 10, 10))));
        assertEquals(Status.BUDGET_EXCEEDED, v.status());
        assertEquals(Exceeded.NODES, v.exceeded());
        assertNull(v.result(), "no smaller answer shaped like a whole one");
        assertEquals(1_500, v.consumed().nodes(), "the measured figure replaces the estimate");
        assertEquals(1, probe.calls.get());
        // and it was not cached: the same request runs the engine again
        settle(svc.submit(indexReq(indexRef(1, 10, 10))));
        assertEquals(2, probe.calls.get());
    }

    @Test
    void anEngineThatReachesPastTheIndexCapEndsBudgetExceededNamingIt() throws Exception {
        Probe probe = new Probe(ctl -> {
            throw new IndexCapExceeded("frontier keys looked up per level", 26, 20);
        });
        svc = new GraphRunService(probe, TIGHT);
        RunView v = settle(svc.submit(indexReq(indexRef(1, 10, 10))));
        assertEquals(Status.BUDGET_EXCEEDED, v.status());
        assertEquals(Exceeded.INDEX_CAP, v.exceeded());
        assertTrue(v.exceededDetail().contains("26") && v.exceededDetail().contains("20"), v.exceededDetail());
        assertNull(v.result());
    }

    @Test
    void theCacheKeySeparatesIndexVersionsAndNeverCollidesWithTheWorkingSetKey() throws Exception {
        Probe probe = new Probe(ctl -> subGraph(2, 1));
        svc = new GraphRunService(probe, TIGHT);
        settle(svc.submit(indexReq(indexRef(1, 10, 10))));
        assertEquals(1, probe.calls.get());
        RunView same = svc.submit(indexReq(indexRef(1, 10, 10)));
        assertTrue(same.cached(), "same index version: a hit");
        assertEquals(1, probe.calls.get());
        RunView next = settle(svc.submit(indexReq(indexRef(2, 10, 10))));                // a newer version, everything else equal
        assertFalse(next.cached(), "a new index version must not be answered from the old version's cache");
        assertEquals(2, probe.calls.get());
        // the seeds are part of the identity too
        GraphInput.IndexRef other = new GraphInput.IndexRef(java.nio.file.Path.of("v1"), 1, "ds:map", List.of("B"), List.of(), 10, 10);
        assertFalse(settle(svc.submit(indexReq(other))).cached());
        // the Working Set key carries no identity suffix: its cache key is exactly what it was
        assertEquals("", ring(3).cacheIdentity());
    }

    @Test
    void aRunReportsTheEngineThatRanItAndTheInputKind() throws Exception {
        GraphEngine routing = new GraphEngine() {
            @Override public String engineId() { return "memory"; }
            @Override public String engineIdFor(GraphInput in) { return in instanceof GraphInput.IndexRef ? "index" : "memory"; }
            @Override public Set<Algorithm> supported() { return EnumSet.allOf(Algorithm.class); }
            @Override public GraphResult run(Algorithm a, Map<String, Object> p, GraphInput in, RunControl ctl) { return subGraph(1, 0); }
        };
        svc = new GraphRunService(routing, TIGHT);
        RunView idx = settle(svc.submit(indexReq(indexRef(7, 1, 1))));
        assertEquals("index", idx.engine());
        assertEquals("index", idx.inputKind());
        assertEquals(7, idx.indexVersion());
        RunView mem = settle(svc.submit(req("alice", ring(3))));
        assertEquals("memory", mem.engine());
        assertEquals("materialised", mem.inputKind());
        assertEquals(0, mem.indexVersion());
    }

    // ── cache key ────────────────────────────────────────────────────────────────────────────────────────────────

    private static String keyOf(Map<String, ?> raw) {
        Request r = new Request("u", "inv-1", "rel-1", "scope-A", Algorithm.PROPAGATED_RISK, raw, "none", ring(3), GraphBudget.UNSTATED);
        return GraphRunService.cacheKey(r, Algorithm.PROPAGATED_RISK.resolve(raw));
    }

    /** toString() made these pairs one key, so a later run was served the other's result: the encoding is unambiguous now. */
    @Test
    void requestsThatPrintAlikeGetDifferentCacheKeysAndTheKeyIsAFixedSizeHash() {
        assertNotEquals(keyOf(Map.of("seeds", List.of("Smith, John"))), keyOf(Map.of("seeds", List.of("Smith", "John"))));
        assertNotEquals(keyOf(Map.of("nodeScores", Map.of("a=1.0, b", 2))), keyOf(Map.of("nodeScores", Map.of("a", 1, "b", 2))));
        assertEquals(keyOf(Map.of("nodeScores", Map.of("b", 2, "a", 1))), keyOf(Map.of("nodeScores", Map.of("a", 1.0, "b", 2.0))),
                "the same request (any key order, int or double) is the same key");
        Map<String, Object> big = new java.util.HashMap<>();
        for (int i = 0; i < 10_000; i++) big.put("node-" + i, 50);
        assertEquals(64, keyOf(Map.of("nodeScores", big)).length(), "SHA-256 hex, whatever the parameter size");
    }
}

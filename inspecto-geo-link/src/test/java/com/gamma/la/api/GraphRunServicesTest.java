package com.gamma.la.api;

import com.gamma.control.ApiException;
import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphBudget;
import com.gamma.la.core.GraphEngine;
import com.gamma.la.core.GraphInput;
import com.gamma.la.core.GraphResult;
import com.gamma.la.core.GraphRunService;
import com.gamma.la.core.InMemoryGraphEngine;
import com.gamma.la.graph.RunControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-GRAPH-RUN-POOL-LIFECYCLE-1: the per-Space service registry closes an idle service (fake clock), drops a Space that is
 * gone, keeps a service with a run in flight, and cannot create a service after close - and none of it leaks a worker thread.
 */
@Timeout(60)
class GraphRunServicesTest {

    private static final long TTL = 1_000;
    private static final GraphRunService.Limits LIMITS = new GraphRunService.Limits(new GraphBudget(1_000, 10_000, 5_000),
            new GraphBudget(2_000, 20_000, 10_000), 1, 1, 60_000L, 50, 60_000L, 8);

    private final AtomicLong now = new AtomicLong(0);
    private final List<GraphRunService> made = new ArrayList<>();      // appended only inside the registry's lock

    private GraphRunServices registry() {
        return new GraphRunServices(root -> {
            GraphRunService s = new GraphRunService(new InMemoryGraphEngine(), LIMITS, now::get);
            made.add(s);
            return s;
        }, now::get, TTL);
    }

    private static long workerThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("la-graph-run-") && t.isAlive()).count();
    }

    private static void until(BooleanSupplier c) throws InterruptedException {
        long end = System.nanoTime() + 10_000_000_000L;
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("condition not reached in 10 s");
            Thread.sleep(5);
        }
    }

    private static GraphRunService.Request request() {
        return request("rel");
    }

    /** {@code relation} is part of the cache key: a different one is never a cache hit (which would not touch the pool). */
    private static GraphRunService.Request request(String relation) {
        GraphInput in = GraphInput.of(List.of(new GraphInput.Node("a", "a")), List.of(), Map.of());
        return new GraphRunService.Request("alice", "inv", relation, "", Algorithm.DEGREE_CENTRALITY, Map.of(), "none", in, GraphBudget.UNSTATED);
    }

    /** One tiny run, so the pool actually starts a worker thread (a pool spawns none until its first task). */
    private static void runOne(GraphRunService s) throws Exception {
        s.await(s.submit(request()).id(), 5_000);
    }

    @Test
    void anIdleServiceIsClosedAfterItsTtlAndAFreshOneIsBuiltOnNextUse(@TempDir Path a, @TempDir Path b) throws Exception {
        long before = workerThreads();
        try (GraphRunServices reg = registry()) {
            GraphRunService first = reg.get(a);
            runOne(first);
            assertTrue(workerThreads() > before, "the run started a worker");
            assertSame(first, reg.get(a), "within the TTL the same service is reused");
            now.set(TTL - 1);
            reg.get(b);
            assertEquals(2, reg.open(), "just under the TTL nothing is closed");

            now.set(TTL * 3);                          // a was last used at 0: idle for three TTLs
            GraphRunService other = reg.get(b);        // touching ANOTHER Space is what sweeps a
            assertEquals(1, reg.open(), "the idle service was closed and forgotten");
            assertSame(other, reg.get(b));
            until(() -> workerThreads() == before);    // its worker thread ended, none leaked
            assertThrows(RuntimeException.class, () -> first.submit(request("another-working-set")), "a closed service takes no more runs");

            GraphRunService fresh = reg.get(a);
            assertNotSame(first, fresh, "the next use builds a fresh service");
            runOne(fresh);
        }
        until(() -> workerThreads() == before);
        assertEquals(3, made.size());
    }

    @Test
    void aServiceWithARunInFlightIsNotClosedNoMatterHowIdle(@TempDir Path a, @TempDir Path b) throws Exception {
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        GraphEngine hold = new GraphEngine() {
            @Override public String engineId() { return "hold"; }
            @Override public Set<Algorithm> supported() { return EnumSet.allOf(Algorithm.class); }

            @Override
            public GraphResult run(Algorithm al, Map<String, Object> p, GraphInput in, RunControl ctl) {
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new GraphResult(al, new GraphResult.Flag(true), 0, 1);
            }
        };
        try (GraphRunServices reg = new GraphRunServices(root -> new GraphRunService(hold, LIMITS, now::get), now::get, TTL)) {
            GraphRunService s = reg.get(a);
            GraphRunService.RunView v = s.submit(request());
            inside.await();
            now.set(TTL * 10);
            reg.get(b);
            assertEquals(2, reg.open(), "a service with a live run survives the sweep");
            assertSame(s, reg.get(a));
            release.countDown();
            assertEquals(GraphRunService.Status.COMPLETED, s.await(v.id(), 5_000).status());
        }
    }

    /** D3: a run that finishes after the last get() is activity - its retained result must outlive a sweep triggered for ANOTHER Space. */
    @Test
    void aServiceWhoseRunJustFinishedIsNotClosedWhileItsResultIsRetained(@TempDir Path a, @TempDir Path b) throws Exception {
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        GraphEngine hold = new GraphEngine() {
            @Override public String engineId() { return "hold"; }
            @Override public Set<Algorithm> supported() { return EnumSet.allOf(Algorithm.class); }

            @Override
            public GraphResult run(Algorithm al, Map<String, Object> p, GraphInput in, RunControl ctl) {
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new GraphResult(al, new GraphResult.Flag(true), 0, 1);
            }
        };
        try (GraphRunServices reg = new GraphRunServices(root -> new GraphRunService(hold, LIMITS, now::get), now::get, TTL)) {
            GraphRunService s = reg.get(a);                    // last get at t = 0
            GraphRunService.RunView v = s.submit(request());
            inside.await();
            now.set(TTL - 100);                                // nobody polls; the run finishes just inside the idle TTL
            release.countDown();
            assertEquals(GraphRunService.Status.COMPLETED, s.await(v.id(), 5_000).status());
            now.set(TTL + 500);                                // 1.5 TTL after the last get, 0.6 TTL after the finish
            reg.get(b);                                        // sweeps a: it must be kept, its result is still retained
            assertEquals(2, reg.open(), "a service holding a retained fresh result is not idle");
            assertEquals(GraphRunService.Status.COMPLETED, s.get(v.id()).status());
            now.set(TTL * 3);                                  // past the TTL of the finish too: now it may go
            reg.get(b);
            assertEquals(1, reg.open());
        }
    }

    @Test
    void aSpaceThatIsGoneIsClosedAtOnceHoweverRecentlyItWasUsed(@TempDir Path base, @TempDir Path b) throws Exception {
        Path gone = Files.createDirectory(base.resolve("space-x"));
        try (GraphRunServices reg = registry()) {
            reg.get(gone);
            reg.get(b);
            assertEquals(2, reg.open());
            Files.delete(gone);                                    // the Space was removed
            reg.get(b);
            assertEquals(1, reg.open(), "no TTL needed: its pool is not kept for a Space that no longer exists");
        }
    }

    @Test
    void nothingIsCreatedAfterCloseAndCloseClosesEveryService(@TempDir Path a) throws Exception {
        long before = workerThreads();
        GraphRunServices reg = registry();
        runOne(reg.get(a));
        reg.close();
        until(() -> workerThreads() == before);
        assertEquals(0, reg.open());
        ApiException refused = assertThrows(ApiException.class, () -> reg.get(a));
        assertTrue(refused.getMessage().contains("shutting down"), refused.getMessage());
        assertEquals(1, made.size(), "no service was built after close");
        reg.close();                                               // idempotent
    }

    /** Many threads racing get() against close(): afterwards no service is open, none was built after close, no thread leaked. */
    @Test
    void racingGetsAndCloseLeaveNoOpenServiceAndNoLeakedThread(@TempDir Path a) throws Exception {
        long before = workerThreads();
        GraphRunServices reg = registry();
        ExecutorService ex = Executors.newFixedThreadPool(8);
        AtomicInteger refused = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 8; i++) fs.add(ex.submit(() -> {
            go.await();
            for (; ; ) {
                try {
                    runOne(reg.get(a));
                } catch (ApiException closed) {
                    refused.incrementAndGet();
                    return null;
                } catch (RuntimeException closedUnderUs) {
                    // the service was closed or full between get() and submit(): a refusal, never a hang
                }
            }
        }));
        go.countDown();
        Thread.sleep(30);
        reg.close();
        for (Future<?> f : fs) f.get();
        ex.shutdown();
        assertEquals(0, reg.open());
        assertEquals(8, refused.get(), "every racer was refused once closed");
        until(() -> workerThreads() == before);
    }
}

package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests for the poll tick's <b>dispatch-and-return</b> path
 * ({@link PipelineScheduler#dispatchCycle()}) — the periodic driver's entry point.
 *
 * <p>{@link PipelineRunGuard} (see {@code CollectorServiceIngestLockTest}) made ingest exclusion
 * per-pipeline, so a manual trigger no longer waits on an unrelated pipeline. But the tick itself was
 * still synchronous, and the scheduler is fixed-delay: a slow pipeline in tick <i>N</i> delayed tick
 * <i>N+1</i> for <em>every</em> pipeline. These tests pin the fix — a tick starts its runs and returns.
 *
 * <h3>How a run is made slow, and why the test cannot slow-pass</h3>
 * {@code BatchEventBus.publish} is synchronous on the runner thread, inside the claim, so a subscriber
 * that parks on a latch stalls that pipeline's run mid-flight. That is a genuinely in-flight run, not a
 * held claim — a held claim would make the pipeline <em>skipped</em>, which is the B1 property, not this
 * one. The tick is then submitted to a separate executor and required to finish within a bound: a
 * synchronous tick would still be inside the parked subscriber, so {@code get} times out and the test
 * fails outright instead of eventually passing once the park expires.
 */
class CollectorServiceDispatchTest {

    private static final String CSV  = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n";
    private static final String CSV2 = "ID,AMT,EVENT_DATE\n2,20,2020-01-02\n";

    /** A pipeline with a seeded inbox. Distinct {@code name}s matter: same id ⇒ one pipeline to the run guard. */
    private static Path source(Path root, String name) throws Exception {
        Path toon = TestConfigs.csv(root, PipelineConfigBatchTest.miniSchema()).name(name).write();
        Path inbox = root.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), CSV);
        return toon;
    }

    private static long outputCsvCount(Path root) throws Exception {
        Path db = root.resolve("db");
        if (!Files.exists(db)) return 0;
        try (var s = Files.walk(db)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".csv")).count();
        }
    }

    private static PipelineScheduler scheduler(CollectorService svc) throws Exception {
        Field f = CollectorService.class.getDeclaredField("pipelineScheduler");
        f.setAccessible(true);
        return (PipelineScheduler) f.get(svc);
    }

    private static PipelineRunGuard runGuard(CollectorService svc) throws Exception {
        Field f = CollectorService.class.getDeclaredField("runGuard");
        f.setAccessible(true);
        return (PipelineRunGuard) f.get(svc);
    }

    /** Fire one poll tick off the test thread, so a tick that (wrongly) waits on its runs shows up as a timeout. */
    private static Future<?> submitTick(ExecutorService ex, CollectorService svc) {
        return ex.submit(() -> {
            try { scheduler(svc).dispatchCycle(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    /** Poll until {@code cond} holds, up to ~10s. Returns whether it ever did. */
    private static boolean awaitTrue(Condition cond) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (cond.holds()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void dispatchCycleReturnsWhileTheRunIsStillInFlight(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"), "TEST_ETL_A");
        CountDownLatch stalled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        // Not try-with-resources: the parked run must be released BEFORE close(), which drains the workers.
        CollectorService svc = new CollectorService(List.of(a), 3600, 1);
        try {
            String name = svc.pipelines().get(0).name();
            svc.eventBus().subscribe(e -> {
                stalled.countDown();
                try { release.await(30, SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            });

            submitTick(ex, svc).get(5, SECONDS);

            assertTrue(stalled.await(10, SECONDS), "the dispatched run started on another thread");
            assertTrue(runGuard(svc).isRunning(name),
                    "the tick returned while its run was still holding the pipeline's claim");

            release.countDown();
            assertTrue(awaitTrue(() -> !runGuard(svc).isRunning(name)),
                    "the dispatched run releases its own claim when it finishes");
            assertTrue(awaitTrue(() -> outputCsvCount(dir.resolve("a")) >= 1),
                    "the dispatched run really ingested, off the tick's thread");
        } finally {
            release.countDown();          // never leave a worker parked if an assertion failed
            ex.shutdownNow();
            svc.close();
        }
    }

    @Test
    void aStalledRunDoesNotBlockTheNextTick(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"), "TEST_ETL_A");         // stalls mid-run, below
        Path b = source(dir.resolve("b"), "TEST_ETL_B");         // unrelated pipeline
        CountDownLatch stalledA = new CountDownLatch(1);
        CountDownLatch release  = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        // Budget of 4, deliberately above the one stalled run: runPermits is a real bound, so a budget of 1
        // would (correctly) park the second tick's run behind the stalled one and prove nothing about ticks.
        CollectorService svc = new CollectorService(List.of(a, b), 3600, 4);
        try {
            List<String> names = svc.pipelines().stream().map(p -> p.name()).toList();
            assertEquals(2, names.size(), "both pipelines registered under distinct ids: " + names);
            String aName = names.stream().filter(n -> !n.toUpperCase().endsWith("_B")).findFirst()
                    .orElseThrow(() -> new AssertionError("no non-_B pipeline in " + names));
            String bName = names.stream().filter(n -> !n.equals(aName)).findFirst().orElseThrow();

            svc.eventBus().subscribe(e -> {
                if (!aName.equalsIgnoreCase(e.pipeline())) return;    // only A stalls
                stalledA.countDown();
                try { release.await(30, SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            });

            submitTick(ex, svc).get(5, SECONDS);
            assertTrue(stalledA.await(10, SECONDS), "A's run started and parked");
            // Both conditions matter: B must have actually produced output AND released its claim, or a
            // not-yet-started B would look "finished" and the baseline below would be a false zero.
            assertTrue(awaitTrue(() -> outputCsvCount(dir.resolve("b")) >= 1), "B's first run produced output");
            assertTrue(awaitTrue(() -> !runGuard(svc).isRunning(bName)), "B's first run finished");
            long bBefore = outputCsvCount(dir.resolve("b"));

            // A is still mid-run. Give B new work and fire another tick: before dispatch-and-return, ticks
            // were serialised behind the runs they started, so this tick could not have happened at all.
            Files.writeString(dir.resolve("b").resolve("inbox").resolve("more.csv"), CSV2);
            submitTick(ex, svc).get(5, SECONDS);

            assertTrue(awaitTrue(() -> outputCsvCount(dir.resolve("b")) > bBefore),
                    "the next tick ran B while A was mid-run (baseline " + bBefore + ")");
            assertTrue(runGuard(svc).isRunning(aName), "...and A was still stalled the whole time");

            release.countDown();
            assertTrue(awaitTrue(() -> !runGuard(svc).isRunning(aName)), "A finishes once released");
        } finally {
            release.countDown();
            ex.shutdownNow();
            svc.close();
        }
    }
}

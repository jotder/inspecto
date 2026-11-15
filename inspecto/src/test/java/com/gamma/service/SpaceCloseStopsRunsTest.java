package com.gamma.service;

import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobResult;
import com.gamma.job.JobService;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.JobTypesForTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code JOB-OUTLIVES-CLOSE-1}: {@code ControlApiSpaceTemplateSeedGateTest} once failed to delete its temp folder
 * because {@code t-la-utility/data/transformer_loss} was still being written after the server had closed. A Space
 * whose drain overran {@link SpaceManager}'s close budget was abandoned at once, while its Job Run went on writing.
 * Now the Runs are interrupted and the close waits a bounded grace for them: a Run that stops returns the close only
 * after it stopped, and a Run that ignores even the interrupt still cannot hold the close past its budgets.
 */
class SpaceCloseStopsRunsTest {

    @AfterEach
    void restoreBudgets() {
        SpaceManager.closeBudgetsForTest(-1, -1);
    }

    private static JobTypeProvider type(String id, Supplier<JobResult> body) {
        JobTypeDescriptor d = new JobTypeDescriptor(id, id, "test", List.of());
        return JobTypeProvider.of(d, c -> new Job() {
            @Override public String name() { return c.name(); }
            @Override public String type() { return id; }
            @Override public JobResult run() { return body.get(); }
        });
    }

    /** Boot Space {@code id} under {@code root} with {@code provider}'s Job in flight. */
    private static JobService runInFlight(SpaceManager mgr, String id, JobTypeProvider provider) {
        JobService js = mgr.space(SpaceId.of(id)).orElseThrow().service().jobServiceOrCreate();
        JobTypesForTest.register(js, provider);
        String type = provider.descriptor().id();
        js.upsertJob(new JobConfig("landing", type, null, null, true, false, Map.of(), null, null));
        js.triggerRun("landing", "test").orElseThrow();
        return js;
    }

    @Test
    void aCloseOverItsBudgetReturnsOnlyAfterTheRunStoppedWriting(@TempDir Path root) throws Exception {
        SpaceManager.closeBudgetsForTest(1_000, 900);   // the drain alone must overrun; the interrupt stops the Run fast
        Files.createDirectories(root.resolve("busy").resolve("config"));
        Path landing = root.resolve("busy").resolve("data").resolve("transformer_loss").resolve("sql-1.parquet.tmp");
        Files.createDirectories(landing.getParent());
        CountDownLatch writing = new CountDownLatch(1), stopped = new CountDownLatch(1);
        // a Run that lands its output a little at a time and never checks the cooperative cancel
        JobTypeProvider slow = type("t.landing", () -> {
            try {
                while (true) {
                    Files.write(landing, new byte[] {1}, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    writing.countDown();
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted", e);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                stopped.countDown();
            }
        });
        SpaceManager mgr = SpaceManager.discover(root);
        try {
            JobService js = runInFlight(mgr, "busy", slow);
            assertTrue(writing.await(10, TimeUnit.SECONDS), "precondition: the Run is writing");

            mgr.close();

            assertEquals(0, stopped.getCount(), "close() returned while the Job Run was still writing");
            long size = Files.size(landing);
            Thread.sleep(200);
            assertEquals(size, Files.size(landing), "nothing writes after close() returned");
            assertEquals("CANCELLED", js.lastRunOf("landing").orElseThrow().status(),
                    "the interrupted Run of a closing Space is recorded as a planned stop");
        } finally {
            stopped.await(10, TimeUnit.SECONDS);   // on a failure, let the Run end before @TempDir is removed
        }
    }

    @Test
    void aRunThatIgnoresTheInterruptCannotHoldTheCloseOpen(@TempDir Path root) throws Exception {
        SpaceManager.closeBudgetsForTest(300, 200);
        Files.createDirectories(root.resolve("stuck").resolve("config"));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        JobTypeProvider stuck = type("t.stuck", () -> {
            entered.countDown();
            boolean interrupted = false;
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    interrupted = true;   // swallowed: the Run will not stop
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return JobResult.ok("released", 0L);
        });
        SpaceManager mgr = SpaceManager.discover(root);
        try {
            runInFlight(mgr, "stuck", stuck);
            assertTrue(entered.await(10, TimeUnit.SECONDS), "precondition: the Run is in flight");

            long t0 = System.nanoTime();
            mgr.close();
            long ms = (System.nanoTime() - t0) / 1_000_000L;

            assertEquals(1, release.getCount(), "precondition: the Run is still in flight when close() returns");
            assertTrue(ms < 5_000, "close() stays bounded by its two budgets (2 x 300 ms), took " + ms + " ms");
        } finally {
            release.countDown();   // let the abandoned Run and its close end before @TempDir is removed
            for (Thread t : Thread.getAllStackTraces().keySet())
                if (t.getName().equals("space-close-stuck") || t.getName().equals("space-flush-stuck")) t.join(10_000);
        }
    }
}

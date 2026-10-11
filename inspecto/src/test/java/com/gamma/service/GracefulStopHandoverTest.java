package com.gamma.service;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.audit.Event;
import com.gamma.inspector.CommitFence;
import com.gamma.job.JobConfig;
import com.gamma.job.JobType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HA-CANCEL-RESIDUALS-1 (1)+(5), end to end: a real {@link CollectorService} over a shared (DB) run lease is
 * closed while a run is mid-flight - parked inside the run thread, with its claim and {@code CommitFence} hold
 * live. The close must signal the run (its next checkpoint throws {@code RunCancelledException}), drain it, and
 * the run thread then releases its claim, so a second node claims the same key. Once for a Collector pipeline
 * run ({@code Scope.RUN}), once for a Job run ({@code Scope.JOB}, its {@code claimsOver} arming lease).
 */
class GracefulStopHandoverTest {

    private static final Duration TTL = Duration.ofSeconds(60);

    @AfterEach
    void clearLeaseBackend() {
        System.clearProperty("run.lease.backend");
    }

    private static String leaseUrl(Path dir) {
        return "jdbc:duckdb:" + dir.resolve("lease.db").toString().replace('\\', '/');
    }

    /**
     * Parks the first matching event's emitting thread (the run thread: EventLog notifies subscribers inline)
     * until the service's close() has signalled, then probes the run's checkpoint from inside the run.
     */
    private static final class Park implements Consumer<Event> {
        final CountDownLatch parked = new CountDownLatch(1), signalled = new CountDownLatch(1),
                resume = new CountDownLatch(1);
        final AtomicBoolean armed = new AtomicBoolean(true);
        final AtomicReference<Throwable> probe = new AtomicReference<>();
        final Predicate<Event> match;
        final Runnable checkpoint;
        final java.util.function.Supplier<java.util.function.BooleanSupplier> cancelSignal;

        Park(Predicate<Event> match, Runnable checkpoint,
             java.util.function.Supplier<java.util.function.BooleanSupplier> cancelSignal) {
            this.match = match; this.checkpoint = checkpoint; this.cancelSignal = cancelSignal;
        }

        @Override public void accept(Event e) {
            if (!match.test(e) || !armed.compareAndSet(true, false)) return;
            parked.countDown();
            try {
                // Deterministic: wait (on the run thread, which carries the run's Space) until close() has
                // actually raised the cancel - re-resolved each poll - then tell the test it has signalled.
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!cancelSignal.get().getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
                signalled.countDown();
                resume.await(10, TimeUnit.SECONDS);
                checkpoint.run();
                probe.set(new AssertionError("checkpoint passed - the run did not see the cancel"));
            } catch (Throwable t) {
                probe.set(t);
            }
        }
    }

    /** close() in the background; wait until it has signalled the run, then let the parked run reach its checkpoint. */
    private static CompletableFuture<Void> closeWhileParked(CollectorService svc, Park park) throws Exception {
        CompletableFuture<Void> closing = CompletableFuture.runAsync(svc::close);
        // close() signals first (cancelRuns), then drains: once the run sees the cancel, the drain waits on us
        assertTrue(park.signalled.await(10, TimeUnit.SECONDS), "close() raised the run's cancel signal");
        assertFalse(closing.isDone(), "close() drains the in-flight run - it must not finish while the run is parked");
        park.resume.countDown();
        closing.get(10, TimeUnit.SECONDS);
        return closing;
    }

    @Test
    void closingMidCollectorRunCancelsItReleasesTheLeaseAndASurvivorClaims(@TempDir Path dir) throws Exception {
        String url = leaseUrl(dir);
        System.setProperty("run.lease.backend", url);
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        PipelineConfig cfg = PipelineConfig.load(pipe.toString());
        String name = cfg.identity().pipelineName();
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("feed.csv"), "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\n");

        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        Park park = new Park(e -> Thread.currentThread().isVirtual() && name.equalsIgnoreCase(e.pipeline()),
                () -> CommitFence.checkpoint(CommitFence.Scope.RUN, name),
                () -> CommitFence.cancelSignal(CommitFence.Scope.RUN, name));
        svc.eventLog().addSubscriber(park);
        try (DbRunLease survivor = DbRunLease.open(url, null, null, svc.spaceId(), DbRunLease.SCOPE_RUN, "pod-b", TTL)) {
            CompletableFuture<?> run = CompletableFuture.runAsync(() -> svc.runPipelineOffThread(name, false));
            assertTrue(park.parked.await(20, TimeUnit.SECONDS), "the run emitted an event mid-flight");
            assertNull(survivor.tryAcquire(name), "mid-run the second node is refused");

            closeWhileParked(svc, park);

            assertInstanceOf(CommitFence.RunCancelledException.class, park.probe.get(),
                    "the run's checkpoint saw the close: " + park.probe.get());
            try { run.get(10, TimeUnit.SECONDS); } catch (Exception ignored) { /* the cancelled run may surface it */ }
            RunLease.Claim taken = survivor.tryAcquire(name);
            assertNotNull(taken, "the drained run released its claim - the second node takes over");
            taken.close();
        } finally {
            svc.eventLog().removeSubscriber(park);
            svc.close();
        }
    }

    /**
     * A pipeline with two inbox files = two Consignments run one at a time (threads: 1), and a Park on the run
     * thread's first event - emitted after the FIRST batch commits, so the second batch is still to come and
     * its checkpoint sees the close.
     */
    private static Object[] parkedPipeline(Path dir, CollectorService[] svcOut) throws Exception {
        System.setProperty("run.lease.backend", leaseUrl(dir));
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        String toon = Files.readString(pipe);
        assertTrue(toon.contains("threads: 2"));
        Files.writeString(pipe, toon.replace("threads: 2", "threads: 1"));
        PipelineConfig cfg = PipelineConfig.load(pipe.toString());
        String name = cfg.identity().pipelineName();
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        for (int i = 0; i < 2; i++)
            Files.writeString(inbox.resolve("f" + i + ".csv"), "ID,AMT,EVENT_DATE\nr" + i + ",1.0,2020-04-03\n");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        Park park = new Park(e -> Thread.currentThread().isVirtual() && name.equalsIgnoreCase(e.pipeline()),
                () -> CommitFence.checkpoint(CommitFence.Scope.RUN, name),
                () -> CommitFence.cancelSignal(CommitFence.Scope.RUN, name));
        svc.eventLog().addSubscriber(park);
        svcOut[0] = svc;
        return new Object[] {name, park, inbox};
    }

    /** Operator decision 2026-10-11: a cancelled manual pipeline run is recorded CANCELLED, never SUCCESS. */
    @Test
    void aManualPipelineRunCancelledByCloseIsRecordedCancelled(@TempDir Path dir) throws Exception {
        CollectorService[] box = new CollectorService[1];
        Object[] p = parkedPipeline(dir, box);
        CollectorService svc = box[0];
        Park park = (Park) p[1];
        try {
            String runId = svc.triggerRunAsync((String) p[0]).orElseThrow();
            assertTrue(park.parked.await(20, TimeUnit.SECONDS), "the run emitted an event mid-flight");
            closeWhileParked(svc, park);
            PipelineRun r = svc.pipelineRunById(runId).orElseThrow();
            assertEquals("CANCELLED", r.status(), "a cancelled run is not a success: " + r);
            assertEquals(0, r.failed(), "nor a failure");
            try (var left = Files.list((Path) p[2])) {
                assertEquals(1, left.count(), "the uncommitted batch's input stays in the inbox");
            }
        } finally {
            svc.eventLog().removeSubscriber(park);
            svc.close();
        }
    }

    /** ...and a cancelled scheduled run neither counts as failed nor marks the poll failed. */
    @Test
    void aScheduledRunCancelledByCloseIsNotAFailure(@TempDir Path dir) throws Exception {
        CollectorService[] box = new CollectorService[1];
        Object[] p = parkedPipeline(dir, box);
        CollectorService svc = box[0];
        Park park = (Park) p[1];
        String name = (String) p[0];
        try {
            CompletableFuture<com.gamma.inspector.MultiCollectorProcessor.RunResult> cycle =
                    CompletableFuture.supplyAsync(svc::runAllOnce);
            assertTrue(park.parked.await(20, TimeUnit.SECONDS), "the run emitted an event mid-flight");
            closeWhileParked(svc, park);
            com.gamma.inspector.MultiCollectorProcessor.RunResult res = cycle.get(10, TimeUnit.SECONDS);
            assertEquals(0, res.failed(), "a cancel is not a failed source run");
            assertEquals(1, res.cancelled(), "run-all reports the source as cancelled: " + res);
            assertEquals(0, res.succeeded(), "...not as succeeded");
            Map<String, Object> row = svc.collectors().stream()
                    .filter(m -> name.equalsIgnoreCase(String.valueOf(m.get("pipeline")))).findFirst().orElseThrow();
            assertNull(row.get("lastError"), "the poll is not marked failed: " + row);
            try (var left = Files.list((Path) p[2])) {
                assertEquals(1, left.count(), "the uncommitted batch's input stays in the inbox");
            }
        } finally {
            svc.eventLog().removeSubscriber(park);
            svc.close();
        }
    }

    @Test
    void closingMidJobRunCancelsItReleasesTheLeaseAndASurvivorClaims(@TempDir Path dir) throws Exception {
        String url = leaseUrl(dir);
        System.setProperty("run.lease.backend", url);
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");

        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        AtomicReference<String> runId = new AtomicReference<>();
        Park park = new Park(e -> {
                    if (!com.gamma.audit.EventType.SIGNAL.equals(e.type())) return false;
                    com.gamma.signal.Signal sig = com.gamma.signal.Signal.fromEvent(e);
                    if (!com.gamma.signal.SignalType.JOB_RUN_STARTED.equals(sig.type())) return false;
                    Object id = sig.payload() == null ? null : sig.payload().get("run");
                    runId.set(id == null ? null : id.toString());
                    return true;
                },
                () -> CommitFence.checkpoint(CommitFence.Scope.JOB, runId.get()),
                () -> CommitFence.cancelSignal(CommitFence.Scope.JOB, runId.get()));
        svc.eventLog().addSubscriber(park);
        try (DbRunLease survivor = DbRunLease.open(url, null, null, svc.spaceId(), DbRunLease.SCOPE_JOB, "pod-b", TTL)) {
            var jobs = svc.jobServiceOrCreate();
            jobs.upsertJob(new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat")));
            assertTrue(jobs.trigger("hb"));
            assertTrue(park.parked.await(20, TimeUnit.SECONDS), "the job run started");
            assertNotNull(runId.get(), "the started signal names its Run");
            assertNull(survivor.tryAcquire("hb"), "mid-run the second node is refused the job's arming claim");

            closeWhileParked(svc, park);

            assertInstanceOf(CommitFence.RunCancelledException.class, park.probe.get(),
                    "the job run's checkpoint saw the close: " + park.probe.get());
            RunLease.Claim taken = survivor.tryAcquire("hb");
            assertNotNull(taken, "the drained job run released its claim - the second node takes over");
            taken.close();
        } finally {
            svc.eventLog().removeSubscriber(park);
            svc.close();
        }
    }
}

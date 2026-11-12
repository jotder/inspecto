package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.event.EventLog;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JOB-SIGNAL-OVERLAP-SKIP-1 (la-card Basic live check 2026-11-07): a coalescing {@code on_signal} Job that is
 * running when more matching Signals land must run ONCE more after the run in flight, not record those Signals
 * SKIPPED "previous run still in flight" — the skipped landing was never re-run and the link Dataset missed it.
 */
class JobSignalCoalesceTest {

    private static final String SIGNAL = "probe.burst";

    /** A Job Type whose first run blocks on {@code release}; every run counts itself. */
    private static JobTypeProvider gated(CountDownLatch entered, CountDownLatch release, AtomicInteger runs) {
        JobTypeDescriptor d = new JobTypeDescriptor("t.gated", "t.gated", "test", List.of());
        return JobTypeProvider.of(d, c -> new Job() {
            @Override public String name() { return c.name(); }
            @Override public String type() { return "t.gated"; }
            @Override public JobResult run() {
                if (runs.incrementAndGet() == 1) {
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return JobResult.ok("ran", 0L);
            }
        });
    }

    @Test
    void aBurstOnASignalRunInFlightFoldsIntoOneFollowUpRunNotSkips(@TempDir Path dir) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            EventLog log = EventLog.create();
            js.eventLog(log);
            js.registerJobType(gated(entered, release, runs));
            JobConfig links = new JobConfig("links", "t.gated", null, null, true, false, Map.of(), SIGNAL, null);
            assertTrue(links.coalesce(), "the default Job coalesces");
            js.upsertJob(links);
            js.start();

            log.emit(probe(0));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the first run never started");
            for (int i = 1; i <= 5; i++) log.emit(probe(i));   // the landings that arrive during the run
            Thread.sleep(300);
            release.countDown();

            awaitSettled(js, "links", 2);
            assertEquals(2, successes(js, "links"), "one run + ONE folded follow-up: " + js.runsFor("links"));
            assertTrue(js.runsFor("links").stream().noneMatch(r -> "SKIPPED".equals(r.status())),
                    "a coalesced signal must not be skipped: " + js.runsFor("links"));
            assertEquals(2, runs.get());
        }
    }

    /** The follow-up also waits for a run of the same Job started by another trigger (cron, manual). */
    @Test
    void aSignalOnAScheduledRunInFlightRunsAfterItNotSkips(@TempDir Path dir) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            EventLog log = EventLog.create();
            js.eventLog(log);
            js.registerJobType(gated(entered, release, runs));
            js.upsertJob(new JobConfig("links", "t.gated", null, null, true, false, Map.of(), SIGNAL, null));
            js.start();

            js.submit("links", "schedule");
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the scheduled run never started");
            log.emit(probe(1));
            Thread.sleep(300);
            release.countDown();

            awaitSettled(js, "links", 2);
            assertEquals(2, successes(js, "links"), js.runsFor("links").toString());
            assertTrue(js.runsFor("links").stream().anyMatch(r -> r.trigger().equals("signal:" + SIGNAL)
                    && "SUCCESS".equals(r.status())), js.runsFor("links").toString());
        }
    }

    /** JOB-SHUTDOWN-FAILED-1 still holds for a waiting follow-up: the Space began to close while it waited, so it
     *  is SKIPPED "shutting down" and never starts. */
    @Test
    void aFollowUpWaitingWhenTheSpaceStartsToCloseIsSkippedNotRun(@TempDir Path dir) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            EventLog log = EventLog.create();
            js.eventLog(log);
            js.registerJobType(gated(entered, release, runs));
            js.upsertJob(new JobConfig("links", "t.gated", null, null, true, false, Map.of(), SIGNAL, null));
            js.start();

            js.submit("links", "schedule");
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the scheduled run never started");
            log.emit(probe(1));
            Thread.sleep(300);   // the follow-up is now waiting for the lock
            js.beginClose();
            release.countDown();

            long until = System.currentTimeMillis() + 10_000;
            while (js.runsFor("links").stream().noneMatch(r -> "SKIPPED".equals(r.status()))
                    && System.currentTimeMillis() < until) Thread.sleep(20);
            Thread.sleep(300);
            assertEquals(1, runs.get(), "the follow-up must not start after close began: " + js.runsFor("links"));
            assertTrue(js.runsFor("links").stream().anyMatch(r -> "SKIPPED".equals(r.status())
                    && JobService.SHUTTING_DOWN.equals(r.message())), js.runsFor("links").toString());
        }
    }

    private static void awaitSettled(JobService js, String name, int want) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (successes(js, name) < want && System.currentTimeMillis() < until) Thread.sleep(20);
        Thread.sleep(300);   // let any extra (wrong) run land before asserting
    }

    private static long successes(JobService js, String name) {
        return js.runsFor(name).stream().filter(r -> "SUCCESS".equals(r.status())).count();
    }

    private static com.gamma.audit.Event probe(int i) {
        return new com.gamma.signal.Signal(null, SIGNAL, java.time.Instant.now(), com.gamma.signal.Severity.INFO,
                com.gamma.signal.Ref.of("test", "probe"), null, null, null, null, null, "probe",
                Map.of("i", i), 1).toEvent();
    }
}

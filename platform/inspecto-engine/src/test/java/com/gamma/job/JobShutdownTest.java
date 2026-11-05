package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.event.EventLog;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.SignalType;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JOB-SHUTDOWN-FAILED-1 (Basic live check 2026-11-06): on a graceful stop a scheduled {@code la_index_build} started
 * while its Space was closing, called a service the control plane had already stopped, and was recorded
 * {@code FAILED in 0ms - null}, with a CRITICAL signal and a Job-failed alarm. A Run due after the Space starts to
 * close is SKIPPED; a Run that breaks while it closes is CANCELLED. Neither raises a failure.
 */
class JobShutdownTest {

    private record Outcome(JobRun run, List<Signal> signals, List<com.gamma.audit.Event> alarms) {}

    private static JobTypeProvider type(String id, Runnable body) {
        JobTypeDescriptor d = new JobTypeDescriptor(id, id, "test", List.of());
        return JobTypeProvider.of(d, c -> new Job() {
            @Override public String name() { return c.name(); }
            @Override public String type() { return id; }
            @Override public JobResult run() {
                body.run();
                return JobResult.ok("ran", 0L);
            }
        });
    }

    /** Fires {@code name} as the cron does ({@code schedule}, so a failure raises the alarm), with {@code around}
     *  run between the submit and the wait for the result. */
    private static Outcome fire(Path dir, JobTypeProvider type, String name, boolean closeFirst,
                                Consumer<JobService> around) throws Exception {
        List<Signal> signals = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<com.gamma.audit.Event> alarms = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.BiConsumer<EventLog, com.gamma.audit.Event> tap = (l, e) -> {
            if (com.gamma.audit.EventType.JOB_FAILED.equals(e.type())) alarms.add(e);
            if (!com.gamma.audit.EventType.SIGNAL.equals(e.type())) return;
            Signal g = Signal.fromEvent(e);
            if (g.type().startsWith("job.run.") && g.payload() != null && name.equals(g.payload().get("job")))
                signals.add(g);
        };
        EventLog.addTap(tap);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            js.registerJobType(type);
            js.upsertJob(new JobConfig(name, type.descriptor().id(), null, null, true, false, Map.of(), null, null));
            if (closeFirst) js.beginClose();
            js.submit(name, "schedule");
            around.accept(js);
            long until = System.nanoTime() + 10_000_000_000L;
            JobRun r = null;
            while (System.nanoTime() < until) {
                r = js.lastRunOf(name).orElse(null);
                if (r != null && !"RUNNING".equals(r.status())) break;
                Thread.sleep(20);
            }
            Thread.sleep(100);
            return new Outcome(r, List.copyOf(signals), List.copyOf(alarms));
        } finally {
            EventLog.removeTap(tap);
        }
    }

    @Test
    void aRunThatBreaksWhileTheSpaceClosesIsCancelledNotFailed(@TempDir Path dir) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        Outcome o = fire(dir, type("t.closing", () -> {
            entered.countDown();
            try {
                closing.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // what a stopped service does: an exception with no message ("FAILED in 0ms - null")
            throw new java.util.concurrent.RejectedExecutionException();
        }), "breaks", false, js -> {
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS), "the run never started");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            js.beginClose();
            closing.countDown();
        });
        assertEquals("CANCELLED", o.run().status(), o.run().message());
        assertEquals(JobService.SHUTTING_DOWN, o.run().message());
        assertTrue(o.signals().stream().noneMatch(g -> SignalType.JOB_RUN_FAILED.equals(g.type())),
                "a stop must not emit job.run.failed: " + o.signals());
        Signal done = o.signals().stream().filter(g -> SignalType.JOB_RUN_COMPLETED.equals(g.type()))
                .findFirst().orElseThrow(() -> new AssertionError("one terminal signal: " + o.signals()));
        assertEquals(Severity.INFO, done.severity());
        assertTrue(o.alarms().isEmpty(), "no Job-failed alarm on a stop: " + o.alarms());
    }

    @Test
    void aRunDueWhileTheSpaceClosesIsSkippedAndNeverStarts(@TempDir Path dir) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        Outcome o = fire(dir, type("t.due", () -> {
            ran.set(true);
            throw new IllegalStateException();
        }), "due", true, js -> {});
        assertFalse(ran.get(), "a Run due after the Space started to close must not start");
        assertEquals("SKIPPED", o.run().status(), o.run().message());
        assertEquals(JobService.SHUTTING_DOWN, o.run().message());
        assertTrue(o.signals().stream().noneMatch(g -> SignalType.JOB_RUN_FAILED.equals(g.type())), o.signals().toString());
        assertTrue(o.alarms().isEmpty(), "no Job-failed alarm: " + o.alarms());
    }

    @Test
    void aRunThatReportsAFailureWhileTheSpaceClosesIsCancelledToo(@TempDir Path dir) throws Exception {
        // la.index.build does not throw when the index service it calls is gone: it RETURNS a FAILED result
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        JobTypeDescriptor d = new JobTypeDescriptor("t.reports", "t.reports", "test", List.of());
        JobTypeProvider p = JobTypeProvider.of(d, c -> new Job() {
            @Override public String name() { return c.name(); }
            @Override public String type() { return "t.reports"; }
            @Override public JobResult run() {
                entered.countDown();
                try {
                    closing.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return JobResult.failed("la.index.build: failed - the control API is not serving the index", 3L);
            }
        });
        Outcome o = fire(dir, p, "reports", false, js -> {
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS), "the run never started");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            js.beginClose();
            closing.countDown();
        });
        assertEquals("CANCELLED", o.run().status(), o.run().message());
        assertEquals(JobService.SHUTTING_DOWN, o.run().message());
        assertTrue(o.alarms().isEmpty(), "no Job-failed alarm on a stop: " + o.alarms());
    }

    @Test
    void withoutAStopTheSameBreakStaysAFailureWithItsAlarm(@TempDir Path dir) throws Exception {
        Outcome o = fire(dir, type("t.fails", () -> {
            throw new java.util.concurrent.RejectedExecutionException();
        }), "fails", false, js -> {});
        assertEquals("FAILED", o.run().status(), o.run().message());
        assertTrue(o.signals().stream().anyMatch(g -> SignalType.JOB_RUN_FAILED.equals(g.type())), o.signals().toString());
        assertFalse(o.alarms().isEmpty(), "the probe must see the alarm when nothing is closing");
    }
}

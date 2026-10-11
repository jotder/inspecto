package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.event.EventLog;
import com.gamma.inspector.CommitFence;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.SignalType;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HA-CANCEL-RESIDUALS-1: a Job Run cooperatively cancelled by a closing host ({@code RunCancelledException}) is
 * recorded {@code CANCELLED} with an INFO {@code job.run.completed} — never {@code FAILED} with a CRITICAL
 * {@code job.run.failed}, so a planned stop does not read as a Job failure. A genuine lease LOSS (the other
 * {@code LeaseLostException}) stays {@code FAILED}.
 */
class JobRunCancelledTest {

    /** A test type whose Run registers a fence hold in its own Space and then hits it. */
    private static JobTypeProvider fenced(String id, CommitFence.State state, boolean cancelled) {
        JobTypeDescriptor d = new JobTypeDescriptor(id, id, "test", List.of());
        return JobTypeProvider.of(d, c -> new Job() {
            @Override public String name() { return c.name(); }
            @Override public String type() { return id; }
            @Override public JobResult run() {
                try (CommitFence.Held h = CommitFence.hold(CommitFence.Scope.JOB, c.name(), () -> state, () -> cancelled)) {
                    CommitFence.check(CommitFence.Scope.JOB, c.name());
                }
                return JobResult.ok("not fenced", 0L);
            }
        });
    }

    private record Outcome(JobRun run, List<Signal> signals) {}

    private static Outcome runOnce(Path dir, JobTypeProvider type, String name) throws Exception {
        List<Signal> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.BiConsumer<EventLog, com.gamma.audit.Event> tap = (l, e) -> {
            if (!com.gamma.audit.EventType.SIGNAL.equals(e.type())) return;
            Signal g = Signal.fromEvent(e);
            if (g.type().startsWith("job.run.") && g.payload() != null && name.equals(g.payload().get("job")))
                seen.add(g);
        };
        EventLog.addTap(tap);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            js.registerJobType(type);
            js.upsertJob(new JobConfig(name, type.descriptor().id(), null, null, true, false, Map.of(), null, null));
            String runId = js.triggerRun(name, "test").orElseThrow();
            long until = System.nanoTime() + 10_000_000_000L;
            JobRun r = js.runById(runId).orElseThrow();
            while ("RUNNING".equals(r.status()) && System.nanoTime() < until) {
                Thread.sleep(20);
                r = js.runById(runId).orElseThrow();
            }
            return new Outcome(r, seen.stream().filter(g -> !SignalType.JOB_RUN_STARTED.equals(g.type())).toList());
        } finally {
            EventLog.removeTap(tap);
        }
    }

    @Test
    void aGracefulStopCancelIsRecordedCancelledWithAnInfoSignalNotAFailure(@TempDir Path dir) throws Exception {
        Outcome o = runOnce(dir, fenced("t.cancel", CommitFence.State.HELD, true), "cancelme");
        assertEquals("CANCELLED", o.run().status(), o.run().message());
        assertTrue(o.signals().stream().noneMatch(g -> SignalType.JOB_RUN_FAILED.equals(g.type())),
                "a planned stop must not emit job.run.failed: " + o.signals());
        Signal done = o.signals().stream().filter(g -> SignalType.JOB_RUN_COMPLETED.equals(g.type()))
                .findFirst().orElseThrow(() -> new AssertionError("one terminal signal: " + o.signals()));
        assertEquals(Severity.INFO, done.severity());
        assertEquals("CANCELLED", String.valueOf(done.payload().get("outcome")));
    }

    @Test
    void aGenuineLeaseLossStaysFailedAndCritical(@TempDir Path dir) throws Exception {
        Outcome o = runOnce(dir, fenced("t.lost", CommitFence.State.LOST, false), "lostme");
        assertEquals("FAILED", o.run().status(), o.run().message());
        Signal failed = o.signals().stream().filter(g -> SignalType.JOB_RUN_FAILED.equals(g.type()))
                .findFirst().orElseThrow(() -> new AssertionError("job.run.failed expected: " + o.signals()));
        assertEquals(Severity.CRITICAL, failed.severity());
    }
}

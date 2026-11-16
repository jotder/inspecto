package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-P4-3: switching a module off in a Space stops its Runs already in flight. The gate the scheduler reads
 * at each fire ({@link JobService#jobTypeGate}, {@link JobService#jobTaskGate}) is read again for every executing Run by
 * {@link JobService#cancelSwitchedOffRuns}: a Run the gate now turns away is interrupted and recorded CANCELLED with
 * "module switched off" and the gate's sentence; a Run of another module keeps running; once the module is back on its
 * Jobs run again.
 */
class JobModuleStopTest {

    /** Job Types whose module is switched off now, and the gate over them. */
    private final Set<String> off = ConcurrentHashMap.newKeySet();
    private final Function<String, String> gate = type -> off.contains(type)
            ? "the 'mod-" + type + "' module is switched off in this Space; its Job Type '" + type + "' does not run" : null;

    /** A Job Type whose body counts down {@code entered} and then waits for {@code release} (interruptibly). */
    private static JobTypeProvider waiting(String id, CountDownLatch entered, CountDownLatch release) {
        JobTypeDescriptor d = new JobTypeDescriptor(id, id, "test", List.of());
        return JobTypeProvider.of(d, c -> new Job() {
            @Override public String name() { return c.name(); }
            @Override public String type() { return id; }
            @Override public JobResult run() throws Exception {
                entered.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) return JobResult.failed("never released", 0L);
                return JobResult.ok("released", 0L);
            }
        });
    }

    private static JobConfig job(String name, String type) {
        return new JobConfig(name, type, null, null, true, false, Map.of(), null, null);
    }

    private static JobRun awaitDone(JobService js, String runId) throws InterruptedException {
        long until = System.nanoTime() + 20_000_000_000L;
        JobRun r = null;
        while (System.nanoTime() < until) {
            r = js.runById(runId).orElse(null);
            if (r != null && !"RUNNING".equals(r.status())) return r;
            Thread.sleep(20);
        }
        return r;
    }

    @Test
    void switchingAModuleOffCancelsItsRunAndLeavesAnotherModulesRunAlone(@TempDir Path dir) throws Exception {
        CountDownLatch aIn = new CountDownLatch(1), bIn = new CountDownLatch(1), never = new CountDownLatch(1),
                bRelease = new CountDownLatch(1);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null, dir.resolve("audit").toString())) {
            js.registerJobType(waiting("t.a", aIn, never));
            js.registerJobType(waiting("t.b", bIn, bRelease));
            js.upsertJob(job("ja", "t.a"));
            js.upsertJob(job("jb", "t.b"));
            js.jobTypeGate(gate);
            String a = js.triggerRun("ja", "test").orElseThrow();
            String b = js.triggerRun("jb", "test").orElseThrow();
            assertTrue(aIn.await(10, TimeUnit.SECONDS) && bIn.await(10, TimeUnit.SECONDS), "precondition: both Runs in flight");

            assertEquals(List.of(), js.cancelSwitchedOffRuns(), "nothing is switched off yet: nothing is cancelled");
            off.add("t.a");
            assertEquals(List.of(a), js.cancelSwitchedOffRuns(), "only the switched-off module's Run is cancelled");

            JobRun ra = awaitDone(js, a);
            assertEquals("CANCELLED", ra.status(), ra.toString());
            assertTrue(ra.message().startsWith(JobService.MODULE_SWITCHED_OFF + ": ")
                    && ra.message().contains("'mod-t.a' module is switched off"), ra.message());
            assertEquals("RUNNING", js.runById(b).orElseThrow().status(), "another module's Run keeps running");
            assertEquals(List.of(), js.cancelSwitchedOffRuns(), "a cancelled Run is not cancelled twice");

            bRelease.countDown();
            assertEquals("SUCCESS", awaitDone(js, b).status(), "and ends normally");
        }
    }

    @Test
    void switchedBackOnItsJobsRunAgain(@TempDir Path dir) throws Exception {
        CountDownLatch in1 = new CountDownLatch(1), never = new CountDownLatch(1);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null, dir.resolve("audit").toString())) {
            js.registerJobType(waiting("t.a", in1, never));
            js.registerJobType(waiting("t.quick", new CountDownLatch(1), new CountDownLatch(0)));
            js.upsertJob(job("ja", "t.a"));
            js.upsertJob(job("jq", "t.quick"));
            js.jobTypeGate(type -> gate.apply("t.quick".equals(type) ? "t.a" : type));   // both types are module mod-t.a's
            String first = js.triggerRun("ja", "test").orElseThrow();
            assertTrue(in1.await(10, TimeUnit.SECONDS));
            off.add("t.a");
            js.cancelSwitchedOffRuns();
            assertEquals("CANCELLED", awaitDone(js, first).status());

            String whileOff = js.triggerRun("jq", "test").orElseThrow();
            JobRun skipped = awaitDone(js, whileOff);
            assertEquals("SKIPPED", skipped.status(), "while off, a fire is turned away before it starts: " + skipped);

            off.clear();
            String again = js.triggerRun("jq", "test").orElseThrow();
            assertEquals("SUCCESS", awaitDone(js, again).status(), "switched back on, the module's Job runs");
        }
    }

    @Test
    void aMaintenanceTaskOfASwitchedOffModuleIsCancelledToo(@TempDir Path dir) throws Exception {
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null, dir.resolve("audit").toString())) {
            js.upsertJob(new JobConfig("hb", "maintenance", null, null, true, false,
                    Map.of("task", "heartbeat", "sleep_ms", "30000"), null, null));
            js.upsertJob(new JobConfig("nop", "maintenance", null, null, true, false,
                    Map.of("task", "noop", "sleep_ms", "30000"), null, null));
            Set<String> offTasks = ConcurrentHashMap.newKeySet();
            js.jobTaskGate(task -> offTasks.contains(task) ? "the 'mod-x' module is switched off in this Space; its "
                    + "maintenance task '" + task + "' does not run" : null);
            String hb = js.triggerRun("hb", "test").orElseThrow();
            String nop = js.triggerRun("nop", "test").orElseThrow();
            awaitRunning(js, hb);
            awaitRunning(js, nop);

            offTasks.add("heartbeat");
            assertEquals(List.of(hb), js.cancelSwitchedOffRuns());
            JobRun r = awaitDone(js, hb);
            assertEquals("CANCELLED", r.status(), r.toString());
            assertTrue(r.message().contains("maintenance task 'heartbeat'"), r.message());
            assertEquals("RUNNING", js.runById(nop).orElseThrow().status(), "the other task keeps running");
            offTasks.add("noop");
            js.cancelSwitchedOffRuns();   // stop it too, so the close below does not wait out its sleep
            assertEquals("CANCELLED", awaitDone(js, nop).status());
        }
    }

    /** A body that turns the interrupt into an I/O error (a transient failure) is not retried once its module is off. */
    @Test
    void aCancelledRunIsNotRetriedEvenWhenItsFailureLooksTransient(@TempDir Path dir) throws Exception {
        CountDownLatch in = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        JobTypeDescriptor d = new JobTypeDescriptor("t.io", "t.io", "test", List.of());
        JobTypeProvider io = JobTypeProvider.of(d, c -> new Job() {
            @Override public String name() { return c.name(); }
            @Override public String type() { return "t.io"; }
            @Override public JobResult run() throws Exception {
                attempts.incrementAndGet();
                in.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    throw new java.io.IOException("read interrupted");   // the flag is cleared, as an I/O library would
                }
                return JobResult.ok("slept", 0L);
            }
        });
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null, dir.resolve("audit").toString())) {
            js.registerJobType(io);
            js.upsertJob(new JobConfig("jio", "t.io", null, null, true, false,
                    Map.of("retries", "2", "retry_backoff_seconds", "0"), null, null));
            js.jobTypeGate(gate);
            String run = js.triggerRun("jio", "test").orElseThrow();
            assertTrue(in.await(10, TimeUnit.SECONDS));
            off.add("t.io");
            assertEquals(List.of(run), js.cancelSwitchedOffRuns());
            JobRun r = awaitDone(js, run);
            assertEquals("CANCELLED", r.status(), r.toString());
            assertEquals(1, attempts.get(), "the body ran once: a cancelled Run is not retried");
        }
    }

    /** Wait until the Run's body has started (its thread is executing): a short settle after it is listed RUNNING. */
    private static void awaitRunning(JobService js, String runId) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < until) {
            if (js.runById(runId).map(r -> "RUNNING".equals(r.status())).orElse(false)
                    && js.runLog(runId).stream().anyMatch(e -> String.valueOf(e.message()).contains("run started"))) {
                Thread.sleep(200);   // "run started" is logged just before the body: let it reach its sleep
                return;
            }
            Thread.sleep(20);
        }
        fail("run " + runId + " never started");
    }
}

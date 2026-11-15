package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JOB-OUTLIVES-CLOSE-1: a Job Run still writing must stop before its Space's close returns. {@link JobService#close()}
 * drains its Runs, but only cooperatively: a Run that ignores the cancel kept the drain waiting, past the server's
 * close budget, and then kept writing after the server had abandoned it. Two halves: {@link JobService#interruptRuns}
 * stops a Run that ignores the cancel, and {@code sql.template} (which wrote the Dataset in the failing test) honours
 * the cancel before it writes.
 */
class JobCloseStopTest {

    private static JobTypeProvider type(String id, java.util.function.Supplier<JobResult> body) {
        JobTypeDescriptor d = new JobTypeDescriptor(id, id, "test", List.of());
        return JobTypeProvider.of(d, c -> new Job() {
            @Override public String name() { return c.name(); }
            @Override public String type() { return id; }
            @Override public JobResult run() { return body.get(); }
        });
    }

    private static JobRun awaitDone(JobService js, String name) throws InterruptedException {
        long until = System.nanoTime() + 20_000_000_000L;
        JobRun r = null;
        while (System.nanoTime() < until) {
            r = js.lastRunOf(name).orElse(null);
            if (r != null && !"RUNNING".equals(r.status())) return r;
            Thread.sleep(20);
        }
        return r;
    }

    @Test
    void anInterruptStopsARunThatIgnoresTheCancelAndTheDrainThenEnds(@TempDir Path dir) throws Exception {
        Path landing = dir.resolve("data").resolve("landing.tmp");
        Files.createDirectories(landing.getParent());
        CountDownLatch writing = new CountDownLatch(1), stopped = new CountDownLatch(1);
        // a Run landing its output a little at a time, which never checks the cooperative cancel
        JobTypeProvider slow = type("t.slow", () -> {
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
        Scheduler s = new Scheduler();
        JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null, dir.resolve("audit").toString());
        Thread closer = null;
        try {
            js.registerJobType(slow);
            js.upsertJob(new JobConfig("slow", "t.slow", null, null, true, false, Map.of(), null, null));
            js.triggerRun("slow", "test").orElseThrow();
            assertTrue(writing.await(10, TimeUnit.SECONDS), "precondition: the Run is writing");

            js.beginClose();
            closer = new Thread(js::close, "close-slow");
            closer.setDaemon(true);
            closer.start();
            closer.join(300);
            assertTrue(closer.isAlive(), "precondition: the cooperative drain alone waits on a Run that ignores it");

            js.interruptRuns();
            closer.join(10_000);
            assertFalse(closer.isAlive(), "the drain ends once the Run is interrupted");
            assertEquals(0, stopped.getCount(), "the Run stopped before the close returned");
            long size = Files.size(landing);
            Thread.sleep(200);
            assertEquals(size, Files.size(landing), "nothing writes after the close returned");

            JobRun run = js.lastRunOf("slow").orElseThrow();
            assertEquals("CANCELLED", run.status(), "an interrupted Run of a closing Space is a planned stop: " + run.message());
            assertEquals(JobService.SHUTTING_DOWN, run.message());
        } finally {
            if (closer == null) js.close();
            s.close();
        }
    }

    @Test
    void aRunThatKeepsTheInterruptAndReturnsStillWritesItsRunLogReceiptAndSignal(@TempDir Path dir) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        JobTypeDescriptor d = new JobTypeDescriptor("t.keeps", "t.keeps", "test",
                List.of(ParameterDecl.required("p", ParamType.STRING, "a value, so the Run has a parameter receipt")));
        // a body that restores the interrupt it caught and returns normally - the polite Java idiom
        JobTypeProvider keeps = JobTypeProvider.of(d, c -> new Job() {
            @Override public String name() { return c.name(); }
            @Override public String type() { return "t.keeps"; }
            @Override public JobResult run() {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return JobResult.ok("returned", 0L);
            }
        });
        // the terminal signal's emit runs on the Run's thread: was that thread still interrupted then?
        List<Boolean> interruptedAtEmit = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.BiConsumer<com.gamma.event.EventLog, com.gamma.audit.Event> tap = (l, e) -> {
            if (!com.gamma.audit.EventType.SIGNAL.equals(e.type())) return;
            com.gamma.signal.Signal g = com.gamma.signal.Signal.fromEvent(e);
            if (com.gamma.signal.SignalType.JOB_RUN_COMPLETED.equals(g.type()) && g.payload() != null
                    && "keeps".equals(g.payload().get("job")))
                interruptedAtEmit.add(Thread.currentThread().isInterrupted());
        };
        com.gamma.event.EventLog.addTap(tap);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null, dir.resolve("audit").toString())) {
            js.registerJobType(keeps);
            js.upsertJob(new JobConfig("keeps", "t.keeps", null, null, true, false, Map.of("p", "v"), null, null));
            String runId = js.triggerRun("keeps", "test").orElseThrow();
            assertTrue(entered.await(10, TimeUnit.SECONDS), "precondition: the Run is in flight");

            js.interruptRuns();
            JobRun run = awaitDone(js, "keeps");

            assertEquals("SUCCESS", run.status(), "the body returned normally: " + run.message());
            // the run log and the receipt (Files.writeString) survive an interrupt on this JDK; asserted all the same,
            // the signal assertion below is the one the interrupt breaks
            assertTrue(js.runLog(runId).stream().anyMatch(en -> String.valueOf(en.message()).contains("run completed")),
                    "the run log's closing line is written after the body kept the interrupt: " + js.runLog(runId));
            assertTrue(js.runArtifacts(runId).stream().anyMatch(a -> "params".equals(a.kind())),
                    "the parameter receipt is written after the body kept the interrupt: " + js.runArtifacts(runId));
            assertEquals(List.of(false), interruptedAtEmit,
                    "the terminal signal is emitted (to an interruptible journal) with the interrupt spent");
        } finally {
            release.countDown();
            com.gamma.event.EventLog.removeTap(tap);
        }
    }

    @Test
    void aRunWaitingForARunSlotWhenInterruptedIsRecordedSkippedShuttingDown(@TempDir Path dir) throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        AtomicBoolean waiterRan = new AtomicBoolean();
        JobTypeProvider hold = type("t.hold", () -> {
            holding.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted", e);
            }
            return JobResult.ok("held", 0L);
        });
        JobTypeProvider waiter = type("t.waiter", () -> {
            waiterRan.set(true);
            return JobResult.ok("ran", 0L);
        });
        Scheduler s = new Scheduler();
        JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null, dir.resolve("audit").toString());
        try {
            js.setMaxConcurrentRuns(1);
            js.registerJobType(hold);
            js.registerJobType(waiter);
            js.upsertJob(new JobConfig("hold", "t.hold", null, null, true, false, Map.of(), null, null));
            js.upsertJob(new JobConfig("waiter", "t.waiter", null, null, true, false, Map.of(), null, null));
            js.triggerRun("hold", "test").orElseThrow();
            assertTrue(holding.await(10, TimeUnit.SECONDS), "precondition: the one run slot is taken");
            js.triggerRun("waiter", "test").orElseThrow();
            Thread.sleep(200);   // the waiter is parked on the run slot
            assertEquals(0, js.availableRunPermits(), "precondition: no slot is free");

            js.beginClose();
            js.interruptRuns();
            js.close();

            assertFalse(waiterRan.get(), "a Run that never got a slot must not start");
            JobRun w = js.lastRunOf("waiter").orElseThrow(
                    () -> new AssertionError("the Run interrupted while it waited for a slot left no record"));
            assertEquals("SKIPPED", w.status(), "an unstarted Run is recorded, not left RUNNING: " + w.message());
            assertEquals(JobService.SHUTTING_DOWN, w.message());
            assertEquals("CANCELLED", js.lastRunOf("hold").orElseThrow().status());
        } finally {
            s.close();
        }
    }

    @Test
    void aSqlTemplateRunCancelledWhileItsQueryRunsWritesNothingToItsDataset(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        Files.createDirectories(dataDir);
        AtomicBoolean cancel = new AtomicBoolean();
        JobConfig job = new JobConfig("loss", "sql.template", null, null, true, false,
                Map.of("sql", "SELECT 42 AS answer", "sink_dataset", "loss_out"), null, null);
        Runnable saved = SqlTemplateJob.afterPin;
        // the host starts to close while the query runs (after the Run started, before it writes)
        SqlTemplateJob.afterPin = () -> cancel.set(true);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.armingClaims(key -> new RunClaims.Claim() {
                @Override public void close() { }
                @Override public boolean cancelled() { return cancel.get(); }
            });
            js.triggerRun("loss", "test").orElseThrow();
            JobRun run = awaitDone(js, "loss");
            assertEquals("CANCELLED", run.status(), "a cancelled sql.template Run is a planned stop: " + run.message());
            assertEquals(List.of(), parquetIn(dataDir.resolve("loss_out")), "a cancelled Run reveals no snapshot");
            assertTrue(js.latestArtifacts("loss").isEmpty(), "and records no Dataset artifact");
        } finally {
            SqlTemplateJob.afterPin = saved;
        }
    }

    @Test
    void anUncancelledSqlTemplateRunStillWritesItsDataset(@TempDir Path dir) throws Exception {
        // the control for the test above: the same Run, never cancelled, lands its snapshot
        Path dataDir = dir.resolve("data");
        Files.createDirectories(dataDir);
        JobConfig job = new JobConfig("loss", "sql.template", null, null, true, false,
                Map.of("sql", "SELECT 42 AS answer", "sink_dataset", "loss_out"), null, null);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.armingClaims(key -> new RunClaims.Claim() {
                @Override public void close() { }
                @Override public boolean cancelled() { return false; }
            });
            js.triggerRun("loss", "test").orElseThrow();
            JobRun run = awaitDone(js, "loss");
            assertEquals("SUCCESS", run.status(), run.message());
            assertEquals(1, parquetIn(dataDir.resolve("loss_out")).size(), "the snapshot is revealed");
        }
    }

    private static List<Path> parquetIn(Path sink) throws java.io.IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(sink)) return out;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(sink, "*.parquet")) {
            files.forEach(out::add);
        }
        return out;
    }
}

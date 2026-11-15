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

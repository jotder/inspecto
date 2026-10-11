package com.gamma.inspector;

import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HA-GRACEFUL-STOP-LEASE-RELEASE-1, engine side: a run whose host is closing (its fence answers
 * {@code CANCELLED}) stops at the next checkpoint without writing outputs, spending a retry attempt, or
 * quarantining its input — and a run that is never cancelled is unchanged.
 */
class RunCancellationTest {

    private String space;

    @BeforeEach
    void isolateSpace() {
        space = "run-cancel-" + UUID.randomUUID();
        EventLog.register(space, EventLog.create());
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
    }

    @AfterEach
    void restore() {
        org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
        ConsignmentOutputStores.unregister(space);
        EventLog.unregister(space);
    }

    private static PipelineConfig pipelineWithFeed(Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, "").toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("feed.csv"), "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\n");
        return cfg;
    }

    private static long filesUnder(String dir) throws Exception {
        Path root = Path.of(dir);
        if (!Files.exists(root)) return 0;
        try (Stream<Path> s = Files.walk(root)) { return s.filter(Files::isRegularFile).count(); }
    }

    /** (a)+(d): cancelled before the batch starts - nothing written, input re-pickable, nothing quarantined. */
    @Test
    void aCancelledRunWritesNothingAndLeavesItsInputRePickable(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipelineWithFeed(dir);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();

        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, cfg.identity().pipelineName(),
                () -> CommitFence.State.HELD, () -> true)) {
            assertThrows(CommitFence.RunCancelledException.class, () -> CollectorProcessor.run(cfg),
                    "a cancelled run surfaces as cancelled, never as a success");
        }

        assertTrue(feed.exists(), "the input stays in the inbox");
        assertEquals(0, filesUnder(cfg.dirs().database()), "no output was written after the cancel");
        assertEquals(0, filesUnder(cfg.dirs().quarantine()), "a good file is never quarantined for a cancel");
        assertEquals(0, filesUnder(cfg.dirs().manifestsDir()), "no manifest");
        assertEquals(0, filesUnder(cfg.dirs().markers()), "no marker");
        assertEquals(0, filesUnder(cfg.dirs().backup()), "no backup");
        assertNull(CommitRetry.recordFor(feed, cfg), "a cancel spends no X1 attempt");

        CollectorProcessor.run(cfg);   // re-pick: the next (surviving) run ingests it normally
        assertFalse(feed.exists(), "re-picked and committed by the next run");
        assertTrue(filesUnder(cfg.dirs().database()) > 0);
    }

    /** (a): cancel arrives after the pre-ingest checkpoint but before the output write - the write checkpoint stops it. */
    @Test
    void aCancelArrivingMidRunStopsBeforeTheOutputWrite(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipelineWithFeed(dir);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();
        AtomicInteger looks = new AtomicInteger();

        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, cfg.identity().pipelineName(),
                () -> CommitFence.State.HELD, () -> looks.incrementAndGet() > 1)) {
            assertThrows(CommitFence.RunCancelledException.class, () -> CollectorProcessor.run(cfg));
        }

        assertTrue(looks.get() >= 2, "the run reached the write checkpoint before it was cancelled");
        assertEquals(0, filesUnder(cfg.dirs().database()), "no output file landed after the cancel point");
        assertTrue(feed.exists(), "input still in the inbox");
        assertEquals(0, filesUnder(cfg.dirs().quarantine()), "not quarantined");
        assertNull(CommitRetry.recordFor(feed, cfg), "no attempt spent");
    }

    /**
     * HA-CANCEL-RESIDUALS-1: one batch is cancelled while another commits. The cancel used to be swallowed per
     * batch, so the run returned normally — recorded SUCCESS. It must surface once every batch has drained,
     * with the lost-lease posture intact for the cancelled batch and the other batch's commit kept.
     */
    @Test
    void aRunWithOneCancelledBatchAmongCommittedOnesIsNotASuccess(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 1
                max_bytes: 268435456
            """).toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        for (int i = 0; i < 2; i++)
            Files.writeString(inbox.resolve("f" + i + ".csv"), "ID,AMT,EVENT_DATE\nr" + i + ",1.0,2020-04-03\n");
        // Each batch runs on its own worker thread: the first thread to reach a checkpoint is never cancelled,
        // every other one is - deterministic whatever processing.threads is.
        java.util.concurrent.atomic.AtomicReference<Thread> first = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.BooleanSupplier cancel = () -> {
            first.compareAndSet(null, Thread.currentThread());
            return first.get() != Thread.currentThread();
        };
        java.util.List<com.gamma.etl.ConsignmentEvent> commits = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, cfg.identity().pipelineName(),
                () -> CommitFence.State.HELD, cancel)) {
            assertThrows(CommitFence.RunCancelledException.class, () -> CollectorProcessor.run(cfg, commits::add),
                    "a partly cancelled run must not complete as a success");
        }

        assertEquals(1, commits.size(), "the uncancelled batch still committed (drained, not abandoned)");
        assertEquals(1, filesUnder(cfg.dirs().poll()), "the cancelled batch's input stays in the inbox");
        assertEquals(1, filesUnder(cfg.dirs().backup()), "only the committed batch was acked");
        assertEquals(0, filesUnder(cfg.dirs().quarantine()), "a cancel quarantines nothing");
        try (Stream<Path> left = Files.list(inbox)) {
            assertNull(CommitRetry.recordFor(left.findFirst().orElseThrow().toFile(), cfg), "no attempt spent");
        }

        // Through the service-facing entry point the cancel is counted as not completed, not as a success.
        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, cfg.identity().pipelineName(),
                () -> CommitFence.State.HELD, () -> true)) {
            MultiCollectorProcessor.RunResult cancelledRun =
                    MultiCollectorProcessor.runConfigs(java.util.List.of(cfg), 1, null, true);
            assertEquals(1, cancelledRun.cancelled(), "a cancelled source run is counted cancelled");
            assertEquals(0, cancelledRun.failed(), "...not failed");
            assertEquals(0, cancelledRun.succeeded(), "...and not succeeded");
        }
        assertEquals(0, MultiCollectorProcessor.runConfigs(java.util.List.of(cfg), 1, null, true).failed(),
                "no cancel: the survivor-style rerun ingests the leftover normally");
        assertEquals(0, filesUnder(cfg.dirs().poll()));
    }

    /** A cancel that first lands at the commit point (after every checkpoint passed) still surfaces as cancelled. */
    @Test
    void aCancelAtTheCommitPointSurfacesAsCancelled(@TempDir Path dir) throws Exception {
        // Count the fence's looks over an uncancelled run, then cancel exactly on the last one: the commit check.
        PipelineConfig probe = pipelineWithFeed(Files.createDirectories(dir.resolve("probe")));
        AtomicInteger total = new AtomicInteger();
        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, probe.identity().pipelineName(),
                () -> CommitFence.State.HELD, () -> { total.incrementAndGet(); return false; })) {
            CollectorProcessor.run(probe);
        }
        PipelineConfig cfg = pipelineWithFeed(Files.createDirectories(dir.resolve("real")));
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();
        AtomicInteger looks = new AtomicInteger();
        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, cfg.identity().pipelineName(),
                () -> CommitFence.State.HELD, () -> looks.incrementAndGet() >= total.get())) {
            assertThrows(CommitFence.RunCancelledException.class, () -> CollectorProcessor.run(cfg));
        }
        assertTrue(feed.exists(), "not committed: the input stays in the inbox");
        assertEquals(0, filesUnder(cfg.dirs().quarantine()));
        assertNull(CommitRetry.recordFor(feed, cfg), "no attempt spent");
    }

    /** (c): a fence that is never cancelled changes nothing. */
    @Test
    void aRunThatIsNotCancelledCompletesNormally(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipelineWithFeed(dir);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();

        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, cfg.identity().pipelineName(),
                () -> CommitFence.State.HELD)) {
            CollectorProcessor.run(cfg);
        }

        assertFalse(feed.exists(), "committed: the original moved to backup");
        assertTrue(filesUnder(cfg.dirs().database()) > 0, "outputs written");
        assertEquals(1, filesUnder(cfg.dirs().backup()));
    }

    /** The commit point also refuses a cancel (check() consults the same state) - the race after the last checkpoint. */
    @Test
    void aCancelBetweenTheLastCheckpointAndTheCommitIsRefusedAtTheCommitPoint() {
        String p = "p-" + UUID.randomUUID();
        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, p, () -> CommitFence.State.HELD, () -> true)) {
            assertThrows(CommitFence.RunCancelledException.class, () -> CommitFence.check(CommitFence.Scope.RUN, p));
            assertThrows(CommitFence.RunCancelledException.class, () -> CommitFence.checkpoint(CommitFence.Scope.RUN, p));
        }
        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, p, () -> CommitFence.State.LOST)) {
            assertDoesNotThrow(() -> CommitFence.checkpoint(CommitFence.Scope.RUN, p),
                    "a lost lease is a commit-point verdict, not a checkpoint's");
        }
    }
}

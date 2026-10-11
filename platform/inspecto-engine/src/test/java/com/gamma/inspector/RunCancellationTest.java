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
            CollectorProcessor.run(cfg);
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
            CollectorProcessor.run(cfg);
        }

        assertTrue(looks.get() >= 2, "the run reached the write checkpoint before it was cancelled");
        assertEquals(0, filesUnder(cfg.dirs().database()), "no output file landed after the cancel point");
        assertTrue(feed.exists(), "input still in the inbox");
        assertEquals(0, filesUnder(cfg.dirs().quarantine()), "not quarantined");
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

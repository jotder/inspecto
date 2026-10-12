package com.gamma.inspector;

import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INGEST-COMMIT-LEASE-LOST-OUTCOME-1: a genuine lease loss (the fence answers LOST, not CANCELLED) at the commit
 * point used to be absorbed into a FAILED batch and the run returned normally, i.e. was reported a success. It
 * must propagate and be counted failed - with no retry spent, no quarantine and the input left for the new holder.
 */
class CommitLeaseLostOutcomeTest {

    private String space;

    @BeforeEach
    void isolateSpace() {
        space = "lease-lost-" + UUID.randomUUID();
        EventLog.register(space, EventLog.create());
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
    }

    @AfterEach
    void restore() {
        org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
        ConsignmentOutputStores.unregister(space);
        EventLog.unregister(space);
    }

    @Test
    void aLeaseLostAtTheCommitPointIsNotASuccessAndLeavesTheInputForTheNewHolder(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, "").toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("feed.csv"), "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\n");
        File feed = inbox.resolve("feed.csv").toFile();
        List<ConsignmentEvent> events = new CopyOnWriteArrayList<>();

        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, cfg.identity().pipelineName(),
                () -> CommitFence.State.LOST)) {
            // Through the service-facing entry point: the source is counted failed, never succeeded.
            MultiCollectorProcessor.RunResult r =
                    MultiCollectorProcessor.runConfigs(List.of(cfg), 1, events::add, true);
            assertEquals(1, r.failed(), "a lost lease is counted failed");
            assertEquals(0, r.succeeded(), "...and never as a success");
            assertEquals(0, r.cancelled(), "...and is not a graceful-stop cancel");
        }

        assertTrue(feed.exists(), "the input stays in the inbox for the new holder");
        assertNull(CommitRetry.recordFor(feed, cfg), "a lease loss spends no X1 attempt");
        assertEquals(0, files(cfg.dirs().quarantine()), "nothing quarantined");
        assertEquals(0, files(cfg.dirs().backup()), "nothing acked");
        assertEquals(0, files(cfg.dirs().markers()), "no marker");
        assertFalse(events.isEmpty(), "the batch row was still audited and announced");
        assertTrue(events.stream().allMatch(e -> "FAILED".equals(e.status())),
                "FAILED (the BATCH_FAILED bridge), never SUCCESS (BATCH_COMMITTED): " + events);
        assertTrue(events.get(0).error().startsWith("lease lost"), events.get(0).error());

        // The direct entry point does not return normally either.
        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, cfg.identity().pipelineName(),
                () -> CommitFence.State.LOST)) {
            assertThrows(CollectorProcessor.ConsignmentProcessingException.class, () -> CollectorProcessor.run(cfg));
        }
        assertTrue(feed.exists());

        CollectorProcessor.run(cfg);   // the new holder (lease intact) ingests it normally
        assertFalse(feed.exists());
    }

    private static long files(String dir) throws Exception {
        Path root = Path.of(dir);
        if (!Files.exists(root)) return 0;
        try (var s = Files.walk(root)) { return s.filter(Files::isRegularFile).count(); }
    }
}

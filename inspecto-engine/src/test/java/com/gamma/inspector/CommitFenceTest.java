package com.gamma.inspector;

import com.gamma.etl.Consignment;
import com.gamma.etl.ManifestStore;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LEASE-TAKEOVER-INFLIGHT-1 at the engine's commit point: a Consignment whose run lease was taken over
 * mid-run must not commit — no manifest, no backup — and its file stays in the inbox for the new holder.
 */
class CommitFenceTest {

    @Test
    void aLostLeaseRefusesTheCommitAndLeavesTheFileInTheInbox(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, "").toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Path file = Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        List<Consignment.Member> survivors = List.of(new Consignment.Member(file.toFile(), 0, Files.size(file),
                new SchemaSelector.Selection(cfg.schemas().single(), null)));
        Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_mini_0001", "mini", null, survivors);
        String pipeline = cfg.identity().pipelineName();

        AtomicBoolean held = new AtomicBoolean(true);
        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, pipeline, held::get)) {
            held.set(false);                                   // another node takes the lease over mid-run
            CommitFence.LeaseLostException e = assertThrows(CommitFence.LeaseLostException.class,
                    () -> ConsignmentIngestor.finalizeSource(batch, cfg, survivors, List.of(), List.of()));
            assertTrue(e.getMessage().contains("lease lost"), e.getMessage());
            assertTrue(Files.exists(file), "the file stays in the inbox for the new holder");
            assertFalse(Files.exists(Path.of(cfg.dirs().manifestsDir(), batch.batchId() + ".json")),
                    "nothing was committed");

            // An acquisition lease is a different lease: its loss must not refuse a run's commit.
            try (CommitFence.Held other = CommitFence.hold(CommitFence.Scope.ACQUIRE, pipeline, () -> true)) {
                assertThrows(CommitFence.LeaseLostException.class,
                        () -> CommitFence.check(CommitFence.Scope.RUN, pipeline));
                assertDoesNotThrow(() -> CommitFence.check(CommitFence.Scope.ACQUIRE, pipeline));
            }
        }

        // No registration (the fence closed): nothing to lose, the commit proceeds.
        ConsignmentIngestor.finalizeSource(batch, cfg, survivors, List.of(), List.of());
        assertNotNull(ManifestStore.read(cfg.dirs().manifestsDir(), batch.batchId()));
        assertFalse(Files.exists(file), "committed: the original moved to backup");
    }
}

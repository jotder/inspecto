package com.gamma.inspector;

import com.gamma.acquire.IntakeGovernor;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T15 — the per-cycle admission cap (pipeline-graph §3.5): one cycle admits at most
 * {@code -Dingest.maxFilesPerCycle} files and the rest wait in the durable inbox for the next cycle.
 */
class CollectorProcessorAdmissionCapTest {

    @AfterEach
    void restoreSystemPolicy() {
        IntakeGovernor.use(null);
    }

    private static PipelineConfig configWithInbox(Path dir, int files) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
                max_bytes: 268435456
            """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        for (int i = 0; i < files; i++)
            Files.writeString(inbox.resolve("f" + i + ".csv"),
                    "ID,AMT,EVENT_DATE\nr" + i + ",1.0,2020-04-03\n");
        return cfg;
    }

    @Test
    void aCappedCycleAdmitsOnlyTheCapAndLeavesTheRestPending(@TempDir Path dir) throws Exception {
        IntakeGovernor.use(new IntakeGovernor(new IntakeGovernor.Policy(2, 1, true)));
        PipelineConfig cfg = configWithInbox(dir, 6);

        // The read-only pending scan must report the TRUE backlog — the cap bounds admission, not observability.
        assertEquals(6, CollectorProcessor.countPending(cfg), "cap must not distort the pending signal");

        CollectorProcessor.run(cfg);
        assertEquals(4, CollectorProcessor.countPending(cfg), "cap 2 of 6 admitted ⇒ 4 still waiting");

        CollectorProcessor.run(cfg);
        assertEquals(2, CollectorProcessor.countPending(cfg), "the next cycle drains the next 2");

        CollectorProcessor.run(cfg);
        assertEquals(0, CollectorProcessor.countPending(cfg), "a bounded inbox still drains fully");
    }

    @Test
    void anUncappedCycleAdmitsEverythingAsBefore(@TempDir Path dir) throws Exception {
        IntakeGovernor.use(new IntakeGovernor(new IntakeGovernor.Policy(0, 1, true)));   // the default
        PipelineConfig cfg = configWithInbox(dir, 6);

        CollectorProcessor.run(cfg);
        assertEquals(0, CollectorProcessor.countPending(cfg), "no cap ⇒ pre-T15 behaviour, all 6 in one cycle");
    }

    @Test
    void admissionIsOldestFirstSoTheMostBehindFilesAreNotStarved(@TempDir Path dir) throws Exception {
        IntakeGovernor.use(new IntakeGovernor(new IntakeGovernor.Policy(1, 1, true)));
        PipelineConfig cfg = configWithInbox(dir, 3);

        Path inbox = Path.of(cfg.dirs().poll());
        Path oldest = inbox.resolve("f2.csv");   // deliberately NOT first in name order
        Files.setLastModifiedTime(oldest,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 600_000));

        CollectorProcessor.run(cfg);
        assertFalse(Files.exists(oldest), "the oldest waiting file must be the one admitted");
        assertEquals(2, CollectorProcessor.countPending(cfg), "the two newer files still wait");
    }
}

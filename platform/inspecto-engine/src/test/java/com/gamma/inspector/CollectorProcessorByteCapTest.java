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
 * The pre-materialise <b>byte</b> cap, driven through the real processor (operator 2026-09-16).
 *
 * <p>⚠ The unit was the call: fetch bandwidth is the scarce resource and a file COUNT cannot bound it —
 * one very large file blows straight through a count cap. The remainder DEFERS to the next cycle, exactly
 * as the file cap's remainder does.
 */
class CollectorProcessorByteCapTest {

    @AfterEach
    void restoreSystemPolicy() {
        IntakeGovernor.use(null);
    }

    /** Each file is ~{@code bytes} long, and they age oldest-first so admission order is deterministic. */
    private static PipelineConfig configWithSizedInbox(Path dir, int files, int bytes) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
                max_bytes: 268435456
            """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        for (int i = 0; i < files; i++) {
            StringBuilder sb = new StringBuilder("ID,AMT,EVENT_DATE\n");
            while (sb.length() < bytes) sb.append("r").append(i).append(",1.0,2020-04-03\n");
            Path f = inbox.resolve("f" + i + ".csv");
            Files.writeString(f, sb.toString());
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(1_000_000L + i * 1000L));
        }
        return cfg;
    }

    @Test
    void aByteCappedCycleAdmitsWhatFitsAndDefersTheRest(@TempDir Path dir) throws Exception {
        // ~600 bytes each; a 1500-byte cap fits two of them.
        IntakeGovernor.use(new IntakeGovernor(new IntakeGovernor.Policy(0, 1, true, 1500)));
        PipelineConfig cfg = configWithSizedInbox(dir, 5, 600);

        assertEquals(5, CollectorProcessor.countPending(cfg), "the cap bounds admission, not observability");

        CollectorProcessor.run(cfg);
        int afterFirst = CollectorProcessor.countPending(cfg);
        assertTrue(afterFirst > 0 && afterFirst < 5,
                "a byte-capped cycle admits SOME and defers the rest, leaving " + afterFirst);

        // …and the deferred remainder is not stranded: repeated cycles drain the inbox.
        for (int i = 0; i < 6 && CollectorProcessor.countPending(cfg) > 0; i++) CollectorProcessor.run(cfg);
        assertEquals(0, CollectorProcessor.countPending(cfg), "a byte-bounded inbox still drains fully");
    }

    /**
     * ⛔ THE STARVATION GUARD. A file larger than the cap fits in no cycle, ever. Without this, it would sit
     * in the inbox permanently while every smaller file behind it overtook it — a silent stall that looks
     * like a working cap. It is admitted ALONE instead, and the overrun is logged.
     */
    @Test
    void aFileLargerThanTheCapIsAdmittedAloneRatherThanStarvedForever(@TempDir Path dir) throws Exception {
        IntakeGovernor.use(new IntakeGovernor(new IntakeGovernor.Policy(0, 1, true, 100)));
        PipelineConfig cfg = configWithSizedInbox(dir, 1, 4000);   // one file, far over the cap

        assertEquals(1, CollectorProcessor.countPending(cfg));

        CollectorProcessor.run(cfg);

        assertEquals(0, CollectorProcessor.countPending(cfg),
                "the over-cap file must be processed, not deferred forever — a cap that can stall a "
                        + "pipeline permanently is worse than one occasionally exceeded by a single file");
    }

    /** With no byte cap the path is byte-for-byte the old one: everything admitted in one cycle. */
    @Test
    void noByteCapAdmitsEverything(@TempDir Path dir) throws Exception {
        IntakeGovernor.use(new IntakeGovernor(new IntakeGovernor.Policy(0, 1, true)));
        PipelineConfig cfg = configWithSizedInbox(dir, 4, 600);

        CollectorProcessor.run(cfg);

        assertEquals(0, CollectorProcessor.countPending(cfg), "admission control stays opt-in");
    }
}

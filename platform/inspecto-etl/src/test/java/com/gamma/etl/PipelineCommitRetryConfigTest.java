package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-pipeline {@code processing.retry} block (X1 deferral, 2026-09-25) — the bounded COMMIT retry's cap
 * and backoff. Through a real TOON file, like {@link PipelineIntakeConfigTest}, and with the same load-bearing
 * property: <b>absent ≠ stated</b>. An absent block or key stays {@code null} so {@code CommitRetry} inherits
 * the live {@code -Dingest.retry.*} global — which is today's behaviour, byte for byte.
 */
class PipelineCommitRetryConfigTest {

    @Test
    void absentBlockMeansInheritTheGlobalsWhole(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        assertNull(cfg.commitRetry(), "no processing.retry ⇒ null ⇒ the -D globals apply untouched");
    }

    @Test
    void fullBlockParsesEveryFieldWithTheCollectorDurationGrammar(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, """
              retry:
                max_attempts: 3
                initial_backoff: 30s
                max_backoff: 2h
            """).toString());
        assertEquals(3, cfg.commitRetry().maxAttempts());
        assertEquals(30_000L, cfg.commitRetry().initialBackoffMs());
        assertEquals(7_200_000L, cfg.commitRetry().maxBackoffMs());
    }

    @Test
    void unstatedFieldsStayNullAndZeroIsAStatement(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, """
              retry:
                max_attempts: 0
            """).toString());
        assertEquals(0, cfg.commitRetry().maxAttempts(), "a stated 0 = unbounded for this pipeline");
        assertNull(cfg.commitRetry().initialBackoffMs());
        assertNull(cfg.commitRetry().maxBackoffMs());
    }

    @Test
    void garbageAndNegativesAreNamedLoadErrors(@TempDir Path dir) throws Exception {
        for (String section : new String[] {
                "  retry:\n    max_attempts: lots\n",
                "  retry:\n    max_attempts: -1\n",
                "  retry:\n    initial_backoff: -5s\n",
                "  retry:\n    max_backoff: soon\n"}) {
            Path p = PipelineConfigBatchTest.writePipeline(dir, section);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> PipelineConfig.load(p.toString()), section);
            assertTrue(e.getMessage().contains("processing.retry."), section + " → " + e.getMessage());
        }
    }
}

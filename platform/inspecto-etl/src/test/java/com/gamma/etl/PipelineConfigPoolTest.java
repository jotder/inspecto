package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code processing.pool} — the named execution pool a Pipeline chooses
 * ({@code DUCKLE-C10-ADMISSION-POOLS-1}): absent is {@code null} (the broker's {@code default}), a
 * stated name parses verbatim, and a name the server may not define is NOT refused here — resolving it
 * to {@code default} is the broker's job at admission.
 */
public class PipelineConfigPoolTest {

    @Test
    void absentPoolIsNull(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        assertNull(cfg.processing().pool());
    }

    @Test
    void aStatedPoolParses(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                PipelineConfigBatchTest.writePipeline(dir, "  pool: heavy_etl").toString());
        assertEquals("heavy_etl", cfg.processing().pool());
    }
}

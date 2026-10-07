package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code processing.priority} — the ConcurrencyBroker share weight (scheduler-system-config plan
 * Part B): defaults to 1 when absent, parses when stated, and refuses out-of-range values at parse
 * time rather than silently clamping (the G3 posture, same as {@code processing.batch.order}).
 */
public class PipelineConfigPriorityTest {

    @Test
    void defaultsToOneWhenAbsent(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                PipelineConfigBatchTest.writePipeline(dir, "").toString());
        assertEquals(1, cfg.processing().priority());
    }

    @Test
    void parsesAStatedPriority(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                PipelineConfigBatchTest.writePipeline(dir, "  priority: 3").toString());
        assertEquals(3, cfg.processing().priority());
    }

    @Test
    void refusesOutOfRangeAtParseTime(@TempDir Path dir) {
        Exception e = assertThrows(Exception.class, () -> PipelineConfig.load(
                PipelineConfigBatchTest.writePipeline(dir, "  priority: 4").toString()));
        assertTrue(mentionsPriority(e), "refusal must name the offending key: " + e);
        Exception zero = assertThrows(Exception.class, () -> PipelineConfig.load(
                PipelineConfigBatchTest.writePipeline(dir, "  priority: 0").toString()));
        assertTrue(mentionsPriority(zero), "refusal must name the offending key: " + zero);
    }

    private static boolean mentionsPriority(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause())
            if (String.valueOf(c.getMessage()).contains("priority")) return true;
        return false;
    }
}

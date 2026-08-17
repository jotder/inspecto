package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema-less draft parsing (v5.1.0, the stream-onboarding draft lifecycle): a minimal config
 * (name + dirs + processing, no schema yet) must parse while {@code active: false} — it is
 * indexed and catalog-visible but never executed — and arming it without any schema source is
 * rejected at parse with a clear error (formerly an NPE in the legacy single-schema branch).
 */
class PipelineConfigDraftTest {

    private static Map<String, Object> minimal(boolean active) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "DRAFT");
        if (active) m.put("active", true);
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        return m;
    }

    @Test
    void schemaLessDraftParses() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(minimal(false));
        assertFalse(cfg.active());
        assertNull(cfg.schemas().single(), "no schema chosen yet");
        assertNull(cfg.schemas().selector());
        assertNull(cfg.schemas().segments());
        assertEquals("draft", cfg.identity().pipelineName());
    }

    /**
     * {@code description:} is display-only, but it must survive the parse so the list route can project it
     * as the row subtitle. Before 2026-08-17 it was an unmodelled passthrough: the create dialog collected
     * it and no surface could ever show it back.
     */
    @Test
    void descriptionIsParsedAndTrimmed() throws Exception {
        Map<String, Object> m = minimal(false);
        m.put("description", "  Nightly orders feed  ");
        assertEquals("Nightly orders feed", PipelineConfig.fromMap(m).description());
        assertEquals("", PipelineConfig.fromMap(minimal(false)).description(), "absent ⇒ empty, never null");
    }

    @Test
    void armingWithoutASchemaIsRejectedClearly() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(minimal(true)));
        assertTrue(ex.getMessage().contains("no schema is configured"), ex.getMessage());
    }
}

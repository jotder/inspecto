package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The flat home of the {@code sink.webhook} node — the top-level {@code webhook:} block. The target is a
 * NAMED Connection (operator decision 2026-09-23: egress rides the admin-only Connection surface, never an
 * authored URL), so the block carries no URL and no secret; {@code batch_size} and {@code retry:} are its
 * only other keys, and everything else is refused by name rather than defaulted.
 */
class PipelineConfigWebhookTest {

    private static Map<String, Object> base(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "WEBHOOK_ETL");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", new LinkedHashMap<String, Object>(Map.of("threads", 1)));
        m.putAll(extra);
        return m;
    }

    @TempDir Path dir;

    /** An ACTIVE config needs a real schema file before prepare() is even reached. */
    private Map<String, Object> active(Map<String, Object> extra) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        Map<String, Object> m = base(extra);
        m.put("active", true);
        m.put("processing", new LinkedHashMap<String, Object>(Map.of("threads", 1,
                "schema_file", schema.toString().replace('\\', '/'))));
        return m;
    }

    @Test
    void parsesTheBlockAndKeepsItVerbatim() throws Exception {
        Map<String, Object> block = Map.of("connection", "orders_hook", "batch_size", 250,
                "retry", Map.of("count", 3, "backoff", "fixed", "initial_delay", "2s", "max_delay", "10s"));
        PipelineConfig cfg = PipelineConfig.fromMap(base(Map.of("webhook", block)));

        assertNotNull(cfg.webhook());
        assertEquals("orders_hook", cfg.webhook().connection());
        assertEquals(250, cfg.webhook().batchSize());
        assertEquals(3, cfg.webhook().retry().count());
        assertEquals("FIXED", cfg.webhook().retry().backoff());
        assertEquals(2_000L, cfg.webhook().retry().initialDelayMillis());
        assertEquals(10_000L, cfg.webhook().retry().maxDelayMillis());
        assertEquals(block, cfg.webhookConfig(), "the raw block rides verbatim so lift/lower round-trips it");
    }

    @Test
    void defaultsBatchSizeAndSendsOnceWithoutARetryBlock() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(base(Map.of("webhook", Map.of("connection", "h"))));
        assertEquals(PipelineConfig.Webhook.DEFAULT_BATCH_SIZE, cfg.webhook().batchSize());
        assertFalse(cfg.webhook().retry().enabled(), "no retry: block ⇒ exactly one attempt");
    }

    @Test
    void absentBlockIsNull() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(base(Map.of()));
        assertNull(cfg.webhook());
        assertNull(cfg.webhookConfig());
    }

    @Test
    void refusesAMissingConnection() {
        assertTrue(refused(Map.of("batch_size", 10)).contains("connection"));
    }

    /** An authored URL is exactly the egress path the Connection decision closed — refused by name. */
    @Test
    void refusesAnAuthoredUrlOrAnyUnknownKey() {
        String msg = refused(Map.of("connection", "h", "url", "https://evil.example/x"));
        assertTrue(msg.contains("url"), msg);
        assertTrue(msg.contains("Connection"), "the refusal points at where a target belongs: " + msg);
        assertTrue(refused(Map.of("connection", "h", "batchsize", 5)).contains("batchsize"));
        assertTrue(refused(Map.of("connection", "h", "retry", Map.of("attempts", 2))).contains("attempts"));
    }

    @Test
    void refusesABatchSizeOutOfBounds() {
        assertTrue(refused(Map.of("connection", "h", "batch_size", 0)).contains("batch_size"));
        assertTrue(refused(Map.of("connection", "h", "batch_size", 10_001)).contains("batch_size"));
        assertTrue(refused(Map.of("connection", "h", "batch_size", "lots")).contains("batch_size"));
    }

    // ── arming: at-rest lane only ────────────────────────────────────────────

    /** The ingest lane never executes the webhook; an active pipeline without output_store: refuses by name. */
    @Test
    void anActiveWebhookWithoutOutputStoreRefusesToArm() throws Exception {
        Map<String, Object> m = active(Map.of("webhook", Map.of("connection", "h")));
        PipelineConfig cfg = PipelineConfig.fromMap(m);
        IllegalStateException e = assertThrows(IllegalStateException.class, cfg::prepare);
        assertTrue(e.getMessage().contains("webhook") && e.getMessage().contains("ingest"), e.getMessage());
        assertTrue(e.getMessage().contains("output_store"), e.getMessage());
    }

    @Test
    void anActiveWebhookWithOutputStoreArms() throws Exception {
        Map<String, Object> m = active(Map.of("webhook", Map.of("connection", "h"), "output_store", "orders_out"));
        PipelineConfig.fromMap(m).prepare();
    }

    /** A route: pipeline runs on the ingest lane, where the webhook has no executor — refuse, never skip. */
    @Test
    void anActiveWebhookBesideARouteRefuses() throws Exception {
        Map<String, Object> m = active(Map.of("webhook", Map.of("connection", "h"), "output_store", "o",
                "route", Map.of("branches", List.of(Map.of("key", "a", "where", "true", "database", "out"))),
                "sinks", List.of(Map.of("database", "out"))));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PipelineConfig.fromMap(m).prepare());
        assertTrue(e.getMessage().contains("webhook"), e.getMessage());
    }

    /** An inactive draft may carry the block with nothing else — authoring must not be blocked. */
    @Test
    void anInactiveDraftLoads() throws Exception {
        PipelineConfig.fromMap(base(Map.of("webhook", Map.of("connection", "h")))).prepare();
    }

    private static String refused(Map<String, Object> block) {
        Exception e = assertThrows(Exception.class, () -> PipelineConfig.fromMap(base(Map.of("webhook", block))));
        return e.getMessage();
    }
}

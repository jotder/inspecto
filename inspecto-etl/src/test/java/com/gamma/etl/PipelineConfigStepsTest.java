package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan slice A2 — the flat config gains an ordered {@code steps:} chain, so the file can finally hold more
 * than one transform of a kind and say what order they run in.
 *
 * <p>⚠ <b>The projection is the dangerous half, not the new reader.</b> Every pre-existing config reaches
 * {@link PipelineConfig#steps()} through the legacy-block projection, so an order that disagrees with
 * {@code PipelineLift} by one position silently reorders someone's pipeline on its next save. That order is
 * cross-checked against the lift itself in {@code PipelineStepsProjectionTest} (inspecto-engine, the module
 * that can see both); this class covers parsing, refusals, and the projection's shape.
 */
class PipelineConfigStepsTest {

    /** A minimal config map; {@code extra} keys are merged at the top level. */
    private static Map<String, Object> base(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "STEPS_ETL");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", new LinkedHashMap<String, Object>(Map.of("threads", 1)));
        m.putAll(extra);
        return m;
    }

    private static List<String> kinds(PipelineConfig cfg) {
        return cfg.steps().stream().map(PipelineConfig.Step::kind).toList();
    }

    // ── the new spelling ─────────────────────────────────────────────────────────

    /** The point of the whole slice: two of a kind, interleaved, in the order authored. */
    @Test
    void anExplicitChainKeepsItsOrderIncludingRepeats() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(base(Map.of("steps", List.of(
                Map.of("dedup", Map.of("keys", List.of("msisdn"))),
                Map.of("summarize", Map.of("group_by", List.of("day"), "measures", List.of("count"))),
                Map.of("dedup", Map.of("keys", List.of("imsi")))))));

        assertEquals(List.of("dedup", "summarize", "dedup"), kinds(cfg),
                "list position IS the order — this is what per-kind blocks could never express");
        assertEquals(List.of("msisdn"), cfg.steps().get(0).config().get("keys"));
        assertEquals(List.of("imsi"), cfg.steps().get(2).config().get("keys"),
                "the second dedup keeps its own config rather than being merged into the first");
    }

    @Test
    void aStepConfigIsCarriedVerbatim() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(base(Map.of("steps", List.of(
                Map.of("dedup", Map.of("keys", List.of("a", "b"), "order_by", "ts desc"))))));

        assertEquals(Map.of("keys", List.of("a", "b"), "order_by", "ts desc"),
                cfg.steps().get(0).config());
    }

    // ── refusals: a malformed chain is structural, never silently dropped ────────

    @Test
    void refusesAnEntryThatIsNotASingleKindToConfigMap() {
        assertTrue(refused(base(Map.of("steps", List.of("dedup")))).getMessage()
                .contains("single-key map"), "a bare string names no config");
        assertTrue(refused(base(Map.of("steps", List.of(
                Map.of("dedup", Map.of("keys", List.of("a")), "summarize", Map.of())))))
                .getMessage().contains("single-key map"), "two kinds in one entry has no order");
    }

    /**
     * A CONTRIBUTED kind loads when the deployment registers that node type — the plugin-step half of
     * the same gate. The check is inverted through {@link StepKindRegistry} because this module sits
     * below the node-type registry, but the refusal stays at LOAD either way.
     */
    @Test
    void acceptsAContributedKindTheRegistryVouchesFor() {
        Map<String, Object> cfg = base(Map.of("steps",
                List.of(Map.of(FakeStepKindRegistry.KIND, Map.of("count", "2")))));
        PipelineConfig loaded = assertDoesNotThrow(() -> PipelineConfig.fromMap(cfg));
        assertEquals(1, loaded.steps().size());
        assertEquals(FakeStepKindRegistry.KIND, loaded.steps().get(0).kind());
        assertEquals("2", loaded.steps().get(0).config().get("count"),
                "the config travels verbatim — the core models none of a plugin's keys");
    }

    @Test
    void refusesAnUnknownKind() {
        assertTrue(refused(base(Map.of("steps", List.of(Map.of("frobnicate", Map.of())))))
                .getMessage().contains("unknown steps[] kind 'frobnicate'"));
    }

    /**
     * ⚠ Carrying both spellings is refused, not merged. There is no non-arbitrary position at which a
     * legacy block would join an authored sequence, and choosing one silently is exactly the reordering
     * {@code steps:} exists to remove — so the ambiguity is refused where the author can see it.
     */
    @Test
    void refusesStepsAlongsideALegacyBlockAndNamesTheOffender() {
        Map<String, Object> both = base(Map.of("steps", List.of(Map.of("dedup", Map.of("keys", List.of("a"))))));
        both.put("processing", new LinkedHashMap<>(Map.of(
                "threads", 1, "dedup", Map.of("keys", List.of("legacy")))));

        String msg = refused(both).getMessage();
        assertTrue(msg.contains("processing.dedup"), msg);
        assertTrue(msg.contains("order undefined"), msg);
    }

    // ── the legacy projection: every existing file still has a chain ─────────────

    /**
     * The order here is {@code PipelineLift}'s wiring order, and it is asserted independently against the
     * lift itself in {@code PipelineStepsProjectionTest} — this test would happily pass against a wrong
     * constant, which is precisely why it is not the only one.
     */
    @Test
    void legacyBlocksProjectIntoTheLiftsOrder() throws Exception {
        Map<String, Object> processing = new LinkedHashMap<>();
        processing.put("threads", 1);
        processing.put("csv_settings", Map.of("where", "duration > 0"));
        processing.put("summarize", Map.of("group_by", List.of("day"), "measures", List.of("count")));
        processing.put("dedup", Map.of("keys", List.of("msisdn")));
        processing.put("join", Map.of("reference", "sites", "on", List.of("cell_id")));

        Map<String, Object> m = base(Map.of());
        m.put("processing", processing);
        m.put("route", Map.of("on", "table"));

        // declaration order in the file is deliberately NOT the chain order — the projection imposes it
        assertEquals(List.of("filter", "join", "dedup", "summarize", "route"),
                kinds(PipelineConfig.fromMap(m)));
    }

    @Test
    void aConfigWithNoTransformsHasAnEmptyChainRatherThanNull() throws Exception {
        assertEquals(List.of(), PipelineConfig.fromMap(base(Map.of())).steps());
    }

    /** A step carries only the keys its block held — an absent {@code order_by} is not a null entry. */
    @Test
    void theProjectionDropsAbsentOptionalKeys() throws Exception {
        Map<String, Object> processing = new LinkedHashMap<>();
        processing.put("threads", 1);
        processing.put("dedup", Map.of("keys", List.of("msisdn")));
        Map<String, Object> m = base(Map.of());
        m.put("processing", processing);

        assertEquals(Map.of("keys", List.of("msisdn")), PipelineConfig.fromMap(m).steps().get(0).config());
    }

    private static IllegalArgumentException refused(Map<String, Object> m) {
        return assertThrows(IllegalArgumentException.class, () -> PipelineConfig.fromMap(m));
    }
}

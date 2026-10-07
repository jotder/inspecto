package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code webhook:} block's graph homes: the at-rest lift runs it as a second commit BRANCH beside the
 * {@code output_store:} sink; the editor lift/lower round-trips it verbatim; the recipe verb compiles to it
 * and the converter projects it back. Each asserts the silent-loss direction as well as the happy one —
 * a lift that skips the node lets the next strict lower DELETE the block.
 */
class WebhookSinkLiftLowerTest {

    private static final Map<String, Object> HOOK = Map.of("connection", "orders_hook", "batch_size", 100,
            "retry", Map.of("count", 2, "backoff", "FIXED"));

    private static Map<String, Object> flat(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "HOOK_ETL");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        m.putAll(extra);
        return m;
    }

    // ── at rest ────────────────────────────────────────────────────────────────

    @Test
    void stageTwoHangsTheWebhookBesideTheOutputStoreSinkOffTheChainEnd() throws Exception {
        PipelineGraph g = PipelineLift.stageTwo(PipelineConfig.fromMap(flat(Map.of("output_store", "shaped",
                "steps", List.of(Map.of("dedup", Map.of("keys", List.of("id")))), "webhook", HOOK))));

        assertEquals(List.of("acquisition", "transform.dedup", "sink.persistent", "sink.webhook"),
                g.nodes().stream().map(PipelineNode::type).toList());
        assertEquals(List.of("src>dedup", "dedup>sink", "dedup>webhook"),
                g.edges().stream().map(e -> e.from() + ">" + e.to()).toList(),
                "both sinks consume the chain's output — the webhook is a branch, not downstream of the store");
        assertEquals(HOOK, g.byId().get("webhook").config(), "the node carries the block verbatim");
        assertTrue(PipelineValidator.validate(g).ok(), () -> PipelineValidator.validate(g).errors().toString());
        assertEquals(java.util.Set.of("shaped"), PipelineStores.produced(g),
                "a webhook declares no store — nothing downstream can bind to it as one");
    }

    /** "Send what landed" needs no transform: a webhook alone is a Stage-2 chain worth running. */
    @Test
    void aWebhookWithNoStepsStillLiftsAtRest() throws Exception {
        PipelineGraph g = PipelineLift.stageTwo(PipelineConfig.fromMap(flat(Map.of("output_store", "copy",
                "webhook", HOOK))));
        assertEquals(List.of("src>sink", "src>webhook"), g.edges().stream().map(e -> e.from() + ">" + e.to()).toList());
    }

    @Test
    void withoutAWebhookStageTwoIsUnchanged() throws Exception {
        var e = assertThrows(IllegalArgumentException.class,
                () -> PipelineLift.stageTwo(PipelineConfig.fromMap(flat(Map.of("output_store", "x")))));
        assertTrue(e.getMessage().contains("no Stage-2 chain"), e.getMessage());
    }

    // ── the editor: lift → lower ─────────────────────────────────────────────────

    @Test
    void theEditorLiftShowsOneWebhookNodeFedLikeThePersistentSink() throws Exception {
        PipelineGraph g = PipelineLift.lift(PipelineConfig.fromMap(flat(Map.of("webhook", HOOK))));
        PipelineNode w = g.byId().get("webhook");
        assertEquals("sink.webhook", w.type());
        String sinkFeed = g.edges().stream().filter(e -> e.to().equals("sink")).findFirst().orElseThrow().from();
        assertTrue(g.edges().stream().anyMatch(e -> e.from().equals(sinkFeed) && e.to().equals("webhook")
                && PipelineRel.DATA.equals(e.rel())));
    }

    @Test
    void lowerWritesTheBlockVerbatimAndAStrictLowerWithoutTheNodeDeletesIt() throws Exception {
        Map<String, Object> raw = flat(Map.of("webhook", HOOK));
        PipelineGraph g = PipelineCodec.fromMap(PipelineEditable.toMap(PipelineConfig.fromMap(raw), raw));

        assertEquals(HOOK, PipelineEditable.lower(g, raw, false).get("webhook"), "round trip is verbatim");

        List<PipelineNode> without = new ArrayList<>();
        for (PipelineNode n : g.nodes()) {
            if ("sink.webhook".equals(n.type())) continue;
            if (!"parse".equals(n.id())) { without.add(n); continue; }
            // a strict lower demands a schema on the parser; lower reads no file, so a name suffices
            Map<String, Object> c = new LinkedHashMap<>(n.config());
            c.put("schema_file", "orders_schema.toon");
            without.add(new PipelineNode(n.id(), n.type(), n.name(), n.description(), c, n.use()));
        }
        PipelineGraph deleted = new PipelineGraph(g.name(), g.active(), without,
                g.edges().stream().filter(e -> !e.to().equals("webhook")).toList());
        assertFalse(PipelineEditable.lower(deleted, raw, true).containsKey("webhook"),
                "deleting the node on the canvas deletes the block (strict)");
        assertEquals(HOOK, PipelineEditable.lower(deleted, raw, false).get("webhook"),
                "a lenient draft save keeps a block it was not given");
    }

    @Test
    void lowerRefusesASecondWebhookAndAnInvalidOne() throws Exception {
        Map<String, Object> raw = flat(Map.of());
        PipelineGraph base = PipelineCodec.fromMap(PipelineEditable.toMap(PipelineConfig.fromMap(raw), raw));

        List<PipelineNode> two = new ArrayList<>(base.nodes());
        two.add(PipelineNode.of("w1", "sink.webhook", new LinkedHashMap<>(HOOK)));
        two.add(PipelineNode.of("w2", "sink.webhook", new LinkedHashMap<>(HOOK)));
        var multi = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(new PipelineGraph(base.name(), false, two, base.edges()), raw, false));
        assertTrue(multi.refusals().stream().anyMatch(r -> PipelineEditable.MULTI_WEBHOOK.equals(r.code())
                && "w2".equals(r.nodeId())), multi.refusals().toString());

        List<PipelineNode> bad = new ArrayList<>(base.nodes());
        bad.add(PipelineNode.of("w", "sink.webhook",
                new LinkedHashMap<>(Map.of("connection", "h", "url", "https://elsewhere.test"))));
        var invalid = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(new PipelineGraph(base.name(), false, bad, base.edges()), raw, false));
        assertTrue(invalid.refusals().stream().anyMatch(r -> PipelineEditable.WEBHOOK_INVALID.equals(r.code())),
                invalid.refusals().toString());
    }

    @Test
    void theWebhookTypeIsLowerable() {
        assertTrue(PipelineEditable.isLowerable("sink.webhook"));
    }

    // ── recipes ───────────────────────────────────────────────────────────────

    private static Map<String, Object> recipe(Map<String, Object> hook) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", "hook_recipe");
        r.put("active", false);
        r.put("steps", List.of(
                Map.of("collect", Map.of("poll", "in")),
                Map.of("parse", Map.of("schema_file", "schemas/orders_schema.toon")),
                Map.of("sink", Map.of("database", "out")),
                Map.of("webhook", hook)));
        return r;
    }

    @Test
    void theRecipeVerbCompilesToTheBlockAndTheConverterProjectsItBack() {
        Map<String, Object> flat = RecipeCompiler.compile(recipe(HOOK));
        assertEquals(HOOK, flat.get("webhook"));

        Map<String, Object> back = RecipeConverter.toRecipe(flat);
        assertTrue(((List<?>) back.get("steps")).contains(Map.of("webhook", HOOK)),
                "the converter must carry the verb, or a convert→compile round trip deletes the block: " + back);
    }

    @Test
    void aRecipeWebhookNamingAUrlRefusesBesideItsStep() {
        var e = assertThrows(PipelineCompileException.class,
                () -> RecipeCompiler.compile(recipe(Map.of("connection", "h", "url", "https://x.test"))));
        assertTrue(e.refusals().stream().anyMatch(r -> "webhook-4".equals(r.nodeId())
                && r.message().contains("url")), e.refusals().toString());
    }
}

package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineEditable;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.PipelineNode;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The multiplicity chain through the <b>real file</b>: graph → {@code lower} → {@code ConfigCodec.toToon}
 * → disk → {@code ToonHelper.load} → {@code PipelineConfig} → {@code lift}.
 *
 * <p>⚠ <b>Why this exists, and why it is here rather than in inspecto-engine.</b> Every other test of the
 * chain — and, it turned out, every test of the {@code sinks:} plural block that preceded it — goes through
 * {@code PipelineConfig.fromMap} with a hand-built Java map. That skips the codec entirely, and the codec
 * is where a config format is actually decided: {@code toToon} is what {@code PipelineRoutes} writes on
 * every save ({@code PipelineRoutes.java:363}). A block can therefore be perfectly modelled, perfectly
 * parsed from a map, and still be unwritable or unreadable as a file.
 *
 * <p>It surfaced exactly that. Probing the codec showed a bare {@code steps:} decodes as a <b>map</b>, not
 * a list — toon needs the {@code steps[N]:} element count — so the parser's list check silently skipped the
 * whole chain, and a hand-written {@code - dedup: {keys: [x]}} decodes as a plain <b>String</b>, never a
 * config block. Both are now refusals; this test is the standing guard that {@code lower} keeps producing
 * something the parser genuinely accepts.
 *
 * <p>This module is the only one that can see both {@code ConfigCodec} (inspecto-config) and
 * {@code PipelineEditable} (inspecto-engine) — the same reason it owns the write route.
 */
class PipelineStepsFileRoundTripTest {

    private static PipelineNode node(String id, String type, Map<String, Object> cfg) {
        return new PipelineNode(id, type, null, null, cfg, null);
    }

    /** Lower {@code extra} into a real file, read it back, and lift it — the whole save/load path. */
    private static PipelineConfig throughTheFile(Path dir, PipelineNode... extra) throws Exception {
        Files.writeString(dir.resolve("s.toon"), com.gamma.etl.PipelineConfigBatchTest.miniSchema(),
                StandardCharsets.UTF_8);
        List<PipelineNode> nodes = new ArrayList<>(List.of(
                node("acq", "acquisition", Map.of("poll", dir.resolve("in").toString())),
                node("parse", "parser", Map.of("schema_file", dir.resolve("s.toon").toString())),
                node("sink", "sink.persistent", Map.of("database", dir.resolve("db").toString()))));
        nodes.addAll(List.of(extra));

        // active:false — an explicit chain is refused at arming until plan slice A5 routes it
        Map<String, Object> lowered = PipelineEditable.lower(
                new PipelineGraph("chain", false, nodes, List.of()), new LinkedHashMap<>(), true);

        Path file = dir.resolve("chain_pipeline.toon");
        Files.writeString(file, ConfigCodec.toToon(lowered), StandardCharsets.UTF_8);
        return PipelineConfig.fromMap(ToonHelper.load(file.toString()));
    }

    /**
     * Two dedups either side of a summarize: the interleaving that no arrangement of per-kind blocks can
     * express, surviving an actual write and read.
     */
    @Test
    void anInterleavedChainSurvivesTheCodecAndParsesBack(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = throughTheFile(dir,
                node("dd1", "transform.dedup", Map.of("keys", List.of("ID"))),
                node("s1", "transform.summarize",
                        Map.of("group_by", List.of("EVENT_DATE"), "measures", List.of("count"))),
                node("dd2", "transform.dedup", Map.of("keys", List.of("AMT"))));

        assertTrue(cfg.hasExplicitSteps(), "the file carries an authored chain, not the legacy projection");
        assertEquals(List.of("dedup", "summarize", "dedup"),
                cfg.steps().stream().map(PipelineConfig.Step::kind).toList());
        assertEquals(List.of("ID"), cfg.steps().get(0).config().get("keys"));
        assertEquals(List.of("AMT"), cfg.steps().get(2).config().get("keys"),
                "the second dedup keeps its own keys rather than being merged into the first");

        // …and the graph comes back with both dedups, in order
        assertEquals(List.of("transform.dedup", "transform.summarize", "transform.dedup"),
                PipelineLift.lift(cfg).nodes().stream()
                        .filter(n -> !"map".equals(n.id()))   // the projection slot is a transform.sql too (2026-09-05)
                        .map(PipelineNode::type)
                        .filter(t -> t.startsWith("transform."))
                        .filter(t -> PipelineConfig.Step.KINDS.contains(t.substring("transform.".length())))
                        .toList());
    }

    /** Two filters: the kind that was silently merged into one {@code csv_settings} map until A3. */
    @Test
    void twoFiltersSurviveTheCodecAsSeparateSteps(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = throughTheFile(dir,
                node("f1", "transform.filter", Map.of("where", "AMT > 0")),
                node("f2", "transform.filter", Map.of("where", "ID IS NOT NULL")));

        assertEquals(List.of("filter", "filter"),
                cfg.steps().stream().map(PipelineConfig.Step::kind).toList());
        assertEquals("AMT > 0", cfg.steps().get(0).config().get("where"));
        assertEquals("ID IS NOT NULL", cfg.steps().get(1).config().get("where"));
        assertNull(cfg.csv().where(),
                "the legacy where: must be gone — both spellings in one file is a parse refusal");
    }

    /**
     * The safety property that lets this ship: a pipeline the singular keys CAN express is written
     * exactly as before, with no {@code steps:} block at all. Every pipeline in existence takes this path.
     */
    @Test
    void aLegacyShapedPipelineIsWrittenWithoutAStepsBlock(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("s.toon"), com.gamma.etl.PipelineConfigBatchTest.miniSchema(),
                StandardCharsets.UTF_8);
        Map<String, Object> lowered = PipelineEditable.lower(new PipelineGraph("chain", false, List.of(
                node("acq", "acquisition", Map.of("poll", dir.resolve("in").toString())),
                node("parse", "parser", Map.of("schema_file", dir.resolve("s.toon").toString())),
                node("dd1", "transform.dedup", Map.of("keys", List.of("ID"))),
                node("sink", "sink.persistent", Map.of("database", dir.resolve("db").toString()))),
                List.of()), new LinkedHashMap<>(), true);

        String toon = ConfigCodec.toToon(lowered);
        assertFalse(toon.contains("steps"), "one dedup still lowers to processing.dedup:\n" + toon);

        Path file = dir.resolve("legacy_pipeline.toon");
        Files.writeString(file, toon, StandardCharsets.UTF_8);
        PipelineConfig cfg = PipelineConfig.fromMap(ToonHelper.load(file.toString()));

        assertFalse(cfg.hasExplicitSteps(), "the chain is the legacy projection here");
        assertNotNull(cfg.dedup(), "the singular slot still holds it");
        assertEquals(List.of("ID"), cfg.dedup().keys());
    }

    // ── MIDBRANCH-1 (R3): route.branches[].steps[] through the real file ─────────────────

    private static PipelineNode node(String id, String type, String name, Map<String, Object> cfg) {
        return new PipelineNode(id, type, name, null, cfg, null);
    }

    /** The flattened graph PipelineLift emits for a two-branch route whose hi branch carries a chain. */
    private static PipelineGraph chainedRouteGraph(Path dir, List<com.gamma.pipeline.PipelineEdge> extraEdges,
                                                   PipelineNode... chainNodes) {
        List<PipelineNode> nodes = new ArrayList<>(List.of(
                node("acq", "acquisition", Map.of("poll", dir.resolve("in").toString())),
                node("parse", "parser", Map.of("schema_file", dir.resolve("s.toon").toString())),
                node("route", "transform.route", new LinkedHashMap<>(Map.of(
                        "mode", "case",
                        "default", "lo",
                        "branches", List.of(
                                new LinkedHashMap<>(Map.of("key", "hi", "where", "AMT >= 200")),
                                new LinkedHashMap<>(Map.of("key", "lo", "where", "AMT < 200")))))),
                node("sink_hi", "sink.persistent", Map.of("database", dir.resolve("hi_db").toString())),
                node("sink_lo", "sink.persistent", Map.of("database", dir.resolve("lo_db").toString()))));
        nodes.addAll(List.of(chainNodes));
        List<com.gamma.pipeline.PipelineEdge> edges = new ArrayList<>(List.of(
                com.gamma.pipeline.PipelineEdge.data("acq", "parse"),
                com.gamma.pipeline.PipelineEdge.data("parse", "route"),
                new com.gamma.pipeline.PipelineEdge("route", com.gamma.pipeline.PipelineRel.route("lo"), "sink_lo")));
        edges.addAll(extraEdges);
        return new PipelineGraph("chain", false, nodes, edges);
    }

    /**
     * The whole MIDBRANCH-1 loop through a real file: graph (flattened chain) → lower →
     * {@code route.branches[].steps[]} → toToon → disk → load → lift → the SAME flattened chain,
     * order preserved per branch — never a fromMap-only shortcut (the config-format lesson).
     */
    @Test
    void aBranchChainRoundTripsThroughTheRealFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("s.toon"), com.gamma.etl.PipelineConfigBatchTest.miniSchema(),
                StandardCharsets.UTF_8);
        PipelineGraph g = chainedRouteGraph(dir,
                List.of(new com.gamma.pipeline.PipelineEdge("route",
                                com.gamma.pipeline.PipelineRel.route("hi"), "filter__hi"),
                        com.gamma.pipeline.PipelineEdge.data("filter__hi", "dedup__hi"),
                        com.gamma.pipeline.PipelineEdge.data("dedup__hi", "sink_hi")),
                node("filter__hi", "transform.filter", "Row filter", Map.of("where", "AMT > 0")),
                node("dedup__hi", "transform.dedup", null, Map.of("keys", List.of("ID"))));

        Map<String, Object> lowered = PipelineEditable.lower(g, new LinkedHashMap<>(), true);

        // the branch chain landed in the hi entry, in order, and the lo entry carries no steps key
        Map<?, ?> route = (Map<?, ?>) lowered.get("route");
        List<?> branches = (List<?>) route.get("branches");
        Map<?, ?> hi = (Map<?, ?>) branches.get(0);
        assertEquals(List.of("filter", "dedup"),
                ((List<?>) hi.get("steps")).stream()
                        .map(s -> ((Map<?, ?>) s).keySet().iterator().next().toString()).toList());
        assertFalse(((Map<?, ?>) branches.get(1)).containsKey("steps"),
                "a branch wired straight to its sink carries no steps key");
        assertEquals(dir.resolve("hi_db").toString(), hi.get("database"),
                "the branch pairs with its chain's TERMINAL sink, not the route edge's direct target");

        // …through the codec, off disk, and lifted back to the same flattened shape
        Path file = dir.resolve("chain_pipeline.toon");
        Files.writeString(file, ConfigCodec.toToon(lowered), StandardCharsets.UTF_8);
        PipelineConfig cfg = PipelineConfig.fromMap(ToonHelper.load(file.toString()));
        PipelineGraph lifted = PipelineLift.lift(cfg);

        assertEquals("transform.filter", lifted.byId().get("filter__hi").type());
        assertEquals("transform.dedup", lifted.byId().get("dedup__hi").type());
        assertEquals(List.of("ID"), lifted.byId().get("dedup__hi").cfg("keys"));
        assertTrue(lifted.edges().contains(new com.gamma.pipeline.PipelineEdge("route",
                        com.gamma.pipeline.PipelineRel.route("hi"), "filter__hi")),
                "route:hi feeds the chain's first node");
        assertTrue(lifted.edges().contains(com.gamma.pipeline.PipelineEdge.data("filter__hi", "dedup__hi")));
        assertTrue(lifted.edges().stream().anyMatch(e -> e.from().equals("dedup__hi")
                        && com.gamma.pipeline.PipelineRel.DATA.equals(e.rel())),
                "the chain's last node feeds the branch sink by a plain data edge");

        // …and a no-edit RE-SAVE over the loaded file is byte-identical: the loop is stable.
        // (Deliberately the graph the editor holds, not the editable toMap projection — that
        // projection has a PRE-EXISTING multi-destination database gap unrelated to branch steps.)
        Map<String, Object> again = PipelineEditable.lower(g, ToonHelper.load(file.toString()), true);
        assertEquals(ConfigCodec.toToon(lowered), ConfigCodec.toToon(again),
                "no-edit round trip must not rewrite the file");
    }

    /** A branch WITHOUT steps stays byte-identical through a no-edit round trip (the R3 safety rail). */
    @Test
    void aBranchWithoutStepsStaysByteIdentical(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("s.toon"), com.gamma.etl.PipelineConfigBatchTest.miniSchema(),
                StandardCharsets.UTF_8);
        PipelineGraph g = chainedRouteGraph(dir,
                List.of(new com.gamma.pipeline.PipelineEdge("route",
                        com.gamma.pipeline.PipelineRel.route("hi"), "sink_hi")));
        Map<String, Object> lowered = PipelineEditable.lower(g, new LinkedHashMap<>(), true);
        String toon = ConfigCodec.toToon(lowered);
        assertFalse(toon.contains("steps"), "no chain anywhere ⇒ no steps key anywhere:\n" + toon);

        Path file = dir.resolve("plain_pipeline.toon");
        Files.writeString(file, toon, StandardCharsets.UTF_8);
        Map<String, Object> again = PipelineEditable.lower(g, ToonHelper.load(file.toString()), true);
        assertEquals(toon, ConfigCodec.toToon(again), "byte-identical no-edit round trip");
    }

    /** A branch chain that dead-ends (no sink downstream) refuses by name at lower, never truncates. */
    @Test
    void aBranchChainNotEndingAtASinkRefuses(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("s.toon"), com.gamma.etl.PipelineConfigBatchTest.miniSchema(),
                StandardCharsets.UTF_8);
        PipelineGraph g = chainedRouteGraph(dir,
                List.of(new com.gamma.pipeline.PipelineEdge("route",
                                com.gamma.pipeline.PipelineRel.route("hi"), "dedup__hi"),
                        com.gamma.pipeline.PipelineEdge.data("dedup__hi", "sink_hi"),
                        // …and a second outgoing data edge — a branch chain is a LIST
                        com.gamma.pipeline.PipelineEdge.data("dedup__hi", "sink_lo")),
                node("dedup__hi", "transform.dedup", null, Map.of("keys", List.of("ID"))));
        var ex = assertThrows(com.gamma.pipeline.PipelineCompileException.class,
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), true));
        assertTrue(ex.refusals().stream().anyMatch(r ->
                        PipelineEditable.UNSUPPORTED_BRANCH_STEP.equals(r.code())),
                ex.refusals().toString());
    }
}

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
                PipelineLift.lift(cfg).nodes().stream().map(PipelineNode::type)
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
}

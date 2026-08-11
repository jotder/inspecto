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
 * The plural {@code sinks:} block through the <b>real file</b>: graph → {@code lower} →
 * {@code ConfigCodec.toToon} → disk → {@code ToonHelper.load} → {@code PipelineConfig} → {@code lift}.
 *
 * <p>⚠ <b>Why this exists.</b> Every other test of {@code sinks:} goes through
 * {@code PipelineConfig.fromMap} with a hand-built Java map ({@code PipelineConfigSinksTest}), which skips
 * the codec — and the codec is where a config format is actually decided: {@code toToon} is what
 * {@code PipelineRoutes} writes on every save. The sibling {@code steps:} block proved the failure mode is
 * real: a block can be perfectly modelled, perfectly parsed from a map, and still be unwritable or
 * unreadable as a file (a bare {@code steps:} decodes as a map, not a list). {@code sinks:} was the one
 * format that never got this treatment — the standing rule being that <b>a config-format slice is NOT
 * verified by a {@code fromMap} test</b>.
 *
 * <p>This module is the only one that can see both {@code ConfigCodec} (inspecto-config) and
 * {@code PipelineEditable} (inspecto-engine) — the same reason it owns the write route.
 */
class PipelineSinksFileRoundTripTest {

    private static PipelineNode node(String id, String type, Map<String, Object> cfg) {
        return new PipelineNode(id, type, null, null, cfg, null);
    }

    /** Lower a graph whose sinks are {@code extraSinks} into a real file, read it back — the save/load path. */
    private static String throughTheCodec(Path dir, PipelineNode... sinks) throws Exception {
        Files.writeString(dir.resolve("s.toon"), com.gamma.etl.PipelineConfigBatchTest.miniSchema(),
                StandardCharsets.UTF_8);
        List<PipelineNode> nodes = new ArrayList<>(List.of(
                node("acq", "acquisition", Map.of("poll", dir.resolve("in").toString())),
                node("parse", "parser", Map.of("schema_file", dir.resolve("s.toon").toString()))));
        nodes.addAll(List.of(sinks));

        Map<String, Object> lowered = PipelineEditable.lower(
                new PipelineGraph("fanout", false, nodes, List.of()), new LinkedHashMap<>(), true);
        return ConfigCodec.toToon(lowered);
    }

    private static PipelineConfig readBack(Path dir, String toon) throws Exception {
        Path file = dir.resolve("fanout_pipeline.toon");
        Files.writeString(file, toon, StandardCharsets.UTF_8);
        return PipelineConfig.fromMap(ToonHelper.load(file.toString()));
    }

    /**
     * Two distinct destinations: the shape no single {@code output:}/{@code dirs.database} can express,
     * surviving an actual write and read with both entries and each one's own format.
     */
    @Test
    void twoDestinationsSurviveTheCodecAndParseBack(@TempDir Path dir) throws Exception {
        String toon = throughTheCodec(dir,
                node("hot", "sink.persistent", Map.of(
                        "database", dir.resolve("hot").toString(), "format", "parquet",
                        "compression", "snappy")),
                node("cold", "sink.persistent", Map.of(
                        "database", dir.resolve("cold").toString(), "format", "csv")));

        // The element count is what makes it a LIST in toon. This is not a redundant spelling check: a
        // countless `sinks:` decodes as a MAP, the parser's instanceof List test skips the block in
        // silence, and both destinations vanish into the single-output shorthand.
        assertTrue(toon.contains("sinks[2]:"),
                "two destinations must lower to a counted plural block:\n" + toon);
        PipelineConfig cfg = readBack(dir, toon);

        assertEquals(2, cfg.sinks().size(),
                "the file's sinks: list must decode as a LIST — a countless block decodes as a map and "
                        + "would silently collapse to the single-output shorthand:\n" + toon);
        assertEquals(dir.resolve("hot").toString(), cfg.sinks().get(0).database());
        assertEquals("PARQUET", cfg.sinks().get(0).format());
        assertEquals("snappy", cfg.sinks().get(0).compression());
        assertEquals(dir.resolve("cold").toString(), cfg.sinks().get(1).database(),
                "the second destination keeps its own database rather than being merged into the first");
        assertEquals("CSV", cfg.sinks().get(1).format());

        // …and the graph comes back with both sink nodes, in order
        assertEquals(List.of(dir.resolve("hot").toString(), dir.resolve("cold").toString()),
                PipelineLift.lift(cfg).nodes().stream()
                        .filter(n -> "sink.persistent".equals(n.type()))
                        .map(n -> String.valueOf(n.cfg("database")))
                        .toList());
    }

    /**
     * The safety property that lets this ship: a pipeline with one destination is written exactly as
     * before, with no {@code sinks:} block at all. Every pipeline in existence takes this path.
     */
    @Test
    void aSingleDestinationIsWrittenWithoutASinksBlock(@TempDir Path dir) throws Exception {
        String toon = throughTheCodec(dir,
                node("only", "sink.persistent", Map.of(
                        "database", dir.resolve("db").toString(), "format", "parquet")));

        assertFalse(toon.contains("sinks"),
                "one destination still lowers to output:/dirs.database:\n" + toon);

        PipelineConfig cfg = readBack(dir, toon);
        assertEquals(1, cfg.sinks().size(), "the shorthand synthesises exactly one destination");
        assertEquals(dir.resolve("db").toString(), cfg.sinks().get(0).database());
        assertEquals(dir.resolve("db").toString(), cfg.dirs().database(),
                "the shorthand IS dirs.database — the two spellings cannot diverge");
        assertEquals("PARQUET", cfg.sinks().get(0).format());
    }
}

package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineEditable;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consignment formation ({@code collector.consignment:} — the home since CONSIGNMENT-HOME-1, 2026-09-02;
 * {@code processing.batch:} before) through the <b>real file</b>: graph → {@code lower}
 * → {@code ConfigCodec.toToon} → disk → {@code ToonHelper.load} → {@code PipelineConfig}.
 *
 * <p>⚠ <b>Why this exists — G3</b> ({@code docs/superpower/consignment-chain-plan.md}). The editor
 * lowered the grouping caps as FLAT {@code processing.batch_max_files}/{@code batch_max_bytes} while
 * the parser reads only the NESTED {@code processing.batch:{max_files,max_bytes}} map — two spellings
 * with no overlap, each side individually tested, so no UI path could configure Consignment Generation
 * in a shape the engine honours. Third instance of the standing rule: <b>a config-format slice is NOT
 * verified by a {@code fromMap} test.</b> The forward key contract now lives in
 * {@code NodeConfigNameContractTest} ({@code consignment__max_files} → {@code processing().batchMaxFiles()});
 * this class pins the file shape, the healing of BOTH legacy spellings (the flat pair and the
 * {@code processing.batch:} map) into the collector block, and the wholesale-map ownership.
 */
class ConsignmentGroupingFileRoundTripTest {

    private static PipelineNode node(String id, String type, Map<String, Object> cfg) {
        return new PipelineNode(id, type, null, null, cfg, null);
    }

    /** Lower an acq→parse→sink graph (the acquisition node carrying {@code extraAcqCfg}) — the editor's save. */
    private static Map<String, Object> lowered(Path dir, Map<String, Object> extraAcqCfg) throws Exception {
        Files.writeString(dir.resolve("s.toon"), com.gamma.etl.PipelineConfigBatchTest.miniSchema(),
                StandardCharsets.UTF_8);
        Map<String, Object> sinkCfg = new LinkedHashMap<>();
        sinkCfg.put("database", dir.resolve("db").toString());
        Map<String, Object> acqCfg = new LinkedHashMap<>();
        acqCfg.put("poll", dir.resolve("in").toString());
        acqCfg.putAll(extraAcqCfg);
        return PipelineEditable.lower(new PipelineGraph("grouping", false, List.of(
                node("acq", "acquisition", acqCfg),
                node("parse", "parser", Map.of("schema_file", dir.resolve("s.toon").toString())),
                node("sink", "sink.persistent", sinkCfg)),
                List.of()), new LinkedHashMap<>(), true);
    }

    private static Path write(Path dir, String name, Map<String, Object> map) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, ConfigCodec.toToon(map), StandardCharsets.UTF_8);
        return file;
    }

    /** Lift a real file exactly as the editor's load does. */
    private static PipelineGraph lift(Path toon) throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(ToonHelper.load(toon.toString()));
        return PipelineCodec.fromMap(PipelineEditable.toMap(cfg, ToonHelper.load(toon.toString())));
    }

    /** The caps the collector node declares land in the file as {@code collector.consignment:}, and the engine reads them. */
    @Test
    void groupingCapsSurviveTheCodecNested(@TempDir Path dir) throws Exception {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("max_files", 500);
        caps.put("max_bytes", 1_000_000);
        Map<String, Object> lowered = lowered(dir, Map.of("consignment", caps));
        String toon = ConfigCodec.toToon(lowered);

        assertFalse(toon.contains("batch"),
                "no batch spelling of any kind may be written any more (flat = G3; the map = pre-move):\n" + toon);
        assertTrue(toon.contains("consignment:"),
                "the caps must lower to the collector.consignment: block the parser reads first:\n" + toon);
        @SuppressWarnings("unchecked")
        Map<String, Object> collector = (Map<String, Object>) lowered.get("collector");
        assertNotNull(collector.get("consignment"), "homed on the collector block, not processing:");

        PipelineConfig cfg = PipelineConfig.fromMap(
                ToonHelper.load(write(dir, "grouping_pipeline.toon", lowered).toString()));
        assertEquals(500, cfg.processing().batchMaxFiles());
        assertEquals(1_000_000L, cfg.processing().batchMaxBytes());
    }

    /**
     * A file carrying only the legacy flat spelling — what the editor wrote before the fix — is
     * proven dead (the engine keeps its default), then HEALS into the nested shape on lift → save.
     */
    @Test
    void legacyFlatSpellingHealsOnResave(@TempDir Path dir) throws Exception {
        Map<String, Object> raw = lowered(dir, Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> processing = (Map<String, Object>) raw.get("processing");
        processing.put("batch_max_files", 500);
        Path file = write(dir, "legacy_pipeline.toon", raw);

        // The original defect, documented: the engine never read the flat spelling.
        assertEquals(1, PipelineConfig.fromMap(ToonHelper.load(file.toString())).processing().batchMaxFiles(),
                "if the engine starts reading the flat spelling, this healing path is obsolete — revisit");

        Map<String, Object> resaved = PipelineEditable.lower(
                lift(file), ToonHelper.load(file.toString()), true);
        String toon = ConfigCodec.toToon(resaved);
        assertFalse(toon.contains("batch"), "every dead spelling must be gone after a save:\n" + toon);

        PipelineConfig healed = PipelineConfig.fromMap(
                ToonHelper.load(write(dir, "healed_pipeline.toon", resaved).toString()));
        assertEquals(500, healed.processing().batchMaxFiles(),
                "the value the user authored must survive the healing, now in collector.consignment");
    }

    /**
     * The collector node owns the {@code consignment} map WHOLESALE, so a key this editor does not model
     * rides through a lift → save untouched instead of being dropped — including across the MOVE: a file
     * still on {@code processing.batch:} heals into {@code collector.consignment:} with every key intact.
     */
    @Test
    void unmodeledBatchKeysSurviveTheSave(@TempDir Path dir) throws Exception {
        Map<String, Object> raw = lowered(dir, Map.of());
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("max_files", 5);
        batch.put("order", "mtime");
        @SuppressWarnings("unchecked")
        Map<String, Object> processing = (Map<String, Object>) raw.get("processing");
        processing.put("batch", batch);
        Path file = write(dir, "keyed_pipeline.toon", raw);

        Map<String, Object> resaved = PipelineEditable.lower(
                lift(file), ToonHelper.load(file.toString()), true);
        Path out = write(dir, "keyed_resaved_pipeline.toon", resaved);

        Map<String, Object> reloaded = ToonHelper.load(out.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> batchBack = (Map<String, Object>)
                ((Map<String, Object>) reloaded.get("collector")).get("consignment");
        assertEquals("mtime", batchBack.get("order"),
                "an editor-unmodeled key inside the consignment map was dropped by the move — wholesale ownership broke");
        assertFalse(((Map<?, ?>) reloaded.get("processing")).containsKey("batch"),
                "the legacy map must not survive beside the canonical block");
        PipelineConfig cfg = PipelineConfig.fromMap(ToonHelper.load(out.toString()));
        assertEquals(5, cfg.processing().batchMaxFiles());
        assertEquals("mtime", cfg.processing().batchOrder(),
                "the engine consumes the ordering knob (S5) even though the editor only carries it");
    }

    /** The ordering knob refuses garbage at parse time — a silently-ignored knob is the G3 failure mode. */
    @Test
    void aGarbageOrderIsRefusedAtParse(@TempDir Path dir) throws Exception {
        Map<String, Object> raw = lowered(dir, Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> processing = (Map<String, Object>) raw.get("processing");
        processing.put("batch", new LinkedHashMap<>(Map.of("order", "size")));
        Path file = write(dir, "garbage_pipeline.toon", raw);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(ToonHelper.load(file.toString())));
        assertTrue(ex.getMessage().contains("order"), ex.getMessage());
    }

    /** The safety property: a pipeline with no grouping is written with no consignment or batch block at all. */
    @Test
    void noGroupingMeansNoBatchBlock(@TempDir Path dir) throws Exception {
        String toon = ConfigCodec.toToon(lowered(dir, Map.of()));
        assertFalse(toon.contains("batch"), "no grouping configured, no batch spelling of any kind:\n" + toon);
        assertFalse(toon.contains("consignment"), "…and no empty consignment block either:\n" + toon);
    }
}

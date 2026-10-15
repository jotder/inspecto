package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An unedited lift to lower must not flip {@code collector.gap_detection.enabled}: the flag belongs to the file
 * (the node-level {@code enabled} means "stopped"), so lower keeps what the file said — {@code false} stays
 * {@code false}, {@code true} stays {@code true}, absent stays absent.
 */
class GapDetectionLiftLowerTest {

    private static Map<String, Object> roundTrip(Path dir, Map<String, Object> gapDetection) throws Exception {
        Path schema = dir.resolve("s.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Map<String, Object> collector = new LinkedHashMap<>();
        collector.put("discovery", "poll");
        collector.put("connector", "local");
        collector.put("gap_detection", gapDetection);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "GAP_ETL");
        raw.put("dirs", Map.of("poll", "in", "database", "out"));
        raw.put("processing", Map.of("threads", 1, "schema_file", schema.toString()));
        raw.put("collector", collector);
        PipelineConfig cfg = PipelineConfig.fromMap(raw);
        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        Map<String, Object> back = PipelineEditable.lower(PipelineCodec.fromMap(editable), raw, true);
        return (Map<String, Object>) ((Map<?, ?>) back.get("collector")).get("gap_detection");
    }

    @Test
    void enabledFalseSurvives(@TempDir Path dir) throws Exception {
        Map<String, Object> gd = new LinkedHashMap<>();
        gd.put("enabled", false);
        gd.put("file_template", "T_{yyyyMMdd}.csv");
        assertEquals(gd, roundTrip(dir, gd));
    }

    @Test
    void enabledTrueSurvives(@TempDir Path dir) throws Exception {
        Map<String, Object> gd = new LinkedHashMap<>();
        gd.put("enabled", true);
        gd.put("sequence", "T_{yyyyMMdd}");
        gd.put("file_template", "T_{yyyyMMdd}.csv");
        assertEquals(gd, roundTrip(dir, gd));
    }

    @Test
    void absentEnabledStaysAbsent(@TempDir Path dir) throws Exception {
        Map<String, Object> gd = new LinkedHashMap<>();
        gd.put("file_template", "T_{yyyyMMdd}.csv");
        assertEquals(gd, roundTrip(dir, gd));
    }
}

package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-I2-GAPS-RESIDUALS-1 (1d): the visual Pipeline editor must keep {@code collector.accepts_upload} on save. If a save dropped
 * it, the Collector would silently stop being an analyst-upload target (the upload route answers 404). The editor's save path is
 * {@link PipelineEditable#toMap} (edit view) then {@link PipelineEditable#lower} (write): the acquisition node owns every
 * {@code collector:} key verbatim, so the flag rides in its config. Pinned here, including after the operator edits another
 * Collector key on the node, and under the strict (save-gate) lower.
 */
class CollectorAcceptsUploadLiftLowerTest {

    private static Map<String, Object> raw(Path dir, Object acceptsUpload) throws Exception {
        Path schema = dir.resolve("s.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Map<String, Object> collector = new LinkedHashMap<>();
        collector.put("discovery", "poll");
        collector.put("connector", "local");
        collector.put("include", List.of("glob:**/UPL_*.csv"));
        if (acceptsUpload != null) collector.put("accepts_upload", acceptsUpload);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "UP_ETL");
        raw.put("dirs", Map.of("poll", "in", "database", "out"));
        raw.put("processing", Map.of("threads", 1, "schema_file", schema.toString()));
        raw.put("collector", collector);
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> collectorAfterSave(Map<String, Object> raw, boolean editIncludes, boolean strict) throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(raw);
        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        if (editIncludes) {
            for (Object n : (List<Object>) editable.get("nodes")) {
                Map<String, Object> node = (Map<String, Object>) n;
                if ("acquisition".equals(node.get("type")))
                    ((Map<String, Object>) node.get("config")).put("include",
                            new ArrayList<>(List.of("glob:**/UPL_*.csv", "glob:**/EXTRA_*.csv")));
            }
        }
        Map<String, Object> back = PipelineEditable.lower(PipelineCodec.fromMap(editable), raw, strict);
        return (Map<String, Object>) back.get("collector");
    }

    @Test
    void trueSurvivesAnUneditedSaveAndReparsesAsAnUploadTarget(@TempDir Path dir) throws Exception {
        Map<String, Object> raw = raw(dir, true);
        Map<String, Object> collector = collectorAfterSave(raw, false, true);
        assertEquals(true, collector.get("accepts_upload"), "the editor save dropped collector.accepts_upload: " + collector);
        Map<String, Object> saved = new LinkedHashMap<>(raw);
        saved.put("collector", collector);
        assertTrue(PipelineConfig.fromMap(saved).collector().acceptsUpload());
    }

    @Test
    void trueSurvivesWhenTheOperatorEditsAnotherCollectorKey(@TempDir Path dir) throws Exception {
        Map<String, Object> collector = collectorAfterSave(raw(dir, true), true, true);
        assertEquals(true, collector.get("accepts_upload"));
        assertEquals(2, ((List<?>) collector.get("include")).size(), "the edit itself landed");
    }

    @Test
    void absentStaysAbsentAndFalseStaysFalse(@TempDir Path dir) throws Exception {
        assertFalse(collectorAfterSave(raw(dir, null), false, true).containsKey("accepts_upload"));
        assertEquals(false, collectorAfterSave(raw(dir, false), false, true).get("accepts_upload"));
    }
}

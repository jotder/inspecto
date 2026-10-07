package com.gamma.etl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * COLLECTOR-ERRMSG-1 (authoring-residuals R1): the parser's refusal messages must cite
 * {@code collector.*} — the key an operator can actually edit — never the retired {@code source.*}
 * spelling, which names a block nothing reads. These drive REAL refusals through
 * {@code PipelineConfig.fromMap} and pin the spelling of the message, not the parsed keys.
 */
class PipelineConfigCollectorMessageTest {

    private static Map<String, Object> minimal() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "COLLECTOR_MSG");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        return m;
    }

    @Test
    @DisplayName("connector: dataset without an id refuses citing collector.dataset, not source.dataset")
    void datasetConnectorWithoutIdCitesCollectorPath() {
        Map<String, Object> m = minimal();
        m.put("collector", Map.of("connector", "dataset"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(m));
        assertTrue(ex.getMessage().contains("collector.dataset"), ex.getMessage());
        assertFalse(ex.getMessage().contains("source."), ex.getMessage());
    }

    @Test
    @DisplayName("a dataset id on a non-dataset connector refuses citing collector.dataset")
    void datasetIdWithoutDatasetConnectorCitesCollectorPath() {
        Map<String, Object> m = minimal();
        m.put("collector", Map.of("dataset", "orders"));   // connector defaults to 'local'
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(m));
        assertTrue(ex.getMessage().contains("collector.dataset"), ex.getMessage());
        assertFalse(ex.getMessage().contains("source."), ex.getMessage());
    }
}

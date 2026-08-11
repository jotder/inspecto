package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code output_store:} top-level key (multiplicity plan "A5 RE-SCOPED"): the authored
 * name of the store the at-rest Stage-2 run writes. Absent ⇒ {@code null} (no default — the name is an
 * operator decision, never derived); present ⇒ normalised like {@code stream:} and validated as a SQL
 * identifier, because it becomes a store directory / catalog join key.
 */
class PipelineConfigOutputStoreTest {

    private static Map<String, Object> minimal(String outputStore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "MINI");
        if (outputStore != null) m.put("output_store", outputStore);
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        return m;
    }

    @Test
    void absentMeansNull_neverADerivedDefault() throws Exception {
        assertNull(PipelineConfig.fromMap(minimal(null)).outputStore());
    }

    @Test
    void normalisedLikeStream() throws Exception {
        assertEquals("orders_shaped", PipelineConfig.fromMap(minimal(" Orders Shaped ")).outputStore());
    }

    @Test
    void aNonIdentifierIsRejectedAtParse() {
        assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(minimal("bad;drop--name")));
    }
}

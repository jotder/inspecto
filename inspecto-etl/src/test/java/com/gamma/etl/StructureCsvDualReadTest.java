package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.gamma.etl.PipelineConfigBatchTest.writePipeline;
import static org.junit.jupiter.api.Assertions.*;

/**
 * STRUCTURE-CSV-1 (ELT final amendment §3.2 first table, built 2026-09-06): a sibling
 * {@code <name>_structure.csv} beside a schema file <b>overrides</b> the schema's inline {@code raw.fields}
 * at the same merge point the {@code _mapping.csv} dual-read uses. Additive — no sibling, no change. The
 * schema file is the helper's {@code mini_schema.toon}, so the sibling is {@code mini_structure.csv}.
 */
class StructureCsvDualReadTest {

    @Test
    void siblingStructureCsvOverridesInlineFields(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mini_structure.csv"), """
                field,type,selector,unit,description,classification
                ACCOUNT_NUMBER,VARCHAR,account,,"Account, as billed",PII
                EVENT_DATE,DATE,event_date,,,
                ZZ_FROM_CSV,DOUBLE,amt,USD,,
                """, StandardCharsets.UTF_8);

        List<Map<String, Object>> fields = fields(load(dir));
        assertEquals(3, fields.size(), "the CSV's 3 fields must replace the schema's inline list");
        assertEquals("Account, as billed", fields.get(0).get("description"), "a quoted cell keeps its comma");
        assertEquals("PII", fields.get(0).get("classification"));
        assertEquals(Map.of("name", "EVENT_DATE", "selector", "event_date", "type", "DATE"), fields.get(1));
        assertEquals("USD", fields.get(2).get("unit"));
    }

    @Test
    void withoutASiblingFileTheInlineFieldsLoadUnchanged(@TempDir Path dir) throws Exception {
        assertTrue(fields(load(dir)).stream().noneMatch(f -> "ZZ_FROM_CSV".equals(f.get("name"))),
                "the helper's mini_schema.toon fields load as authored");
    }

    @Test
    void aBadStructureHeaderFailsFastNamingTheFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mini_structure.csv"), "field,selector\nA,0\n", StandardCharsets.UTF_8);
        Exception e = assertThrows(Exception.class, () -> load(dir));
        assertTrue(String.valueOf(e.getMessage()).contains("mini_structure.csv"), e.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(PipelineConfig cfg) {
        Map<String, Object> raw = (Map<String, Object>) cfg.schemas().single().get("raw");
        return (List<Map<String, Object>>) raw.get("fields");
    }

    private static PipelineConfig load(Path configDir) throws Exception {
        return PipelineConfig.load(writePipeline(configDir, "").toString());
    }
}

package com.gamma.util;

import dev.toonformat.jtoon.JToon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MAPPING-GEN-1 (decided 2026-09-10): {@code create-schema} — the only generator that writes to disk — must
 * emit the Record Transformer field list ({@code mapping.fields[]} with the {@code fn} marker), the spelling
 * every committed schema uses, never the legacy {@code mapping.rules[]} the engine reads only through a
 * back-compatibility bridge. Drives the real {@link SchemaExtractor#run} write path over a sample CSV.
 */
class SchemaExtractorFieldListTest {

    @Test
    @SuppressWarnings("unchecked")
    void createSchemaWritesAFieldListWithTheKeepMarkerAndNoLegacyRules(@TempDir Path dir) throws Exception {
        Path sample = dir.resolve("acct.csv");
        Files.writeString(sample, """
                ACCOUNT_NUMBER,AMOUNT,REVERSAL_DATE
                A-1,10.5,2020-04-03
                A-2,7.25,2020-04-04
                """, StandardCharsets.UTF_8);
        Path gen = dir.resolve("gen_config.toon");
        Files.writeString(gen, """
                csv_settings:
                  delimiter: ","
                  date_formats[1]: "%Y-%m-%d"
                """, StandardCharsets.UTF_8);

        SchemaExtractor.run("acct", sample.toString(), gen.toString());

        Path schema = dir.resolve("acct_schema.toon");
        assertTrue(Files.exists(schema), "the generator writes <output>/<source>_schema.toon beside the gen config");
        Map<String, Object> cfg = (Map<String, Object>) JToon.decode(Files.readString(schema, StandardCharsets.UTF_8));
        Map<String, Object> mapping = (Map<String, Object>) cfg.get("mapping");

        assertNull(mapping.get("rules"), "the legacy rules[] spelling must not be written any more: " + mapping);
        List<Map<String, Object>> fields = (List<Map<String, Object>>) mapping.get("fields");
        assertNotNull(fields, "mapping.fields[] is the emitted shape: " + mapping);
        assertEquals(3, fields.size());
        for (Map<String, Object> f : fields) {
            assertEquals(f.get("name"), f.get("from"), "a generated field is a pass-through: " + f);
            assertEquals("keep", f.get("fn"), "the fn marker is what makes it a field list: " + f);
            assertFalse(f.containsKey("transformType"), "transformType belongs to the legacy spelling only: " + f);
        }
        assertEquals(List.of("ACCOUNT_NUMBER", "AMOUNT", "REVERSAL_DATE"),
                fields.stream().map(f -> f.get("name")).toList(), "one field per raw column, in header order");
        // the raw side is unchanged by this decision
        List<Map<String, Object>> raw = (List<Map<String, Object>>) ((Map<String, Object>) cfg.get("raw")).get("fields");
        assertEquals(3, raw.size());
    }
}

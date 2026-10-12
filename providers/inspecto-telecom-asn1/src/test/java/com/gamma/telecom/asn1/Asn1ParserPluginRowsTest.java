package com.gamma.telecom.asn1;

import com.gamma.parse.ParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PARSING-PREVIEW-DERIVED-1: {@link Asn1ParserPlugin#previewRows} builds the derived columns and the exploded child rows
 * through the ingester's own row path, over the tracked SYNTHETIC {@code repeated_child} corpus (invented values only).
 */
class Asn1ParserPluginRowsTest {

    private static final Path CASE = Path.of("..", "asn-parser", "corpus-synthetic", "repeated_child");

    private static final String RECORD_SCHEMA = """
            partitionKey: ID
            raw:
              name: rec
              format: CSV
              fields[1]{name,selector,type}:
                ID,id,VARCHAR
            mapping:
              canonicalName: rec
              rawName: rec
              rules[1]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
            """;

    private static final String CHILD_SCHEMA = """
            partitionKey: PARENT_ID
            raw:
              name: use
              format: CSV
              fields[3]{name,selector,type}:
                PARENT_ID,$parent.id,VARCHAR
                IDX,$index,VARCHAR
                UP,up,VARCHAR
            mapping:
              canonicalName: use
              rawName: use
              rules[1]{targetColumn,sourceExpression,transformType}:
                PARENT_ID,PARENT_ID,DIRECT
            """;

    private final Asn1ParserPlugin plugin = new Asn1ParserPlugin();

    @Test
    void aDeriveAddsItsColumnToTheRecordRows(@TempDir Path dir) throws Exception {
        Map<String, Object> grammar = grammar(dir, Map.of("derive", Map.of("Record",
                Map.of("USAGE_COUNT", Map.of("reduce", "usage.usage", "op", "count")))));

        ParseResult.Rows rows = plugin.previewRows(sample(), grammar, dir).orElseThrow();

        assertEquals(2, rows.segments().size(), "every declared segment is listed");
        assertEquals(0, rows.segments().get(1).rowCount(), "Usage has no rows without a children block");
        ParseResult.RowSet record = rows.segments().get(0);
        assertEquals("Record", record.segment());
        assertFalse(record.child());
        assertEquals(List.of("ID", "USAGE_COUNT", "EVENT_TYPE"), record.columns());
        assertEquals(List.of("USAGE_COUNT"), record.derivedColumns());
        assertEquals(2, record.rowCount());
        assertEquals("3", record.rows().get(0).get("USAGE_COUNT"));
        assertEquals("5", record.rows().get(0).get("ID"));
    }

    @Test
    void childrenExplodeIntoTaggedChildRowsAfterTheirParent(@TempDir Path dir) throws Exception {
        Map<String, Object> grammar = grammar(dir, Map.of("children",
                Map.of("Record", Map.of("usage.usage", "Usage"))));

        ParseResult.Rows rows = plugin.previewRows(sample(), grammar, dir).orElseThrow();

        assertEquals(2, rows.segments().size());
        ParseResult.RowSet record = rows.segments().get(0);
        ParseResult.RowSet usage = rows.segments().get(1);
        assertFalse(record.child());
        assertEquals(2, record.rowCount());
        assertEquals("Usage", usage.segment());
        assertTrue(usage.child());
        assertEquals(List.of("PARENT_ID", "IDX", "UP", "EVENT_TYPE"), usage.columns());
        assertEquals(3, usage.rowCount());
        assertEquals("100", usage.rows().get(2).get("UP"));
        assertEquals("2", usage.rows().get(2).get("IDX"));
        assertEquals("5", usage.rows().get(2).get("PARENT_ID"));
    }

    @Test
    void theSampleIsBoundedButTheCountIsNot(@TempDir Path dir) throws Exception {
        Map<String, Object> grammar = grammar(dir, Map.of("children",
                Map.of("Record", Map.of("usage.usage", "Usage"))));
        asn1(grammar).put("max_records", 1);

        ParseResult.Rows rows = plugin.previewRows(sample(), grammar, dir).orElseThrow();

        assertEquals(1, rows.segments().get(0).rowCount(), "only the first record is previewed");
    }

    @Test
    void aGrammarThatDeclaresNeitherGivesNoVariant(@TempDir Path dir) throws Exception {
        Optional<ParseResult.Rows> rows = plugin.previewRows(sample(), grammar(dir, Map.of()), dir);
        assertTrue(rows.isEmpty());
    }

    @Test
    void aDeriveWithoutSegmentsIsRefusedNotSilentlyEmpty(@TempDir Path dir) throws Exception {
        Map<String, Object> grammar = grammar(dir, Map.of("derive", Map.of("Record",
                Map.of("N", Map.of("reduce", "usage.usage", "op", "count")))));
        asn1(grammar).remove("segments");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> plugin.previewRows(sample(), grammar, dir));
        assertTrue(e.getMessage().contains("asn1.segments"), e.getMessage());
    }

    // helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asn1(Map<String, Object> grammar) {
        return (Map<String, Object>) grammar.get("asn1");
    }

    private static Map<String, Object> grammar(Path dir, Map<String, Object> extra) throws Exception {
        Path rec = dir.resolve("rec_schema.toon");
        Files.writeString(rec, RECORD_SCHEMA, StandardCharsets.UTF_8);
        Path use = dir.resolve("use_schema.toon");
        Files.writeString(use, CHILD_SCHEMA, StandardCharsets.UTF_8);
        Map<String, Object> segments = new LinkedHashMap<>();
        segments.put("Record", "rec_schema.toon");
        segments.put("Usage", "use_schema.toon");
        Map<String, Object> asn1 = new LinkedHashMap<>();
        asn1.put("grammar", Files.readString(CASE.resolve("grammar.asn"), StandardCharsets.UTF_8));
        asn1.put("root_type", "Record");
        asn1.put("segments", segments);
        asn1.putAll(extra);
        Map<String, Object> grammar = new LinkedHashMap<>();
        grammar.put("asn1", asn1);
        return grammar;
    }

    private static byte[] sample() throws Exception {
        StringBuilder hex = new StringBuilder();
        for (String line : Files.readAllLines(CASE.resolve("data.hex"), StandardCharsets.UTF_8))
            if (!line.startsWith("#")) hex.append(line);
        return HexFormat.of().parseHex(hex.toString().replaceAll("\\s", ""));
    }
}

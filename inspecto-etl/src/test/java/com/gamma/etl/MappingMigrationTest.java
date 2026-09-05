package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Record Transformer is the ONLY projection spelling: every stored schema under {@code spaces/} must
 * carry {@code mapping.fields[]} (no {@code rules[]} anywhere), and every one must compile. The converter
 * {@link RecordTransform#fromMappingRules} stays as the read-time bridge for schemas written by older
 * builds, and its equivalence rules are asserted per type below.
 *
 * <p>🔴 Until 2026-09-05 this class asserted byte-identical SQL for every rules-bearing stored schema —
 * that equality was the migration's whole safety argument, and it held for 14 schemas / 799 rules. Now
 * that the corpus is migrated the invariant is simpler: nothing legacy may come back.
 */
class MappingMigrationTest {

    private static final PipelineConfig.CsvSettings CSV =
            PipelineConfig.CsvSettings.ofFormats(List.of("%Y-%m-%d"), List.of("%Y-%m-%d %H:%M:%S"));

    /** The repo root — the ancestor holding {@code spaces/}. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent())
            if (Files.isDirectory(dir.resolve("spaces"))) return dir;
        throw new AssertionError("cannot locate the repo root from " + Path.of("").toAbsolutePath());
    }

    /** Every committed schema carrying a mapping block — the real corpus, not a fixture. */
    private static List<Path> storedSchemas() throws IOException {
        try (Stream<Path> walk = Files.walk(repoRoot().resolve("spaces"))) {
            return walk.filter(p -> p.toString().endsWith(".toon"))
                    .filter(p -> {
                        try {
                            String s = Files.readString(p);
                            return s.contains("mapping:");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .sorted()
                    .toList();
        }
    }

    @Test
    void everyStoredSchemaIsOnFieldsAndCompiles() throws Exception {
        List<Path> schemas = storedSchemas();
        assertFalse(schemas.isEmpty(), "the corpus must not be empty — this test would prove nothing");

        List<String> checked = new ArrayList<>();
        for (Path p : schemas) {
            String rel = repoRoot().relativize(p).toString();
            Map<String, Object> schema = com.gamma.config.io.ConfigCodec.toMap(Files.readString(p));
            if (!(schema.get("mapping") instanceof Map<?, ?> mapping)) continue;

            assertNull(mapping.get("rules"), rel + " still carries mapping.rules[] — transform.map is gone; "
                    + "run com.gamma.etl.MappingMigrator on it");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) mapping.get("fields");
            assertNotNull(fields, rel + " has a mapping block with no fields[]");
            assertTrue(RecordTransform.isFieldList(fields), rel + ": fields[] is not a Record Transformer field list");

            List<Map<String, Object>> compiled = DataTransformer.dataColumns(schema, CSV, "raw_input");
            assertEquals(fields.size(), compiled.size(), rel + " compiled to a different column count");
            checked.add(rel + " (" + fields.size() + " fields)");
        }

        assertFalse(checked.isEmpty(), "no schema was actually compiled");
        System.out.println("[record-transformer] " + checked.size() + " stored schemas compile: " + checked);
    }

    /** The two date rules convert to their catalog functions and stay audited on the date column. */
    @Test
    void theDateRulesConvertToTheirCatalogFunctions() {
        List<Map<String, Object>> fields = RecordTransform.fromMappingRules(List.of(
                Map.of("targetColumn", "EVENT_TIME", "sourceExpression", "D|T", "transformType", "CONCAT_DT"),
                Map.of("targetColumn", "EVENT_DATE", "sourceExpression", "F|data_|%Y%m%d",
                        "transformType", "FILENAME_DATE")));

        Map<String, Object> concat = fields.get(0);
        assertEquals("date.concat_parts", concat.get("fn"));
        assertEquals("D", concat.get("from"));
        assertEquals("T", ((Map<?, ?>) concat.get("args")).get("time_column"));
        assertEquals("D", RecordTransform.auditedSourceColumn(concat, Map.of("D", "DATE")),
                "CONCAT_DT measured its date half; the conversion must keep that coverage");

        Map<String, Object> fromFile = fields.get(1);
        assertEquals("date.from_filename", fromFile.get("fn"));
        assertEquals("F", fromFile.get("from"));
        assertEquals(Map.of("pattern", "data_([0-9]{8})", "format", "%Y%m%d"), fromFile.get("args"));

        List<Map<String, Object>> compiled = RecordTransform.compile(fields, Map.of("D", "DATE"), CSV,
                SourceZones.of(Map.of(), null), "raw_input", false);
        assertEquals("TRY_STRPTIME(regexp_extract(F, 'data_([0-9]{8})', 1), '%Y%m%d')::DATE",
                compiled.get(1).get("expr"));
    }

    /** A block with an EXPR row is rewritten in the block-list form, and the result parses and compiles the same. */
    @Test
    void theMigratorWritesTheBlockListFormWhenARowNeedsArgs() throws IOException {
        Path dir = Files.createTempDirectory("mig");
        Path schema = dir.resolve("orders_schema.toon");
        Files.writeString(schema, """
                raw:
                  name: ORDERS
                  format: CSV
                  fields[3]{name,selector,type}:
                    ORDER_ID,0,VARCHAR
                    REGION,1,VARCHAR
                    QUANTITY,2,BIGINT
                mapping:
                  canonicalName: orders
                  rawName: ORDERS
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    ORDER_ID,ORDER_ID,DIRECT
                    REGION,"UPPER(TRIM(REGION))",EXPR
                    QUANTITY,QUANTITY,DIRECT
                """);

        MappingMigrator.Result r = MappingMigrator.migrate(schema, false);
        assertNull(r.problem(), r.problem());
        assertTrue(r.changed());

        String after = Files.readString(schema);
        assertFalse(after.contains("rules["), after);
        assertTrue(after.contains("  fields[3]:\n    - name: ORDER_ID\n      from: ORDER_ID\n      fn: keep\n"), after);
        assertTrue(after.contains("    - name: REGION\n      from: \"\"\n      fn: custom\n      args:\n"
                + "        expression: \"UPPER(TRIM(REGION))\"\n"), after);
        assertTrue(after.startsWith("raw:\n  name: ORDERS"), "lines outside the block must survive: " + after);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) com.gamma.config.io.ConfigCodec.toMap(after);
        List<Map<String, Object>> cols = DataTransformer.dataColumns(parsed, CSV, "raw_input");
        assertEquals(List.of("ORDER_ID", "REGION", "QUANTITY"), cols.stream().map(c -> c.get("name")).toList());
        assertEquals("UPPER(TRIM(REGION))", cols.get(1).get("expr"));
    }

    /** EXPR converts to `custom`, and both are excluded from the audit — coverage is unchanged. */
    @Test
    void anExprRuleConvertsToCustomAndStaysUnaudited() {
        List<Map<String, Object>> fields = RecordTransform.fromMappingRules(List.of(
                Map.of("targetColumn", "cents", "sourceExpression", "amt * 100", "transformType", "EXPR")));

        assertEquals("custom", fields.get(0).get("fn"));
        assertEquals(Map.of("expression", "amt * 100"), fields.get(0).get("args"));
        assertNull(RecordTransform.auditedSourceColumn(fields.get(0), Map.of("amt", "DOUBLE")),
                "an EXPR rule was unaudited and its custom conversion must stay unaudited — "
                        + "the migration must neither gain nor lose audit coverage");
    }
}

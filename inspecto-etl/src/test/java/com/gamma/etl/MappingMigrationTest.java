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
 * Phase 4 — converting a stored {@code mapping.rules[]} to {@code fields[]} must not change the SQL
 * that runs. This walks EVERY schema committed under {@code spaces/} and asserts the compiled
 * projection is byte-identical before and after conversion.
 *
 * <p>🔴 That equality IS the migration's safety argument. A converter that merely "looks right" would
 * silently re-type a column or drop a coercion across 799 rules in three spaces; comparing the emitted
 * SQL is the only check that cannot be fooled by a plausible-looking field list.
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

    /** Every committed schema carrying a mapping with rules — the real corpus, not a fixture. */
    private static List<Path> storedSchemas() throws IOException {
        try (Stream<Path> walk = Files.walk(repoRoot().resolve("spaces"))) {
            return walk.filter(p -> p.toString().endsWith(".toon"))
                    .filter(p -> {
                        try {
                            String s = Files.readString(p);
                            return s.contains("mapping:") && s.contains("rules[");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .sorted()
                    .toList();
        }
    }

    @Test
    void everyStoredSchemaConvertsToFieldsWithoutChangingTheSql() throws Exception {
        List<Path> schemas = storedSchemas();
        assertFalse(schemas.isEmpty(), "the corpus must not be empty — this test would prove nothing");

        List<String> checked = new ArrayList<>();
        for (Path p : schemas) {
            Map<String, Object> schema = com.gamma.config.io.ConfigCodec.toMap(Files.readString(p));
            if (!(schema.get("mapping") instanceof Map<?, ?> mapping)) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rules = (List<Map<String, Object>>) mapping.get("rules");
            if (rules == null || rules.isEmpty()) continue;

            // What runs today, from the legacy spelling.
            List<Map<String, Object>> before = DataTransformer.dataColumns(schema, CSV, "raw_input");

            // The same schema with its rules converted to the Record Transformer spelling.
            Map<String, Object> converted = new LinkedHashMap<>(schema);
            Map<String, Object> newMapping = new LinkedHashMap<>();
            mapping.forEach((k, v) -> newMapping.put(String.valueOf(k), v));
            newMapping.remove("rules");
            newMapping.put("fields", RecordTransform.fromMappingRules(rules));
            converted.put("mapping", newMapping);

            List<Map<String, Object>> after = DataTransformer.dataColumns(converted, CSV, "raw_input");

            assertEquals(before, after,
                    "converting " + repoRoot().relativize(p) + " changed the compiled projection");
            checked.add(repoRoot().relativize(p).toString() + " (" + rules.size() + " rules)");
        }

        assertFalse(checked.isEmpty(), "no schema was actually compared");
        System.out.println("[migration] byte-identical projection for " + checked.size()
                + " stored schemas: " + checked);
    }

    /**
     * ⛔ The two spellings with no faithful equivalent are refused by NAME rather than approximated —
     * hand-writing them as `custom` would silently move those columns out of the cast-failure audit.
     */
    @Test
    void aRuleWithNoFaithfulEquivalentIsRefusedRatherThanApproximated() {
        for (String type : List.of("CONCAT_DT", "FILENAME_DATE")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> RecordTransform.fromMappingRules(List.of(Map.of(
                            "targetColumn", "EVENT_TIME", "sourceExpression", "D|T", "transformType", type))));
            assertTrue(e.getMessage().contains(type), e.getMessage());
            assertTrue(e.getMessage().contains("EVENT_TIME"), "the refusal must name the rule: " + e.getMessage());
        }
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

package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A schema whose {@code raw} block has no {@code fields[]} — its structure normally comes from a sibling
 * {@code <name>_structure.csv} the loader merges in — must fail compilation with an error that names the
 * schema and the missing list, not a {@code NullPointerException} from deep inside the compiler.
 */
class DataTransformerMissingRawFieldsTest {

    private static final PipelineConfig.CsvSettings CSV =
            PipelineConfig.CsvSettings.ofFormats(List.of("%Y-%m-%d"), List.of("%Y-%m-%d %H:%M:%S"));

    @Test
    void aSchemaWithNoRawFieldsFailsNamingTheSchemaAndTheMissingList() {
        Map<String, Object> schema = Map.of(
                "raw", Map.of("name", "matches", "format", "CSV"),
                "mapping", Map.of("canonicalName", "matches",
                        "fields", List.of(Map.of("name", "MATCH_ID", "from", "MATCH_ID", "fn", "keep"))));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DataTransformer.dataColumns(schema, CSV, "raw_input"));
        assertTrue(e.getMessage().contains("schema 'matches'"), e.getMessage());
        assertTrue(e.getMessage().contains("no raw.fields[]"), e.getMessage());
        assertTrue(e.getMessage().contains("_structure.csv"), "the error must point at the fix: " + e.getMessage());
    }
}

package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pipeline editor's Record Transformer slot saves {@code processing.map.fields[]}; Stage-1 ingest
 * projects only the schema's {@code mapping}. The loader must carry the slot's rows onto the schema, or a
 * calculated column is saved but never lands (found piloting the football demo: 64 authored fields, a
 * 42-column parquet).
 */
class MapFieldsIngestTest {

    private static final PipelineConfig.CsvSettings CSV =
            PipelineConfig.CsvSettings.ofFormats(List.of("%Y-%m-%d"), List.of("%Y-%m-%d %H:%M:%S"));

    private static final String SCHEMA = """
            raw:
              name: ev
              format: CSV
              fields[2]{name,selector,type}:
                ACCOUNT,"account",VARCHAR
                AMT,"amt",VARCHAR
            mapping:
              canonicalName: ev
              rawName: ev
              rules[2]{targetColumn,sourceExpression,transformType}:
                ACCOUNT,ACCOUNT,DIRECT
                AMT,AMT,DIRECT
            """;

    private static final String MAP_BLOCK = """
              map:
                fields[3]:
                  - id: r-0
                    name: ACCOUNT
                    from: ACCOUNT
                    fn: keep
                  - id: r-1
                    name: AMT
                    from: AMT
                    fn: keep
                  - id: r-2
                    name: GROSS
                    from: ""
                    fn: custom
                    args:
                      expression: "TRY_CAST(AMT AS DOUBLE) / 100"
            """;

    @Test
    @SuppressWarnings("unchecked")
    void mapFieldsBecomeTheSchemaProjectionThatIngestCompiles(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, MAP_BLOCK);
        Map<String, Object> schema = cfg.schemas().single();
        List<Map<String, Object>> fields = (List<Map<String, Object>>) ((Map<String, Object>) schema.get("mapping")).get("fields");
        assertNotNull(fields, "processing.map.fields must be carried onto mapping.fields");
        assertEquals(3, fields.size());

        String sql = DataTransformer.dataColumns(schema, CSV, "raw_input").toString();
        assertTrue(sql.contains("GROSS"), "the calculated column must be projected at ingest: " + sql);
        assertTrue(sql.contains("TRY_CAST(AMT AS DOUBLE) / 100"), sql);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutAMapBlockTheSchemaMappingIsUnchanged(@TempDir Path dir) throws Exception {
        Map<String, Object> mapping = (Map<String, Object>) load(dir, "").schemas().single().get("mapping");
        assertNull(mapping.get("fields"));
        assertEquals(2, ((List<?>) mapping.get("rules")).size());
    }

    private static PipelineConfig load(Path dir, String processingExtra) throws Exception {
        Files.writeString(dir.resolve("ev_schema.toon"), SCHEMA, StandardCharsets.UTF_8);
        String d = dir.toString().replace('\\', '/');
        Path pipeline = dir.resolve("ev_pipeline.toon");
        Files.writeString(pipeline, """
                name: EV_ETL
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status
                output:
                  format: PARQUET
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schema_file: ev_schema.toon
                %s""".formatted(d, d, d, d, d, d, d, processingExtra).stripTrailing() + "\n",
                StandardCharsets.UTF_8);
        return PipelineConfig.load(pipeline.toString());
    }
}

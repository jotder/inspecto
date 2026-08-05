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
 * ELT final amendment Phase 1 slice 1 ({@code docs/superpower/elt-final-amendment-plan.md} §8): a
 * sibling {@code <name>_mapping.csv} beside a schema file <b>overrides</b> the schema's inline
 * {@code mapping.rules} at the {@code resolveSchemaRef} merge point. Additive — no sibling file,
 * no behaviour change (the whole pre-existing fixture corpus stays untouched by design).
 */
class MappingCsvDualReadTest {

    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: ev
              format: CSV
              fields[2]{name,selector,type}:
                ACCOUNT_NUMBER,"account",VARCHAR
                EVENT_DATE,"event_date",DATE
            mapping:
              canonicalName: ev
              rawName: ev
              rules[2]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """;

    @Test
    void siblingMappingCsvOverridesInlineRules(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("orders_schema.toon"), SCHEMA, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("orders_mapping.csv"), """
                targetColumn,sourceExpression,transformType
                ACCOUNT_NUMBER,"UPPER(TRIM(ACCOUNT_NUMBER))",EXPR
                EVENT_DATE,EVENT_DATE,DIRECT
                GROSS,"TRY_CAST(AMT AS DOUBLE) / 100",EXPR
                """, StandardCharsets.UTF_8);

        List<Map<String, String>> rules = rules(load(dir));
        assertEquals(3, rules.size(), "the CSV's 3 rules must replace the schema's inline 2");
        assertEquals("UPPER(TRIM(ACCOUNT_NUMBER))", rules.get(0).get("sourceExpression"),
                "a quoted expression must carry its commas/content verbatim");
        assertEquals("EXPR", rules.get(0).get("transformType"));
        assertEquals("GROSS", rules.get(2).get("targetColumn"));
    }

    @Test
    void withoutASiblingFileTheInlineRulesLoadUnchanged(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("orders_schema.toon"), SCHEMA, StandardCharsets.UTF_8);
        List<Map<String, String>> rules = rules(load(dir));
        assertEquals(2, rules.size());
        assertEquals("ACCOUNT_NUMBER", rules.get(0).get("targetColumn"));
    }

    @Test
    void aBlankTransformTypeMeansDirect(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("orders_schema.toon"), SCHEMA, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("orders_mapping.csv"), """
                targetColumn,sourceExpression,transformType
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,
                """, StandardCharsets.UTF_8);
        assertEquals("", rules(load(dir)).get(0).get("transformType"),
                "blank transformType travels as-is; TransformCompiler treats blank as DIRECT");
    }

    @Test
    void aCsvTargetColumnCountsAsADeclaredColumn(@TempDir Path dir) throws Exception {
        // reference.key must validate against the MERGED rules — proof the merge happens
        // before declaredColumns is accumulated, not just before DataTransformer.
        Files.writeString(dir.resolve("orders_schema.toon"), SCHEMA, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("orders_mapping.csv"), """
                targetColumn,sourceExpression,transformType
                CSV_ONLY_KEY,ACCOUNT_NUMBER,DIRECT
                """, StandardCharsets.UTF_8);
        Path pipeline = writePipeline(dir, """
                reference:
                  key[1]: CSV_ONLY_KEY
                  load: upsert
                produces: reference
                """);
        assertDoesNotThrow(() -> PipelineConfig.load(pipeline.toString()));
    }

    @Test
    void aBadHeaderFailsFastNamingTheFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("orders_schema.toon"), SCHEMA, StandardCharsets.UTF_8);
        // NB: target/source/kind are accepted read aliases (MappingCsv) — this header is truly wrong.
        Files.writeString(dir.resolve("orders_mapping.csv"), """
                colA,colB
                A,B
                """, StandardCharsets.UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load(dir));
        assertTrue(e.getMessage().contains("orders_mapping.csv"), e.getMessage());
        assertTrue(e.getMessage().contains("targetColumn"), e.getMessage());
    }

    @Test
    void anInvalidCsvTargetIdentifierIsStillRejectedByValidateSchema(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("orders_schema.toon"), SCHEMA, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("orders_mapping.csv"), """
                targetColumn,sourceExpression,transformType
                bad-name!,ACCOUNT_NUMBER,DIRECT
                """, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> load(dir),
                "merged rules must pass through Identifiers.validateSchema like inline ones");
    }

    // ── slice 3: registry refs + explicit mapping_file ─────────────────────────

    @Test
    void aSchemaRegistryRefResolvesToTheRegistryCopy(@TempDir Path dir) throws Exception {
        // schema/<id> → registry/schemas/<id>.toon — the wiring that makes an id-addressed
        // schema component executable (the W1 objection, resolved).
        Path schemas = Files.createDirectories(dir.resolve("registry/schemas"));
        Files.writeString(schemas.resolve("orders_v1.toon"), SCHEMA, StandardCharsets.UTF_8);
        Path pipeline = writePipelineWithSchemaRef(dir, "schema/orders_v1", "");
        assertDoesNotThrow(() -> PipelineConfig.load(pipeline.toString()));
    }

    @Test
    void anExplicitMappingFileWinsOverTheSibling(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("orders_schema.toon"), SCHEMA, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("orders_mapping.csv"), """
                targetColumn,sourceExpression,transformType
                FROM_SIBLING,ACCOUNT_NUMBER,DIRECT
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("explicit.csv"), """
                targetColumn,sourceExpression,transformType
                FROM_EXPLICIT,ACCOUNT_NUMBER,DIRECT
                """, StandardCharsets.UTF_8);
        Path pipeline = writePipelineWithSchemaRef(dir, "orders_schema.toon",
                "  mapping_file: explicit.csv");
        List<Map<String, String>> rules = rules(PipelineConfig.load(pipeline.toString()));
        assertEquals("FROM_EXPLICIT", rules.get(0).get("targetColumn"),
                "explicit mapping_file > sibling dual-read > inline rules");
    }

    @Test
    void aMappingRegistryRefResolvesToTheRegistryCsv(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("orders_schema.toon"), SCHEMA, StandardCharsets.UTF_8);
        Path mappings = Files.createDirectories(dir.resolve("registry/mappings"));
        Files.writeString(mappings.resolve("std.csv"), """
                targetColumn,sourceExpression,transformType
                FROM_REGISTRY,ACCOUNT_NUMBER,DIRECT
                """, StandardCharsets.UTF_8);
        Path pipeline = writePipelineWithSchemaRef(dir, "orders_schema.toon",
                "  mapping_file: mapping/std");
        List<Map<String, String>> rules = rules(PipelineConfig.load(pipeline.toString()));
        assertEquals("FROM_REGISTRY", rules.get(0).get("targetColumn"));
    }

    @Test
    void aDeclaredMappingFileThatDoesNotResolveFailsFast(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("orders_schema.toon"), SCHEMA, StandardCharsets.UTF_8);
        Path pipeline = writePipelineWithSchemaRef(dir, "orders_schema.toon",
                "  mapping_file: mapping/nope");
        assertThrows(java.io.FileNotFoundException.class,
                () -> PipelineConfig.load(pipeline.toString()),
                "unlike the best-effort sibling, an explicit reference must exist");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> rules(PipelineConfig cfg) {
        Map<String, Object> mapping = (Map<String, Object>) cfg.schemas().single().get("mapping");
        return (List<Map<String, String>>) mapping.get("rules");
    }

    private static PipelineConfig load(Path configDir) throws Exception {
        return PipelineConfig.load(writePipeline(configDir, "").toString());
    }

    /** Like {@link #writePipeline} but with a custom schema reference and an extra processing line. */
    private static Path writePipelineWithSchemaRef(Path configDir, String schemaRef,
                                                   String processingExtra) throws Exception {
        String d = configDir.toString().replace('\\', '/');
        Path pipeline = configDir.resolve("orders_pipeline.toon");
        Files.writeString(pipeline, """
                name: ORDERS_ETL
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
                  schema_file: %s
                %s
                """.formatted(d, d, d, d, d, d, d, schemaRef, processingExtra).stripTrailing() + "\n",
                StandardCharsets.UTF_8);
        return pipeline;
    }

    private static Path writePipeline(Path configDir, String extra) throws Exception {
        String d = configDir.toString().replace('\\', '/');
        Path pipeline = configDir.resolve("orders_pipeline.toon");
        Files.writeString(pipeline, """
                name: ORDERS_ETL
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
                  schema_file: orders_schema.toon
                %s
                """.formatted(d, d, d, d, d, d, d, extra.stripTrailing()), StandardCharsets.UTF_8);
        return pipeline;
    }
}

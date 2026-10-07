package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two {@code sinks[]} destinations whose EFFECTIVE ducklake (entry → {@code output.ducklake}) is one
 * catalog and whose REGISTERED table is one table are refused at {@link PipelineConfig#prepare()}
 * (operator decision 2026-09-23, {@code SINK-DUCKLAKE-SHARED-LAKE-DUPLICATES-1}): every batch would
 * insert both destinations' files into that table and duplicate its rows, silently.
 */
class SinkDuckLakeSharedTableTest {

    private static Map<String, Object> lake(String catalog, String table) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("enabled", true);
        l.put("catalog_url", catalog);
        l.put("data_path", "lake-data");
        if (table != null) l.put("table", table);
        return l;
    }

    private static Map<String, Object> sink(Path dir, String name, Map<String, Object> lake) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("database", dir.resolve(name).toString());
        if (lake != null) s.put("ducklake", lake);
        return s;
    }

    @SafeVarargs
    private static PipelineConfig config(Path dir, Map<String, Object> outLake, Map<String, Object> processing,
                                         Map<String, Object>... sinks) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "SHARED_LAKE_ETL");
        m.put("dirs", Map.of("poll", dir.resolve("in").toString(), "database", dir.resolve("out").toString()));
        m.put("processing", processing != null ? processing : Map.of("threads", 1));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("format", "parquet");
        if (outLake != null) output.put("ducklake", outLake);
        m.put("output", output);
        m.put("sinks", List.of(sinks));
        return PipelineConfig.fromMap(m);
    }

    @Test
    void twoSinksInheritingOneOutputLakeAreRefusedNamingBothAndTheFix(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir, lake("lake.ducklake", "orders"), null,
                sink(dir, "hot", null), sink(dir, "cold", null));

        IllegalStateException boom = assertThrows(IllegalStateException.class, cfg::prepare);
        String msg = boom.getMessage();
        assertTrue(msg.contains(dir.resolve("hot").toString()) && msg.contains(dir.resolve("cold").toString()),
                "the refusal must name BOTH destinations: " + msg);
        assertTrue(msg.contains("lake.ducklake") && msg.contains("main.orders"), msg);
        assertTrue(msg.contains("its own ducklake table"), "the refusal must suggest a per-sink table: " + msg);
    }

    @Test
    void twoSinksDeclaringTheSameLakeAndTableAreRefused(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir, null, null,
                sink(dir, "a", lake("lake.ducklake", "orders")), sink(dir, "b", lake(" lake.ducklake ", "orders")));
        assertThrows(IllegalStateException.class, cfg::prepare);
    }

    @Test
    void sameLakeWithDifferentTablesIsAllowed(@TempDir Path dir) throws Exception {
        // one sink overrides only the table; the other inherits output.ducklake's
        PipelineConfig cfg = config(dir, lake("lake.ducklake", "orders"), null,
                sink(dir, "hot", lake("lake.ducklake", "orders_hot")), sink(dir, "cold", null));
        assertDoesNotThrow(cfg::prepare);
    }

    @Test
    void sameLakeAndTableInDifferentSchemasIsAllowed(@TempDir Path dir) throws Exception {
        Map<String, Object> other = lake("lake.ducklake", "orders");
        other.put("schema", "archive");
        PipelineConfig cfg = config(dir, lake("lake.ducklake", "orders"), null,
                sink(dir, "hot", null), sink(dir, "cold", other));
        assertDoesNotThrow(cfg::prepare);
    }

    @Test
    void differentLakesAreAllowed(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir, lake("lake_a.ducklake", "orders"), null,
                sink(dir, "hot", null), sink(dir, "cold", lake("lake_b.ducklake", "orders")));
        assertDoesNotThrow(cfg::prepare);
    }

    @Test
    void aDisabledLakeRegistersNothingSoCannotCollide(@TempDir Path dir) throws Exception {
        Map<String, Object> off = lake("lake.ducklake", "orders");
        off.put("enabled", false);
        PipelineConfig cfg = config(dir, lake("lake.ducklake", "orders"), null,
                sink(dir, "hot", null), sink(dir, "cold", off));
        assertDoesNotThrow(cfg::prepare);
    }

    /**
     * {@code batch.table()} OVERRIDES the lake block's {@code table} key when the pipeline selects among
     * schemas, so per-sink tables do NOT separate two sinks sharing a catalog there — the table that would
     * actually be registered is {@code processing.schemas[].table}.
     */
    @Test
    void schemaTablesOverrideThePerSinkTableSoTheyStillCollide(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir, null, multiSchema(dir, "orders"),
                sink(dir, "hot", lake("lake.ducklake", "t_hot")), sink(dir, "cold", lake("lake.ducklake", "t_cold")));

        IllegalStateException boom = assertThrows(IllegalStateException.class, cfg::prepare);
        assertTrue(boom.getMessage().contains("main.orders"), boom.getMessage());
        assertTrue(boom.getMessage().contains("processing.schemas[].table"),
                "the refusal must say a per-sink table cannot help here: " + boom.getMessage());
    }

    @Test
    void aBlankSchemaTableFallsBackToThePerSinkTable(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir, null, multiSchema(dir, null),
                sink(dir, "hot", lake("lake.ducklake", "t_hot")), sink(dir, "cold", lake("lake.ducklake", "t_cold")));
        assertDoesNotThrow(cfg::prepare);
    }

    private static Map<String, Object> multiSchema(Path dir, String table) throws Exception {
        Path schema = dir.resolve("ev_schema.toon");
        Files.writeString(schema, """
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
                """, StandardCharsets.UTF_8);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("column_count", 2);
        entry.put("schema_file", schema.toString());
        if (table != null) entry.put("table", table);
        return Map.of("threads", 1, "schemas", List.of(entry));
    }
}

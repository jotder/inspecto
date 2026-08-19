package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plural {@code sinks:} config-format, slice 1 (model + parse). A single {@code output:} synthesises a
 * one-element {@link PipelineConfig.Sink} shorthand (so {@code sinks()} is never empty and existing configs
 * are unchanged); an explicit one-entry {@code sinks:} parses; a config naming more than one destination is
 * rejected at load, because multi-destination execution is not yet wired (see
 * {@code docs/superpower/sinks-config-format-plan.md}). Configs stay inactive drafts so {@code fromMap} is a
 * pure parse with no schema/disk requirement.
 */
class PipelineConfigSinksTest {

    private static Map<String, Object> base() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "SINK_ETL");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        return m;
    }

    @Test
    void singleOutputSynthesisesAOneElementSinkShorthand() throws Exception {
        Map<String, Object> m = base();
        m.put("output", Map.of("format", "parquet", "compression", "snappy"));

        PipelineConfig cfg = PipelineConfig.fromMap(m);

        assertEquals(1, cfg.sinks().size(), "single output: ⇒ one-element sinks()");
        PipelineConfig.Sink s = cfg.sinks().get(0);
        assertEquals(cfg.dirs().database(), s.database(), "shorthand carries dirs.database as the destination");
        assertEquals("PARQUET", s.format(), "output.format is upper-cased");
        assertEquals("snappy", s.compression());
        assertNull(s.duckLake());
    }

    @Test
    void noOutputBlockStillSynthesisesTheDefaultCsvSink() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(base());

        assertEquals(1, cfg.sinks().size());
        assertEquals("CSV", cfg.sinks().get(0).format(), "output.format default is CSV");
        assertEquals(cfg.dirs().database(), cfg.sinks().get(0).database());
    }

    @Test
    void anExplicitSingleSinkParses() throws Exception {
        Map<String, Object> m = base();
        m.put("sinks", List.of(Map.of("database", "out_hot", "format", "parquet")));

        PipelineConfig cfg = PipelineConfig.fromMap(m);

        assertEquals(1, cfg.sinks().size());
        assertEquals("out_hot", cfg.sinks().get(0).database(), "explicit sink names its own database");
        assertEquals("PARQUET", cfg.sinks().get(0).format());
    }

    @Test
    void twoSinksAreConstructibleAndRunnable() throws Exception {
        Map<String, Object> m = base();
        m.put("sinks", List.of(
                Map.of("database", "out_hot", "format", "parquet"),
                Map.of("database", "out_cold", "format", "csv")));

        PipelineConfig cfg = PipelineConfig.fromMap(m);
        assertEquals(2, cfg.sinks().size());
        assertEquals("out_hot", cfg.sinks().get(0).database());
        assertEquals("out_cold", cfg.sinks().get(1).database());

        // plain multi-destination is wired (slice 3) — the execution gate lets it through
        assertDoesNotThrow(cfg::prepare, "plain multi-destination ingest is runnable");
    }

    // ── B4: output.filename_column / sinks[].filename_column ────────────────────

    @Test
    void filenameColumnParsesOntoOutputAndTheSynthesisedSink() throws Exception {
        Map<String, Object> m = base();
        m.put("output", Map.of("format", "csv", "filename_column", "src_file"));

        PipelineConfig cfg = PipelineConfig.fromMap(m);

        assertEquals("src_file", cfg.output().filenameColumn());
        assertEquals("src_file", cfg.sinks().get(0).filenameColumn(),
                "the single-output shorthand carries the lineage column onto its sink");
    }

    @Test
    void perSinkFilenameColumnParses() throws Exception {
        Map<String, Object> m = base();
        m.put("sinks", List.of(
                Map.of("database", "out_hot", "format", "parquet", "filename_column", "origin_file"),
                Map.of("database", "out_cold", "format", "csv")));

        PipelineConfig cfg = PipelineConfig.fromMap(m);
        assertEquals("origin_file", cfg.sinks().get(0).filenameColumn());
        assertNull(cfg.sinks().get(1).filenameColumn(), "unset stays null — no column, no behavior change");
    }

    @Test
    void aNonIdentifierFilenameColumnFailsLoad() {
        Map<String, Object> m = base();
        m.put("output", Map.of("filename_column", "bad name!"));
        Exception e = assertThrows(IllegalArgumentException.class, () -> PipelineConfig.fromMap(m));
        assertTrue(e.getMessage().contains("filename_column"), e.getMessage());
    }

    /** A lineage column shadowing a declared data column would corrupt every written file — load fails. */
    @Test
    void filenameColumnCollidingWithASchemaColumnFailsLoad(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path schema = dir.resolve("s.toon");
        java.nio.file.Files.writeString(schema, """
                partitionKey: TXN_DATE
                raw:
                  name: t
                  format: CSV
                  fields[1]{name,selector,type}:
                    ID,"0",VARCHAR
                mapping:
                  canonicalName: t
                  rawName: t
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                """);
        java.nio.file.Path pipe = dir.resolve("fc_pipeline.toon");
        java.nio.file.Files.writeString(pipe, """
                name: FC_ETL
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                output:
                  format: CSV
                  filename_column: ID
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schema_file: %s
                """.formatted(dir, dir, schema.toString().replace('\\', '/')));
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.load(pipe.toString()));
        assertTrue(e.getMessage().contains("filename_column 'ID' collides"), e.getMessage());
    }

    @Test
    void aVersionedReferenceStoreWithMultipleSinksIsRefusedAtLoad() throws Exception {
        Map<String, Object> m = base();
        m.put("produces", "reference");
        m.put("reference", Map.of("load", "upsert", "key", List.of("id")));
        m.put("sinks", List.of(
                Map.of("database", "out_hot", "format", "parquet"),
                Map.of("database", "out_cold", "format", "parquet")));

        PipelineConfig cfg = PipelineConfig.fromMap(m);   // constructible
        assertEquals(2, cfg.sinks().size());

        // the one unsupported combo — a versioned reference store's single version history is ill-defined
        // across destinations — is refused at the execution gate (prepare())
        IllegalStateException ex = assertThrows(IllegalStateException.class, cfg::prepare);
        assertTrue(ex.getMessage().contains("reference"), ex.getMessage());
    }

    @Test
    void aSinkEntryWithoutADatabaseIsRejected() {
        Map<String, Object> m = base();
        m.put("sinks", List.of(Map.of("format", "csv")));   // no database

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(m));
        assertTrue(ex.getMessage().contains("database"), ex.getMessage());
    }
}

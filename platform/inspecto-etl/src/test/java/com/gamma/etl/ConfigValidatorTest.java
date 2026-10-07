package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConfigValidator}.
 *
 * <p>{@code ConfigValidator} runs after {@link PipelineConfig#load} and emits
 * non-fatal warnings for suspicious-but-legal patterns.  These tests load
 * configs that intentionally trigger each warning and confirm it's emitted.
 */
class ConfigValidatorTest {

    @Test
    void warnsWhenNoPartitionsDeclared(@TempDir Path dir) throws Exception {
        // Single schema, no partitionKey, no partitions[].
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, """
                raw:
                  name: x
                  format: CSV
                  fields[1]{name,selector,type}:
                    ID,"0",VARCHAR
                mapping:
                  canonicalName: x
                  rawName: x
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                """);
        PipelineConfig cfg = loadPipeline(dir, schema);
        List<String> warnings = ConfigValidator.validate(cfg);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("No partitions[] or partitionKey")),
                "Expected partitions warning. Got: " + warnings);
    }

    @Test
    void warnsWhenDateFormatsEmpty(@TempDir Path dir) throws Exception {
        Path schema = writeMinimalSchema(dir);
        Path pipeline = dir.resolve("pipeline.toon");
        Files.writeString(pipeline, basePipeline(dir, schema.toString().replace("\\", "/"))
                .replace("date_formats[1]: \"%Y-%m-%d\"", "")
                .replace("timestamp_formats[1]: \"%Y-%m-%d\"", ""));
        PipelineConfig cfg = PipelineConfig.load(pipeline.toString());
        List<String> warnings = ConfigValidator.validate(cfg);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("date_formats is empty")),
                "Expected date_formats warning. Got: " + warnings);
    }

    @Test
    void cleanConfigEmitsNoWarnings(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, """
                partitionKey: EVENT_DATE
                raw:
                  name: x
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID,"0",VARCHAR
                    EVENT_DATE,"1",DATE
                mapping:
                  canonicalName: x
                  rawName: x
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """);
        PipelineConfig cfg = loadPipeline(dir, schema);
        List<String> warnings = ConfigValidator.validate(cfg);
        assertTrue(warnings.isEmpty(),
                "Clean config should not emit warnings. Got: " + warnings);
    }

    @Test
    void oversubscriptionWarningFactorsInSourcesMax(@TempDir Path dir) throws Exception {
        // Explicit duckdb_threads: sources.max multiplies the worker pressure on top of
        // threads, and the warning must reflect that combined product.
        Path schema = writeMinimalSchema(dir);
        Path pipeline = dir.resolve("pipeline.toon");
        Files.writeString(pipeline, basePipeline(dir, schema.toString().replace("\\", "/"))
                .replace("threads: 1", "threads: 4\n  duckdb_threads: 64"));
        PipelineConfig cfg = PipelineConfig.load(pipeline.toString());

        String prev = System.getProperty("sources.max");
        System.setProperty("sources.max", "4");
        try {
            List<String> warnings = ConfigValidator.validate(cfg);
            assertTrue(warnings.stream().anyMatch(w -> w.contains("sources.max(4)") && w.contains("oversubscribe")),
                    "Expected sources.max-factored oversubscription warning. Got: " + warnings);
        } finally {
            if (prev == null) System.clearProperty("sources.max"); else System.setProperty("sources.max", prev);
        }
    }

    /** S5 (scheduler-system-config plan): in server mode the fleet factor is the broker's system
     *  Consignment cap, handed in as a supplier — the warning must fire at a cap the CLI's
     *  {@code -Dsources.max} never set, and must stay silent when no cap is installed. */
    @Test
    void oversubscriptionWarningUsesTheInstalledFleetConsignmentCap(@TempDir Path dir) throws Exception {
        Path schema = writeMinimalSchema(dir);
        Path pipeline = dir.resolve("pipeline.toon");
        Files.writeString(pipeline, basePipeline(dir, schema.toString().replace("\\", "/"))
                .replace("threads: 1", "threads: 1\n  duckdb_threads: 64"));
        PipelineConfig cfg = PipelineConfig.load(pipeline.toString());

        // No supplier installed (the CLI / default posture): the fleet-cap warning must not fire.
        ConfigValidator.fleetConsignmentCap(null);
        assertTrue(ConfigValidator.validate(cfg).stream().noneMatch(w -> w.contains("scheduler cap")),
                "No fleet cap installed — the S5 warning must stay silent");

        // Server posture: the hosting service installs the broker's live cap. 64 duckdb threads per
        // consignment × a cap of 1024 always exceeds any test host's cores.
        ConfigValidator.fleetConsignmentCap(() -> 1024);
        try {
            List<String> warnings = ConfigValidator.validate(cfg);
            assertTrue(warnings.stream().anyMatch(w -> w.contains("scheduler cap 1024") && w.contains("oversubscribe")),
                    "Expected the fleet-cap oversubscription warning. Got: " + warnings);
        } finally {
            ConfigValidator.fleetConsignmentCap(null);
        }
    }

    @Test
    void warnsAutoDuckdbThreadsBlindSpotUnderMultiSource(@TempDir Path dir) throws Exception {
        // duckdb_threads unset → 0 (auto). The auto cap (cores ÷ threads) ignores sources.max,
        // so under MultiCollectorProcessor with sources.max > 1 it still oversubscribes — surface it.
        Path schema = writeMinimalSchema(dir);
        Path pipeline = dir.resolve("pipeline.toon");
        Files.writeString(pipeline, basePipeline(dir, schema.toString().replace("\\", "/"))
                .replace("threads: 1", "threads: 2"));
        PipelineConfig cfg = PipelineConfig.load(pipeline.toString());
        assertEquals(0, cfg.processing().duckdbThreads(), "default duckdb_threads should be 0 (auto)");

        String prev = System.getProperty("sources.max");
        System.setProperty("sources.max", "3");
        try {
            List<String> warnings = ConfigValidator.validate(cfg);
            assertTrue(warnings.stream().anyMatch(w -> w.contains("sources.max=3") && w.contains("auto")),
                    "Expected multi-source auto blind-spot warning. Got: " + warnings);
        } finally {
            if (prev == null) System.clearProperty("sources.max"); else System.setProperty("sources.max", prev);
        }
    }

    @Test
    void noBlindSpotWarningWithoutSourcesMax(@TempDir Path dir) throws Exception {
        // Single-source (no sources.max property): auto duckdb_threads self-manages, so the
        // multi-source blind-spot warning must NOT fire.
        Path schema = writeMinimalSchema(dir);
        Path pipeline = dir.resolve("pipeline.toon");
        Files.writeString(pipeline, basePipeline(dir, schema.toString().replace("\\", "/"))
                .replace("threads: 1", "threads: 8"));
        PipelineConfig cfg = PipelineConfig.load(pipeline.toString());

        String prev = System.getProperty("sources.max");
        System.clearProperty("sources.max");
        try {
            List<String> warnings = ConfigValidator.validate(cfg);
            assertFalse(warnings.stream().anyMatch(w -> w.contains("sources.max")),
                    "No sources.max set → no multi-source warning. Got: " + warnings);
        } finally {
            if (prev != null) System.setProperty("sources.max", prev);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static PipelineConfig loadPipeline(Path dir, Path schema) throws Exception {
        Path pipeline = dir.resolve("pipeline.toon");
        Files.writeString(pipeline, basePipeline(dir, schema.toString().replace("\\", "/")));
        return PipelineConfig.load(pipeline.toString());
    }

    private static Path writeMinimalSchema(Path dir) throws Exception {
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, """
                partitionKey: EVENT_DATE
                raw:
                  name: x
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID,"0",VARCHAR
                    EVENT_DATE,"1",DATE
                mapping:
                  canonicalName: x
                  rawName: x
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """);
        return schema;
    }

    /**
     * WB-04 / {@code VALIDATE-CSV-RULE-FRONTEND-BLIND-1} — the three {@code csv_settings} rules apply to a
     * DELIMITED pipeline and to nothing else.
     *
     * <p>🔴 They used to fire unconditionally, so 6 of 26 shipped Pipelines carrying no
     * {@code csv_settings} block at all — JSON, Excel, ASN.1, fixed-width, XML and the parquet re-ingest —
     * were each born {@code clean:false} for a rule that cannot apply to them. A warning that is always
     * wrong for a whole class of configs trains authors to skip warnings, including the real ones this
     * same routine emits.
     */
    @Test
    void theCsvSettingsRulesDoNotFireOnANonDelimitedPipeline(@TempDir Path dir) throws Exception {
        Path schema = writeMinimalSchema(dir);
        Path pipeline = dir.resolve("json_pipeline.toon");
        // A JSON frontend with NO csv_settings block — the shape every non-delimited Pipeline authors.
        Files.writeString(pipeline, basePipeline(dir, schema.toString().replace("\\", "/"))
                .replaceAll("(?s)  csv_settings:.*", "")
                + "parsing:\n  frontend: json\n  json:\n    records_path: $\n");

        PipelineConfig cfg = PipelineConfig.load(pipeline.toString());
        List<String> warnings = ConfigValidator.validate(cfg);

        assertTrue(warnings.stream().noneMatch(w -> w.contains("csv_settings")),
                "a pipeline with no csv_settings block and a non-delimited frontend must not be told its "
                        + "csv_settings are wrong. Got: " + warnings);
    }

    /** The same rules must STILL fire for a delimited pipeline — the gate narrows the scope, not the rule. */
    @Test
    void theCsvSettingsRulesStillFireOnADelimitedPipeline(@TempDir Path dir) throws Exception {
        Path schema = writeMinimalSchema(dir);
        Path pipeline = dir.resolve("delimited_pipeline.toon");
        Files.writeString(pipeline, basePipeline(dir, schema.toString().replace("\\", "/"))
                .replace("date_formats[1]: \"%Y-%m-%d\"", "")
                .replace("timestamp_formats[1]: \"%Y-%m-%d\"", ""));

        PipelineConfig cfg = PipelineConfig.load(pipeline.toString());
        List<String> warnings = ConfigValidator.validate(cfg);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("date_formats is empty")),
                "WB-04 must not have silenced the rule where it DOES apply — that would trade a false "
                        + "positive for a false negative. Got: " + warnings);
    }

    /**
     * {@code PARTITION-KEY-VALIDATION-GAPS-1} (a) — a partition is cut from the RAW relation, before mapping,
     * so a {@code partitionKey} naming a column only the mapping produces cannot bind. It used to validate
     * clean and then fail at run time with a DuckDB binder error.
     */
    @Test
    void aPartitionKeyNamingAMappedOnlyColumnIsReported(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, """
                partitionKey: POSTING_DATE
                raw:
                  name: x
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID,"0",VARCHAR
                    POSTING_SERIAL,"1",INTEGER
                mapping:
                  canonicalName: x
                  rawName: x
                  fields[2]:
                    - name: ID
                      from: ID
                      fn: keep
                    - name: POSTING_DATE
                      from: ""
                      fn: custom
                      args:
                        expression: "CAST(DATE '1899-12-30' + POSTING_SERIAL AS DATE)"
                """);
        List<String> warnings = ConfigValidator.validate(loadPipeline(dir, schema));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'POSTING_DATE'") && w.contains("not a raw field")
                        && w.contains("mapped column")),
                "a partition source that only the mapping produces must be reported. Got: " + warnings);
    }

    /**
     * {@code PARTITION-KEY-VALIDATION-GAPS-1} (b) — DuckDB column names are case-insensitive, so a partition
     * column {@code account_class} next to a mapped {@code ACCOUNT_CLASS} is a DUPLICATE: DuckDB silently
     * renames the partition column {@code account_class_1}, and the folders are cut from the mapped column.
     */
    @Test
    void aPartitionColumnCollidingByCaseWithAMappedColumnIsReported(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, """
                partitions[1]{column,source,type}:
                  account_class,ACCOUNT_CLASS,VARCHAR
                raw:
                  name: x
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID,"0",VARCHAR
                    ACCOUNT_CLASS,"1",VARCHAR
                mapping:
                  canonicalName: x
                  rawName: x
                  fields[2]{name,from,fn}:
                    ID,ID,keep
                    ACCOUNT_CLASS,ACCOUNT_CLASS,keep
                """);
        List<String> warnings = ConfigValidator.validate(loadPipeline(dir, schema));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'account_class'") && w.contains("'ACCOUNT_CLASS'")),
                "a partition column that collides by case with a mapped column must be reported. Got: " + warnings);
    }

    /**
     * {@code DATE-PARTITION-ON-TEXT-SHIPPED-1} — a {@code partitionKey:} over a field declared {@code VARCHAR}
     * (an IMSI) loads and runs clean and puts every row under {@code __HIVE_DEFAULT_PARTITION__}. Reported once
     * per source, although the shorthand expands to three DATE defs.
     */
    @Test
    void aDatePartitionOverAFieldDeclaredNonDateIsReportedOnce(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, """
                partitionKey: IMSI
                raw:
                  name: x
                  format: CSV
                  fields[2]{name,selector,type}:
                    IMSI,"0",VARCHAR
                    DURATION,"1",INTEGER
                mapping:
                  canonicalName: x
                  rawName: x
                  fields[2]{name,from,fn}:
                    IMSI,IMSI,keep
                    DURATION,DURATION,keep
                """);
        List<String> hits = ConfigValidator.validate(loadPipeline(dir, schema)).stream()
                .filter(w -> w.contains("DATE partition source")).toList();
        assertEquals(1, hits.size(), "one report for the one source. Got: " + hits);
        assertTrue(hits.get(0).contains("'IMSI'") && hits.get(0).contains("VARCHAR")
                && hits.get(0).contains("__HIVE_DEFAULT_PARTITION__"), hits.get(0));
    }

    /** The negative's probe: the same shape over date-typed sources is silent, whichever spelling. */
    @Test
    void aDatePartitionOverADateOrTimestampFieldIsNotReported(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, """
                partitions[3]{column,source,type}:
                  year,EVENT_DATE,DATE_YEAR
                  month,EVENT_TS,DATE_MONTH
                  region_code,REGION,VARCHAR
                raw:
                  name: x
                  format: CSV
                  fields[3]{name,selector,type}:
                    EVENT_DATE,"0",date
                    EVENT_TS,"1",TIMESTAMP
                    REGION,"2",VARCHAR
                mapping:
                  canonicalName: x
                  rawName: x
                  fields[3]{name,from,fn}:
                    EVENT_DATE,EVENT_DATE,keep
                    EVENT_TS,EVENT_TS,keep
                    REGION,REGION,keep
                """);
        List<String> hits = ConfigValidator.validate(loadPipeline(dir, schema)).stream()
                .filter(w -> w.contains("DATE partition source")).toList();
        assertEquals(List.of(), hits, "DATE / TIMESTAMP sources (any spelling), and a VARCHAR partition of a text field, are fine");
    }

    private static String basePipeline(Path dir, String schemaPath) {
        return """
                name: VALIDATOR_ETL
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status
                  log_dir: %s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schema_file: %s
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, schemaPath);
    }
}

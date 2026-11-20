package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} O6: the {@code output.load} arrival mode ({@code append | replace | period} with
 * {@code output.period_column}) - parsed fail-closed, absent = append (today's behaviour), and refused at load
 * ({@link PipelineConfig#arrivalLoadRefusal()}, which the save gate applies too) on every store a replacement cannot
 * rewrite safely: anything but a single local, unpartitioned, one-schema Parquet store of a Stream.
 */
class PipelineConfigArrivalLoadTest {

    private static Map<String, Object> minimal(Map<String, Object> output) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "calls");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        if (output != null) m.put("output", output);
        return m;
    }

    @Test
    void absentIsAppendAndRunsAsToday() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(minimal(null));
        assertEquals(PipelineConfig.ArrivalLoad.APPEND, cfg.arrivalLoad());
        assertNull(cfg.arrivalLoadRefusal());
        assertEquals(PipelineConfig.ArrivalLoad.Mode.APPEND,
                PipelineConfig.fromMap(minimal(Map.of("format", "PARQUET"))).arrivalLoad().mode());
    }

    @Test
    void replaceAndPeriodParse() throws Exception {
        PipelineConfig replace = PipelineConfig.fromMap(minimal(Map.of("load", "REPLACE")));
        assertEquals(PipelineConfig.ArrivalLoad.Mode.REPLACE, replace.arrivalLoad().mode());
        assertNull(replace.arrivalLoad().periodColumn());
        assertNull(replace.arrivalLoadRefusal(), "a single local Parquet store may replace");
        PipelineConfig period = PipelineConfig.fromMap(minimal(Map.of("load", "period", "period_column", "event_date")));
        assertEquals(PipelineConfig.ArrivalLoad.Mode.PERIOD, period.arrivalLoad().mode());
        assertEquals("event_date", period.arrivalLoad().periodColumn());
        assertEquals(period.arrivalLoad(), period.forNewRun().arrivalLoad(), "a run's copy keeps the mode");
    }

    @Test
    void anUnknownModeAndAMismatchedColumnAreRefused() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(minimal(Map.of("load", "merge"))));
        assertTrue(unknown.getMessage().contains("output.load must be 'append', 'replace' or 'period'"), unknown.getMessage());
        IllegalArgumentException noColumn = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(minimal(Map.of("load", "period"))));
        assertTrue(noColumn.getMessage().contains("needs output.period_column"), noColumn.getMessage());
        IllegalArgumentException stray = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(minimal(Map.of("load", "replace", "period_column", "event_date"))));
        assertTrue(stray.getMessage().contains("applies only to output.load period"), stray.getMessage());
    }

    @Test
    void theShapesAReplacementCannotRewriteAreRefused() throws Exception {
        assertRefused(Map.of("load", "replace", "format", "CSV"), "its output format is CSV");
        assertRefused(Map.of("load", "replace", "ducklake", Map.of("enabled", true, "catalog_url", "x.ducklake",
                "data_path", "lake", "table", "t")), "registers its files in DuckLake");
        Map<String, Object> twoSinks = minimal(Map.of("load", "replace"));
        twoSinks.put("sinks", List.of(Map.of("database", "a"), Map.of("database", "b")));
        assertRefusal(twoSinks, "writes to 2 destinations");
        Map<String, Object> objectStore = minimal(Map.of("load", "period", "period_column", "d", "connection", "s3conn"));
        objectStore.put("dirs", Map.of("poll", "in", "database", "s3://bucket/calls"));
        assertRefusal(objectStore, "dirs.database is an object store");
        Map<String, Object> reference = minimal(Map.of("load", "replace"));
        reference.put("produces", "reference");
        assertRefusal(reference, "a Reference is loaded by reference.load");
        // append is never refused, on any shape
        Map<String, Object> appendCsv = minimal(Map.of("load", "append", "format", "CSV"));
        assertNull(PipelineConfig.fromMap(appendCsv).arrivalLoadRefusal());
    }

    @Test
    void aPartitionedSchemaIsRefusedAtLoadAndTheColumnMustBeStored(@TempDir Path dir) throws Exception {
        String partitioned = PipelineConfigBatchTest.miniSchema();   // partitionKey: EVENT_DATE
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> load(dir, partitioned, "load: period\n  period_column: EVENT_DATE"));
        assertTrue(refused.getMessage().contains("its schema partitions the store"), refused.getMessage());

        String flat = partitioned.replaceFirst("partitionKey: EVENT_DATE\\R", "");
        PipelineConfig ok = load(dir, flat, "load: period\n  period_column: EVENT_DATE");
        assertEquals("EVENT_DATE", ok.arrivalLoad().periodColumn());
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> load(dir, flat, "load: period\n  period_column: WHEN"));
        assertTrue(unknown.getMessage().contains("output.period_column 'WHEN' is not declared"), unknown.getMessage());
        assertNotNull(load(dir, partitioned, "load: append"), "append on a partitioned store loads as today");
    }

    private static void assertRefused(Map<String, Object> output, String why) throws Exception {
        assertRefusal(minimal(output), why);
    }

    private static void assertRefusal(Map<String, Object> raw, String why) throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(raw);
        String refusal = cfg.arrivalLoadRefusal();
        assertNotNull(refusal, why);
        assertTrue(refusal.contains("needs a single local Parquet store") && refusal.contains(why), refusal);
        IllegalStateException atLoad = assertThrows(IllegalStateException.class, cfg::requireRunnable);
        assertEquals(refusal, atLoad.getMessage(), "the load refuses what the save refuses");
    }

    private static PipelineConfig load(Path dir, String schema, String outputBlock) throws Exception {
        Path s = dir.resolve("calls_schema.toon");
        Files.writeString(s, schema);
        String d = dir.toString().replace("\\", "/");
        Path p = dir.resolve("calls_pipeline.toon");
        Files.writeString(p, """
                name: calls
                dirs:
                  poll: %s/inbox
                  database: %s/db
                output:
                  format: PARQUET
                  %s
                processing:
                  threads: 1
                  schema_file: "%s"
                """.formatted(d, d, outputBlock, s.toString().replace("\\", "/")));
        return PipelineConfig.load(p.toString());
    }
}

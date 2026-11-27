package com.gamma.inspector;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code OUTPUT-NAME-OVERWRITE-1}: a second file with the same file name (e.g. {@code later/feed.csv} after
 * {@code feed.csv}) must not silently overwrite the first file's output rows on any Pipeline when
 * {@code processing.output_per_member} is off (the default). A re-delivery of the SAME path ({@code feed.csv}
 * again under {@code on_change: reprocess}) is a correction and replaces its own output.
 */
class OutputNameOverwriteTest {

    private static String rowsBody(String prefix, int count) {
        StringBuilder sb = new StringBuilder("ID,AMT,EVENT_DATE\n");
        for (int i = 0; i < count; i++) {
            sb.append(prefix).append(i).append(',').append(i + 1).append(".0,2020-04-03\n");
        }
        return sb.toString();
    }

    private static Path pipelineToon(Path dir, String path, String extra) throws Exception {
        String engine = path.startsWith("java") ? "java" : "duckdb";
        String section = extra
                + ("duckdb-chunked".equals(path) ? "  chunking:\n    max_file_bytes: 100\n    target_chunk_bytes: 100\n" : "");
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, section);
        Files.writeString(toon, Files.readString(toon).replace(
                "    delimiter: \",\"", "    delimiter: \",\"\n    engine: " + engine));
        return toon;
    }

    private static PipelineConfig pipeline(Path dir, String path, String extra) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(pipelineToon(dir, path, extra).toString());
        Files.createDirectories(Path.of(cfg.dirs().poll()));
        return cfg;
    }

    private static List<Path> outputs(PipelineConfig cfg) throws Exception {
        Path db = Path.of(cfg.dirs().database());
        if (!Files.exists(db)) return List.of();
        try (Stream<Path> w = Files.walk(db)) {
            return w.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static long totalCsvRows(List<Path> files) throws Exception {
        long n = 0;
        for (Path p : files) n += Files.readAllLines(p).size() - 1;   // one CSV header per output file
        return n;
    }

    @ParameterizedTest
    @ValueSource(strings = {"java", "duckdb", "duckdb-chunked"})
    void laterSubdirSameFilenamePreserves112Plus106Rows(String path, @TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir, path, "");
        assertFalse(cfg.outputPerMember(), "output_per_member stays off by default");

        Path inbox = Path.of(cfg.dirs().poll());
        Files.writeString(inbox.resolve("feed.csv"), rowsBody("a", 112));
        CollectorProcessor.run(cfg);
        assertEquals(112, totalCsvRows(outputs(cfg)), path + ": first batch landed 112 rows");

        Path later = inbox.resolve("later");
        Files.createDirectories(later);
        Files.writeString(later.resolve("feed.csv"), rowsBody("b", 106));
        CollectorProcessor.run(cfg);

        List<Path> files = outputs(cfg);
        assertEquals(218, totalCsvRows(files), path + ": 112 + 106 rows must both survive: " + files);
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().startsWith("feed_")), files.toString());
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().contains("feed__")), files.toString());
    }

    private static PipelineConfig reprocessPipeline(Path dir, String path) throws Exception {
        AcquisitionLedgers.use(new InMemoryAcquisitionLedger());
        Path orig = pipelineToon(dir, path, "");
        Path toon = dir.resolve("reprocess_pipeline.toon");
        Files.writeString(toon, Files.readString(orig) + """
                collector:
                  duplicate:
                    mode: checksum
                    algorithm: SHA256
                    on_change: reprocess
                """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Files.createDirectories(Path.of(cfg.dirs().poll()));
        return cfg;
    }

    /** A corrected SAME-path file under {@code on_change: reprocess} REPLACES its output (LA-DAILY-INGEST-1 owner
     *  call, 2026-10-14): keeping v1 beside v2 would serve every row twice ({@code ENGINE-ETL-TESTS-RED-1}). */
    @ParameterizedTest
    @ValueSource(strings = {"java", "duckdb"})
    void onChangeReprocessOfTheSamePathReplacesItsOutput(String path, @TempDir Path dir) throws Exception {
        PipelineConfig cfg = reprocessPipeline(dir, path);
        Path inbox = Path.of(cfg.dirs().poll());
        Files.writeString(inbox.resolve("feed.csv"), rowsBody("v1_", 112));
        CollectorProcessor.run(cfg);
        assertEquals(112, totalCsvRows(outputs(cfg)), path + ": v1 landed 112 rows");

        Files.writeString(inbox.resolve("feed.csv"), rowsBody("v2_", 106));
        CollectorProcessor.run(cfg);

        List<Path> files = outputs(cfg);
        assertEquals(1, files.size(), path + ": v2 replaced v1's file: " + files);
        assertEquals(106, totalCsvRows(files), path + ": only v2's rows are served: " + files);
    }

    @Test
    void onChangeReprocessSameByteSizeReplacesItsOutput(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = reprocessPipeline(dir, "duckdb");
        Path inbox = Path.of(cfg.dirs().poll());
        Files.writeString(inbox.resolve("feed.csv"), "ID,AMT,EVENT_DATE\na1,1.0,2020-04-03\na2,2.0,2020-04-03\n");
        CollectorProcessor.run(cfg);
        assertEquals(2, totalCsvRows(outputs(cfg)));

        // Identical byte size, different content -> ConsignmentId mints the same batchId, whose manifest exists
        Files.writeString(inbox.resolve("feed.csv"), "ID,AMT,EVENT_DATE\nb1,1.0,2020-04-03\nb2,2.0,2020-04-03\n");
        CollectorProcessor.run(cfg);

        List<Path> files = outputs(cfg);
        assertEquals(1, files.size(), "one output file: " + files);
        assertEquals(2, totalCsvRows(files), "only the corrected rows: " + files);
        assertTrue(Files.readString(files.get(0)).contains("b1"), files.toString());
    }

    /** The disambiguated name is the ORIGIN's, so {@code later/feed.csv}'s own correction replaces its own file,
     *  and {@code feed.csv}'s file is untouched. */
    @Test
    void aCorrectionOfTheSecondOriginReplacesOnlyItsOwnDisambiguatedFile(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = reprocessPipeline(dir, "duckdb");
        Path inbox = Path.of(cfg.dirs().poll());
        Files.writeString(inbox.resolve("feed.csv"), rowsBody("a", 112));
        CollectorProcessor.run(cfg);
        Path later = Files.createDirectories(inbox.resolve("later"));
        Files.writeString(later.resolve("feed.csv"), rowsBody("b", 106));
        CollectorProcessor.run(cfg);
        assertEquals(218, totalCsvRows(outputs(cfg)));

        Files.writeString(later.resolve("feed.csv"), rowsBody("c", 50));
        CollectorProcessor.run(cfg);

        List<Path> files = outputs(cfg);
        assertEquals(2, files.size(), "feed.csv's file + later/feed.csv's file: " + files);
        assertEquals(162, totalCsvRows(files), "112 + the corrected 50: " + files);
    }

    @Test
    void generationModeSameStemAcrossBatchesPreserves112Plus106Rows(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("call_schema.toon");
        Files.writeString(schema, """
                partitions[4]{column,source,type}:
                  record_type,EVENT_TYPE,VARCHAR
                  year,EVENT_DATE,DATE_YEAR
                  month,EVENT_DATE,DATE_MONTH
                  day,EVENT_DATE,DATE_DAY
                raw:
                  name: call
                  format: CSV
                  fields[3]{name,selector,type}:
                    ID,"0",VARCHAR
                    EVENT_TYPE,"1",VARCHAR
                    EVENT_DATE,"2",DATE
                mapping:
                  canonicalName: call
                  rawName: call
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVENT_TYPE,EVENT_TYPE,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """);
        String d = dir.toString().replace("\\", "/");
        Path toon = dir.resolve("gen_pipeline.toon");
        Files.writeString(toon, """
                name: GEN_OVERWRITE_ETL
                active: true
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  markers: %s/markers
                  status_dir: %s/status
                  log_dir: %s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.bin"
                  ingester: %s
                  segments:
                    CALL: "%s"
                  streaming:
                    large_file_bytes: 1
                """.formatted(d, d, d, d, d, d, d, d, d,
                OutputStemCollisionTest.StubIngester.class.getName(),
                schema.toString().replace("\\", "/")));
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox.resolve("a"));
        Files.createDirectories(inbox.resolve("b"));

        StringBuilder b1 = new StringBuilder();
        for (int i = 0; i < 112; i++) b1.append("CALL,A").append(i).append(",2020-04-03\n");
        Files.writeString(inbox.resolve("a/feed.bin"), b1.toString());
        CollectorProcessor.run(cfg);
        assertEquals(112, totalCsvRows(outputs(cfg)));

        StringBuilder b2 = new StringBuilder();
        for (int i = 0; i < 106; i++) b2.append("CALL,B").append(i).append(",2020-04-03\n");
        Files.writeString(inbox.resolve("b/feed.bin"), b2.toString());
        CollectorProcessor.run(cfg);

        List<Path> files = outputs(cfg);
        assertEquals(2, files.size(), "two generation output files: " + files);
        assertEquals(218, totalCsvRows(files), "112 + 106 rows survive across batches: " + files);
    }
}

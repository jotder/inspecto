package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-destination ingest ({@code sinks:} slice 3): one batch driven through {@link ConsignmentIngestor}
 * against a pipeline that declares two {@code sinks:} destinations must land its output under <em>each</em>
 * destination's own {@code database}, while the source is finalised exactly <b>once</b> (one backup, one
 * marker, one batch-audit row) — backup/markers/ledger are per-source-file, not per-destination.
 */
class ConsignmentIngestorSinksTest {

    /** A single-schema CSV pipeline that fans its output out to two destinations (hot + cold). */
    private static Path writeTwoSinkPipeline(Path dir) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        String d = dir.toString().replace("\\", "/");
        String toon = """
            name: FANOUT_ETL
            active: true
            dirs:
              poll: %s/inbox
              database: %s/db
              backup: %s/backup
              temp: %s/temp
              quarantine: %s/quarantine
              markers: %s/markers
              status_dir: %s/status
            output:
              format: CSV
            sinks[2]{database,format}:
              "%s/hot",CSV
              "%s/cold",CSV
            processing:
              threads: 1
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, d, d, d, d, d, d, d, d, schema.toString().replace("\\", "/"));
        Path p = dir.resolve("fanout_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    private Consignment.Member member(PipelineConfig cfg, File f, int id) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        return new Consignment.Member(f, id, f.length(), sel);
    }

    private static boolean hasCsvOutput(Path root) throws Exception {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> w = Files.walk(root)) {
            return w.anyMatch(p -> p.getFileName().toString().endsWith("_out.csv"));
        }
    }

    @Test
    void fanOutWritesEachDestinationAndFinalisesOnce(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writeTwoSinkPipeline(dir).toString());
        assertEquals(2, cfg.sinks().size(), "pipeline declares two destinations");

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path a = inbox.resolve("a.csv");
        Files.writeString(a, "ID,AMT,EVENT_DATE\na1,1.0,2020-04-03\na2,2.0,2020-04-03\n");

        Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_fan_0001", "mini", null,
                List.of(member(cfg, a.toFile(), 0)));
        ConsignmentIngestor.process(batch, cfg, new ConsignmentAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));

        // the batch output landed under BOTH destination databases (each its own copy)
        assertTrue(hasCsvOutput(dir.resolve("hot")), "hot destination written");
        assertTrue(hasCsvOutput(dir.resolve("cold")), "cold destination written");

        // the source was finalised exactly once — backed up, marked, out of the inbox
        assertFalse(Files.exists(a), "source moved out of the inbox");
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "a.csv")), "backed up once");
        assertTrue(Files.exists(Path.of(cfg.dirs().markers(), "a.csv.processed")), "marked once");

        // one SUCCESS batch-audit row (single finalize), not one per destination
        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertTrue(batches.contains(",SUCCESS,"), "batch succeeded: " + batches);
        assertEquals(1, batches.lines().filter(l -> l.contains(batch.batchId())).count(),
                "exactly one batch row — the source is finalised once regardless of destination count");
    }

    // ── SINKS-ENTRY-IGNORES-OUTPUT-DEFAULTS-1: output: is each sinks[] entry's default layer ─────────

    /** A PARQUET fan-out to {@code hot} + {@code cold} under {@code output: {format: PARQUET, compression:
     *  zstd}}; {@code sinksBlock} is the tabular sinks declaration. zstd, NOT snappy: snappy is DuckDB's own
     *  parquet default, so an entry that silently dropped an inherited snappy would still write snappy
     *  bytes and the test could not tell the fix from the bug. */
    private static PipelineConfig parquetFanOut(Path dir, String sinksBlock) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        String d = dir.toString().replace("\\", "/");
        String toon = """
            name: FANOUT_ETL
            active: true
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
            output:
              format: PARQUET
              compression: zstd
            %2$s
            processing:
              threads: 1
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%3$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, sinksBlock.replace("$D", d), schema.toString().replace("\\", "/"));
        Path p = dir.resolve("fanout_pipeline.toon");
        Files.writeString(p, toon);
        return PipelineConfig.load(p.toString());
    }

    private void ingestOne(PipelineConfig cfg) throws Exception {
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path a = inbox.resolve("a.csv");
        Files.writeString(a, "ID,AMT,EVENT_DATE\na1,1.0,2020-04-03\na2,2.0,2020-04-03\n");
        Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_fan_0001", "mini", null,
                List.of(member(cfg, a.toFile(), 0)));
        ConsignmentIngestor.process(batch, cfg, new ConsignmentAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));
    }

    /** The codecs DuckDB reports for every parquet file written under {@code root} — the bytes, not the config. */
    private static Set<String> parquetCodecs(Path root) throws Exception {
        Set<String> codecs = new TreeSet<>();
        String glob = root.toString().replace("\\", "/") + "/**/*.parquet";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT DISTINCT compression FROM parquet_metadata('" + glob + "')")) {
            while (rs.next()) codecs.add(rs.getString(1));
        }
        assertFalse(codecs.isEmpty(), "no parquet written under " + root);
        return codecs;
    }

    @Test
    void anEntryThatOmitsCompressionWritesTheOutputBlocksCodec(@TempDir Path dir) throws Exception {
        // the shipped premed_events shape: output.compression above {database,format} entries
        PipelineConfig cfg = parquetFanOut(dir, """
            sinks[2]{database,format}:
              "$D/hot",PARQUET
              "$D/cold",PARQUET""");
        for (PipelineConfig.Sink s : cfg.sinks())
            assertEquals("zstd", s.compression(), "effective compression of " + s.database());

        ingestOne(cfg);

        assertEquals(Set.of("ZSTD"), parquetCodecs(dir.resolve("hot")), "hot inherits output.compression on disk");
        assertEquals(Set.of("ZSTD"), parquetCodecs(dir.resolve("cold")), "cold inherits output.compression on disk");
    }

    @Test
    void anEntrysOwnCompressionOverridesTheOutputBlock(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = parquetFanOut(dir, """
            sinks[2]{database,format,compression}:
              "$D/hot",PARQUET,gzip
              "$D/cold",PARQUET,zstd""");

        ingestOne(cfg);

        assertEquals(Set.of("GZIP"), parquetCodecs(dir.resolve("hot")), "the entry's own gzip wins over output's zstd");
        assertEquals(Set.of("ZSTD"), parquetCodecs(dir.resolve("cold")));
    }
}

package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-destination ingest ({@code sinks:} slice 3): one batch driven through {@link BatchProcessor}
 * against a pipeline that declares two {@code sinks:} destinations must land its output under <em>each</em>
 * destination's own {@code database}, while the source is finalised exactly <b>once</b> (one backup, one
 * marker, one batch-audit row) — backup/markers/ledger are per-source-file, not per-destination.
 */
class BatchProcessorSinksTest {

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

    private Batch.Member member(PipelineConfig cfg, File f, int id) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        return new Batch.Member(f, id, f.length(), sel);
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

        Batch batch = new Batch(cfg.identity().runTimestamp() + "_fan_0001", "mini", null,
                List.of(member(cfg, a.toFile(), 0)));
        BatchProcessor.process(batch, cfg, new BatchAuditWriter(
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
}

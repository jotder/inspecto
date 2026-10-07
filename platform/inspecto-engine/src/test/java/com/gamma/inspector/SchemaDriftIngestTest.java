package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.event.EventLog;
import com.gamma.signal.SchemaDriftSignal;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code quality.schema.drift} end to end through the real CSV ingest: the hook sits in
 * {@link CsvIngestStrategy} above the lane dispatch, so this proves the wiring, not the diff (that is
 * {@code SchemaDriftTest}). The case that matters is the SILENT one — a header renamed at equal width
 * parses perfectly by position, the batch commits SUCCESS, and only the Signal tells anyone.
 */
class SchemaDriftIngestTest {

    private static PipelineConfig config(Path dir) throws Exception {
        Files.createDirectories(dir);
        String d = dir.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Path toon = dir.resolve("drift_pipeline.toon");
        Files.writeString(toon, """
            name: DRIFT_ETL
            active: true
            version: 1
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              errors: %1$s/errors
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
              log_dir: %1$s/logs
            output:
              format: CSV
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              schema_file: "%2$s"
              csv_settings:
                delimiter: ","
                has_header: true
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, schema.toString().replace("\\", "/")));
        return PipelineConfig.load(toon.toString());
    }

    private static Consignment.Member member(PipelineConfig cfg, File f, int id) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        return new Consignment.Member(f, id, f.length(), sel);
    }

    /** Run one file through the real ingest inside an isolated EventLog; return the drift Signals it left. */
    private static List<Signal> ingest(Path dir, String name, String csv, String[] batchIdOut) throws Exception {
        String space = "schema-drift-ingest-test-" + UUID.randomUUID();
        EventLog log = EventLog.create();
        EventLog.register(space, log);
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
        try {
            PipelineConfig cfg = config(dir);
            Path inbox = Path.of(cfg.dirs().poll());
            Files.createDirectories(inbox);
            Path file = inbox.resolve(name);
            Files.writeString(file, csv);
            String batchId = cfg.identity().runTimestamp() + "_mini_0001";
            batchIdOut[0] = batchId;
            Consignment batch = new Consignment(batchId, "mini", null, List.of(member(cfg, file.toFile(), 0)));
            ConsignmentIngestor.process(batch, cfg, new ConsignmentAuditWriter(
                    cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));
            assertTrue(Files.readString(Path.of(cfg.dirs().batchesFilePath())).contains(",SUCCESS,"),
                    "detection only — the batch must still commit");
            return Signals.query(log.store(), SchemaDriftSignal.TYPE, null, null, null, null, 10);
        } finally {
            org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
            EventLog.unregister(space);
        }
    }

    @Test
    void aRenamedHeaderAtEqualWidthCommitsSuccessAndRaisesOneDriftSignal(@TempDir Path dir) throws Exception {
        String[] batchId = new String[1];
        List<Signal> signals = ingest(dir, "renamed.csv", """
                ID,AMOUNT,EVENT_DATE
                a1,1.0,2020-04-03
                a2,2.0,2020-04-03
                """, batchId);

        assertEquals(1, signals.size(), "one quality.schema_drift Signal for the batch: " + signals);
        Signal sig = signals.get(0);
        assertEquals(batchId[0], sig.correlationId(), "correlated on the batch id");
        assertEquals("drift_etl", sig.subject().id(), "the (lowercased) pipeline identity, as every other Signal names it");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) sig.payload().get("files");
        assertEquals(1, files.size());
        assertEquals("renamed.csv", files.get(0).get("file"));
        assertEquals(List.of("AMOUNT"), files.get(0).get("added"));
        assertEquals(List.of("AMT"), files.get(0).get("missing"));
        assertEquals(Boolean.TRUE, files.get(0).get("namesCompared"));
    }

    @Test
    void theDeclaredHeaderRaisesNothing(@TempDir Path dir) throws Exception {
        List<Signal> signals = ingest(dir, "same.csv", """
                ID,AMT,EVENT_DATE
                a1,1.0,2020-04-03
                """, new String[1]);
        assertTrue(signals.isEmpty(), "no drift, no Signal: " + signals);
    }
}

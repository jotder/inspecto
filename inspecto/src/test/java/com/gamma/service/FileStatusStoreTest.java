package com.gamma.service;

import com.gamma.etl.ConsignmentAuditWriter;
import com.gamma.etl.LineageRow;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.unpack.UnpackLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileStatusStoreTest {

    /**
     * Regression: Windows output paths contain backslashes. The audit reader must preserve them.
     * OpenCSV's default parser treats '\' as an escape character and silently strips it
     * ("C:\\db\\out.csv" -> "C:dbout.csv"); the RFC4180 parser reads it literally. This writes a
     * real audit row via ConsignmentAuditWriter and reads it back through FileStatusStore end-to-end.
     */
    @Test
    void preservesBackslashesInWindowsOutputPaths(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                PipelineConfigBatchTest.writePipeline(dir, "").toString());

        Path statusDir = Path.of(cfg.dirs().statusFilePath()).toAbsolutePath().getParent();
        Files.createDirectories(statusDir);
        String name = cfg.identity().pipelineName();   // reader globs <name>_status_*.csv
        String winPath = "C:\\data\\db\\year=2020\\month=04\\day=03\\B1_out.csv";

        String statusCsv  = statusDir.resolve(name + "_status_TEST.csv").toString();
        String batchesCsv = statusDir.resolve(name + "_batches_TEST.csv").toString();
        String lineageCsv = statusDir.resolve(name + "_lineage_TEST.csv").toString();
        ConsignmentAuditWriter w = new ConsignmentAuditWriter(statusCsv, batchesCsv, lineageCsv);

        var fileRow = new ConsignmentAuditWriter.FileRow("2026-06-09 08:00:00", "2026-06-09 08:00:01",
                "a.csv", "SUCCESS", 2, 0, List.of(winPath), List.of(120L), 1000, "", "B1");
        var batchRow = new ConsignmentAuditWriter.ConsignmentRow("B1", name, "mini", "",
                "2026-06-09 08:00:00", "2026-06-09 08:00:02", "SUCCESS",
                1, 0, 2, 2, 1, 120L, 2000, "");
        var lineage = List.of(new LineageRow("B1", 0, "a.csv", winPath, "year=2020/month=04/day=03", 2));

        w.flush(batchRow, List.of(fileRow), lineage);

        FileStatusStore store = new FileStatusStore();

        List<Map<String, String>> files = store.files(cfg);
        assertEquals(1, files.size());
        assertEquals(winPath, files.get(0).get("output_paths"),
                "backslashes in the Windows output path must survive the CSV round-trip");

        List<Map<String, String>> lin = store.lineage(cfg, "B1");
        assertEquals(1, lin.size());
        assertEquals(winPath, lin.get(0).get("output_file"),
                "lineage output_file must keep its backslashes too");
    }

    /**
     * The unpack ledger's read surface: rows written by {@link UnpackLedger} come back through
     * {@code StatusStore.unpack} keyed by the ledger's own {@link UnpackLedger#COLUMNS} — the
     * single declaration the whole surface references, never restates.
     */
    @Test
    void readsUnpackLedgerRowsByCanonicalColumns(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                PipelineConfigBatchTest.writePipeline(dir, "").toString());

        Path statusDir = Path.of(cfg.dirs().statusFilePath()).toAbsolutePath().getParent();
        Files.createDirectories(statusDir);
        String name = cfg.identity().pipelineName();   // reader globs <name>_unpack_*.csv

        Path inbox = dir.resolve("in");
        Files.createDirectories(inbox);
        java.io.File archive = inbox.resolve("east/data.zip").toFile();

        String runId = "UNPACK_READ_TEST";
        UnpackLedger.expanded(runId, archive, "zip", 3, 1, 100, 250, false, "");
        UnpackLedger.entryOutcome(runId, archive, "B1", true);
        UnpackLedger.entryOutcome(runId, archive, "B2", false);
        UnpackLedger.flush(runId,
                statusDir.resolve(name + "_unpack_TEST.csv").toString(), inbox);

        List<Map<String, String>> rows = new FileStatusStore().unpack(cfg);
        assertEquals(1, rows.size(), "one row per archive per run");
        Map<String, String> row = rows.get(0);
        assertEquals(new java.util.LinkedHashSet<>(UnpackLedger.COLUMNS), row.keySet(),
                "the read side's columns ARE the ledger's single declaration");
        assertEquals(runId, row.get("run_id"));
        assertEquals("east/data.zip", row.get("archive_relpath"));
        assertEquals("UNPACKED_PARTIAL", row.get("status"),
                "1 ingested + 1 failed + 1 skipped is a partial expansion");
    }
}

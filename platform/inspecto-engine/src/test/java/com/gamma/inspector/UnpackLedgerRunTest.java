package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.util.Csv;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The run-level unpack ledger END TO END (unpack-stage plan §2.2): a real archive through a real
 * poll cycle produces exactly one ledger row, with the tallies and verdict the operator signed off
 * in §6 Q1 — including that an archive with one undecodable member commits its good entries and is
 * reported {@code UNPACKED_PARTIAL} rather than failing whole (§6 Q1b).
 *
 * <p>Its own fixture rather than {@code PipelineConfigBatchTestRef}: that pipeline's
 * {@code file_pattern} admits only {@code .csv}/{@code .csv.gz}, so a {@code .zip} would never be
 * collected and the test would pass without exercising anything.
 */
class UnpackLedgerRunTest {

    private static final String A = "ID,V\na,1\n";
    private static final String B = "ID,V\nb,2\n";

    @Test
    void aPartialArchiveCommitsAndLandsOneLedgerRow(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir);
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        // Two readable entries plus one flagged encrypted — the shape that must read PARTIAL.
        zip(inbox.resolve("bundle.zip"), List.of("locked.csv", "one.csv", "two.csv"),
                List.of(A, A, B), true);

        CollectorProcessor.run(cfg);

        Path ledger = Path.of(cfg.dirs().unpackFilePath());
        assertTrue(Files.exists(ledger), "the run wrote its unpack ledger at " + ledger);
        List<Map<String, String>> rows = read(ledger);
        assertEquals(1, rows.size(), "one row per archive per run");

        Map<String, String> r = rows.get(0);
        assertEquals("bundle.zip", r.get("archive_relpath"));
        assertEquals("zip", r.get("format"));
        assertEquals(cfg.identity().runTimestamp(), r.get("run_id"));
        assertEquals("3", r.get("entries_found"), "two readable + one skipped were all SEEN");
        assertEquals("2", r.get("entries_ingested"));
        assertEquals("1", r.get("entries_skipped"));
        assertEquals("UNPACKED_PARTIAL", r.get("status"),
                "one undecodable member makes the archive partial, not clean and not failed");
        assertFalse(r.get("consignment_ids").isBlank(), "the consignments its entries landed in");
        assertTrue(Long.parseLong(r.get("bytes_in")) > 0);
        assertTrue(Long.parseLong(r.get("bytes_out")) > 0);

        // §6 Q1b: the verdict is REPORTING, never a gate — the good entries really did commit.
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "bundle.zip")),
                "the archive completed and was backed up despite the bad member");
    }

    /** A wholly clean archive reads UNPACKED — the row is not merely always 'partial'. */
    @Test
    void aCleanArchiveReadsUNPACKED(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir);
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        zip(inbox.resolve("clean.zip"), List.of("one.csv", "two.csv"), List.of(A, B), false);

        CollectorProcessor.run(cfg);

        Map<String, String> r = read(Path.of(cfg.dirs().unpackFilePath())).get(0);
        assertEquals("UNPACKED", r.get("status"));
        assertEquals("2", r.get("entries_found"));
        assertEquals("2", r.get("entries_ingested"));
        assertEquals("0", r.get("entries_skipped"));
        assertEquals("0", r.get("entries_failed"));
    }

    /** A run with no archives at all leaves no ledger file — plain files are not archive rows. */
    @Test
    void aRunWithNoArchivesWritesNoLedger(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir);
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("plain.csv"), A);

        CollectorProcessor.run(cfg);

        assertFalse(Files.exists(Path.of(cfg.dirs().unpackFilePath())),
                "an ordinary file is fully described by its own status row — no archive row is owed");
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private static List<Map<String, String>> read(Path ledger) throws Exception {
        List<Map<String, String>> out = new ArrayList<>();
        Csv.readInto(ledger, out);
        return out;
    }

    /**
     * A zip of STORED entries; when {@code lockFirst}, the first entry's encryption flag is set so
     * the reader reports it undecodable — the same shape a password-protected member presents.
     */
    private static void zip(Path p, List<String> names, List<String> bodies, boolean lockFirst)
            throws IOException {
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(Files.newOutputStream(p))) {
            for (int i = 0; i < names.size(); i++) {
                ZipArchiveEntry e = new ZipArchiveEntry(names.get(i));
                byte[] b = bodies.get(i).getBytes(StandardCharsets.UTF_8);
                e.setMethod(ZipArchiveEntry.STORED);
                e.setSize(b.length);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(b);
                e.setCrc(crc.getValue());
                zos.putArchiveEntry(e);
                zos.write(b);
                zos.closeArchiveEntry();
            }
        }
        if (lockFirst) {   // general-purpose bit 0 of the FIRST local file header (at offset 6)
            byte[] bytes = Files.readAllBytes(p);
            bytes[6] |= 1;
            Files.write(p, bytes);
        }
    }

    private static PipelineConfig pipeline(Path dir) throws Exception {
        Files.createDirectories(dir);
        String d = dir.toAbsolutePath().toString().replace('\\', '/');
        Path schema = dir.resolve("schema_ulr.toon");
        Files.writeString(schema, """
                partitionKey: V
                raw:
                  name: t
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID,"0",VARCHAR
                    V,"1",VARCHAR
                mapping:
                  canonicalName: t
                  rawName: t
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    V,V,DIRECT
                """, StandardCharsets.UTF_8);
        Path pipe = dir.resolve("pipe_ulr.toon");
        Files.writeString(pipe,
                "name: ULR\n"
              + "version: 1\n"
              + "dirs:\n"
              + "  poll: " + d + "/inbox\n"
              + "  database: " + d + "/db\n"
              + "  backup: " + d + "/backup\n"
              + "  temp: " + d + "/temp\n"
              + "  errors: " + d + "/errors\n"
              + "  quarantine: " + d + "/quarantine\n"
              + "  markers: " + d + "/markers\n"
              + "  status_dir: " + d + "/status\n"
              + "  log_dir: " + d + "/logs\n"
              + "output:\n"
              + "  format: CSV\n"
              + "processing:\n"
              + "  threads: 1\n"
              + "  file_pattern: \"glob:**/*\"\n"
              + "  schema_file: " + d + "/schema_ulr.toon\n"
              + "  csv_settings:\n"
              + "    delimiter: \",\"\n"
              + "    engine: duckdb\n", StandardCharsets.UTF_8);
        PipelineConfig cfg = PipelineConfig.load(pipe.toString());
        cfg.prepare();
        return cfg;
    }
}

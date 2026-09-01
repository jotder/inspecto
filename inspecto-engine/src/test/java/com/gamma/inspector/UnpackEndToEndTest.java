package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Collector-level unpack stage end-to-end (unpack-stage plan Phase 1): a {@code .bz2} inbox file
 * on the NATIVE engine — a format DuckDB's {@code read_csv} cannot decode, which quarantined as
 * unreadable before this stage — ingests through the full {@code CollectorProcessor.run} path, with
 * every original↔actual side effect landing on the right file:
 *
 * <ul>
 *   <li>rows land in the store (the expansion parsed),</li>
 *   <li>the ORIGINAL {@code .bz2} is backed up — the temp expansion is scratch, never backed up,</li>
 *   <li>the marker + logical alias key the ORIGINAL, so a re-run is a no-op — and so is a re-delivery
 *       of the SAME logical file under another compression spelling (§2.3),</li>
 *   <li>the scratch copy is deleted once the commit is durable,</li>
 *   <li>a corrupt {@code .bz2} falls through to the engine and quarantines as the ORIGINAL file.</li>
 * </ul>
 */
class UnpackEndToEndTest {

    private static final String ROWS = "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\nr2,2.0,2020-04-03\n";

    /** Consignment caps that keep several members in ONE consignment (the default is max_files: 1). */
    private static final String BATCH_10 = "  batch:\n    max_files: 10\n    max_bytes: 268435456\n";

    @Test
    void bz2IngestsNativelyWithOriginalBackupMarkerAndCleanTemp(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir);
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        bz2(inbox.resolve("feed.csv.bz2"), ROWS);

        CollectorProcessor.run(cfg);

        // The expansion parsed: one output file with both rows.
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            Path out = w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).findFirst().orElseThrow();
            String content = Files.readString(out);
            assertTrue(content.contains("r1") && content.contains("r2"), content);
        }
        // The ORIGINAL left the inbox into backup; the scratch expansion is gone.
        assertFalse(Files.exists(inbox.resolve("feed.csv.bz2")), "original left the inbox");
        assertTrue(Files.exists(Path.of(cfg.dirs().backup()).resolve("feed.csv.bz2")),
                "the BACKUP subject is the original .bz2");
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().temp()))) {
            assertEquals(0, w.filter(p -> p.getFileName().toString().equals("feed.csv")).count(),
                    "the scratch expansion is deleted after commit");
        }
        // Markers key the ORIGINAL (+ the §2.3 logical alias for compression-involved names).
        Path markers = Path.of(cfg.dirs().markers());
        assertTrue(Files.exists(markers.resolve("feed.csv.bz2.processed")), "primary marker = original");
        assertTrue(Files.exists(markers.resolve("feed.logical.processed")), "logical alias beside it");

        // Re-run: no-op. Re-delivery under ANOTHER compression spelling: skipped by the alias.
        CollectorProcessor.run(cfg);
        Files.writeString(inbox.resolve("feed.csv.gz"), "not even gzip");   // never read — alias skips it
        assertEquals(0, CollectorProcessor.countPending(cfg),
                "another spelling of the processed logical file is not pending");
    }

    @Test
    void corruptBz2QuarantinesTheOriginalWithItsRealName(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir);
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        // Real bzip2 magic, garbage body: the unpack stage claims it, fails, and fails OPEN — the
        // original flows to the engine, whose read fails, quarantining the ORIGINAL as unreadable.
        Files.write(inbox.resolve("bad.csv.bz2"),
                new byte[] {'B', 'Z', 'h', '9', 0x42, 0x13, 0x37, 0x00});

        CollectorProcessor.run(cfg);

        assertFalse(Files.exists(inbox.resolve("bad.csv.bz2")), "the bad file left the inbox");
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().quarantine()))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().equals("bad.csv.bz2")),
                    "quarantined under its ORIGINAL name");
        }
    }

    /**
     * 🔴 The archive contract end-to-end, on the DEFAULT {@code batch.max_files: 1} — so the zip's
     * three members land in THREE separate batches and the archive's own disposal must wait for the
     * last of them. Asserts what a premature marker would break: all three members' rows land, and
     * the container is backed up + marked exactly once, after they all committed.
     */
    @Test
    void multiEntryZipSpansBatchesAndTheArchiveIsDisposedOnce(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir);
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        zip(inbox.resolve("bundle.zip"),
                new String[] {"a.csv", "b.csv", "c.csv"},
                new String[] {"ID,AMT,EVENT_DATE\nz1,1.0,2020-04-03\n",
                              "ID,AMT,EVENT_DATE\nz2,2.0,2020-04-04\n",
                              "ID,AMT,EVENT_DATE\nz3,3.0,2020-04-05\n"});

        CollectorProcessor.run(cfg);

        // Every member parsed — the whole archive, not just its first entry (the old inline-zip limit).
        StringBuilder all = new StringBuilder();
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            for (Path p : w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).toList())
                all.append(Files.readString(p));
        }
        for (String id : new String[] {"z1", "z2", "z3"})
            assertTrue(all.toString().contains(id), id + " missing from " + all);

        // The CONTAINER is what leaves the inbox and what carries the marker — exactly once.
        assertFalse(Files.exists(inbox.resolve("bundle.zip")), "the archive left the inbox");
        assertTrue(Files.exists(Path.of(cfg.dirs().backup()).resolve("bundle.zip")),
                "the archive itself is the backup subject");
        assertTrue(Files.exists(Path.of(cfg.dirs().markers()).resolve("bundle.zip.processed")));
        // No member ever gets a marker of its own — members are derived, the container is the record.
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().markers()))) {
            assertEquals(0, w.filter(p -> p.getFileName().toString().startsWith("00001_")).count(),
                    "members are not marked individually");
        }
        // Nothing left in the workspace, and a re-run is a no-op.
        CollectorProcessor.run(cfg);
        assertEquals(0, CollectorProcessor.countPending(cfg));
    }

    /**
     * 🔴 Phase 4 — "the end status must be against each source file". A batch with one good and one
     * unreadable file must yield TWO manifest members: before this, only survivors were recorded, so
     * the authoritative per-Consignment record simply omitted anything that failed.
     */
    @Test
    void theManifestRecordsFailedMembersBesideSurvivors(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "  batch:\n    max_files: 10\n    max_bytes: 268435456\n");
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("good.csv"), "ID,AMT,EVENT_DATE\ng1,1.0,2020-04-03\n");
        // Binary garbage under a .csv name: read_csv cannot parse it ⇒ QUARANTINED_*.
        Files.write(inbox.resolve("bad.csv"), new byte[] {0x00, 0x01, 0x02, (byte) 0xFF, 0x00});

        CollectorProcessor.run(cfg);

        Path manifests = Path.of(cfg.dirs().manifestsDir());
        String json;
        try (Stream<Path> w = Files.walk(manifests)) {
            json = Files.readString(w.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow());
        }
        assertTrue(json.contains("good.csv"), "the survivor is recorded: " + json);
        assertTrue(json.contains("bad.csv"),
                "🔴 the FAILED member must be recorded too — that is the whole point of Phase 4: " + json);
        assertTrue(json.contains("QUARANTINED"), "with its failure status: " + json);
        // …and the failed file is NOT marked processed (it never landed; it must stay auditable).
        assertFalse(Files.exists(Path.of(cfg.dirs().markers()).resolve("bad.csv.processed")));
        assertTrue(Files.exists(Path.of(cfg.dirs().markers()).resolve("good.csv.processed")));
    }

    /**
     * 🔴 The `origin` ledger column: an unpack-expanded member records the archive/compressed
     * original it came OUT of, so a cross-pipeline problem-files view can say what the operator
     * actually dropped. Pinned end-to-end because the capture point is subtle — `writeAudit` runs
     * AFTER `commit`, whose `UnpackStage.cleanup` consumes the origin mapping, so resolving origin at
     * row-write time reads blank for every expanded file. It is captured on MemberAudit at ingest.
     *
     * <p>Extended for step 4d: the `logical_name` column beside it — the same inbox file's
     * extension-insensitive IDENTITY (poll-relative, so it is a key, unlike `origin`'s display
     * basename). ⚠ For an expanded entry that is the ARCHIVE's identity, shared by every entry of
     * the delivery, NOT the entry's own name.
     */
    @Test
    void theStatusLedgerRecordsTheArchiveAMemberCameOutOf(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, BATCH_10);
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        zip(inbox.resolve("bundle.zip"), new String[] {"a.csv"},
                new String[] {"ID,AMT,EVENT_DATE\nz1,1.0,2020-04-03\n"});
        // A plain file in the same batch, to prove origin is blank when the file IS what arrived.
        Files.writeString(inbox.resolve("plain.csv"), "ID,AMT,EVENT_DATE\np1,1.0,2020-04-03\n");

        CollectorProcessor.run(cfg);

        Path statusCsv;
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().statusFilePath()).getParent())) {
            statusCsv = w.filter(p -> p.getFileName().toString().contains("_status_")).findFirst().orElseThrow();
        }
        List<String> lines = Files.readAllLines(statusCsv);
        assertTrue(lines.get(0).endsWith(",origin,logical_name"),
                "both columns are appended last, identity after origin: " + lines.get(0));

        // Read by header NAME — the shape every consumer actually sees.
        List<java.util.Map<String, String>> rows = new java.util.ArrayList<>();
        com.gamma.util.Csv.readInto(statusCsv, rows);
        java.util.Map<String, String> member = rows.stream()
                .filter(r -> "00001_a.csv".equals(r.get("filename"))).findFirst().orElseThrow();
        assertEquals("bundle.zip", member.get("origin"), "the expanded member names its archive");
        assertEquals("bundle", member.get("logical_name"),
                "…and carries the ARCHIVE's identity — the .zip stripped, shared by every entry");

        java.util.Map<String, String> plain = rows.stream()
                .filter(r -> "plain.csv".equals(r.get("filename"))).findFirst().orElseThrow();
        assertEquals("", plain.get("origin"), "a file that arrived as itself has a BLANK origin");
        assertEquals("plain", plain.get("logical_name"),
                "…but still has an identity: its own name, with the data extension stripped");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static void zip(Path p, String[] names, String[] bodies) throws Exception {
        try (var zos = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(
                Files.newOutputStream(p))) {
            for (int i = 0; i < names.length; i++) {
                var e = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(names[i]);
                byte[] b = bodies[i].getBytes(StandardCharsets.UTF_8);
                e.setSize(b.length);
                zos.putArchiveEntry(e);
                zos.write(b);
                zos.closeArchiveEntry();
            }
        }
    }

    /** The mini-ETL fixture with the glob widened to admit compressed spellings. */
    private static PipelineConfig load(Path dir) throws Exception {
        return load(dir, "");
    }

    private static PipelineConfig load(Path dir, String batchSection) throws Exception {
        Path toon = PipelineConfigBatchTest.writePipeline(dir, batchSection);
        String cfg = Files.readString(toon).replace(
                "glob:**/*.{csv,csv.gz}", "glob:**/*.{csv,csv.gz,csv.bz2,zip}");
        Files.writeString(toon, cfg);
        return PipelineConfig.load(toon.toString());
    }

    private static void bz2(Path p, String content) throws Exception {
        try (OutputStream os = new BZip2CompressorOutputStream(Files.newOutputStream(p))) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}

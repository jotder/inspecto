package com.gamma.inspector;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.acquire.LedgerEntry;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.ConsignmentManifest;
import com.gamma.etl.ManifestStore;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ReprocessCommandTest {

    @AfterEach
    void dropLedger() {
        AcquisitionLedgers.use(null);
    }

    // ── BASIC-ELT-PROFILE-1 B3 part 2: a content-based duplicate mode's fingerprint is forgotten with the marker ──────

    /** The pipeline of these tests: the batch section of the others, then the Collector's duplicate mode (appended at top level). */
    private static Path pipeline(Path dir, String mode) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
            """);
        if (mode != null)
            Files.writeString(toon, Files.readString(toon)
                    + "collector:\n  duplicate:\n    mode: " + mode + "\n    algorithm: SHA256\n    on_change: ignore\n");
        return toon;
    }

    private static String onlyBatch(PipelineConfig cfg) throws Exception {
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().manifestsDir()))) {
            return w.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow()
                    .getFileName().toString().replace(".json", "");
        }
    }

    private static boolean hasOutput(PipelineConfig cfg) throws Exception {
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            return w.anyMatch(p -> p.getFileName().toString().endsWith("_out.csv"));
        }
    }

    private static LedgerEntry sentinel(String source, String path) {
        return new LedgerEntry(source, path, path, 5L, "deadbeef-" + path, null, null, 1L, 2L, LedgerEntry.PROCESSED);
    }

    /**
     * In {@code content} mode the ledger answers DUPLICATE to a restored file (same path, same bytes): the reprocess must
     * forget the members' fingerprints, or the poll skips them and the rows their deleted outputs held are never read again.
     * Another arrival's fingerprint, at another path, is left alone.
     */
    @Test
    void contentModeReadsTheRestoredFilesAgainAndKeepsOtherArrivals(@TempDir Path dir) throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        Path toon = pipeline(dir, "content");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        String source = cfg.collector().id();
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na,1.0,2020-04-03\n");
        Files.writeString(inbox.resolve("b.csv"), "ID,AMT,EVENT_DATE\nb,2.0,2020-04-03\n");
        CollectorProcessor.run(cfg);
        String batchId = onlyBatch(cfg);
        assertTrue(ledger.find(source, "a.csv").isPresent() && ledger.find(source, "b.csv").isPresent(),
                "precondition: the commit fingerprinted both members");
        assertTrue(hasOutput(cfg), "precondition: the run produced outputs");
        LedgerEntry other = sentinel(source, "c.csv");
        ledger.record(other);   // another arrival, another path: not this Consignment's

        ReprocessCommand.run(toon.toString(), batchId);

        assertTrue(hasOutput(cfg), "the restored files were read again, not skipped as duplicates");
        assertEquals(other, ledger.find(source, "c.csv").orElse(null), "another arrival's fingerprint survives");
    }

    /** Path mode keeps no fingerprint (its marker is the file the command already deletes): nothing is forgotten. */
    @Test
    void pathModeForgetsNoFingerprint(@TempDir Path dir) throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        Path toon = pipeline(dir, null);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na,1.0,2020-04-03\n");
        CollectorProcessor.run(cfg);
        LedgerEntry held = sentinel(cfg.collector().id(), "a.csv");
        ledger.record(held);

        ReprocessCommand.run(toon.toString(), onlyBatch(cfg));

        assertEquals(held, ledger.find(cfg.collector().id(), "a.csv").orElse(null), "path mode forgets nothing");
        assertTrue(hasOutput(cfg), "and the file is still read again");
    }

    /**
     * Only a member that SUCCEEDED and whose backup is RESTORED loses its fingerprint: the ledger keeps one row per path, so
     * that of a failed member, or of one whose file cannot come back, may be a later arrival's.
     */
    @Test
    void aFailedOrUnrestoredMemberKeepsItsFingerprint(@TempDir Path dir) throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        Path toon = pipeline(dir, "content");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        String source = cfg.collector().id();
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na,1.0,2020-04-03\n");
        Files.writeString(inbox.resolve("b.csv"), "ID,AMT,EVENT_DATE\nb,2.0,2020-04-03\n");
        Files.writeString(inbox.resolve("d.csv"), "ID,AMT,EVENT_DATE\nd,4.0,2020-04-03\n");
        CollectorProcessor.run(cfg);
        String batchId = onlyBatch(cfg);
        ConsignmentManifest m = ManifestStore.read(cfg.dirs().manifestsDir(), batchId);
        // a.csv: its status says it did not succeed; b.csv: its backup is gone; d.csv: restored and succeeded
        for (int i = 0; i < m.members.size(); i++) {
            ConsignmentManifest.MemberEntry me = m.members.get(i);
            if (me.filename().equals("a.csv"))
                m.members.set(i, new ConsignmentManifest.MemberEntry(me.filename(), me.srcId(), me.originalRelPath(),
                        me.backupPath(), "QUARANTINED_MISMATCH"));
        }
        ManifestStore.write(cfg.dirs().manifestsDir(), m);
        Files.delete(Path.of(cfg.dirs().backup(), "b.csv"));
        LedgerEntry a = ledger.find(source, "a.csv").orElseThrow(), b = ledger.find(source, "b.csv").orElseThrow();
        LedgerEntry d = ledger.find(source, "d.csv").orElseThrow();

        ReprocessCommand.run(toon.toString(), batchId);

        assertEquals(a, ledger.find(source, "a.csv").orElse(null), "a failed member keeps its fingerprint");
        assertEquals(b, ledger.find(source, "b.csv").orElse(null), "so does one whose file could not be restored");
        assertTrue(Files.exists(inbox.resolve("a.csv")) || Files.exists(Path.of(cfg.dirs().backup(), "a.csv")), "a.csv was not lost");
        LedgerEntry reread = ledger.find(source, "d.csv").orElse(null);
        assertNotNull(reread, "d.csv was read again and fingerprinted again");
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "d.csv")), "d.csv went through a fresh poll");
        assertTrue(reread.processedAt() >= d.processedAt());
    }

    /**
     * A folder name may hold {@code !}, the archive-member separator: the key a reprocess forgets is the one the commit
     * recorded (the logical name of the compressed file, in its folder), not whatever precedes the first {@code !}.
     */
    @Test
    void aCompressedFileInAFolderNamedWithABangIsReadAgain(@TempDir Path dir) throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        Path toon = pipeline(dir, "content");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path folder = Files.createDirectories(Path.of(cfg.dirs().poll()).resolve("in!box"));
        try (java.io.OutputStream out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(folder.resolve("feed.csv.gz")))) {
            out.write("ID,AMT,EVENT_DATE\ng,1.0,2020-04-03\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        CollectorProcessor.run(cfg);
        String batchId = onlyBatch(cfg);
        assertTrue(hasOutput(cfg), "precondition: the run produced outputs");

        ReprocessCommand.run(toon.toString(), batchId);

        assertTrue(hasOutput(cfg), "the restored file was read again, not skipped under a key the forget missed");
    }

    /** The key of a manifest member is the commit's: an archive entry by the archive's name, whatever its folder holds. */
    @Test
    void theLedgerKeyOfAMemberIsTheCommitsKey(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(pipeline(dir, "content").toString());
        // as ConsignmentIngestor.commit calls ledgerKey: the original's own name and the path (+ "!entry" of an archive)
        assertEquals(ConsignmentIngestor.ledgerKey(cfg, "bundle.zip", "bundle.zip!00002_two.csv"),
                ConsignmentIngestor.ledgerKeyOfMember(cfg, "00002_two.csv", "bundle.zip!00002_two.csv"));
        assertEquals(ConsignmentIngestor.ledgerKey(cfg, "bundle.zip", "a!b/bundle.zip!00002_two.csv"),
                ConsignmentIngestor.ledgerKeyOfMember(cfg, "00002_two.csv", "a!b/bundle.zip!00002_two.csv"),
                "a ! in a folder is not the separator");
        assertEquals(ConsignmentIngestor.ledgerKey(cfg, "feed.csv.gz", "a!b/feed.csv.gz"),
                ConsignmentIngestor.ledgerKeyOfMember(cfg, "feed.csv", "a!b/feed.csv.gz"), "a 1-to-1 expansion: the original's name");
        assertEquals("a!b/feed", ConsignmentIngestor.ledgerKeyOfMember(cfg, "feed.csv", "a!b/feed.csv.gz"),
                "its logical name (no compression or data extension), in its folder");
        assertEquals("x!y.csv", ConsignmentIngestor.ledgerKeyOfMember(cfg, "x!y.csv", "x!y.csv"), "a plain file named with a !");
    }

    @Test
    void deletesOutputsRestoresFilesAndReprocesses(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
            """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na,1.0,2020-04-03\n");
        Files.writeString(inbox.resolve("b.csv"), "ID,AMT,EVENT_DATE\nb,2.0,2020-04-03\n");

        CollectorProcessor.run(cfg);

        // Find the batch id from the single manifest written.
        String batchId;
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().manifestsDir()))) {
            Path mf = w.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
            batchId = mf.getFileName().toString().replace(".json", "");
        }

        // Sanity: outputs + markers + backup present before reprocess.
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "a.csv")));
        assertTrue(Files.exists(Path.of(cfg.dirs().markers(), "a.csv.processed")));

        // Reprocess: must restore files, delete old outputs/markers, supersede manifest, re-run.
        ReprocessCommand.run(toon.toString(), batchId);

        // Old manifest superseded.
        assertTrue(Files.exists(Path.of(cfg.dirs().manifestsDir(), batchId + ".json.superseded")));
        // Markers exist again (re-run re-created them) and outputs exist.
        assertTrue(Files.exists(Path.of(cfg.dirs().markers(), "a.csv.processed")));
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().endsWith("_out.csv")));
        }
    }

    /**
     * §11.3(a) — the silent data-duplication bug, now refused. With the output files merged away by compaction,
     * the old path deleted nothing (its {@code deleteIfExists} no-ops), restored the members and re-ingested
     * rows that still exist inside the merged file. This asserts the refusal, and that it names the reason.
     */
    @Test
    void refusesWhenTheOutputsWereCompactedAway(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
            """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na,1.0,2020-04-03\n");

        CollectorProcessor.run(cfg);

        String batchId;
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().manifestsDir()))) {
            batchId = w.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow()
                    .getFileName().toString().replace(".json", "");
        }
        ConsignmentManifest m = ManifestStore.read(cfg.dirs().manifestsDir(), batchId);
        assertFalse(m.outputs.isEmpty(), "precondition: the run produced outputs to compact away");

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(m.outputs.stream()
                    .map(o -> new ConsignmentOutput(batchId, null, "t", "dt=2020-04-03", "2020-04-03",
                            o.outputFile(), 1L, 100L, "2026-08-04T10:00:00Z",
                            ConsignmentOutput.State.LIVE))
                    .toList());
            // What PartitionCompactor now does when it merges those files away.
            assertEquals(m.outputs.size(),
                    store.markCompactedAway(m.outputs.stream().map(o -> o.outputFile()).toList()));
            ConsignmentOutputStores.use(store);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ReprocessCommand.run(toon.toString(), batchId));
            assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
            assertTrue(e.getMessage().contains(batchId), e.getMessage());
            // REPROCESS-REFUSAL-500-1: a typed refusal whose reason (the control plane's 409) names no server path
            String reason = assertInstanceOf(ReprocessCommand.Refused.class, e).reason();
            assertTrue(reason.contains("merged away by compaction") && reason.contains(batchId), reason);
            for (ConsignmentManifest.OutputEntry o : m.outputs)
                assertFalse(reason.contains(Path.of(o.outputFile()).getFileName().toString()), reason);

            // And it refused before mutating anything — the manifest is still live.
            assertTrue(Files.exists(Path.of(cfg.dirs().manifestsDir(), batchId + ".json")));
            assertFalse(Files.exists(Path.of(cfg.dirs().manifestsDir(), batchId + ".json.superseded")));
        } finally {
            ConsignmentOutputStores.use(null);
        }
    }

    /**
     * {@code BASIC-ELT-PROFILE-1} B4: a Consignment a later arrival replaced rows of ({@code REPLACED}: Replace the period
     * rewrote its file without some days, or Replace everything set it aside) is refused too - re-ingesting its backup
     * would bring the replaced rows back - before anything moves.
     */
    @Test
    void refusesWhenALaterArrivalReplacedRowsOfItsOutputs(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
            """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na,1.0,2020-04-03\n");
        CollectorProcessor.run(cfg);
        String batchId;
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().manifestsDir()))) {
            batchId = w.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow()
                    .getFileName().toString().replace(".json", "");
        }
        ConsignmentManifest m = ManifestStore.read(cfg.dirs().manifestsDir(), batchId);
        List<String> outputs = m.outputs.stream().map(o -> o.outputFile()).toList();

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(outputs.stream().map(o -> new ConsignmentOutput(batchId, null, "t", "dt=2020-04-03", "2020-04-03",
                    o, 1L, 100L, "2026-08-04T10:00:00Z", ConsignmentOutput.State.LIVE)).toList());
            assertEquals(outputs.size(), store.markReplaced(outputs));
            ConsignmentOutputStores.use(store);

            ReprocessCommand.Refused e = assertThrows(ReprocessCommand.Refused.class,
                    () -> ReprocessCommand.run(toon.toString(), batchId));
            assertTrue(e.reason().contains("replaced by a later arrival") && e.reason().contains(batchId), e.reason());
            for (String o : outputs) {
                assertFalse(e.reason().contains(Path.of(o).getFileName().toString()), "no server path: " + e.reason());
                assertTrue(Files.exists(Path.of(o)), "nothing was deleted: " + o);
            }
            assertTrue(Files.exists(Path.of(cfg.dirs().manifestsDir(), batchId + ".json")), "the manifest is still live");
        } finally {
            ConsignmentOutputStores.use(null);
        }
    }

    /** The same run must still succeed while the outputs are LIVE — the guard blocks only the unsafe case. */
    @Test
    void stillReprocessesWhenTheRegistryShowsLiveOutputs(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
            """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na,1.0,2020-04-03\n");

        CollectorProcessor.run(cfg);
        String batchId;
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().manifestsDir()))) {
            batchId = w.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow()
                    .getFileName().toString().replace(".json", "");
        }
        ConsignmentManifest m = ManifestStore.read(cfg.dirs().manifestsDir(), batchId);

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(m.outputs.stream()
                    .map(o -> new ConsignmentOutput(batchId, null, "t", "dt=2020-04-03", "2020-04-03",
                            o.outputFile(), 1L, 100L, "2026-08-04T10:00:00Z",
                            ConsignmentOutput.State.LIVE))
                    .toList());
            ConsignmentOutputStores.use(store);

            List<String> originalPaths = m.outputs.stream().map(o -> o.outputFile()).toList();

            ReprocessCommand.run(toon.toString(), batchId);

            assertTrue(Files.exists(Path.of(cfg.dirs().manifestsDir(), batchId + ".json.superseded")));

            // Count SUPERSEDED rows rather than asserting every row for this id (or even for these paths) has
            // moved. Two things collide here, both by design: batch ids are yyyyMMdd_HHmmss, so a reprocess
            // finishing inside the same second re-ingests under the *same* id (ReprocessCommand's "fresh batch
            // id" holds only across a second boundary), and output file names are deterministic
            // (<baseName>_out.<ext>), so the re-run lands on the same path. The result is one SUPERSEDED row
            // and one fresh LIVE row sharing a path — which is exactly the history the registry should keep.
            List<ConsignmentOutput> rows = store.outputs(batchId);
            assertEquals(originalPaths.size(),
                    rows.stream().filter(o -> o.state() == ConsignmentOutput.State.SUPERSEDED).count(),
                    "every pre-existing row is superseded alongside the manifest, got "
                            + rows.stream().map(ConsignmentOutput::state).toList());
            assertTrue(rows.stream().anyMatch(o -> originalPaths.contains(o.path())),
                    "the original output paths are still listed — superseded, not deleted");
        } finally {
            ConsignmentOutputStores.use(null);
        }
    }
}

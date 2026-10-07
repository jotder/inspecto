package com.gamma.inspector;

import com.gamma.etl.Consignment;
import com.gamma.etl.ConsignmentManifest;
import com.gamma.etl.ManifestStore;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import com.gamma.etl.unpack.UnpackOrigins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The unpack stage's two honesty guarantees at the manifest boundary (BACKLOG §4 "Unpack stage —
 * open items" (3)+(4), fixed 2026-08-26):
 *
 * <ol>
 *   <li>an archive entry the expansion had to SKIP (encrypted / unsupported method) becomes a
 *       {@code SKIPPED_UNREADABLE} manifest row — a partial expansion must never read as a clean
 *       success — drained from {@link UnpackOrigins} exactly once, by the first of the archive's
 *       batches to finalize;</li>
 *   <li>the archive-LEVEL status vocabulary stays untouched (unpack plan §6 Q1 — still the
 *       operator's), so the skip row carries no srcId (-1), no backup and no marker.</li>
 * </ol>
 */
class UnpackManifestReportingTest {

    private Consignment.Member member(PipelineConfig cfg, File f, int id) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        return new Consignment.Member(f, id, f.length(), sel);
    }

    @Test
    void skippedEntriesBecomeManifestRowsDrainedExactlyOnce(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        // The archive sits in the inbox; its one readable entry was expanded into temp and
        // registered with its ENTRY name — the workspace copy carries the ordering prefix.
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Path archive = Files.writeString(inbox.resolve("feed.zip"), "not really a zip — never read here");
        Path work = Files.createDirectories(Path.of(cfg.dirs().temp()).resolve("unpack_fixture"));
        Path entry = Files.writeString(work.resolve("00002_good.csv"),
                "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        Path entry2 = Files.writeString(work.resolve("00003_more.csv"),
                "ID,AMT,EVENT_DATE\ny,1.0,2020-04-04\n");
        UnpackOrigins.register(entry, archive.toFile(), "good.csv");
        UnpackOrigins.register(entry2, archive.toFile(), "more.csv");
        UnpackOrigins.registerSkipped(archive.toFile(), List.of("locked.csv"));

        List<Consignment.Member> survivors = List.of(member(cfg, entry.toFile(), 0));
        Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_mini_0007", "mini", null, survivors);
        ConsignmentIngestor.finalizeSource(batch, cfg, survivors, List.of(), List.of());

        ConsignmentManifest m = ManifestStore.read(cfg.dirs().manifestsDir(), batch.batchId());
        ConsignmentManifest.MemberEntry skipped = m.members.stream()
                .filter(e -> "SKIPPED_UNREADABLE".equals(e.status())).findFirst()
                .orElseThrow(() -> new AssertionError("no SKIPPED_UNREADABLE row in " + m.members));
        assertEquals("locked.csv", skipped.filename(), "the ENTRY name, there is no temp file to name");
        assertEquals(-1, skipped.srcId(), "never planned, so no srcId");
        assertEquals("feed.zip!locked.csv", skipped.originalRelPath(), "JAR-style archive!entry address");        assertEquals("", skipped.backupPath(), "nothing was backed up — there are no bytes");
        assertTrue(m.members.stream().anyMatch(e -> "SUCCESS".equals(e.status())),
                "the readable member's own row is untouched beside it");

        // Drained exactly once: the archive's SECOND batch (max_files:1 spreads an archive's
        // members over batches) writes no second skip row.
        List<Consignment.Member> survivors2 = List.of(member(cfg, entry2.toFile(), 0));
        Consignment again = new Consignment(cfg.identity().runTimestamp() + "_mini_0008", "mini", null, survivors2);
        ConsignmentIngestor.finalizeSource(again, cfg, survivors2, List.of(), List.of());
        ConsignmentManifest m2 = ManifestStore.read(cfg.dirs().manifestsDir(), again.batchId());
        assertTrue(m2.members.stream().noneMatch(e -> "SKIPPED_UNREADABLE".equals(e.status())),
                "the skip record is drained by the FIRST finalizing batch only");
    }
}

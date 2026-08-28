package com.gamma.etl.unpack;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 1→N ARCHIVE half of the unpack stage (unpack-stage plan Phase 3 + the archive half of the
 * Phase 2 caps): zip / tar / tar.gz expansion, archive-order naming, zip-slip refusal, the caps, the
 * longest-suffix resolution that keeps {@code .tar.gz} out of the plain-gzip plugin's hands, and the
 * refcounted origin bookkeeping that lets an archive's entries span batches.
 */
class ArchiveUnpackTest {

    private static final String A = "ID,V\na,1\n";
    private static final String B = "ID,V\nb,2\n";

    // ── resolution ─────────────────────────────────────────────────────────────

    /**
     * 🔴 {@code feed.tar.gz} is claimed by BOTH the tar.gz plugin and the plain gzip plugin (gzip
     * magic; {@code .gz} is a suffix of {@code .tar.gz}). Longest-suffix resolution must pick the
     * archive — expanding it as a stream would yield one undifferentiated tar blob and silently lose
     * every entry but the concatenation.
     */
    @Test
    void tarGzResolvesToTheArchivePluginNotPlainGzip(@TempDir Path dir) throws Exception {
        Path tgz = tarGz(dir, "feed.tar.gz", List.of("a.csv", "b.csv"), List.of(A, B));
        DecompressorPlugin p = Decompressors.forFile(tgz).orElseThrow();
        assertEquals("tar.gz", p.id());
        assertEquals(DecompressorPlugin.Kind.ARCHIVE, p.kind());

        assertEquals("tar.gz", Decompressors.forFile(
                tarGz(dir, "feed.tgz", List.of("a.csv"), List.of(A))).orElseThrow().id());
    }

    @Test
    void zipAndTarResolveByMagicAndSuffix(@TempDir Path dir) throws Exception {
        assertEquals("zip", Decompressors.forFile(zip(dir, "z.zip", List.of("a.csv"), List.of(A)))
                .orElseThrow().id());
        assertEquals("tar", Decompressors.forFile(tar(dir, "t.tar", List.of("a.csv"), List.of(A)))
                .orElseThrow().id());
        // A .zip name over non-zip bytes is unclaimed — it flows to the engine, which reports it.
        Path fake = dir.resolve("fake.zip");
        Files.writeString(fake, "definitely not a zip");
        assertTrue(Decompressors.forFile(fake).isEmpty());
    }

    // ── expansion ──────────────────────────────────────────────────────────────

    @Test
    void zipExpandsEveryEntryInArchiveOrder(@TempDir Path dir) throws Exception {
        Path z = zip(dir, "multi.zip", List.of("second.csv", "first.csv"), List.of(A, B));
        Path work = Files.createDirectories(dir.resolve("w"));

        List<Path> out = new ArchiveDecompressorPlugin.Zip().expand(z, work, UnpackLimits.DEFAULTS);

        assertEquals(2, out.size());
        // Zero-padded index ⇒ PATH order == ARCHIVE order, which is what the planner's
        // mtime-then-path tie-break falls back to (every entry shares an mtime).
        assertEquals(List.of("00001_second.csv", "00002_first.csv"),
                out.stream().map(p -> p.getFileName().toString()).toList());
        assertEquals(A, Files.readString(out.get(0)));
        assertEquals(B, Files.readString(out.get(1)));
        assertTrue(out.stream().allMatch(p -> p.startsWith(work)));
    }

    @Test
    void tarGzExpandsEveryEntry(@TempDir Path dir) throws Exception {
        Path tgz = tarGz(dir, "t.tar.gz", List.of("a.csv", "b.csv"), List.of(A, B));
        List<Path> out = new ArchiveDecompressorPlugin.TarGz()
                .expand(tgz, Files.createDirectories(dir.resolve("w")), UnpackLimits.DEFAULTS);
        assertEquals(2, out.size());
        assertEquals(A, Files.readString(out.get(0)));
    }

    /** Directory entries are skipped, and a nested path is FLATTENED into the workspace. */
    @Test
    void nestedEntryPathsAreFlattenedAndDirectoriesSkipped(@TempDir Path dir) throws Exception {
        Path z = zip(dir, "nested.zip", List.of("sub/dir/deep.csv"), List.of(A));
        List<Path> out = new ArchiveDecompressorPlugin.Zip()
                .expand(z, Files.createDirectories(dir.resolve("w")), UnpackLimits.DEFAULTS);
        assertEquals(List.of("00001_deep.csv"), out.stream().map(p -> p.getFileName().toString()).toList());
    }

    /** 🔴 Zip-slip: a traversing entry name must never write outside the workspace. */
    @Test
    void zipSlipEntryWritesNothingOutsideTheWorkspace(@TempDir Path dir) throws Exception {
        Path z = zip(dir, "slip.zip", List.of("../../escaped.csv"), List.of(A));
        Path work = Files.createDirectories(dir.resolve("w"));
        List<Path> out = new ArchiveDecompressorPlugin.Zip().expand(z, work, UnpackLimits.DEFAULTS);
        // Flattening removes the traversal, so the entry lands INSIDE work under its bare name…
        assertTrue(out.get(0).startsWith(work), out.get(0).toString());
        // …and nothing appeared beside the workspace.
        assertFalse(Files.exists(dir.resolve("escaped.csv")));
        assertFalse(Files.exists(dir.getParent().resolve("escaped.csv")));
    }

    // ── caps (archive half of Phase 2) ─────────────────────────────────────────

    @Test
    void maxEntriesCapFailsWholeAndLeavesNoPartial(@TempDir Path dir) throws Exception {
        Path z = zip(dir, "many.zip", List.of("a.csv", "b.csv", "c.csv"), List.of(A, B, A));
        Path work = Files.createDirectories(dir.resolve("w"));
        UnpackLimits tight = new UnpackLimits(2, 1L << 30, 1L << 30, 0d, 1);
        IOException e = assertThrows(IOException.class,
                () -> new ArchiveDecompressorPlugin.Zip().expand(z, work, tight));
        assertTrue(e.getMessage().contains("max_entries"), e.getMessage());
        try (var kids = Files.list(work)) {
            assertEquals(0, kids.count(), "a breached cap never leaves a partial expansion behind");
        }
    }

    @Test
    void maxTotalBytesCapFailsWhole(@TempDir Path dir) throws Exception {
        Path z = zip(dir, "big.zip", List.of("a.csv", "b.csv"), List.of(A.repeat(50), B.repeat(50)));
        Path work = Files.createDirectories(dir.resolve("w"));
        UnpackLimits tight = new UnpackLimits(10, 1L << 30, 100, 0d, 1);
        IOException e = assertThrows(IOException.class,
                () -> new ArchiveDecompressorPlugin.Zip().expand(z, work, tight));
        assertTrue(e.getMessage().contains("max_total_bytes"), e.getMessage());
        try (var kids = Files.list(work)) {
            assertEquals(0, kids.count());
        }
    }

    @Test
    void emptyArchiveIsAFailureNotAnEmptyExpansion(@TempDir Path dir) throws Exception {
        Path z = zip(dir, "empty.zip", List.of(), List.of());
        IOException e = assertThrows(IOException.class, () -> new ArchiveDecompressorPlugin.Zip()
                .expand(z, Files.createDirectories(dir.resolve("w")), UnpackLimits.DEFAULTS));
        assertTrue(e.getMessage().contains("no readable entries"), e.getMessage());
    }

    // ── unreadable entries (open item (4), honesty half) ───────────────────────

    /** {@code entryName} is the exact reverse of the workspace's {@code <NNNNN>_} naming — and only that. */
    @Test
    void entryNameStripsOnlyTheOrderingPrefix() {
        assertEquals("a.csv", ArchiveDecompressorPlugin.entryName("00001_a.csv"));
        // an entry whose REAL name looks like a prefix keeps it — only the outer one is ours
        assertEquals("00042_data.csv", ArchiveDecompressorPlugin.entryName("00007_00042_data.csv"));
        assertEquals("entry", ArchiveDecompressorPlugin.entryName("000123_entry"));  // %05d widened
        assertEquals("feed.csv", ArchiveDecompressorPlugin.entryName("feed.csv"));   // no prefix
        assertEquals("123_x.csv", ArchiveDecompressorPlugin.entryName("123_x.csv")); // too few digits
        assertEquals("a1cde_x.csv", ArchiveDecompressorPlugin.entryName("a1cde_x.csv")); // not digits
    }

    /**
     * 🔴 An encrypted (or unsupported-method) entry must be REPORTED, never silently dropped — an
     * encrypted zip that expands to fewer entries must not look like a clean success.
     */
    @Test
    void unreadableEntryIsSkippedAndReported(@TempDir Path dir) throws Exception {
        Path z = encryptedFirstEntryZip(dir, "mixed.zip", "locked.csv", "open.csv");
        Path work = Files.createDirectories(dir.resolve("w"));
        List<String> skipped = new java.util.ArrayList<>();
        List<Path> out = new ArchiveDecompressorPlugin.Zip()
                .expand(z, work, UnpackLimits.DEFAULTS, skipped);
        assertEquals(List.of("locked.csv"), skipped, "the skip is reported to the caller");
        assertEquals(List.of("00001_open.csv"),
                out.stream().map(p -> p.getFileName().toString()).toList());
        assertEquals(B, Files.readString(out.get(0)));
    }

    /** An ALL-unreadable archive still fails whole — that posture is unchanged. */
    @Test
    void allUnreadableArchiveStillFailsWhole(@TempDir Path dir) throws Exception {
        Path z = encryptedFirstEntryZip(dir, "locked.zip", "only.csv", null);
        IOException e = assertThrows(IOException.class, () -> new ArchiveDecompressorPlugin.Zip()
                .expand(z, Files.createDirectories(dir.resolve("w")), UnpackLimits.DEFAULTS));
        assertTrue(e.getMessage().contains("no readable entries"), e.getMessage());
    }

    /**
     * End-to-end through the stage: lineage gets the ENTRY name (open item (3) — never the
     * index-prefixed temp name), and the skipped entries are recorded against the ORIGINAL for the
     * manifest to drain — exactly once.
     */
    @Test
    void stageRecordsEntryLineageNamesAndSkippedEntries(@TempDir Path dir) throws Exception {
        var cfg = UnpackFixtures.load(dir, "");
        Path inbox = Path.of(cfg.dirs().poll());
        Path z = encryptedFirstEntryZip(inbox, "mixed.zip", "locked.csv", "open.csv");

        List<File> out = UnpackStage.expand(cfg, List.of(z.toFile()));

        assertEquals(1, out.size());
        assertTrue(out.get(0).getName().endsWith("_open.csv"), out.get(0).getName());
        assertEquals("open.csv", UnpackOrigins.lineageName(out.get(0)),
                "lineage records the ENTRY name, never the workspace temp name");
        assertEquals(List.of("locked.csv"), UnpackOrigins.takeSkipped(z.toFile()));
        assertEquals(List.of(), UnpackOrigins.takeSkipped(z.toFile()), "drained exactly once");
        UnpackStage.cleanup(out.get(0));   // release the static registry for other tests
    }

    // ── the refcounted origin bookkeeping ──────────────────────────────────────

    /**
     * 🔴 The cross-batch contract. With {@code batch.max_files: 1} (the DEFAULT) an N-entry archive's
     * entries land in N separate batches, so the archive's own backup+marker may only fire when the
     * LAST entry commits — {@link UnpackStage#cleanup} returns the original exactly once, on that
     * last call. Marking it earlier would strand every entry still to come.
     */
    @Test
    void originalIsReleasedOnlyByItsLastEntry(@TempDir Path dir) throws Exception {
        Path work = Files.createDirectories(dir.resolve("w"));
        File archive = dir.resolve("arch.zip").toFile();
        Files.writeString(archive.toPath(), "x");
        Path e1 = Files.writeString(work.resolve("00001_a.csv"), A);
        Path e2 = Files.writeString(work.resolve("00002_b.csv"), B);
        Path e3 = Files.writeString(work.resolve("00003_c.csv"), A);

        UnpackOrigins.register(e1, archive);
        UnpackOrigins.register(e2, archive);
        UnpackOrigins.register(e3, archive);
        assertEquals(3, UnpackOrigins.totalFor(archive), "archive semantics: total > 1");

        assertNull(UnpackStage.cleanup(e1.toFile()), "first entry: the archive is NOT yet done");
        assertNull(UnpackStage.cleanup(e2.toFile()), "second entry: still not done");
        assertEquals(archive, UnpackStage.cleanup(e3.toFile()), "the LAST entry releases the archive");
        assertFalse(Files.exists(e1), "each entry's scratch copy is deleted as it completes");
        assertFalse(Files.exists(e3));
        // Idempotent: a repeat call reports nothing rather than releasing twice.
        assertNull(UnpackStage.cleanup(e3.toFile()));
    }

    /** A 1→1 stream expansion releases immediately — total == 1, so the first call is the last. */
    @Test
    void streamExpansionReleasesOnFirstCleanup(@TempDir Path dir) throws Exception {
        Path work = Files.createDirectories(dir.resolve("w"));
        File original = dir.resolve("feed.csv.bz2").toFile();
        Files.writeString(original.toPath(), "x");
        Path expanded = Files.writeString(work.resolve("feed.csv"), A);
        UnpackOrigins.register(expanded, original);
        assertEquals(1, UnpackOrigins.totalFor(original));
        assertEquals(original, UnpackStage.cleanup(expanded.toFile()));
    }

    /**
     * Open item (9): a batch that fails at COMMIT runs neither release path, leaving its mappings
     * behind. The end-of-run sweep drops exactly the failed run's entries (keyed by the run's poll
     * root) — a concurrent pipeline on a DIFFERENT root keeps its in-flight entries.
     */
    @Test
    void sweepDropsOnlyThePollRootsLeftovers(@TempDir Path dir) throws Exception {
        Path rootA = Files.createDirectories(dir.resolve("inboxA"));
        Path rootB = Files.createDirectories(dir.resolve("inboxB"));
        Path work = Files.createDirectories(dir.resolve("w"));
        File leakedArchive = rootA.resolve("arch.zip").toFile();
        Files.writeString(leakedArchive.toPath(), "x");
        File otherOriginal = rootB.resolve("feed.csv.bz2").toFile();
        Files.writeString(otherOriginal.toPath(), "x");
        Path leaked1 = Files.writeString(work.resolve("00001_l1.csv"), A);
        Path leaked2 = Files.writeString(work.resolve("00002_l2.csv"), B);
        Path inflight = Files.writeString(work.resolve("00003_ok.csv"), A);

        UnpackOrigins.register(leaked1, leakedArchive);
        UnpackOrigins.register(leaked2, leakedArchive);
        UnpackOrigins.registerSkipped(leakedArchive, java.util.List.of("locked.csv"));
        UnpackOrigins.register(inflight, otherOriginal);

        assertEquals(2, UnpackOrigins.sweep(rootA), "both of the failed run's mappings dropped");
        assertFalse(UnpackOrigins.isExpanded(leaked1.toFile()), "mapping gone");
        assertEquals(0, UnpackOrigins.totalFor(leakedArchive), "archive semantics no longer reported");
        assertEquals(java.util.List.of(), UnpackOrigins.takeSkipped(leakedArchive), "skipped record purged");
        assertEquals(leaked1.toFile().getName(), UnpackOrigins.lineageName(leaked1.toFile()),
                "lineage name falls back to the plain filename after the sweep");
        // The other root's entry is untouched and still releases normally.
        assertTrue(UnpackOrigins.isExpanded(inflight.toFile()));
        assertEquals(otherOriginal, UnpackOrigins.consume(inflight.toFile()));
        assertEquals(0, UnpackOrigins.sweep(rootA), "idempotent: a clean repeat sweeps nothing");
    }

    // ── config surface + threading (Phases 5/6) ────────────────────────────────

    /** An absent block is the shipped posture; a partial block overrides only what it states. */
    @Test
    void unpackConfigDefaultsAndPartialOverrides(@TempDir Path dir) throws Exception {
        var d = com.gamma.etl.PipelineConfig.Unpack.defaults();
        assertTrue(UnpackFixtures.load(dir, "").unpack().enabled(), "absent block ⇒ stage on");
        assertEquals(d.maxEntries(), UnpackFixtures.load(dir, "").unpack().maxEntries());

        var partial = UnpackFixtures.load(dir, "  unpack:\n    max_entries: 5\n    threads: 3\n").unpack();
        assertEquals(5, partial.maxEntries());
        assertEquals(3, partial.threads());
        assertEquals(d.maxTotalBytes(), partial.maxTotalBytes(), "unstated keys keep the shipped cap");
        assertTrue(partial.enabled());
    }

    @Test
    void unpackConfigRefusesNonsenseFailClosed(@TempDir Path dir) {
        for (String bad : List.of("    max_entries: 0\n", "    max_ratio: -1\n",
                                  "    threads: 0\n", "    max_total_bytes: 0\n"))
            assertThrows(IllegalArgumentException.class,
                    () -> UnpackFixtures.load(dir, "  unpack:\n" + bad), bad);
        // depth > 1 is refused by name: nested archives are a deliberate non-feature, not an oversight.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> UnpackFixtures.load(dir, "  unpack:\n    depth: 2\n"));
        assertTrue(e.getMessage().contains("nested archives"), e.getMessage());
    }

    /** {@code enabled: false} is a full bypass — a claimed file passes through untouched. */
    @Test
    void disabledStageLeavesEverythingAlone(@TempDir Path dir) throws Exception {
        var cfg = UnpackFixtures.load(dir, "  unpack:\n    enabled: false\n");
        Path z = zip(Path.of(cfg.dirs().poll()), "off.zip", List.of("a.csv"), List.of(A));
        assertEquals(List.of(z.toFile()), UnpackStage.expand(cfg, List.of(z.toFile())));
    }

    /**
     * Parallel expansion must be observationally identical to sequential: the SAME files, in the
     * SAME order (the stage decides up front and reassembles in candidate order, so thread
     * scheduling cannot reorder what the planner sees) and byte-identical contents.
     */
    @Test
    void parallelExpansionMatchesSequentialExactly(@TempDir Path dir) throws Exception {
        List<String> names = List.of("a.csv", "b.csv", "c.csv", "d.csv");
        List<String> bodies = List.of(A, B, A + B, B + A);

        List<String> sequential = expandNames(dir.resolve("seq"), 1, names, bodies);
        List<String> parallel   = expandNames(dir.resolve("par"), 4, names, bodies);

        assertEquals(4, sequential.size());
        assertEquals(sequential, parallel, "parallel unpack must not reorder or drop entries");
    }

    /** Expand 4 single-entry archives at the given thread count; returns the entry names in order. */
    private static List<String> expandNames(Path root, int threads,
                                            List<String> names, List<String> bodies) throws Exception {
        var cfg = UnpackFixtures.load(root, "  unpack:\n    threads: " + threads + "\n");
        Path inbox = Path.of(cfg.dirs().poll());
        List<File> in = new java.util.ArrayList<>();
        for (int i = 0; i < names.size(); i++)
            in.add(zip(inbox, "arch" + i + ".zip", List.of(names.get(i)), List.of(bodies.get(i))).toFile());

        List<File> out = UnpackStage.expand(cfg, in);
        List<String> got = new java.util.ArrayList<>();
        for (File f : out) {
            got.add(f.getName());
            assertTrue(f.exists(), f + " was expanded");
        }
        return got;
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    /**
     * A zip whose FIRST entry carries the encryption flag ({@code lockedName}, body {@link #A}) —
     * the streaming reader then reports {@code canReadEntryData == false} for it, exactly like a
     * password-protected entry. {@code openName} (body {@link #B}) follows as a readable entry, or
     * pass null for an all-unreadable archive. STORED entries, so the reader can skip the locked one
     * by its known size.
     */
    private static Path encryptedFirstEntryZip(Path dir, String name,
                                               String lockedName, String openName) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        List<String> names  = openName != null ? List.of(lockedName, openName) : List.of(lockedName);
        List<String> bodies = openName != null ? List.of(A, B) : List.of(A);
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
        // Flip the encryption bit (general-purpose bit 0, 2-byte LE field at offset 6 of the local
        // file header — the first LFH starts at byte 0) on the FIRST entry only.
        byte[] bytes = Files.readAllBytes(p);
        bytes[6] |= 1;
        Files.write(p, bytes);
        return p;
    }

    private static Path zip(Path dir, String name, List<String> names, List<String> bodies) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(Files.newOutputStream(p))) {
            for (int i = 0; i < names.size(); i++) {
                ZipArchiveEntry e = new ZipArchiveEntry(names.get(i));
                byte[] b = bodies.get(i).getBytes(StandardCharsets.UTF_8);
                e.setSize(b.length);
                zos.putArchiveEntry(e);
                zos.write(b);
                zos.closeArchiveEntry();
            }
        }
        return p;
    }

    private static Path tar(Path dir, String name, List<String> names, List<String> bodies) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(Files.newOutputStream(p))) {
            writeTar(tos, names, bodies);
        }
        return p;
    }

    private static Path tarGz(Path dir, String name, List<String> names, List<String> bodies) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        try (OutputStream gz = new GzipCompressorOutputStream(Files.newOutputStream(p));
             TarArchiveOutputStream tos = new TarArchiveOutputStream(gz)) {
            writeTar(tos, names, bodies);
        }
        return p;
    }

    private static void writeTar(TarArchiveOutputStream tos, List<String> names, List<String> bodies)
            throws IOException {
        for (int i = 0; i < names.size(); i++) {
            byte[] b = bodies.get(i).getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry e = new TarArchiveEntry(names.get(i));
            e.setSize(b.length);
            tos.putArchiveEntry(e);
            tos.write(b);
            tos.closeArchiveEntry();
        }
    }
}

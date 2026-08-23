package com.gamma.etl.unpack;

import com.gamma.etl.MarkerManager;
import com.gamma.etl.PipelineConfig;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Collector-level unpack stage (unpack-stage plan Phases 1 + 1b + the stream half of 2):
 * plugin resolution by suffix+magic, engine-aware expansion, fail-open on corrupt input, the
 * fail-closed caps, the extension-insensitive {@link LogicalNames} identity, and the marker alias.
 *
 * <p>{@code .Z} decode itself is commons-compress's tested code behind a one-line wrapper, and the
 * library offers no {@code .Z} <em>writer</em> to build a fixture with — so {@code .Z} is covered at
 * the {@code supports()} level (suffix + magic) and via the corrupt-input path, not by a round-trip.
 */
class UnpackStageTest {

    private static final String CSV = "MARKER,ID,VAL\nA,1,x\nB,2,y\n";

    // ── plugin resolution ──────────────────────────────────────────────────────

    @Test
    void resolvesBySuffixAndMagicTogether(@TempDir Path dir) throws Exception {
        Path realGz = gz(dir, "feed.csv.gz", CSV);
        assertEquals("gzip", Decompressors.forFile(realGz).orElseThrow().id());

        // The suffix alone must NOT claim a file — a mislabeled plain file flows to the engine,
        // whose failure path reports it honestly.
        Path fakeGz = write(dir, "fake.csv.gz", "not gzip at all");
        assertTrue(Decompressors.forFile(fakeGz).isEmpty(), "wrong magic ⇒ unclaimed");

        Path plain = write(dir, "plain.csv", CSV);
        assertTrue(Decompressors.forFile(plain).isEmpty());

        // .Z: magic 1f 9d with the right suffix resolves to the LZW plugin.
        Path z = writeBytes(dir, "data.csv.Z", new byte[] {0x1f, (byte) 0x9d, 0x01, 0x02});
        assertEquals("compress-z", Decompressors.forFile(z).orElseThrow().id());
    }

    @Test
    void knownSuffixesComeFromTheDiscoveredPlugins() {
        List<String> s = Decompressors.knownSuffixes();
        assertTrue(s.containsAll(List.of(".gz", ".bz2", ".z")), "built-ins own their suffixes: " + s);
    }

    // ── expansion (engine-aware, fail-open) ────────────────────────────────────

    @Test
    void expandsBz2ForTheNativeLaneAndRegistersTheOrigin(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, "duckdb");
        Path bz2 = bz2(dir.resolve("inbox"), "feed.csv.bz2", CSV);

        List<File> out = UnpackStage.expand(cfg, List.of(bz2.toFile()));

        assertEquals(1, out.size());
        File actual = out.get(0);
        assertEquals("feed.csv", actual.getName());
        assertNotEquals(bz2.toFile(), actual, "the candidate was swapped for the expansion");
        assertEquals(CSV, Files.readString(actual.toPath()), "byte-identical payload");
        assertTrue(actual.toPath().startsWith(dir.resolve("temp")), "materialized under dirs.temp");
        assertEquals(bz2.toFile(), UnpackOrigins.originalOr(actual), "origin registered");
        UnpackStage.cleanup(actual);
        assertFalse(Files.exists(actual.toPath()), "cleanup removes the scratch copy");
    }

    /** The lane that reads a format itself never pays a decode for it. */
    @Test
    void gzPassesThroughUntouchedOnBothLanes(@TempDir Path dir) throws Exception {
        Path gz = gz(dir.resolve("inbox"), "feed.csv.gz", CSV);
        for (String engine : List.of("duckdb", "java")) {
            List<File> out = UnpackStage.expand(cfg(dir, engine), List.of(gz.toFile()));
            assertEquals(List.of(gz.toFile()), out, "gz is native to the " + engine + " lane");
        }
    }

    /** bz2 is inflated inline by the Java lane — only the native lane needs the stage. */
    @Test
    void bz2PassesThroughOnTheJavaLane(@TempDir Path dir) throws Exception {
        Path bz2 = bz2(dir.resolve("inbox"), "feed.csv.bz2", CSV);
        assertEquals(List.of(bz2.toFile()), UnpackStage.expand(cfg(dir, "java"), List.of(bz2.toFile())));
    }

    /**
     * 🔴 Fail-OPEN: a corrupt compressed file stays in the candidate list AS ITSELF, so the engine's
     * existing quarantine/status machinery reports it per-file. Quarantining here would bypass the
     * status ledger — the exact reporting the operator asked to keep.
     */
    @Test
    void corruptInputFallsThroughAsTheOriginal(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, "duckdb");
        // Real gzip magic, garbage body — claimed by the plugin, fails mid-decode.
        Path corrupt = writeBytes(dir.resolve("inbox"), "bad.csv.gz",
                new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00, 0x42, 0x42, 0x42, 0x42, 0x42});
        List<File> out = UnpackStage.expand(cfg, List.of(corrupt.toFile()));
        assertEquals(List.of(corrupt.toFile()), out);
        assertFalse(UnpackOrigins.isExpanded(corrupt.toFile()));
    }

    // ── the fail-closed caps (stream half of Phase 2) ──────────────────────────

    @Test
    void breachedRatioCapFailsWholeAndLeavesNoPartial(@TempDir Path dir) throws Exception {
        Path gz = gz(dir, "bomb.csv.gz", CSV.repeat(2000));   // tiny in, big out
        Path work = Files.createDirectories(dir.resolve("work"));
        UnpackLimits tight = new UnpackLimits(10, 1L << 30, 1L << 30, 2d, 1);
        IOException e = assertThrows(IOException.class,
                () -> new BuiltinDecompressors.Gzip().expand(gz, work, tight));
        assertTrue(e.getMessage().contains("max_ratio"), e.getMessage());
        try (var kids = Files.list(work)) {
            assertEquals(0, kids.count(), "a breached cap never leaves a partial expansion behind");
        }
    }

    @Test
    void breachedEntryBytesCapNamesTheCap(@TempDir Path dir) throws Exception {
        Path gz = gz(dir, "big.csv.gz", CSV.repeat(100));
        UnpackLimits tight = new UnpackLimits(10, 64, 1L << 30, 0d, 1);
        IOException e = assertThrows(IOException.class,
                () -> new BuiltinDecompressors.Gzip().expand(gz, Files.createDirectories(dir.resolve("w")), tight));
        assertTrue(e.getMessage().contains("max_entry_bytes"), e.getMessage());
    }

    // ── logical names (§2.3) ───────────────────────────────────────────────────

    @Test
    void logicalNameFollowsTheThreeRules() {
        List<String> sfx = List.of(".gz", ".bz2", ".z");
        // rule 1 — iterative compression strip; rule 2 — at most ONE data extension
        assertEquals("data", LogicalNames.logicalName("data.csv.gz", sfx));
        assertEquals("data", LogicalNames.logicalName("data.CSV.GZ", sfx));
        assertEquals("data", LogicalNames.logicalName("data.csv.gz.Z", sfx));
        assertEquals("data", LogicalNames.logicalName("data.Z", sfx));
        assertEquals("data", LogicalNames.logicalName("data.csv", sfx));
        assertEquals("data", LogicalNames.logicalName("data", sfx));
        // ⛔ never "everything after the first dot"
        assertEquals("feed.2026.08.23", LogicalNames.logicalName("feed.2026.08.23.csv", sfx));
        // rule 3 — directories stay in the key
        assertEquals("a/x", LogicalNames.logicalName("a/x.csv", sfx));
        assertNotEquals(LogicalNames.logicalName("a/x.csv", sfx), LogicalNames.logicalName("b/x.csv", sfx));
        // a bare suffix-only name never strips to nothing
        assertEquals(".gz", LogicalNames.logicalName(".gz", sfx));
    }

    @Test
    void involvesCompressionScopesTheAliasToSuchCases() {
        assertTrue(LogicalNames.involvesCompression("feed.csv.gz"));
        assertTrue(LogicalNames.involvesCompression("feed.Z"));
        assertFalse(LogicalNames.involvesCompression("feed.csv"), "plain files never write an alias");
        assertFalse(LogicalNames.involvesCompression("feed"));
    }

    // ── the marker alias (§2.3 over MarkerManager) ─────────────────────────────

    /**
     * The whole §2.3 duplicate story at the marker layer: processing {@code feed.csv.bz2} skips a
     * later {@code feed.csv.gz} AND a later plain {@code feed.csv} (lookup checks the alias for every
     * file; the alias is only ever WRITTEN by compression-involved names). An unrelated plain inbox
     * writes no alias, so plain-only deployments are byte-for-byte unchanged — and an OLD-STYLE
     * verbatim marker still skips its own file (the migration guarantee: switching identity never
     * re-ingests the backlog).
     */
    @Test
    void markerAliasSkipsOtherSpellingsOfOneLogicalFile(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, "duckdb");
        Path inbox = dir.resolve("inbox");
        Path bz2 = bz2(inbox, "feed.csv.bz2", CSV);

        MarkerManager.createMarkerFile(bz2.toFile(), cfg);
        assertTrue(MarkerManager.isAlreadyProcessed(bz2.toFile(), cfg), "its own primary marker");
        assertTrue(MarkerManager.isAlreadyProcessed(write(inbox, "feed.csv.gz", "x").toFile(), cfg),
                "another compression form of the same logical file is skipped");
        assertTrue(MarkerManager.isAlreadyProcessed(write(inbox, "feed.csv", "x").toFile(), cfg),
                "the plain re-delivery is skipped too");
        assertFalse(MarkerManager.isAlreadyProcessed(write(inbox, "other.csv", "x").toFile(), cfg));

        // Plain files never write the alias: no compressed sibling ⇒ no cross-extension collision.
        MarkerManager.createMarkerFile(write(inbox, "plain.csv", "x").toFile(), cfg);
        assertFalse(MarkerManager.isAlreadyProcessed(write(inbox, "plain.json", "x").toFile(), cfg),
                "a plain-only inbox keeps today's behaviour");

        // Migration: an old-style verbatim marker (no alias beside it) still skips its file.
        Path legacy = write(inbox, "legacy.csv.bz2", "x");
        Path legacyMarker = dir.resolve("markers").resolve("legacy.csv.bz2.processed");
        Files.createDirectories(legacyMarker.getParent());
        Files.createFile(legacyMarker);
        assertTrue(MarkerManager.isAlreadyProcessed(legacy.toFile(), cfg));
    }

    // ── fail-closed compression validation (Phase 1 step 4) ───────────────────

    @Test
    void unsupportedCompressionValueIsRefusedAtLoad(@TempDir Path dir) {
        for (String bad : List.of("zip", "tar", "Z", "bz2")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> cfgWithCompression(dir, bad), bad + " must be refused");
            assertTrue(e.getMessage().contains("unpack stage"), e.getMessage());
        }
    }

    @Test
    void supportedCompressionValuesStillLoad(@TempDir Path dir) throws Exception {
        for (String ok : List.of("auto", "gzip", "zstd", "none", "GZIP"))
            assertEquals(ok, cfgWithCompression(dir, ok).csv().inputCompression());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Path write(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private static Path writeBytes(Path dir, String name, byte[] bytes) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private static Path gz(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(p))) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return p;
    }

    private static Path bz2(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        try (OutputStream os = new BZip2CompressorOutputStream(Files.newOutputStream(p))) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return p;
    }

    private static PipelineConfig cfg(Path dir, String engine) throws Exception {
        return load(dir, "  csv_settings:\n    delimiter: \",\"\n    engine: " + engine + "\n");
    }

    private static PipelineConfig cfgWithCompression(Path dir, String value) throws Exception {
        return load(dir, "  csv_settings:\n    delimiter: \",\"\n    compression: \"" + value + "\"\n");
    }

    /** Minimal loadable pipeline over a 1-field schema — the DelimitedGrammarTest harness shape. */
    private static PipelineConfig load(Path dir, String procExtra) throws Exception {
        Path schema = dir.resolve("schema_u.toon");
        if (!Files.exists(schema))
            Files.writeString(schema, """
                    partitionKey: VAL
                    raw:
                      name: t
                      format: CSV
                      fields[3]{name,selector,type}:
                        MARKER,"0",VARCHAR
                        ID,"1",VARCHAR
                        VAL,"2",VARCHAR
                    mapping:
                      canonicalName: t
                      rawName: t
                      rules[3]{targetColumn,sourceExpression,transformType}:
                        MARKER,MARKER,DIRECT
                        ID,ID,DIRECT
                        VAL,VAL,DIRECT
                    """, StandardCharsets.UTF_8);
        String d = dir.toString().replace('\\', '/');
        Path pipe = dir.resolve("pipe_" + Math.abs(procExtra.hashCode()) + ".toon");
        Files.writeString(pipe,
                "name: U_unpack\n"
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
              + "  schema_file: " + d + "/schema_u.toon\n"
              + "  duplicate_check:\n"
              + "    enabled: true\n"
              + procExtra, StandardCharsets.UTF_8);
        return PipelineConfig.load(pipe.toString());
    }
}

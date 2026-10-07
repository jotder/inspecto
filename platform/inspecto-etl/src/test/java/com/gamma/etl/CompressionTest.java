package com.gamma.etl;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gamma.etl.unpack.Decompressors;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-E: transparent {@code .gz}/{@code .bz2}/{@code .zip} decompression on the streaming read path. */
class CompressionTest {

    private static final String BODY = "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\nr2,2.0,2020-04-04\n";

    private static String read(Path file) throws Exception {
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = Compression.decompress(file.toFile(), raw, 1 << 16)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsGzip(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("feed.csv.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(f))) {
            out.write(BODY.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(BODY, read(f));
    }

    @Test
    void readsBzip2(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("feed.csv.bz2");
        try (OutputStream out = new BZip2CompressorOutputStream(Files.newOutputStream(f))) {
            out.write(BODY.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(BODY, read(f));
    }

    @Test
    void readsFirstEntryOfZip(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("feed.csv.zip");
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(Files.newOutputStream(f))) {
            zos.putArchiveEntry(new ZipArchiveEntry("feed.csv"));
            zos.write(BODY.getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();
        }
        assertEquals(BODY, read(f));
    }

    @Test
    void plainFileIsUnchanged(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("feed.csv");
        Files.writeString(f, BODY);
        assertEquals(BODY, read(f));
    }

    @Test
    void stripExtensionsHandlesAllCompressionSuffixes() {
        assertEquals("feed", CsvIngester.stripExtensions("feed.csv"));
        assertEquals("feed", CsvIngester.stripExtensions("feed.csv.gz"));
        assertEquals("feed", CsvIngester.stripExtensions("feed.csv.bz2"));
        assertEquals("feed", CsvIngester.stripExtensions("feed.csv.zip"));
        assertTrue(Compression.isCompressed("x.BZ2"), "case-insensitive");
        assertFalse(Compression.isCompressed("x.csv"));
    }

    /**
     * The drift guard between the two decompression vocabularies (BACKLOG §4 unpack open-item 12).
     * Every suffix this legacy inline path inflates must also be claimed by a discovered
     * {@code DecompressorPlugin}, because {@code UnpackStage.laneReadsItself} uses THIS list to
     * decide the stage may leave a file alone for the Java lane. A suffix only this path knew would
     * be skipped by the stage and then handed to a lane whose plugin set cannot open it.
     *
     * <p>⚠ The containment is deliberately ONE-WAY. The SPI is wider on purpose — {@code .Z},
     * {@code .tar}, {@code .tar.gz}/{@code .tgz} have plugins and no inline branch — and that is
     * safe: the stage expands them before any engine sees them.
     */
    @Test
    void inlineVocabularyIsOwnedByPlugins() {
        List<String> spi = Decompressors.knownSuffixes();
        for (String s : Compression.INLINE_SUFFIXES)
            assertTrue(spi.contains(s),
                    "Compression.INLINE_SUFFIXES has '" + s + "' but no DecompressorPlugin claims it — "
                            + "the unpack stage would skip such a file and the Java lane could not read it. "
                            + "Register a plugin (META-INF/services) or drop the inline branch. SPI set: " + spi);
    }

    /** {@code isCompressed} is exactly membership of the declared vocabulary, so the two cannot drift. */
    @Test
    void isCompressedIsExactlyTheDeclaredVocabulary() {
        for (String s : Compression.INLINE_SUFFIXES)
            assertTrue(Compression.isCompressed("feed.csv" + s), s + " must be recognised");
        assertFalse(Compression.isCompressed("feed.csv.tar"), "not an inline branch");
        assertFalse(Compression.isCompressed("feed.csv.Z"), "SPI-only — the stage expands it");
    }
}

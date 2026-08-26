package com.gamma.etl.unpack;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Base for the 1→N {@link DecompressorPlugin.Kind#ARCHIVE} built-ins (unpack-stage plan Phase 3):
 * walks entries and materializes each into {@code workDir}, enforcing every
 * {@link UnpackLimits} cap DURING the walk — an archive is the classic bomb vector, so a breach
 * throws and {@link #expand} deletes everything it wrote (a partial expansion is never handed on).
 *
 * <h3>Entry naming</h3>
 * {@code <NNNNN>_<flattened-entry-name>}. The zero-padded index makes <b>path order == archive
 * order</b>, which matters because every entry is written in the same instant and
 * {@code ConsignmentPlanner} orders by mtime with an absolute-path tie-break — without the prefix,
 * entry order would be deterministic but arbitrary. The name is flattened (separators → {@code _})
 * so nested archive directories cannot recreate a tree in the workspace.
 *
 * <h3>Zip-slip</h3>
 * Flattening already removes the traversal vector, and the resolved path is still asserted to stay
 * under {@code workDir} (the {@code TarUtil.extractTar} guard idiom) — belt and braces, because this
 * is the class of bug that writes outside the workspace.
 */
public abstract class ArchiveDecompressorPlugin implements DecompressorPlugin {

    private static final Logger log = LoggerFactory.getLogger(ArchiveDecompressorPlugin.class);

    private final String id;
    private final List<String> suffixes;

    ArchiveDecompressorPlugin(String id, List<String> suffixes) {
        this.id = id;
        this.suffixes = List.copyOf(suffixes);
    }

    /** Open the archive walker over the raw bytes. */
    protected abstract ArchiveInputStream<?> open(InputStream raw) throws IOException;

    /** Whether {@code magic} is this archive format's signature. */
    protected abstract boolean magicMatches(byte[] magic);

    @Override public String id() { return id; }
    @Override public List<String> suffixes() { return suffixes; }
    @Override public Kind kind() { return Kind.ARCHIVE; }

    @Override
    public boolean supports(String fileName, byte[] magic) {
        String n = fileName.toLowerCase(Locale.ROOT);
        boolean named = suffixes.stream().anyMatch(n::endsWith);
        return named && magicMatches(magic);
    }

    @Override
    public List<Path> expand(Path source, Path workDir, UnpackLimits limits) throws IOException {
        return expand(source, workDir, limits, new ArrayList<>());
    }

    @Override
    public List<Path> expand(Path source, Path workDir, UnpackLimits limits,
                             List<String> skippedOut) throws IOException {
        List<Path> written = new ArrayList<>();
        Path inProgress = null;          // deleted on a breach too — it is a partial file by definition
        long inBytes = Files.size(source);
        long total = 0;
        int index = 0;
        try (ArchiveInputStream<?> in = open(new BufferedInputStream(Files.newInputStream(source)))) {
            ArchiveEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                // An encrypted or unsupported-method entry has readable metadata but no readable
                // bytes: skip it, and REPORT the skip via skippedOut so the caller can record it in
                // the batch manifest (a partial expansion must never look like a clean success —
                // BACKLOG §4 "Unpack stage — open items" (4), honesty half fixed 2026-08-26). An
                // all-unreadable archive still fails whole, via the "no readable entries" throw
                // below; the archive-level status vocabulary stays the plan's §6 Q1.
                if (!in.canReadEntryData(e)) {
                    skippedOut.add(e.getName());
                    log.warn("[UNPACK] {}: skipping unreadable entry '{}' of {} (encrypted or "
                            + "unsupported method) — it will NOT appear in the expansion",
                            id, e.getName(), source.getFileName());
                    continue;
                }
                if (++index > limits.maxEntries())
                    throw new IOException(id + ": archive exceeds max_entries ("
                            + limits.maxEntries() + "): " + source.getFileName());
                Path out = entryPath(workDir, index, e.getName());
                inProgress = out;
                long entryBytes = 0;
                try (OutputStream os = Files.newOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        entryBytes += n;
                        total += n;
                        if (entryBytes > limits.maxEntryBytes())
                            throw new IOException(id + ": entry '" + e.getName()
                                    + "' exceeds max_entry_bytes (" + limits.maxEntryBytes() + ")");
                        if (total > limits.maxTotalBytes())
                            throw new IOException(id + ": archive exceeds max_total_bytes ("
                                    + limits.maxTotalBytes() + "): " + source.getFileName());
                        if (limits.maxRatio() > 0 && inBytes > 0 && total > inBytes * limits.maxRatio())
                            throw new IOException(id + ": decompression ratio exceeds max_ratio ("
                                    + (long) limits.maxRatio() + ":1): " + source.getFileName());
                        os.write(buf, 0, n);
                    }
                }
                written.add(out);
                inProgress = null;
            }
        } catch (IOException ex) {
            if (inProgress != null) Files.deleteIfExists(inProgress);
            for (Path p : written) Files.deleteIfExists(p);
            throw ex;
        }
        // Nothing usable came out. EMPTY (zero entries in the archive) and UNREADABLE (entries
        // existed, none decodable) are one code path here but stay DISTINCT statuses per §6 Q1, so
        // carry the count rather than making the caller parse this message.
        if (written.isEmpty())
            throw new NoUsableEntriesException(
                    id + ": no readable entries in " + source.getFileName(), skippedOut.size());
        return written;
    }

    /**
     * Reverse of {@link #entryPath}: the entry name behind an expansion filename — the
     * {@code <NNNNN>_} ordering prefix is an implementation detail of the workspace and must never
     * leak into lineage or {@code output.filename_column} (it is DATA there). Kept beside
     * {@code entryPath} so the two halves of the format cannot drift.
     */
    public static String entryName(String expansionFileName) {
        int u = expansionFileName.indexOf('_');
        if (u < 5 || u == expansionFileName.length() - 1) return expansionFileName;   // %05d widens past 99999
        for (int i = 0; i < u; i++)
            if (!Character.isDigit(expansionFileName.charAt(i))) return expansionFileName;
        return expansionFileName.substring(u + 1);
    }

    /** {@code <NNNNN>_<flattened>} under {@code workDir}, asserted to stay inside it. */
    private Path entryPath(Path workDir, int index, String entryName) throws IOException {
        String flat = entryName.replace('\\', '/');
        flat = flat.substring(flat.lastIndexOf('/') + 1);      // drop any directory component
        if (flat.isBlank() || flat.equals(".") || flat.equals("..")) flat = "entry";
        Path out = workDir.resolve(String.format("%05d_%s", index, flat)).normalize();
        if (!out.startsWith(workDir.normalize()))
            throw new IOException(id + ": unsafe path in archive: " + entryName);
        return out;
    }

    // ── the built-ins ──────────────────────────────────────────────────────────

    /** {@code .zip} — every file entry; magic {@code PK\003\004} (also PK\005\006 / PK\007\010). */
    public static final class Zip extends ArchiveDecompressorPlugin {
        public Zip() { super("zip", List.of(".zip")); }
        @Override protected ArchiveInputStream<?> open(InputStream raw) { return new ZipArchiveInputStream(raw); }
        @Override protected boolean magicMatches(byte[] m) {
            return m.length >= 4 && m[0] == 'P' && m[1] == 'K'
                    && (m[2] == 3 || m[2] == 5 || m[2] == 7);
        }
    }

    /**
     * {@code .tar} — uncompressed tar. No usable leading magic (the {@code ustar} marker sits at byte
     * 257, past the sniff window), so the suffix carries the decision and a malformed tar simply
     * fails the expansion, which is fail-open into the engine's own reporting.
     */
    public static final class Tar extends ArchiveDecompressorPlugin {
        public Tar() { super("tar", List.of(".tar")); }
        @Override protected ArchiveInputStream<?> open(InputStream raw) { return new TarArchiveInputStream(raw); }
        @Override protected boolean magicMatches(byte[] m) { return true; }
    }

    /** {@code .tar.gz} / {@code .tgz} — gzip-wrapped tar; magic {@code 1f 8b}. */
    public static final class TarGz extends ArchiveDecompressorPlugin {
        public TarGz() { super("tar.gz", List.of(".tar.gz", ".tgz")); }
        @Override protected ArchiveInputStream<?> open(InputStream raw) throws IOException {
            return new TarArchiveInputStream(new GzipCompressorInputStream(raw));
        }
        @Override protected boolean magicMatches(byte[] m) {
            return m.length >= 2 && m[0] == (byte) 0x1f && m[1] == (byte) 0x8b;
        }
    }
}

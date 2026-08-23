package com.gamma.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;

/**
 * Moves rejected input files into the quarantine directory tree.
 *
 * <p>The quarantine mirrors the poll directory structure:
 * a file at {@code poll/providerA/20240101/feed.csv.gz} is moved to
 * {@code quarantine/providerA/20240101/<subDir>/feed.csv.gz}.
 * Files dropped directly in the poll root land at
 * {@code quarantine/<subDir>/feed.csv.gz}.
 *
 * <p>Optionally, the companion error CSV (produced by {@link CsvIngester} and
 * placed in {@code dirs.errors}) is relocated alongside the bad file so the
 * rejection evidence stays co-located.
 *
 * <p>Extracted from {@link com.gamma.inspector.CollectorProcessor}.
 */
public final class QuarantineManager {

    private static final Logger log = LoggerFactory.getLogger(QuarantineManager.class);

    private QuarantineManager() {}

    // ── parse-stage reasons (legacy) ──────────────────────────────────────────
    /** A row failed field/type validation against the schema. */ public static final String REASON_FIELD_MISMATCH = "field_mismatch";
    /** The file could not be read/parsed at all. */              public static final String REASON_UNREADABLE     = "unreadable";
    /** The file was readable but yielded zero ingestable rows (empty / header-only) — consumed so it
     *  isn't rediscovered and reprocessed every poll cycle (an EMPTY batch never backs up/marks). */
    public static final String REASON_EMPTY = "empty";

    // ── acquisition-stage reasons (Data Acquisition roadmap Phase F dead-letter) ──
    /** A fetched file failed its post-download integrity check (size/checksum mismatch). */
    public static final String REASON_CORRUPT_DOWNLOAD = "corrupt_download";

    /**
     * Move {@code inputFile} into the quarantine tree.
     *
     * @param inputFile       the file to quarantine
     * @param subDir          reason sub-directory: {@code "field_mismatch"} or {@code "unreadable"}
     * @param includeErrorCsv when {@code true}, also move the companion error CSV
     * @param cfg             pipeline configuration
     * @throws IOException if the move fails or the file is outside the poll root
     */
    public static void quarantine(File inputFile, String subDir,
                                  boolean includeErrorCsv, PipelineConfig cfg)
            throws IOException {
        // An unpack-expanded member lives in dirs.temp, which the poll-relativize below would refuse
        // outright — so the subject is remapped (unpack-stage plan §2.0), and the two expansion kinds
        // are remapped DIFFERENTLY:
        //
        //  • 1→1 STREAM (feed.csv.bz2 → feed.csv): the original IS this file. Quarantine the
        //    original — that is the evidence the operator dropped, and it is what must leave the
        //    inbox to stop rediscovery.
        //  • 1→N ARCHIVE entry: quarantining the container would evict members that are fine and
        //    would race the siblings still being ingested. Quarantine the ENTRY, named
        //    <archive>!<entry> under the archive's own relative parent, and leave the archive to its
        //    normal completion. ⚠ The archive is then still marked processed with one member
        //    quarantined — today's per-file semantics (a bad file never blocks its batch-mates); the
        //    explicit UNPACKED_PARTIAL verdict is the plan's §6 Q1, pending operator sign-off.
        if (com.gamma.etl.unpack.UnpackOrigins.isExpanded(inputFile)) {
            File actual   = inputFile;
            File original = com.gamma.etl.unpack.UnpackOrigins.originalOr(actual);
            if (com.gamma.etl.unpack.UnpackOrigins.totalFor(original) > 1) {
                // ARCHIVE entry: file the entry itself, under the ARCHIVE's relative parent (the
                // entry has none of its own) and named archive!entry so the evidence says which
                // member of which container failed. The error CSV keys off the entry's own name.
                move(actual, original, subDir, original.getName() + "!" + actual.getName(),
                        includeErrorCsv, actual.getName(), cfg);
                // Count the member done — an archive with a bad member must still complete. Whoever
                // consumes the LAST member disposes of the container: finalizeSource backs it up when
                // that member succeeded, and this branch quarantines it when it did not (otherwise a
                // wholly-bad archive would never leave the inbox and every poll would retry it).
                File last = com.gamma.etl.unpack.UnpackOrigins.consume(actual);
                if (last == null) return;
                inputFile = last;
            } else {
                // 1→1 STREAM: the original IS this file — quarantine it, drop the scratch copy.
                com.gamma.etl.unpack.UnpackStage.cleanup(actual);
                inputFile = original;
            }
        }
        move(inputFile, inputFile, subDir, inputFile.getName(),
                includeErrorCsv, inputFile.getName(), cfg);
    }

    /**
     * The one mover: {@code <quarantine>/<relParent-of-anchor>/<subDir>/<targetName>}.
     *
     * @param file       the file to move
     * @param anchor     the file whose poll-relative parent locates the destination — the same file,
     *                   except for an unpack ARCHIVE entry, which is anchored on its container
     * @param targetName the destination filename
     * @param errorBase  the name the companion {@code <base>_errors.csv} was written under
     */
    private static void move(File file, File anchor, String subDir, String targetName,
                             boolean includeErrorCsv, String errorBase, PipelineConfig cfg)
            throws IOException {
        Path pollPath  = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Path fileParent= anchor.toPath().toAbsolutePath().normalize().getParent();
        Path relParent = pollPath.relativize(fileParent);

        // Guard against symlinks or misconfiguration that places the file outside poll
        if (relParent.startsWith(".."))
            throw new IOException(
                    "Input file is not under poll root — cannot quarantine safely: " + anchor);

        // <quarantine_dir>/<relative_parent>/<reason>/filename
        Path qDir = Paths.get(cfg.dirs().quarantine()).toAbsolutePath()
                         .resolve(relParent).resolve(subDir);
        Files.createDirectories(qDir);

        Path dst = qDir.resolve(targetName);
        Files.move(file.toPath(), dst, StandardCopyOption.REPLACE_EXISTING);
        log.info("Quarantined [{}]: {} → {}", subDir, file.getName(), dst);

        if (includeErrorCsv) {
            String baseName = CsvIngester.stripExtensions(errorBase);
            Path errorCsv = Paths.get(cfg.dirs().errors()).toAbsolutePath()
                                 .resolve(baseName + "_errors.csv");
            if (Files.exists(errorCsv))
                Files.move(errorCsv, qDir.resolve(errorCsv.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

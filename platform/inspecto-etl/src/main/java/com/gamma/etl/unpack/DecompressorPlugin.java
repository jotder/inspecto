package com.gamma.etl.unpack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A pluggable decompressor for the Collector's unpack stage (unpack-stage plan §3): turns one
 * compressed/archived input file into the plain file(s) the parsing engines read. Implementations are
 * discovered via {@link java.util.ServiceLoader} ({@code META-INF/services}), the
 * {@code CollectorConnectorFactory} house idiom — deliberately NOT a config-named FQCN like
 * {@code StreamingFileIngester}, because the format is discovered <em>from the file</em>, so the
 * resolver genuinely needs the discovered set.
 *
 * <p>An implementation is format-specific and stateless. It must never write outside {@code workDir}
 * and must honour {@link UnpackLimits} fail-closed: on a breached cap it throws, leaving the caller to
 * route the ORIGINAL file into the normal failure path — a partial expansion is never handed on.
 */
public interface DecompressorPlugin {

    /** How the expansion multiplies files — the phasing boundary of the unpack-stage plan (§3). */
    enum Kind {
        /** 1→1: a single compressed stream ({@code .gz}, {@code .bz2}, {@code .Z}, …). */
        STREAM,
        /** 1→N: an archive with entries ({@code .zip}, {@code .tar}, …). */
        ARCHIVE
    }

    /** Stable identifier, e.g. {@code "gzip"} — used in logs and fail-fast messages. */
    String id();

    /**
     * The lower-case filename suffixes this plugin owns, with the dot (e.g. {@code [".gz"]}).
     * Also feeds {@link LogicalNames} — the compression-suffix set is derived from the discovered
     * plugins, never a hand-mirrored list.
     */
    List<String> suffixes();

    /**
     * Whether this plugin can expand {@code fileName}. {@code magic} holds the file's first bytes
     * (may be shorter than requested, or empty for an empty file) so an implementation can require
     * both the suffix AND the magic number — a {@code .gz} that does not start {@code 1f 8b} is
     * refused here and flows to the engines unchanged, whose failure path reports it honestly.
     */
    boolean supports(String fileName, byte[] magic);

    Kind kind();

    /**
     * Expand {@code source} into {@code workDir} (already existing, unique to this source) and return
     * the materialized file(s) — the parsing engines need real paths, never streams (the DuckDB
     * {@code read_csv} contract). A {@link Kind#STREAM} plugin returns exactly one file.
     *
     * @throws IOException on corrupt input or a breached {@link UnpackLimits} cap; the caller treats
     *         the ORIGINAL as the failing file
     */
    List<Path> expand(Path source, Path workDir, UnpackLimits limits) throws IOException;

    /**
     * As {@link #expand(Path, Path, UnpackLimits)}, additionally reporting the entries the plugin
     * had to SKIP (e.g. an encrypted or unsupported-method archive entry — readable metadata, no
     * readable bytes) by adding their entry names to {@code skippedOut}. The caller records those in
     * the batch manifest so a partial expansion never looks like a clean success. Default delegates
     * for plugins that can never skip ({@link Kind#STREAM} is all-or-nothing by construction).
     */
    default List<Path> expand(Path source, Path workDir, UnpackLimits limits,
                              List<String> skippedOut) throws IOException {
        return expand(source, workDir, limits);
    }
}

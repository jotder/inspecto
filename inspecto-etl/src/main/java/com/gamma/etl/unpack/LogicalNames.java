package com.gamma.etl.unpack;

import java.util.List;
import java.util.Locale;

/**
 * The extension-insensitive identity of a source file (unpack-stage plan §2.3, operator-required):
 * one logical file may present as {@code cdr_20260823.csv.gz}, {@code cdr_20260823.csv},
 * {@code cdr_20260823.Z} or bare {@code cdr_20260823} across deliveries and across the compression
 * boundary — the duplicate check and reporting must treat those as ONE name.
 *
 * <p>Rules, in order of what they protect:
 * <ol>
 *   <li><b>Strip compression suffixes iteratively, registered ones only</b> — the set comes from the
 *       discovered {@link DecompressorPlugin}s (plus the legacy inline set), never a hand-mirrored
 *       twin list. {@code data.csv.gz.Z → data.csv}.</li>
 *   <li><b>At most ONE data-extension strip, from the allow-list.</b> ⛔ Never "everything after the
 *       first dot": {@code feed.2026.08.23.csv} must become {@code feed.2026.08.23}, not
 *       {@code feed}.</li>
 *   <li><b>Directories stay in the key</b> — extension-insensitive, never path-insensitive.</li>
 * </ol>
 *
 * <p>The data-extension allow-list is the plan's §6 Q4 (operator default pending); the collision
 * posture — {@code report.csv} and {@code report.json} in one directory being ONE logical file — is
 * deliberate under "ignoring extensions", and every drop it causes is logged with both spellings,
 * never silent (see the marker/ledger call sites).
 */
public final class LogicalNames {

    /**
     * The §6 Q4 default, CONFIRMED by the operator 2026-08-26 and now the default of the published
     * {@code processing.unpack.data_extensions} key — a deployment may narrow it, or set it EMPTY to
     * opt out of extension-insensitive identity entirely (verbatim names only). {@code .zip} is not
     * here: it is a compression suffix, stripped by rule 1 via {@link #LEGACY_SUFFIXES}.
     *
     * <p>⚠ This is the ONE engine-side declaration — {@code PipelineConfig.Unpack.defaults()} reads
     * it rather than restating it. It is necessarily mirrored ONCE more, in {@code ConfigSpecs}
     * (module {@code inspecto-config} cannot see {@code inspecto-etl}); {@code LogicalNamesTest}
     * pins the two equal. ⛔ Do not add a third.
     */
    public static final List<String> DEFAULT_DATA_EXTENSIONS =
            List.of(".csv", ".tsv", ".txt", ".json", ".jsonl", ".ndjson", ".xml");

    /** Suffixes the legacy inline path ({@code Compression.java}) understands but no plugin owns yet. */
    private static final List<String> LEGACY_SUFFIXES = List.of(".zip");

    private LogicalNames() {}

    /** {@link #logicalName(String, List)} over the discovered plugin suffixes and the DEFAULT list. */
    public static String logicalName(String relativePath) {
        return logicalName(relativePath, Decompressors.knownSuffixes());
    }

    /**
     * The logical key under the pipeline's CONFIGURED data-extension allow-list
     * ({@code processing.unpack.data_extensions}) — what every production call site should use, so a
     * deployment that narrowed or emptied the list is actually honoured.
     */
    public static String logicalName(String relativePath, com.gamma.etl.PipelineConfig cfg) {
        return logicalName(relativePath, Decompressors.knownSuffixes(), cfg.unpack().dataExtensions());
    }

    /**
     * Whether {@code relativePath} carries at least one registered compression suffix — the
     * operator's "for such cases" scope: the extension-insensitive duplicate ALIAS is only ever
     * <em>written</em> for compression-involved files, so two plain files ({@code feed.csv} /
     * {@code feed.json}) collide only when a compressed spelling of that logical name has actually
     * been processed. Lookup still checks the alias for every file, so a plain re-delivery of a
     * previously-compressed file is caught.
     */
    public static boolean involvesCompression(String relativePath) {
        String name = relativePath.substring(
                Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\')) + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        for (String s : Decompressors.knownSuffixes())
            if (lower.length() > s.length() && lower.endsWith(s)) return true;
        for (String s : LEGACY_SUFFIXES)
            if (lower.length() > s.length() && lower.endsWith(s)) return true;
        return false;
    }

    /**
     * The logical key for {@code relativePath} (forward or back slashes; returned with the original
     * separators preserved on the directory part, name part normalized per the rules above).
     */
    public static String logicalName(String relativePath, List<String> compressionSuffixes) {
        return logicalName(relativePath, compressionSuffixes, DEFAULT_DATA_EXTENSIONS);
    }

    /**
     * As above, over an explicit data-extension allow-list. An EMPTY list means rule 2 is skipped
     * entirely — the deployment's opt-out from extension-insensitive identity.
     */
    public static String logicalName(String relativePath, List<String> compressionSuffixes,
                                     List<String> dataExtensions) {
        int cut = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        String dir  = cut >= 0 ? relativePath.substring(0, cut + 1) : "";
        String name = cut >= 0 ? relativePath.substring(cut + 1) : relativePath;

        // rule 1 — iterative compression-suffix strip
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            String lower = name.toLowerCase(Locale.ROOT);
            for (String s : compressionSuffixes) {
                if (lower.length() > s.length() && lower.endsWith(s)) {
                    name = name.substring(0, name.length() - s.length());
                    stripped = true;
                    break;
                }
            }
            if (!stripped) {
                for (String s : LEGACY_SUFFIXES) {
                    String lower2 = name.toLowerCase(Locale.ROOT);
                    if (lower2.length() > s.length() && lower2.endsWith(s)) {
                        name = name.substring(0, name.length() - s.length());
                        stripped = true;
                        break;
                    }
                }
            }
        }

        // rule 2 — at most one data-extension strip
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : dataExtensions) {
            if (lower.length() > ext.length() && lower.endsWith(ext)) {
                name = name.substring(0, name.length() - ext.length());
                break;
            }
        }
        return dir + name;
    }
}

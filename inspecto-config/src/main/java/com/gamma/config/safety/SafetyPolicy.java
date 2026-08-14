package com.gamma.config.safety;

import com.gamma.api.PublicApi;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The policy a {@link ConfigSafetyValidator} enforces against a config draft: which filesystem roots
 * a config may write under, the numeric caps it may not exceed, and the output formats/codecs it may
 * target. Introduced at M5 (v3.5.0) for the hard-fail config safety gate (security guardrail R6).
 *
 * <p>A config can <em>parse</em> yet be harmful — write outside the workspace, oversubscribe the box,
 * or target an unknown sink. The policy makes "safe" a checkable property rather than a hope. The
 * defaults are deliberately permissive on caps (a safety backstop, not a tuning knob) and strict on
 * paths (the real security boundary): everything must resolve under an allowed root.
 *
 * <p>All fields are normalised + never-null via the compact constructor, so callers can read them
 * without guards. {@code allowedRoots} are made absolute + normalised so {@code startsWith} containment
 * is meaningful.
 *
 * @param allowedRoots       filesystem roots a config's paths must resolve under (absolute, normalised)
 * @param maxThreads         upper bound for {@code processing.threads} / {@code duckdb_threads}
 * @param maxBatchFiles      upper bound for {@code processing.batch.max_files}
 * @param maxBatchBytes      upper bound for {@code processing.batch.max_bytes}
 * @param allowedFormats     permitted {@code output.format} values (upper-case)
 * @param allowedCompression permitted {@code output.compression} codecs (lower-case)
 * @since 3.5.0
 */
@PublicApi(since = "4.0.0")
public record SafetyPolicy(
        List<Path> allowedRoots,
        int maxThreads,
        int maxBatchFiles,
        long maxBatchBytes,
        Set<String> allowedFormats,
        Set<String> allowedCompression) {

    private static final Set<String> DEFAULT_FORMATS = Set.of("CSV", "PARQUET");
    private static final Set<String> DEFAULT_COMPRESSION =
            Set.of("none", "uncompressed", "snappy", "gzip", "zstd", "lz4");

    public SafetyPolicy {
        // ⛔ No working-directory substitution here. Until 2026-08-14 an empty list silently became
        // [CWD] — which quietly granted the server's working directory to every containment check on
        // an unconfigured deployment, the exact posture defaultPolicy()'s Javadoc forbids. Empty stays
        // empty; PathJail.requireUnderAny throws on it, so the failure is loud and names the property.
        allowedRoots = allowedRoots == null ? List.of() : normalizeRoots(allowedRoots);
        if (maxThreads <= 0) maxThreads = Runtime.getRuntime().availableProcessors();
        if (maxBatchFiles <= 0) maxBatchFiles = 1_000_000;
        if (maxBatchBytes <= 0) maxBatchBytes = Long.MAX_VALUE;
        allowedFormats = (allowedFormats == null || allowedFormats.isEmpty())
                ? DEFAULT_FORMATS : Set.copyOf(allowedFormats);
        allowedCompression = (allowedCompression == null || allowedCompression.isEmpty())
                ? DEFAULT_COMPRESSION : Set.copyOf(allowedCompression);
    }

    private static List<Path> normalizeRoots(List<Path> roots) {
        List<Path> out = new ArrayList<>(roots.size());
        for (Path p : roots) {
            if (p != null) out.add(p.toAbsolutePath().normalize());
        }
        return List.copyOf(out);
    }

    /**
     * The production default: the <b>union</b> of the operator-declared {@code -Dassist.safety.roots}
     * (a {@code ;}-separated list — destinations outside the space layout, e.g. {@code /mnt/backups})
     * and every hosted space base pushed into {@link DiscoveredRoots} by the space lifecycle; caps
     * sized to this box. Recomputed per call, so a space created at runtime is an allowed root on the
     * very next check — no restart.
     *
     * <p>⚠ There is <b>no working-directory fallback</b> — with the property unset and no spaces
     * hosted the root list is <b>empty</b>, and {@link PathJail#requireUnderAny} throws on that, so
     * every jailed reference fails rather than silently jailing to the CWD. Fail-closed: a
     * misconfigured deployment must not quietly grant the server's working directory. (This Javadoc
     * claimed that posture from 2026-08-14, but the record constructor still substituted the CWD
     * until later the same day — the claim is now true.)
     */
    public static SafetyPolicy defaultPolicy() {
        List<Path> roots = new ArrayList<>();
        String prop = System.getProperty("assist.safety.roots", "");
        for (String s : prop.split(";")) {
            if (!s.isBlank()) roots.add(Paths.get(s.trim()).toAbsolutePath().normalize());
        }
        roots.addAll(DiscoveredRoots.snapshot());
        return new SafetyPolicy(roots, Runtime.getRuntime().availableProcessors(),
                1_000_000, Long.MAX_VALUE, DEFAULT_FORMATS, DEFAULT_COMPRESSION);
    }

    /** A policy rooted at the given dirs (the skill's workspace, or a test temp dir). */
    public static SafetyPolicy withRoots(Path... roots) {
        return new SafetyPolicy(List.of(roots), Runtime.getRuntime().availableProcessors(),
                1_000_000, Long.MAX_VALUE, DEFAULT_FORMATS, DEFAULT_COMPRESSION);
    }
}

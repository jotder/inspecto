package com.gamma.etl.unpack;

/**
 * Fail-closed caps every {@link DecompressorPlugin} must honour (unpack-stage plan Phase 2). A
 * decompressor is a bomb vector by construction — a few KB of input can claim to expand without
 * bound — so the caps exist BEFORE any archive format ships, and a breach fails the expansion whole
 * (the original file then takes the normal failure path; a partial expansion is never handed on).
 *
 * <p>Defaults are deliberately generous for legitimate data and hostile to bombs. They are constants
 * for now; the {@code processing.unpack.*} config surface is the plan's Phase 6.
 *
 * @param maxEntries    most entries one archive may expand to ({@code ARCHIVE} kinds only)
 * @param maxEntryBytes most decompressed bytes any single output file may reach
 * @param maxTotalBytes most decompressed bytes one source may expand to in total
 * @param maxRatio      most output/input bytes (the classic bomb tell); ≤ 0 disables the check
 * @param depth         nested-archive depth; 1 = never recurse into an expanded output (default —
 *                      a zip-of-zips is opt-in, never implicit)
 */
public record UnpackLimits(int maxEntries, long maxEntryBytes, long maxTotalBytes, double maxRatio, int depth) {

    /** The default posture: 10k entries · 8 GiB per file · 32 GiB total · 10 000:1 ratio · no recursion. */
    public static final UnpackLimits DEFAULTS =
            new UnpackLimits(10_000, 8L << 30, 32L << 30, 10_000d, 1);
}

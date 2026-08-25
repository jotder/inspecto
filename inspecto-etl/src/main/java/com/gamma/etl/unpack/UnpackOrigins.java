package com.gamma.etl.unpack;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Collector's original ↔ actual filename registry (unpack-stage plan §2.0): when the unpack stage
 * expands {@code feed.csv.bz2} (the ORIGINAL, in the inbox) into {@code feed.csv} (the ACTUAL, in
 * {@code dirs.temp}), every downstream side effect that does path math against the poll root —
 * quarantine, backup, markers, the fingerprint ledger, the manifest — must run against the ORIGINAL,
 * or its {@code poll.relativize(...)} walks out of the root ({@code ../..}) and either throws
 * ({@code QuarantineManager}) or writes a marker outside the markers tree.
 *
 * <p>Same lifecycle idiom as {@code AcquisitionLedgers.stashChecksum}: registered at collect time,
 * consulted by the finalize/quarantine paths in the same process and cycle, removed when the mapping
 * is consumed. A crash loses the map — and loses nothing with it: no marker/backup happened, the
 * original is still in the inbox, the next cycle re-expands to a fresh temp dir.
 *
 * <p>⚠ RESTRICTION (BACKLOG §4 "Unpack stage — open items" (9)): entries are removed ONLY by
 * {@link #consume} — i.e. by the finalize or quarantine path. A batch that fails at COMMIT runs
 * neither, so its mappings stay behind: a slow leak in a long-running poller, and {@link #totalFor}
 * keeps reporting archive semantics for that original. Bounded in practice (one entry per expanded
 * file per failed batch) but it is unbounded in principle — a per-run sweep is the fix.
 */
public final class UnpackOrigins {

    private static final Map<Path, File> ORIGINS = new ConcurrentHashMap<>();
    /**
     * Outstanding entries per ORIGINAL — the cross-batch coordination an archive needs: with
     * {@code batch.max_files: 1} (the default) an N-entry archive's members land in N batches, and
     * the archive's backup+marker may only fire when the LAST of them commits ({@link #consume}
     * hitting zero — atomic, so exactly one finalizer wins). A crash before zero leaves no marker;
     * the next cycle re-expands and re-ingests, which the OVERWRITE_OR_IGNORE outputs make
     * idempotent — the same guarantee the finalize ordering already gives multi-file batches.
     */
    private static final Map<Path, java.util.concurrent.atomic.AtomicInteger> PENDING = new ConcurrentHashMap<>();
    /** Total entries the original expanded to — 1 ⇒ stream semantics, >1 ⇒ archive semantics. */
    private static final Map<Path, Integer> TOTALS = new ConcurrentHashMap<>();
    /**
     * The LINEAGE name behind an expanded file — the ENTRY name for an archive member (never the
     * workspace's {@code NNNNN_}-prefixed temp name, which is an implementation detail that must not
     * leak into {@code output.filename_column}/lineage: BACKLOG §4 "Unpack stage — open items" (3),
     * fixed 2026-08-26). Recorded at register time by the ONE caller that knows the plugin kind.
     */
    private static final Map<Path, String> NAMES = new ConcurrentHashMap<>();
    /**
     * Entries an archive's expansion had to SKIP (encrypted / unsupported method — no readable
     * bytes, so nothing to plan or quarantine), keyed by ORIGINAL. Drained once by
     * {@code BatchProcessor.finalizeSource} into the first finalising batch's manifest, so a partial
     * expansion never looks like a clean success (open item (4), honesty half). Same crash posture
     * as the rest of this registry: a crash loses the record, the WARN log keeps the trace.
     */
    private static final Map<Path, List<String>> SKIPPED = new ConcurrentHashMap<>();

    private UnpackOrigins() {}

    /** {@link #register(Path, File, String)} with the actual filename as the lineage name. */
    public static void register(Path actual, File original) {
        register(actual, original, actual.getFileName().toString());
    }

    /**
     * Record that {@code actual} (the expanded temp file) stands in for {@code original} (inbox),
     * with {@code lineageName} as the name lineage and {@code filename_column} record for it.
     */
    public static void register(Path actual, File original, String lineageName) {
        Path key = original.toPath().toAbsolutePath().normalize();
        ORIGINS.put(actual.toAbsolutePath().normalize(), original);
        NAMES.put(actual.toAbsolutePath().normalize(), lineageName);
        PENDING.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
        TOTALS.merge(key, 1, Integer::sum);
    }

    /**
     * The name lineage should record for {@code file}: the registered lineage name for an expansion
     * product (the archive ENTRY's own name), the plain filename for everything else.
     *
     * <p>⚠ FIVE call sites feed lineage a filename and every one must come through here, or the
     * {@code NNNNN_} temp name leaks back into DATA for whichever lane is missed: the three
     * {@code srcIdToFile} puts ({@code CsvBatchStrategy}, {@code NativeCsvStreamingEngine},
     * {@code UnionModeIngester}) plus the two {@code DuckDbRecordSink} constructions that pass its
     * {@code lineageName} ({@code GenerationModeIngester}, {@code UnionModeIngester} — the wrap lane
     * names the file at construction, not per row).
     */
    public static String lineageName(File file) {
        String name = NAMES.get(file.toPath().toAbsolutePath().normalize());
        return name != null ? name : file.getName();
    }

    /** Record the entries {@code original}'s expansion skipped as unreadable (empty = no-op). */
    public static void registerSkipped(File original, List<String> entryNames) {
        if (entryNames.isEmpty()) return;
        SKIPPED.merge(original.toPath().toAbsolutePath().normalize(),
                List.copyOf(entryNames), (a, b) -> {
                    List<String> merged = new java.util.ArrayList<>(a);
                    merged.addAll(b);
                    return List.copyOf(merged);
                });
    }

    /**
     * Drain {@code original}'s skipped-entry record — returned exactly once (atomically, so exactly
     * one finalising batch writes the manifest rows), empty every other call.
     */
    public static List<String> takeSkipped(File original) {
        List<String> skipped = SKIPPED.remove(original.toPath().toAbsolutePath().normalize());
        return skipped != null ? skipped : List.of();
    }

    /** The inbox original behind {@code file}, or {@code file} itself when it was never expanded. */
    public static File originalOr(File file) {
        File orig = ORIGINS.get(file.toPath().toAbsolutePath().normalize());
        return orig != null ? orig : file;
    }

    /** Whether {@code file} is an expansion product (and so lives in temp, not the inbox). */
    public static boolean isExpanded(File file) {
        return ORIGINS.containsKey(file.toPath().toAbsolutePath().normalize());
    }

    /** How many files {@code original} expanded to in this cycle (0 when it was never expanded). */
    public static int totalFor(File original) {
        return TOTALS.getOrDefault(original.toPath().toAbsolutePath().normalize(), 0);
    }

    /**
     * Drop {@code actual}'s mapping and count it done. Returns the ORIGINAL exactly when this was its
     * LAST outstanding entry — the one moment the archive's own side effects (backup, marker) may
     * run; every other call, and a never-expanded file, returns null.
     */
    public static File consume(File actual) {
        File original = ORIGINS.remove(actual.toPath().toAbsolutePath().normalize());
        if (original == null) return null;
        NAMES.remove(actual.toPath().toAbsolutePath().normalize());
        Path key = original.toPath().toAbsolutePath().normalize();
        java.util.concurrent.atomic.AtomicInteger left = PENDING.get(key);
        if (left == null || left.decrementAndGet() > 0) return null;
        PENDING.remove(key);
        TOTALS.remove(key);
        SKIPPED.remove(key);   // normally drained at first finalize; purged here when manifests are off
        return original;
    }
}

package com.gamma.acquire;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link AcquisitionLedger} — the lean default (no extra files; tests stay light). State is lost on
 * restart, which is acceptable for the default deployment because {@code PATH} mode still writes durable
 * {@code MarkerManager} sentinels; a deployment that wants durable METADATA/CHECKSUM dedup across restarts uses
 * {@link DbAcquisitionLedger}.
 */
public final class InMemoryAcquisitionLedger implements AcquisitionLedger {

    private final ConcurrentHashMap<String, LedgerEntry> rows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> dbWatermarks = new ConcurrentHashMap<>();

    @Override
    public Optional<LedgerEntry> find(String sourceId, String relativePath) {
        return Optional.ofNullable(rows.get(key(sourceId, relativePath)));
    }

    @Override
    public void record(LedgerEntry entry) {
        rows.put(key(entry.sourceId(), entry.relativePath()), entry);
    }

    @Override
    public OptionalLong highWatermark(String sourceId) {
        long max = Long.MIN_VALUE;
        boolean any = false;
        for (LedgerEntry e : rows.values()) {
            if (e.sourceId().equals(sourceId)) {
                any = true;
                if (e.lastModified() > max) max = e.lastModified();
            }
        }
        return any ? OptionalLong.of(max) : OptionalLong.empty();
    }

    @Override
    public Optional<String> dbWatermark(String sourceKey) {
        return Optional.ofNullable(dbWatermarks.get(sourceKey));
    }

    @Override
    public void recordDbWatermark(String sourceKey, String value) {
        if (value != null) dbWatermarks.put(sourceKey, value);
    }

    /**
     * The one predicate {@link #prune} and {@link #countPrunable} share — the preview and the sweep must never
     * be two independently-written tests again ({@code PRUNE-PREVIEW-DRIFT-1}).
     *
     * <p>🔴 <b>The watermark floor.</b> {@link #highWatermark} is the {@code MAX(lastModified)} <em>of these
     * very rows</em> — there is no separate watermark column — so an age-only sweep that reached the frontier
     * would delete the source's resume position and make it look never-seen
     * ({@code LEDGER-PRUNE-EATS-RESUME-STATE-1}). Keeping the row that holds the maximum keeps
     * {@code highWatermark} identical across a prune.
     *
     * <p>🔴 <b>Exactly ONE row per source is protected, not every row tied at the maximum.</b> Keeping all
     * ties looks equivalent and is not: a source whose files share one mtime — a coarse clock, a bulk copy,
     * a generated feed — would have its <em>entire</em> history protected and retention would silently stop
     * working. The decision says history still shrinks and the sweep stops at what the resume position
     * needs; the resume position needs one row. {@link #watermarkHolders} designates it deterministically.
     *
     * <p>⚠ Computed per source, because {@code sourceId == null} sweeps every source at once and one shared
     * floor would let one source's frontier protect another's rows.
     *
     * <p>⚠ {@code processedAt} (when we handled it) and {@code lastModified} (the source's mtime) are different
     * clocks on the same row: the age test reads the first, the floor the second.
     */
    private boolean prunable(LedgerEntry v, long processedBefore, String sourceId,
                             java.util.Map<String, LedgerEntry> holders) {
        if (v.processedAt() >= processedBefore) return false;
        if (sourceId != null && !v.sourceId().equals(sourceId)) return false;
        LedgerEntry holder = holders.get(v.sourceId());
        // never prune the row that carries this source's high watermark
        return holder == null || !holder.relativePath().equals(v.relativePath());
    }

    /**
     * {@code sourceId →} the one row that carries the source's high watermark and must survive any sweep.
     *
     * <p>⚠ Ordered by {@code lastModified}, then {@code processedAt}, then {@code relativePath} — the last two
     * only break ties, but they must be there: without a total order the protected row would depend on map
     * iteration order, so the same ledger could prune differently on two runs and the dry run could disagree
     * with the sweep. ⛔ This ordering is mirrored in {@code DbAcquisitionLedger}'s SQL; changing one alone
     * makes the two backends diverge.
     */
    private java.util.Map<String, LedgerEntry> watermarkHolders() {
        java.util.Map<String, LedgerEntry> holders = new java.util.HashMap<>();
        for (LedgerEntry v : rows.values()) {
            holders.merge(v.sourceId(), v, (a, b) -> {
                if (a.lastModified() != b.lastModified()) return a.lastModified() > b.lastModified() ? a : b;
                if (a.processedAt() != b.processedAt()) return a.processedAt() > b.processedAt() ? a : b;
                return a.relativePath().compareTo(b.relativePath()) >= 0 ? a : b;
            });
        }
        return holders;
    }

    @Override
    public int prune(long processedBefore, String sourceId) {
        java.util.Map<String, LedgerEntry> holders = watermarkHolders();
        int[] removed = {0};
        rows.entrySet().removeIf(e -> {
            boolean prune = prunable(e.getValue(), processedBefore, sourceId, holders);
            if (prune) removed[0]++;
            return prune;
        });
        return removed[0];
    }

    @Override
    public int countPrunable(long processedBefore, String sourceId) {
        java.util.Map<String, LedgerEntry> holders = watermarkHolders();
        int n = 0;
        for (LedgerEntry v : rows.values()) {
            if (prunable(v, processedBefore, sourceId, holders)) n++;
        }
        return n;
    }

    @Override
    public int renameSource(String oldSourceId, String newSourceId) {
        int moved = 0;
        for (LedgerEntry e : rows.values()) {
            if (!e.sourceId().equals(oldSourceId)) continue;
            rows.remove(key(oldSourceId, e.relativePath()));
            rows.put(key(newSourceId, e.relativePath()), new LedgerEntry(newSourceId, e.relativePath(), e.name(),
                    e.size(), e.checksum(), e.etag(), e.version(), e.lastModified(), e.processedAt(), e.status()));
            moved++;
        }
        String wm = dbWatermarks.remove(oldSourceId);
        if (wm != null) dbWatermarks.put(newSourceId, wm);
        return moved;
    }

    private static String key(String sourceId, String relativePath) {
        return sourceId + '\0' + relativePath;
    }
}

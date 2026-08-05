package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.event.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide, per-space {@link DbFileStageStore} accessor (Phase 4 Slice 2 wiring) — the same
 * global-registry idiom as {@link ConsignmentOutputStores}, so {@code BatchProcessor.finalizeSource}
 * can record stage transitions without threading a store through every call.
 *
 * <p><b>Pure registry — no backend resolution here.</b> {@code ServiceStores.openFileStageStore}
 * remains the single place that reads {@code -Dfile.stages.backend}; the {@code CollectorService}
 * constructor registers the result. Absent by default: {@link #record} then no-ops, and the crash-safe
 * commit ordering is unchanged — see {@link DbFileStageStore}'s class doc for the fail-open contract.
 */
@PublicApi(since = "5.1.0")
public final class FileStages {

    private static final Logger log = LoggerFactory.getLogger(FileStages.class);

    private FileStages() {}

    /** One registry per space ({@link EventLog#currentSpaceId()}); absent ⇒ no stage registry for that space. */
    private static final ConcurrentHashMap<String, DbFileStageStore> STORES = new ConcurrentHashMap<>();

    /** The registry for the calling thread's space, or {@code null} when none is registered (the default). */
    public static DbFileStageStore shared() {
        return STORES.get(EventLog.currentSpaceId());
    }

    /** Install {@code store} for an explicit {@code spaceId} — the per-space bootstrap, which has no MDC set. */
    public static void register(String spaceId, DbFileStageStore store) {
        if (spaceId != null && store != null) STORES.put(spaceId, store);
    }

    /** Install a store for the calling thread's space (tests / embedders); {@code null} removes it. */
    public static void use(DbFileStageStore store) {
        String space = EventLog.currentSpaceId();
        if (store == null) STORES.remove(space);
        else STORES.put(space, store);
    }

    /** Remove and {@link DbFileStageStore#close() close} {@code spaceId}'s registry (on space deletion). */
    public static void unregister(String spaceId) {
        DbFileStageStore removed = (spaceId == null) ? null : STORES.remove(spaceId);
        if (removed != null) {
            try {
                removed.close();
            } catch (Exception e) {
                log.warn("Error closing file-stages registry for space {}: {}", spaceId, e.getMessage());
            }
        }
    }

    /**
     * Record {@code records} in the calling space's registry, or do nothing when none is registered —
     * the one entry point every write path uses, so the default-off case costs a map lookup and no
     * caller branches on it.
     */
    public static void record(List<FileStageRecord> records) {
        if (records == null || records.isEmpty()) return;
        DbFileStageStore store = shared();
        if (store != null) store.record(records);
    }

    /** The stage history for one file in the calling space's registry, or an empty list when none is registered. */
    public static List<FileStageRecord> stages(String sourceId, String relativePath) {
        DbFileStageStore store = shared();
        return store == null ? List.of() : store.stages(sourceId, relativePath);
    }
}

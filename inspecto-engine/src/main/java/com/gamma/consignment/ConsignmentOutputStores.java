package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.event.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide, per-space {@link DbConsignmentOutputStore} accessor (§11.3 slice 2 wiring) — the global-registry
 * idiom of {@link com.gamma.acquire.AcquisitionLedgers} / {@code MetricRegistry.global()}, so the <em>static</em>
 * write paths ({@code BatchProcessor.finalizeSource}, {@code EnrichmentEngine.runResult}) can record output files
 * without threading a store through every call.
 *
 * <p><b>Pure registry — no backend resolution here.</b> Unlike {@code AcquisitionLedgers}, this class never builds
 * a store: {@code ServiceStores.openConsignmentOutputStore} remains the single place that reads
 * {@code -Dconsignment.outputs.backend} and the URL properties, and the {@code CollectorService} constructor
 * registers the result.
 * Two resolvers for one toggle is how a default-off store silently becomes on.
 *
 * <p><b>Optional, and absence is not degraded correctness.</b> The store is on by default since 2026-08-10
 * (D1), but every test and any deployment configuring {@code none} still has none registered — and with none,
 * {@link #record} is a no-op: output files are still revealed and still recorded in the
 * per-Consignment JSON manifest, which stays authoritative for <em>existence</em> while this registry is
 * authoritative for <em>state</em>. That split is what makes recording fail-open safe, so no caller needs a null
 * check and none should treat a missing row as proof a file does not exist.
 */
@PublicApi(since = "5.0.0")
public final class ConsignmentOutputStores {

    private static final Logger log = LoggerFactory.getLogger(ConsignmentOutputStores.class);

    private ConsignmentOutputStores() {}

    /** One registry per space ({@link EventLog#currentSpaceId()}); absent ⇒ no output registry for that space. */
    private static final ConcurrentHashMap<String, DbConsignmentOutputStore> STORES = new ConcurrentHashMap<>();

    /** The registry for the calling thread's space, or {@code null} when none is registered (the default). */
    public static DbConsignmentOutputStore shared() {
        return STORES.get(EventLog.currentSpaceId());
    }

    /** Install {@code store} for an explicit {@code spaceId} — the per-space bootstrap, which has no MDC set. */
    public static void register(String spaceId, DbConsignmentOutputStore store) {
        if (spaceId != null && store != null) STORES.put(spaceId, store);
    }

    /** Install a store for the calling thread's space (tests / embedders); {@code null} removes it. */
    public static void use(DbConsignmentOutputStore store) {
        String space = EventLog.currentSpaceId();
        if (store == null) STORES.remove(space);
        else STORES.put(space, store);
    }

    /** Remove and {@link DbConsignmentOutputStore#close() close} {@code spaceId}'s registry (on space deletion). */
    public static void unregister(String spaceId) {
        DbConsignmentOutputStore removed = (spaceId == null) ? null : STORES.remove(spaceId);
        if (removed != null) {
            try {
                removed.close();
            } catch (Exception e) {
                log.warn("Error closing consignment-outputs registry for space {}: {}", spaceId, e.getMessage());
            }
        }
    }

    /**
     * Record {@code outputs} in the calling space's registry, or do nothing when none is registered — the one
     * entry point every write path uses, so the default-off case costs a map lookup and no caller branches on it.
     * Never throws: {@link DbConsignmentOutputStore#record} is itself best-effort, because losing an index row
     * must not fail a Consignment whose data has already landed.
     */
    public static void record(List<ConsignmentOutput> outputs) {
        if (outputs == null || outputs.isEmpty()) return;
        DbConsignmentOutputStore store = shared();
        if (store != null) store.record(outputs);
    }

    /**
     * Flip {@code paths} to {@code COMPACTED_AWAY} in the calling space's registry, or do nothing when none is
     * registered — the compaction-side twin of {@link #record}, so {@code PartitionCompactor} stays static and
     * unaware of whether a registry exists.
     */
    public static void markCompactedAway(List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        DbConsignmentOutputStore store = shared();
        if (store != null) store.markCompactedAway(paths);
    }
}

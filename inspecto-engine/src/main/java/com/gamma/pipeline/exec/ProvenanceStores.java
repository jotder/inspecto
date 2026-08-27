package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.event.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide, per-space {@link DbProvenanceStore} accessor (consignment-chain-plan.md S3) — the same
 * global-registry idiom as {@code FileStages}/{@code ConsignmentOutputStores}, so the static ingest
 * lane ({@code BatchProcessor}) can record step counts without threading a store through every call.
 *
 * <p><b>Pure registry — no backend resolution here.</b> {@code ServiceStores.openProvenanceStore}
 * remains the single place that reads {@code -Dprovenance.backend}; the {@code CollectorService}
 * constructor registers the result and hands the <b>same instance</b> to the Job engine (one store,
 * one connection — DuckDB is single-writer). The Job engine's close and {@link #unregister} may both
 * run; JDBC {@code close()} is idempotent, so the double call is free — the same posture as the
 * sibling registries' space-teardown double-unregister.
 */
@PublicApi(since = "4.0.0")
public final class ProvenanceStores {

    private static final Logger log = LoggerFactory.getLogger(ProvenanceStores.class);

    private ProvenanceStores() {}

    /** One store per space ({@link EventLog#currentSpaceId()}); absent ⇒ no provenance for that space. */
    private static final ConcurrentHashMap<String, DbProvenanceStore> STORES = new ConcurrentHashMap<>();

    /** The store for the calling thread's space, or {@code null} when none is registered (the default). */
    public static DbProvenanceStore shared() {
        return STORES.get(EventLog.currentSpaceId());
    }

    /** Install {@code store} for an explicit {@code spaceId} — the per-space bootstrap, which has no MDC set. */
    public static void register(String spaceId, DbProvenanceStore store) {
        if (spaceId != null && store != null) STORES.put(spaceId, store);
    }

    /** Install a store for the calling thread's space (tests / embedders); {@code null} removes it. */
    public static void use(DbProvenanceStore store) {
        String space = EventLog.currentSpaceId();
        if (store == null) STORES.remove(space);
        else STORES.put(space, store);
    }

    /** Remove and close {@code spaceId}'s store (on space teardown). Idempotent; double close is safe. */
    public static void unregister(String spaceId) {
        DbProvenanceStore removed = (spaceId == null) ? null : STORES.remove(spaceId);
        if (removed != null) {
            try {
                removed.close();
            } catch (Exception e) {
                log.warn("Error closing provenance store for space {}: {}", spaceId, e.getMessage());
            }
        }
    }

    /**
     * Record {@code rows} in the calling space's store, or do nothing when none is registered — the
     * one entry point the ingest lane uses, so the default-off case costs a map lookup and no caller
     * branches on it. Best-effort like the store itself: never throws into the commit path.
     */
    public static void record(List<ProvenanceRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        DbProvenanceStore store = shared();
        if (store != null) store.record(rows);
    }
}

package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.event.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide, per-space {@link DbDedupLedger} accessor (D-9 wiring) — the same pure-registry idiom as
 * {@link ConsignmentOutputStores}: no backend resolution here ({@code ServiceStores.openDedupLedger}
 * remains the single reader of {@code -Ddedup.ledger.backend} and the URL properties; the
 * {@code CollectorService} constructor registers the result), so one toggle has one resolver.
 *
 * <p><b>Unlike the output registry, absence here is NOT fail-open.</b> A windowed {@code transform.dedup}
 * step that silently skipped the ledger would emit the very duplicates it was configured to drop — worse
 * than absent. So no {@code record}-style no-op wrapper exists: the consumer
 * ({@code RowShaper.ExecutionContext}) refuses a windowed scope when {@link #shared()} answers
 * {@code null}, loudly, exactly as {@code ReferenceResolver.NONE} refuses a by-name join.
 */
@PublicApi(since = "4.0.0")
public final class DedupLedgers {

    private static final Logger log = LoggerFactory.getLogger(DedupLedgers.class);

    private DedupLedgers() {}

    /** One ledger per space ({@link EventLog#currentSpaceId()}); absent ⇒ no dedup ledger for that space. */
    private static final ConcurrentHashMap<String, DbDedupLedger> LEDGERS = new ConcurrentHashMap<>();

    /** The ledger for the calling thread's space, or {@code null} when none is registered. */
    public static DbDedupLedger shared() {
        return LEDGERS.get(EventLog.currentSpaceId());
    }

    /** Install {@code ledger} for an explicit {@code spaceId} — the per-space bootstrap, which has no MDC set. */
    public static void register(String spaceId, DbDedupLedger ledger) {
        if (spaceId != null && ledger != null) LEDGERS.put(spaceId, ledger);
    }

    /** Install a ledger for the calling thread's space (tests / embedders); {@code null} removes it. */
    public static void use(DbDedupLedger ledger) {
        String space = EventLog.currentSpaceId();
        if (ledger == null) LEDGERS.remove(space);
        else LEDGERS.put(space, ledger);
    }

    /** Remove and close {@code spaceId}'s ledger (on space deletion / shutdown). */
    public static void unregister(String spaceId) {
        DbDedupLedger removed = (spaceId == null) ? null : LEDGERS.remove(spaceId);
        if (removed != null) {
            try {
                removed.close();
            } catch (Exception e) {
                log.warn("Error closing dedup ledger for space {}: {}", spaceId, e.getMessage());
            }
        }
    }
}

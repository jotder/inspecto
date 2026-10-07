package com.gamma.event;

import com.gamma.audit.AuditAttrs;
import com.gamma.audit.AuditChain;
import com.gamma.audit.Event;
import com.gamma.audit.EventStore;
import com.gamma.util.JsonAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Space's audit hash chain WRITER state: the head (seq, hash, ts) and the store it was recovered from. The
 * chain's format and verification (canonical form, hash, {@link AuditChain#chained}, {@link AuditChain#seq}) stay
 * in the {@code audit-spi} contract ({@link AuditChain}); this per-log linker is the implementation and lives with
 * {@code EventLog}. See {@link AuditChain} for the conventions (seq from 1, genesis prevHash, one total order per
 * Space, restart recovery, monotonic timestamps).
 */
final class AuditChainLinker {

    private long headSeq;
    private String headHash;
    private long headTs;
    /** The store the head was recovered from; a different store (a swap) means recover again. */
    private EventStore recoveredFrom;

    AuditChainLinker() {}

    /** Forget the head so the next {@link #link} recovers it from the store (a store swap). Caller holds the monitor. */
    void reset() {
        recoveredFrom = null;
    }

    /**
     * Link {@code e} as the next record of the chain held in {@code store}: seq = head + 1, prevHash = head hash,
     * ts raised to the head's if earlier, payload normalised, any chain attribute the caller supplied replaced.
     * Caller holds this instance's monitor and appends the returned event to {@code store} before releasing it.
     *
     * @throws IllegalStateException when the head cannot be recovered from the store, or a unit of it could not be
     *         read — linking anyway would restart at genesis, or reuse stored seqs, and fork the chain
     */
    Event link(Event e, EventStore store) {
        store.claimChainWriter();   // EVERY link: one linker per directory, and the lock must still be ours
        if (recoveredFrom != store) {
            Event head = store.chainHead();
            // A head read that skipped a file has not seen that file's seqs: the true head may be in it, and linking
            // onto the head it did see would reuse seqs already stored — a fork. Refuse; the row is stored unlinked.
            List<String> unread = store.unreadableUnits();
            if (!unread.isEmpty())
                throw new IllegalStateException("the audit chain head cannot be recovered: " + unread
                        + " could not be read and may hold a later seq");
            if (head == null) {
                headSeq = 0;
                headHash = AuditChain.GENESIS;
                headTs = Long.MIN_VALUE;
            } else {
                headSeq = AuditChain.seq(head);
                headHash = AuditChain.storedHash(head);
                headTs = head.ts();
                if (headSeq < 1 || headHash == null)
                    throw new IllegalStateException("the audit chain head in the store carries no seq/hash");
            }
            recoveredFrom = store;
        }
        Map<String, String> attrs = new LinkedHashMap<>(e.attributes());
        attrs.keySet().removeAll(AuditChain.CHAIN_KEYS);
        attrs.put(AuditAttrs.AUDIT_SEQ, Long.toString(headSeq + 1));
        attrs.put(AuditAttrs.AUDIT_PREV_HASH, headHash);
        Map<String, Object> payload = JsonAttributes.fromPayloadJson(JsonAttributes.toPayloadJson(e.payload()));
        long ts = Math.max(e.ts(), headTs);
        Event unsealed = new Event(e.eventId(), ts, e.level(), e.type(), e.source(), e.pipeline(),
                e.correlationId(), e.message(), attrs, payload);
        String h = AuditChain.hash(unsealed);
        attrs.put(AuditAttrs.AUDIT_HASH, h);
        Event sealed = new Event(unsealed.eventId(), ts, unsealed.level(), unsealed.type(), unsealed.source(),
                unsealed.pipeline(), unsealed.correlationId(), unsealed.message(), attrs, payload);
        headSeq++;
        headHash = h;
        headTs = ts;
        return sealed;
    }
}

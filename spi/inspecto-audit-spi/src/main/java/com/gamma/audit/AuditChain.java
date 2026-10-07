package com.gamma.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.util.JsonAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The audit trail's hash chain (ASSURE-AUDIT-CHAIN-1, WS-25): every {@link EventType#AUDIT} and
 * {@link EventType#ACCESS_DENIED} event a Space's {@link EventLog} emits is given a sequence number
 * ({@link AuditAttrs#AUDIT_SEQ}), the previous record's hash ({@link AuditAttrs#AUDIT_PREV_HASH}) and its own
 * hash ({@link AuditAttrs#AUDIT_HASH}) — SHA-256 (lowercase hex) over the {@link #canonical canonical encoding}
 * of the record, prevHash included. Editing, deleting, inserting or reordering a stored record then shows as a
 * break that {@code GET /audit/verify} names by seq.
 *
 * <h3>Conventions (shared with the Link Analysis Entity Fact log)</h3>
 * Seq starts at 1; the genesis record's prevHash is {@value #GENESIS} (the empty string); hashes are SHA-256
 * lowercase hex. The Entity Fact log hashes each fact FILE's exact bytes — possible there because a fact is one
 * file. An audit event is not stored as bytes: it round-trips through Parquet columns (or a JDBC row), so the
 * hash is over a canonical re-encoding of the fields instead.
 *
 * <h3>One total order per Space</h3>
 * One instance per {@link EventLog}, and a Space owns exactly one log — so the chain is per Space. The log holds
 * this instance's monitor across {@link #link} AND the store append, so seq order is append order and no two
 * records share a seq (a single writer per Space). ⚠ Per PROCESS: a {@code DbEventStore} shared by several pods
 * would get one chain per pod interleaved in one table, which verifies as duplicates — a residual, not handled.
 *
 * <h3>Restart and journal replay</h3>
 * The head (last seq + hash) is not kept anywhere of its own: it is recovered from the store on the first link
 * ({@link EventStore#chainHead()}). A record replayed from {@code ParquetEventStore}'s write-ahead journal is
 * already linked — its chain attributes were fixed before it was journaled — and replay appends it without
 * re-linking, so the recovered head is that record and the next one continues after it: no fork, no duplicate.
 *
 * <h3>Timestamps are monotonic along the chain</h3>
 * {@link #link} raises a record's {@code ts} to the head's when the clock reads earlier (two threads that read
 * the clock in one order and take the lock in the other; a clock stepped back). So an honest chain never goes
 * back in time, and a record whose time is earlier than its predecessor's is a {@code reorder}.
 *
 * <h3>Records from before the chain</h3>
 * An audit event stored before this existed has no chain attributes and is not part of the chain: the chain
 * starts at the first record linked after the upgrade, at seq 1.
 */
public final class AuditChain {

    /** The prevHash of seq 1. */
    public static final String GENESIS = "";

    /** The canonical encoding's format version — part of the hash input, so a future change cannot collide. */
    static final int FORMAT = 1;

    /** The event types that are chained: exactly the audit projection {@code /audit/search} serves. */
    public static final Set<String> TYPES = Set.of(EventType.AUDIT, EventType.ACCESS_DENIED);

    private static final Set<String> CHAIN_KEYS =
            Set.of(AuditAttrs.AUDIT_SEQ, AuditAttrs.AUDIT_PREV_HASH, AuditAttrs.AUDIT_HASH, AuditAttrs.AUDIT_UNLINKED);

    /** Recursively key-sorted, compact, nulls written — the canonical JSON (the same form {@code ContentHash} uses). */
    private static final ObjectMapper CANONICAL =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private long headSeq;
    private String headHash;
    private long headTs;
    /** The store the head was recovered from; a different store (a swap) means recover again. */
    private EventStore recoveredFrom;

    AuditChain() {}

    /** Whether {@code e} is a chained type. */
    public static boolean chained(Event e) {
        return e != null && TYPES.contains(e.type());
    }

    /** The record's seq, or {@code -1} when it carries none (unchained, or pre-dating the chain). */
    public static long seq(Event e) {
        String s = e.attributes().get(AuditAttrs.AUDIT_SEQ);
        if (s == null) return -1;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException bad) {
            return -1;
        }
    }

    /** Whether {@code e} is off the chain: no seq, or explicitly marked {@link AuditAttrs#AUDIT_UNLINKED}. */
    public static boolean unlinked(Event e) {
        return seq(e) < 1 || "true".equals(e.attributes().get(AuditAttrs.AUDIT_UNLINKED));
    }

    /** The stored prevHash, or {@code null}. */
    public static String prevHash(Event e) {
        return e.attributes().get(AuditAttrs.AUDIT_PREV_HASH);
    }

    /** The stored hash, or {@code null}. */
    public static String storedHash(Event e) {
        return e.attributes().get(AuditAttrs.AUDIT_HASH);
    }

    /**
     * The canonical encoding of {@code e} that its hash is taken over: a JSON object, keys sorted at every depth,
     * no whitespace, every field present (a null is written as {@code null}, never dropped), UTF-8. Numbers are
     * only {@code seq}/{@code ts} (integers) and whatever the payload holds, which {@link #link} has already
     * normalised through the same JSON round trip the store applies — so a double reads back as the same text.
     * Attributes are strings; the three chain attributes are excluded from them, and seq/prevHash appear as
     * their own fields instead (the hash cannot cover itself).
     */
    public static String canonical(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", FORMAT);
        m.put("seq", seq(e));
        m.put("prevHash", prevHash(e));
        m.put("eventId", e.eventId());
        m.put("ts", e.ts());
        m.put("level", e.level().name());
        m.put("type", e.type());
        m.put("source", e.source());
        m.put("pipeline", e.pipeline());
        m.put("correlationId", e.correlationId());
        m.put("message", e.message());
        Map<String, String> attrs = new TreeMap<>(e.attributes());
        attrs.keySet().removeAll(CHAIN_KEYS);
        m.put("attributes", attrs);
        m.put("payload", e.payload());
        try {
            return CANONICAL.writeValueAsString(m);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("audit event is not JSON-serialisable", ex);
        }
    }

    /** SHA-256 (lowercase hex) over the UTF-8 bytes of {@link #canonical}. */
    public static String hash(Event e) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(e).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);   // never on a conformant JDK
        }
    }

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
                headHash = GENESIS;
                headTs = Long.MIN_VALUE;
            } else {
                headSeq = seq(head);
                headHash = storedHash(head);
                headTs = head.ts();
                if (headSeq < 1 || headHash == null)
                    throw new IllegalStateException("the audit chain head in the store carries no seq/hash");
            }
            recoveredFrom = store;
        }
        Map<String, String> attrs = new LinkedHashMap<>(e.attributes());
        attrs.keySet().removeAll(CHAIN_KEYS);
        attrs.put(AuditAttrs.AUDIT_SEQ, Long.toString(headSeq + 1));
        attrs.put(AuditAttrs.AUDIT_PREV_HASH, headHash);
        Map<String, Object> payload = JsonAttributes.fromPayloadJson(JsonAttributes.toPayloadJson(e.payload()));
        long ts = Math.max(e.ts(), headTs);
        Event unsealed = new Event(e.eventId(), ts, e.level(), e.type(), e.source(), e.pipeline(),
                e.correlationId(), e.message(), attrs, payload);
        String h = hash(unsealed);
        attrs.put(AuditAttrs.AUDIT_HASH, h);
        Event sealed = new Event(unsealed.eventId(), ts, unsealed.level(), unsealed.type(), unsealed.source(),
                unsealed.pipeline(), unsealed.correlationId(), unsealed.message(), attrs, payload);
        headSeq++;
        headHash = h;
        headTs = ts;
        return sealed;
    }
}

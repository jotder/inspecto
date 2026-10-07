package com.gamma.la.api;

import com.gamma.spi.http.ApiContext;
import com.gamma.la.core.InvestigationMembers;
import com.gamma.la.core.InvestigationMembers.Entry;
import com.gamma.la.core.InvestigationMembers.Op;
import com.gamma.la.core.InvestigationMembers.Role;
import com.gamma.la.core.InvestigationStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D7-1 - the membership of an Investigation: one JSON line per grant or revoke, only ever APPENDED (never rewritten), held by the
 * {@link InvestigationStore} beside the header. The pure model and the fold live in {@link InvestigationMembers}. No lines means
 * "no grants": the owner is the sole lead, exactly as before membership existed.
 *
 * <p>An append carries the number of lines the caller folded its last-lead check over; the store refuses it
 * ({@link com.gamma.la.core.InvestigationVersionConflictException}) when another grant or revoke landed meanwhile, so the check
 * and the append stay one decision without any caller-held lock.
 */
final class InvestigationMemberStore {

    private InvestigationMemberStore() {}

    /** Whether the Investigation has ever had a grant or revoke. Without one, only the legacy rules apply. */
    static boolean explicit(InvestigationStore store, String id) throws IOException {
        return !store.members(id).isEmpty();
    }

    /** The entries in order. A line that does not parse is a corrupt record: fail closed (the IOException is the caller's 500). */
    @SuppressWarnings("unchecked")
    static List<Entry> read(InvestigationStore store, String id) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (String line : store.members(id)) {
            Map<String, Object> m = ApiContext.JSON.readValue(line, Map.class);
            Role role = Role.parse(String.valueOf(m.get("role")))
                    .orElseThrow(() -> new IOException("members.jsonl: unknown role in " + line));
            Op op = Op.parse(String.valueOf(m.get("op")))
                    .orElseThrow(() -> new IOException("members.jsonl: unknown op in " + line));
            out.add(new Entry(((Number) m.get("seq")).longValue(), String.valueOf(m.get("ts")),
                    String.valueOf(m.get("actor")), String.valueOf(m.get("subject")), role, op));
        }
        return out;
    }

    /** The current role per Subject: the header's owner as first lead, then the folded entries. */
    static Map<String, Role> roles(InvestigationStore store, String id, Object owner) throws IOException {
        return fold(owner, read(store, id));
    }

    /** {@link #roles} over entries the caller already read (so the same read can be the append's precondition). */
    static Map<String, Role> fold(Object owner, List<Entry> entries) {
        return InvestigationMembers.fold(owner == null ? null : String.valueOf(owner), entries);
    }

    /** How many times a grant or revoke re-reads and retries after losing a race to another membership change. */
    static final int MAX_ATTEMPTS = 20;

    /**
     * Append one entry (seq = {@code seenCount} + 1), where {@code seenCount} is the number of lines the caller's decision was
     * made over. Throws {@link com.gamma.la.core.InvestigationVersionConflictException} if the member list moved.
     */
    static Entry append(InvestigationStore store, String id, long seenCount, String ts, String actor, String subject, Role role, Op op)
            throws IOException {
        Entry e = new Entry(seenCount + 1, ts, actor, subject, role, op);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.seq());
        m.put("ts", e.ts());
        m.put("actor", e.actor());
        m.put("subject", e.subject());
        m.put("role", e.role().wire());
        m.put("op", e.op().wire());
        store.appendMember(id, seenCount, ApiContext.JSON.writeValueAsString(m));
        return e;
    }
}

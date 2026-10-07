package com.gamma.entitylist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The Entity Lists as of one position in the identity fact log (LA-17, design §4.2) — a pure fold over
 * {@link EntityFactLog} facts.
 *
 * <p><b>Fold.</b> Facts with {@code seq <= atSeq} are applied in seq order:
 * <ul>
 *   <li>{@code list.created {title, purpose, entityType, normaliser}} — a new, empty list. {@code normaliser} is the
 *       Entity Type's rule SEALED at creation (D-M9): every later member write on the list normalises with it, whatever
 *       the type says today. A fact written before the field existed reads as {@code default};</li>
 *   <li>{@code list.member.added {keys[]}} / {@code list.member.removed {keys[]}} — set union / difference. One fact
 *       carries every EFFECTIVE key of one call (normalised, sorted), not one fact per key, so a 5 000-key call is
 *       one file, not 5 000;</li>
 *   <li>{@code list.retired} — flagged; its members are kept (a retired list still reads as it was).</li>
 *   <li>ASSURE-ENTITY-LISTS-1 (WS-12): {@code list.member.added} may carry {@code expiresAt} (an ISO instant) for
 *       every key it names; a later add without it makes them permanent, a remove forgets the expiry.
 *       {@code list.range.added {ranges[], expiresAt?}} / {@code list.range.removed {ranges[]}} keep the range entries
 *       ({@link EntityListEntries}: prefix, same-length range, CIDR) the same way. An expired entry stays in the
 *       fold (as-of reads show it) but no longer MATCHES ({@link EntityList#live}).</li>
 * </ul>
 * The writer ({@link EntityListRoutes}) guarantees the invariants — a created id is new, a changed list exists and
 * is not retired — so the fold does not re-judge them; a fact naming a list it has not seen is ignored.
 *
 * <p>⛔ <b>Not cached.</b> {@link EntityFactLog#read()} must parse every fact to verify the chain, so the only thing a
 * head-hash cache (the {@code WorkingSetRoutes} idiom) could save is this linear set fold — not worth a cache.
 */
public final class EntityRegistry {

    private EntityRegistry() {}

    /** A JSON array of anything as strings; a non-array is empty. Same coercion as Link Analysis' evaluator — duplicated, not shared, so this module needs nothing from it. */
    static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        return out;
    }

    /**
     * One Entity List as of a log position. {@code members} are normalised keys, sorted; {@code ranges} the canonical
     * range entries; {@code expiresAt} / {@code rangeExpiresAt} the expiry of each expiring member / range (absent =
     * permanent). Two maps, because a member key of a {@code default} list may spell like a range entry.
     */
    public record EntityList(String id, String title, String purpose, String entityType, String normaliser, String createdAt,
                      String createdBy, boolean retired, long lastSeq, SortedSet<String> members,
                      SortedSet<String> ranges, java.util.SortedMap<String, String> expiresAt,
                      java.util.SortedMap<String, String> rangeExpiresAt) {

        /** Whether member {@code key} still matches at {@code now}: it has no expiry, or one after now. */
        public boolean live(String key, java.time.Instant now) {
            return notExpired(expiresAt.get(key), now);
        }

        /** Whether range entry {@code range} still matches at {@code now}. */
        public boolean liveRange(String range, java.time.Instant now) {
            return notExpired(rangeExpiresAt.get(range), now);
        }

        private static boolean notExpired(String at, java.time.Instant now) {
            return at == null || java.time.Instant.parse(at).isAfter(now);
        }

        /** The members that still match at {@code now}. */
        public SortedSet<String> liveMembers(java.time.Instant now) {
            SortedSet<String> out = new TreeSet<>();
            for (String k : members) if (live(k, now)) out.add(k);
            return Collections.unmodifiableSortedSet(out);
        }

        /**
         * The entry that admits {@code raw} at {@code now}: its key when an exact member, else the first live range
         * entry that contains it; {@code null} when none does. An expired entry never matches.
         */
        public String match(String raw, java.time.Instant now) {
            String key = com.gamma.control.EntityTypes.normalise(normaliser, raw);
            if (!key.isEmpty() && members.contains(key) && live(key, now)) return key;
            for (String r : ranges)
                if (liveRange(r, now) && EntityListEntries.matches(EntityListEntries.parse(r), key, raw)) return r;
            return null;
        }
    }

    /** Every list that existed at {@code atSeq}, in creation order. */
    public static Map<String, EntityList> fold(List<EntityFactLog.Fact> facts, long atSeq) {
        Map<String, Acc> lists = new LinkedHashMap<>();
        for (EntityFactLog.Fact f : facts) {
            if (f.seq() > atSeq) break;
            Map<String, Object> b = f.body();
            String id = f.listId();
            if ("list.created".equals(f.kind())) {
                lists.putIfAbsent(id, new Acc(id, str(b, "title"), str(b, "purpose"), str(b, "entityType"),
                        b.get("normaliser") == null ? "default" : str(b, "normaliser"), str(b, "at"), str(b, "actor"), f.seq()));
                continue;
            }
            Acc l = lists.get(id);
            if (l == null) continue;
            switch (f.kind()) {
                case "list.member.added" -> expiring(strings(b.get("keys")), b.get("expiresAt"), l.members, l.expires);
                case "list.member.removed" -> forget(strings(b.get("keys")), l.members, l.expires);
                case "list.range.added" -> expiring(strings(b.get("ranges")), b.get("expiresAt"), l.ranges, l.rangeExpires);
                case "list.range.removed" -> forget(strings(b.get("ranges")), l.ranges, l.rangeExpires);
                case "list.retired" -> l.retired = true;
                default -> { continue; }
            }
            l.lastSeq = f.seq();
        }
        Map<String, EntityList> out = new LinkedHashMap<>();
        lists.forEach((id, l) -> out.put(id, new EntityList(l.id, l.title, l.purpose, l.entityType, l.normaliser, l.createdAt,
                l.createdBy, l.retired, l.lastSeq, Collections.unmodifiableSortedSet(l.members),
                Collections.unmodifiableSortedSet(l.ranges), Collections.unmodifiableSortedMap(l.expires),
                Collections.unmodifiableSortedMap(l.rangeExpires))));
        return out;
    }

    private static void expiring(List<String> entries, Object expiresAt, SortedSet<String> into,
                                 java.util.SortedMap<String, String> expiries) {
        into.addAll(entries);
        for (String e : entries)
            if (expiresAt == null) expiries.remove(e); else expiries.put(e, String.valueOf(expiresAt));
    }

    private static void forget(List<String> entries, SortedSet<String> from, java.util.SortedMap<String, String> expiries) {
        from.removeAll(entries);
        entries.forEach(expiries::remove);
    }

    // ── identity resolution (slice 2, design §8.1) ─────────────────────────────────────────────────────

    /** One {@code identity.asserted} fact: typed keys {@code a}, {@code b} (normalised, sealed), and whether it is retracted. */
    public record Assertion(long seq, String a, String b, String via, String actor, String at, String reason,
                     long retractedBy) {
        public boolean live() { return retractedBy == 0; }
    }

    /**
     * One resolved group: its id (the lexicographically smallest member key), every member key (sorted) and the
     * seqs of the live assertions that join it (sorted) — a merge never hides which assertion matched.
     */
    public record Group(String id, SortedSet<String> members, SortedSet<Long> assertions) {}

    /** Every {@code identity.asserted} fact as of {@code atSeq}, by seq, with the retraction (if any) as of {@code atSeq}. */
    public static Map<Long, Assertion> assertions(List<EntityFactLog.Fact> facts, long atSeq) {
        Map<Long, Assertion> out = new LinkedHashMap<>();
        for (EntityFactLog.Fact f : facts) {
            if (f.seq() > atSeq) break;
            Map<String, Object> b = f.body();
            switch (f.kind()) {
                case "identity.asserted" -> out.put(f.seq(), new Assertion(f.seq(), str(b, "a"), str(b, "b"),
                        str(b, "via"), str(b, "actor"), str(b, "at"), str(b, "reason"), 0));
                case "identity.retracted" -> {
                    if (b.get("assertionSeq") instanceof Number n && out.get(n.longValue()) instanceof Assertion x
                            && x.live())
                        out.put(x.seq(), new Assertion(x.seq(), x.a(), x.b(), x.via(), x.actor(), x.at(), x.reason(),
                                f.seq()));
                }
                default -> { }
            }
        }
        return out;
    }

    /**
     * The resolved groups as of {@code atSeq}: a union-find over the LIVE assertions, applied in seq order. The
     * result depends only on the set of live edges, never on the order they were applied, so it is deterministic:
     * groups keyed and sorted by their smallest member key. A key in no live assertion is in no group.
     */
    public static Map<String, Group> resolve(List<EntityFactLog.Fact> facts, long atSeq) {
        Map<String, String> parent = new java.util.HashMap<>();
        List<Assertion> live = assertions(facts, atSeq).values().stream().filter(Assertion::live).toList();
        for (Assertion x : live) {
            String ra = find(parent, x.a()), rb = find(parent, x.b());
            if (ra.equals(rb)) continue;
            if (ra.compareTo(rb) < 0) parent.put(rb, ra); else parent.put(ra, rb);   // root = smallest key
        }
        Map<String, Group> groups = new java.util.TreeMap<>();
        for (Assertion x : live) {
            String root = find(parent, x.a());
            Group g = groups.computeIfAbsent(root, r -> new Group(r, new TreeSet<>(), new TreeSet<>()));
            g.members().add(x.a());
            g.members().add(x.b());
            g.assertions().add(x.seq());
        }
        Map<String, Group> out = new LinkedHashMap<>();
        groups.forEach((id, g) -> out.put(id, new Group(id, Collections.unmodifiableSortedSet(g.members()),
                Collections.unmodifiableSortedSet(g.assertions()))));
        return out;
    }

    private static String find(Map<String, String> parent, String k) {
        String r = k;
        while (parent.containsKey(r)) r = parent.get(r);
        return r;
    }

    private static String str(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static final class Acc {
        final String id, title, purpose, entityType, normaliser, createdAt, createdBy;
        final SortedSet<String> members = new TreeSet<>();
        final SortedSet<String> ranges = new TreeSet<>();
        final java.util.SortedMap<String, String> expires = new java.util.TreeMap<>();
        final java.util.SortedMap<String, String> rangeExpires = new java.util.TreeMap<>();
        boolean retired;
        long lastSeq;

        Acc(String id, String title, String purpose, String entityType, String normaliser, String createdAt,
            String createdBy, long seq) {
            this.id = id;
            this.title = title;
            this.purpose = purpose;
            this.entityType = entityType;
            this.normaliser = normaliser;
            this.createdAt = createdAt;
            this.createdBy = createdBy;
            this.lastSeq = seq;
        }
    }
}

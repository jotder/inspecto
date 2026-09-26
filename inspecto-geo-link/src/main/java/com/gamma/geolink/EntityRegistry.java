package com.gamma.geolink;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static com.gamma.geolink.InvestigationEvaluator.strings;

/**
 * The Entity Lists as of one position in the identity fact log (LA-17, design §4.2) — a pure fold over
 * {@link EntityFactLog} facts.
 *
 * <p><b>Fold.</b> Facts with {@code seq <= atSeq} are applied in seq order:
 * <ul>
 *   <li>{@code list.created {title, purpose, entityType}} — a new, empty list;</li>
 *   <li>{@code list.member.added {keys[]}} / {@code list.member.removed {keys[]}} — set union / difference. One fact
 *       carries every EFFECTIVE key of one call (normalised, sorted), not one fact per key, so a 5 000-key call is
 *       one file, not 5 000;</li>
 *   <li>{@code list.retired} — flagged; its members are kept (a retired list still reads as it was).</li>
 * </ul>
 * The writer ({@link EntityListRoutes}) guarantees the invariants — a created id is new, a changed list exists and
 * is not retired — so the fold does not re-judge them; a fact naming a list it has not seen is ignored.
 *
 * <p>⛔ <b>Not cached.</b> {@link EntityFactLog#read()} must parse every fact to verify the chain, so the only thing a
 * head-hash cache (the {@code WorkingSetRoutes} idiom) could save is this linear set fold — not worth a cache.
 */
final class EntityRegistry {

    private EntityRegistry() {}

    /** One Entity List as of a log position. {@code members} are normalised keys, sorted. */
    record EntityList(String id, String title, String purpose, String entityType, String createdAt, String createdBy,
                      boolean retired, long lastSeq, SortedSet<String> members) {}

    /** Every list that existed at {@code atSeq}, in creation order. */
    static Map<String, EntityList> fold(List<EntityFactLog.Fact> facts, long atSeq) {
        Map<String, Acc> lists = new LinkedHashMap<>();
        for (EntityFactLog.Fact f : facts) {
            if (f.seq() > atSeq) break;
            Map<String, Object> b = f.body();
            String id = f.listId();
            if ("list.created".equals(f.kind())) {
                lists.putIfAbsent(id, new Acc(id, str(b, "title"), str(b, "purpose"), str(b, "entityType"),
                        str(b, "at"), str(b, "actor"), f.seq()));
                continue;
            }
            Acc l = lists.get(id);
            if (l == null) continue;
            switch (f.kind()) {
                case "list.member.added" -> l.members.addAll(strings(b.get("keys")));
                case "list.member.removed" -> l.members.removeAll(strings(b.get("keys")));
                case "list.retired" -> l.retired = true;
                default -> { continue; }
            }
            l.lastSeq = f.seq();
        }
        Map<String, EntityList> out = new LinkedHashMap<>();
        lists.forEach((id, l) -> out.put(id, new EntityList(l.id, l.title, l.purpose, l.entityType, l.createdAt,
                l.createdBy, l.retired, l.lastSeq, Collections.unmodifiableSortedSet(l.members))));
        return out;
    }

    private static String str(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static final class Acc {
        final String id, title, purpose, entityType, createdAt, createdBy;
        final SortedSet<String> members = new TreeSet<>();
        boolean retired;
        long lastSeq;

        Acc(String id, String title, String purpose, String entityType, String createdAt, String createdBy, long seq) {
            this.id = id;
            this.title = title;
            this.purpose = purpose;
            this.entityType = entityType;
            this.createdAt = createdAt;
            this.createdBy = createdBy;
            this.lastSeq = seq;
        }
    }
}

package com.gamma.la.api;

import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.EntityListFacts;
import com.gamma.entitystore.EntityRegistry;
import com.gamma.entitystore.EntityTypes;
import com.gamma.la.core.InvestigationEvaluator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * LA-GROW-EXCLUDE-1 GE-3: the answer of a Grow preview, shared by the Investigation dry run
 * ({@code POST /inv/investigations/{id}/expand/preview}) and the Basic explorer's
 * ({@code POST /inv/projection/neighbors/preview}):
 * {@code {addsNodes, addsLinks, held[{id, contacts}], heldCount, excludedByLists[{listId, title, removed, rounded}],
 * truncated, readPath, reason?, needsApproval}}.
 *
 * <p><b>Restricted lists (plan §7, GE-8).</b> A list's {@code removed} count can name its members on a tiny graph
 * (removing 1 node from 3 says who it was), so a {@code restricted} list (LA-GROW-EXCLUDE-1 phase 4: restricted now, or
 * sealed restricted, or no longer known - fail closed) reports the count rounded to the nearest 5, never below 5, and 0
 * stays 0 ({@link EntityListFacts#roundedCount}) to a caller holding neither {@code canManageExclusionCategories} nor
 * {@code canRevealLinkEntities}. Every other count is exact. The Basic explorer's preview refuses a restricted list to
 * such a caller instead ({@code InvRoutes}), because differencing two previews would name its members.
 */
final class GrowPreview {

    private GrowPreview() {}

    /** The Space's Entity Lists by id, folded at the Identity Fact log's head - a READ of the log (both previews' titles and members). */
    static Map<String, EntityRegistry.EntityList> entityLists(Path writeRoot) throws IOException {
        EntityFactLog.Log head = EntityListFacts.read(new EntityFactLog(writeRoot));
        return EntityRegistry.fold(head.facts(), head.headSeq());
    }

    /**
     * One line of {@code excludedByLists}: the title from the registry (the id when the list is gone), the count rounded
     * when {@code restricted} (see the class note; the caller decides it, with the caller's grants).
     */
    static Map<String, Object> listLine(String listId, EntityRegistry.EntityList list, long removed, boolean restricted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("listId", listId);
        m.put("title", list == null || list.title() == null ? listId : list.title());
        m.put("removed", restricted ? EntityListFacts.roundedCount((int) Math.min(removed, Integer.MAX_VALUE)) : removed);
        m.put("rounded", restricted);
        return m;
    }

    /** One held busy number: {@code {id, contacts}}. */
    static Map<String, Object> held(String id, long contacts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("contacts", contacts);
        return m;
    }

    /** Busiest first, then by id. */
    static void sortHeld(List<Map<String, Object>> held) {
        held.sort(Comparator.comparingLong((Map<String, Object> m) -> -((Number) m.get("contacts")).longValue())
                .thenComparing(m -> String.valueOf(m.get("id"))));
    }

    /** The common answer; {@code reason} only when there is one. */
    static Map<String, Object> answer(long addsNodes, long addsLinks, List<Map<String, Object>> held,
                                      List<Map<String, Object>> lists, boolean truncated, String readPath, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("addsNodes", addsNodes);
        out.put("addsLinks", addsLinks);
        out.put("held", held);
        out.put("heldCount", held.size());
        out.put("excludedByLists", lists);
        out.put("truncated", truncated);
        out.put("readPath", readPath);
        if (reason != null) out.put("reason", reason);
        out.put("needsApproval", false);
        return out;
    }

    /**
     * The Investigation preview from the state before the step, the state after the would-be entry and that entry:
     * {@code addsNodes}/{@code addsLinks} are the append's own delta ({@code admitted}, {@code linksAdded});
     * {@code held} the read's hubs the step leaves flagged high connectivity (the append's {@code hubsFlagged}), with
     * their sealed degree as {@code contacts}; {@code excludedByLists} one line per {@code excludeBy} in force, counting
     * the distinct read endpoints not yet admitted whose key that list remembers (what the evaluator drops);
     * {@code readPath} {@code index} when the edge index answered, else {@code flat} with the fallback's reason.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> ofExpand(InvestigationEvaluator.State before, InvestigationEvaluator.State after,
                                        Map<String, Object> entry, List<Map<String, Object>> log,
                                        Map<String, EntityRegistry.EntityList> lists, boolean seesRestricted) {
        Map<String, Object> read = (Map<String, Object>) entry.get("read");
        Map<String, Object> delta = InvestigationEvaluator.delta(before, after);
        List<Map<String, Object>> held = new ArrayList<>();
        if (read.get("hubs") instanceof List<?> hubs)
            for (Object o : hubs)
                if (o instanceof Map<?, ?> h && after.highConnectivity.contains(String.valueOf(h.get("id"))))
                    held.add(held(String.valueOf(h.get("id")), h.get("degree") instanceof Number d ? d.longValue() : 0));
        sortHeld(held);

        Map<Integer, String> listAt = new HashMap<>();   // the excludeBy step -> the list it sealed
        for (Map<String, Object> e : log)
            if ("excludeBy".equals(e.get("op")) && e.get("list") instanceof Map<?, ?> l && e.get("step") instanceof Number s)
                listAt.put(s.intValue(), String.valueOf(l.get("listId")));
        TreeMap<String, TreeSet<String>> removed = new TreeMap<>();
        for (TreeMap<String, Integer> keys : before.excludedKeys.values())
            for (Integer step : keys.values()) removed.putIfAbsent(String.valueOf(listAt.get(step)), new TreeSet<>());
        if (!removed.isEmpty())
            for (Object o : (List<Object>) read.get("rows")) {
                Map<String, Object> r = (Map<String, Object>) o;
                for (String end : List.of("source", "target")) {
                    if (r.get(end) == null) continue;
                    String id = String.valueOf(r.get(end));
                    if (before.entities.containsKey(id)) continue;
                    for (var k : before.excludedKeys.entrySet()) {
                        Integer step = k.getValue().get(EntityTypes.normalise(k.getKey(), id));
                        if (step != null) removed.get(String.valueOf(listAt.get(step))).add(id);
                    }
                }
            }
        List<Map<String, Object>> lines = new ArrayList<>();
        java.util.Set<String> sealedRestricted = new java.util.HashSet<>();
        for (Map<String, Object> e : log)
            if (e.get("list") instanceof Map<?, ?> l && "restricted".equals(l.get("visibility")))
                sealedRestricted.add(String.valueOf(l.get("listId")));
        removed.forEach((listId, ids) -> {
            EntityRegistry.EntityList l = lists.get(listId);
            boolean restricted = !seesRestricted && (l == null || l.restricted() || sealedRestricted.contains(listId));
            lines.add(listLine(listId, l, ids.size(), restricted));
        });

        String reason = read.get("fallback") instanceof Map<?, ?> fb && fb.get("reason") != null ? String.valueOf(fb.get("reason")) : null;
        return answer(((List<?>) delta.get("admitted")).size(), ((Number) delta.get("linksAdded")).longValue(), held, lines,
                Boolean.TRUE.equals(read.get("truncated")), read.get("index") != null ? "index" : "flat", reason);
    }

    /** A sensitive expand (D-U7) is not read by a preview: nothing is counted, and appending it would hold it for a second person. */
    static Map<String, Object> needsApproval(Map<String, Object> sensitive) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("addsNodes", null);
        out.put("addsLinks", null);
        out.put("held", List.of());
        out.put("heldCount", null);
        out.put("excludedByLists", List.of());
        out.put("truncated", false);
        out.put("readPath", null);
        out.put("reason", "this expand is sensitive " + sensitive.get("exceeded") + " - four-eyes approval applies (D-U7), so it is "
                + "not previewed; appending it holds it for a second person");
        out.put("needsApproval", true);
        out.put("exceeded", sensitive.get("exceeded"));
        return out;
    }
}

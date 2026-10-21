package com.gamma.la.api;

import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.EntityListFacts;
import com.gamma.entitystore.EntityRegistry;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Restricted category exclusion lists in Investigation answers (LA-GROW-EXCLUDE-1 phase 4, GE-8 and plan §7): a caller
 * holding neither {@code canManageExclusionCategories} nor {@code canRevealLinkEntities} learns a restricted list's name
 * and how many nodes it removed, never its members.
 *
 * <p><b>Which lists.</b> A list op's sealed {@code list} names its list; the list is restricted when it was SEALED
 * {@code visibility: restricted} or is restricted NOW in the identity fact log (fail closed either way, as
 * {@link EntityMasking} reads a list's masking flag). Render time only: the sealed log and every hash are untouched.
 *
 * <p><b>What changes</b> for such a caller: a step answer's {@code list.unmatched} (member keys) and {@code protected}
 * (the kept ids that are members) become {@code null} with {@code list.membersHidden: true}, and {@code list.removed} is
 * rounded ({@link EntityListFacts#roundedCount}); a Working Set's {@code excludedKeys} lose the keys those steps
 * remembered, with {@code excludedKeysHidden: true}; a log line's count of removed entities is rounded. ⚠ Not hidden: the
 * Working Set delta still names the admitted entities a step removed (the canvas needs them, and the analyst saw them
 * before the step), and the Dossier is unchanged (deferred, BACKLOG).
 */
final class RestrictedLists {

    /** The restricted lists and the list-op steps over them, for this caller; {@link #NONE} when nothing is hidden. */
    record View(Set<String> lists, Set<Integer> steps) {
        boolean none() {
            return lists.isEmpty();
        }

        /** Whether step {@code step} is a list op over a restricted list. */
        boolean hides(int step) {
            return steps.contains(step);
        }

        int count(int n) {
            return EntityListFacts.roundedCount(n);
        }

        /** A step answer ({@code commit}): hide the members a list op would echo, round what it removed. */
        @SuppressWarnings("unchecked")
        void stepAnswer(Map<String, Object> out) {
            if (none() || !(out.get("list") instanceof Map<?, ?> l) || !lists.contains(String.valueOf(l.get("listId")))) return;
            Map<String, Object> list = new LinkedHashMap<>((Map<String, Object>) l);
            list.put("unmatched", null);
            list.put("membersHidden", true);
            if (list.get("removed") instanceof Number n) list.put("removed", count(n.intValue()));
            out.put("list", list);
            if (out.containsKey("protected")) out.put("protected", null);
        }

        /** A Working Set map ({@code toMap()} shape): drop the keys a restricted step remembered. */
        void workingSet(Map<String, Object> ws) {
            if (none() || !(ws.get("excludedKeys") instanceof List<?> keys)) return;
            List<Object> kept = new ArrayList<>();
            for (Object k : keys)
                if (!(k instanceof Map<?, ?> m && m.get("step") instanceof Number s && hides(s.intValue()))) kept.add(k);
            if (kept.size() == keys.size()) return;
            if (kept.isEmpty()) ws.remove("excludedKeys");
            else ws.put("excludedKeys", kept);
            ws.put("excludedKeysHidden", true);
        }
    }

    static final View NONE = new View(Set.of(), Set.of());

    private RestrictedLists() {}

    /** What this caller may not see in an answer over {@code log}: nothing for a privileged caller, a log with no restricted list op, or no Subject. */
    static View of(HttpExchange ex, Path writeRoot, List<Map<String, Object>> log) throws IOException {
        if (EntityListFacts.seesRestricted(ex)) return NONE;
        Set<String> named = new HashSet<>();
        for (Map<String, Object> e : log) if (e.get("list") instanceof Map<?, ?> l) named.add(String.valueOf(l.get("listId")));
        if (named.isEmpty()) return NONE;
        EntityFactLog.Log facts = EntityListFacts.read(new EntityFactLog(writeRoot));
        Map<String, EntityRegistry.EntityList> now = EntityRegistry.fold(facts.facts(), facts.headSeq());
        Set<String> lists = new HashSet<>();
        Set<Integer> steps = new HashSet<>();
        for (Map<String, Object> e : log) {
            if (!(e.get("list") instanceof Map<?, ?> l)) continue;
            String id = String.valueOf(l.get("listId"));
            EntityRegistry.EntityList current = now.get(id);
            if ("restricted".equals(l.get("visibility")) || (current != null && current.restricted())) {
                lists.add(id);
                if (e.get("step") instanceof Number s) steps.add(s.intValue());
            }
        }
        return lists.isEmpty() ? NONE : new View(Set.copyOf(lists), Set.copyOf(steps));
    }
}

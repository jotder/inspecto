package com.gamma.la.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** LA-UNEXPANDED-MARKER-1: an entity an effective expand had in its frontier is {@code expanded}; it never enters the hash. */
class InvestigationExpandedFoldTest {

    private static Map<String, Object> seed(int step, String... ids) {
        return Map.of("kind", "op", "op", "seed", "step", step, "params", Map.of("ids", List.of(ids)));
    }

    private static Map<String, Object> expand(int step, String frontier, String... neighbours) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String n : neighbours) rows.add(Map.of("source", frontier, "target", n, "count", 1));
        return Map.of("kind", "op", "op", "expand", "step", step, "params", Map.of(),
                "read", Map.of("query", Map.of("frontier", List.of(frontier)), "rows", rows));
    }

    @Test
    void onlyTheEntitiesTheExpandFollowedAreExpandedAndNewNeighboursAreNot() {
        // two seeds, ONE node expanded at a time: b stays unexpanded, a's neighbours are unexpanded too
        List<Map<String, Object>> log = List.of(seed(1, "a", "b"), expand(2, "a", "x", "y"));
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(log, -1, null);
        assertEquals(Set.of("a"), s.expanded);
        assertFalse(s.expanded.contains("b"), "a seed nobody expanded is still frontier");
        assertFalse(s.expanded.contains("x"), "an entity an expand ADDED is not itself expanded");
    }

    @Test
    void anExpandThatFindsNothingStillMarksTheNodeExpanded() {
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(List.of(seed(1, "a"), expand(2, "a")), -1, null);
        assertEquals(Set.of("a"), s.expanded);
    }

    @Test
    void anUndoneExpandRevertsAndAsOfAStepIsHonoured() {
        List<Map<String, Object>> log = List.of(seed(1, "a"), expand(2, "a", "x"), expand(3, "x", "z"),
                Map.of("kind", "undo", "step", 4, "undoes", 3));
        assertEquals(Set.of("a"), InvestigationEvaluator.evaluate(log, -1, null).expanded, "step 3 was undone");
        assertEquals(Set.of("a"), InvestigationEvaluator.evaluate(log, 2, null).expanded, "as of step 2");
        assertEquals(Set.of(), InvestigationEvaluator.evaluate(log, 1, null).expanded, "as of step 1");
    }

    @Test
    void anExpandRestrictedToChosenNeighboursLeavesTheEntityUnexpanded() {
        // LA-EXPAND-PICK-NEIGHBOURS-1: the rest of a's links are unread, so a is still frontier; a later full expand clears it
        Map<String, Object> restricted = Map.of("kind", "op", "op", "expand", "step", 2, "params", Map.of(),
                "read", Map.of("query", Map.of("frontier", List.of("a"), "only", List.of("x")),
                        "rows", List.of(Map.of("source", "a", "target", "x", "count", 1))));
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(List.of(seed(1, "a"), restricted), -1, null);
        assertEquals(Set.of(), s.expanded);
        assertEquals(Set.of("a", "x"), s.entities.keySet(), "the chosen neighbour was admitted");
        assertEquals(Set.of("a"), InvestigationEvaluator.evaluate(List.of(seed(1, "a"), restricted, expand(3, "a", "x", "y")), -1, null).expanded);
    }

    @Test
    void theExpandedSetNeverEntersTheHash() {
        List<Map<String, Object>> log = List.of(seed(1, "a"), expand(2, "a", "x"));
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(log, -1, null);
        String with = s.hash();
        s.expanded.clear();
        assertEquals(with, s.hash(), "clearing the display-only set changes no hash");
    }

    @Test
    void anExcludedEntityLeavesTheExpandedSet() {
        List<Map<String, Object>> log = List.of(seed(1, "a"), expand(2, "a", "x"),
                Map.of("kind", "op", "op", "exclude", "step", 3, "params", Map.of("ids", List.of("a"), "reason", "test")));
        assertFalse(InvestigationEvaluator.evaluate(log, -1, null).expanded.contains("a"));
    }
}

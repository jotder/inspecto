package com.gamma.la.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LA-SEED-CONNECT-1: folding a sealed {@code connect} - seeds at hop 0, route nodes one hop past a seed, whatever the row order. */
class InvestigationConnectFoldTest {

    private static Map<String, Object> row(String s, String t) {
        return Map.of("source", s, "target", t, "kind", "voice", "count", 1);
    }

    private static Map<String, Object> connect(int step, List<String> seeds, List<Map<String, Object>> rows) {
        return Map.of("kind", "op", "op", "connect", "step", step, "params", Map.of("ids", seeds, "maxHops", 3),
                "read", Map.of("rows", rows, "pairs", List.of(), "hubs", List.of(Map.of("id", "b", "degree", 9))));
    }

    @Test
    void routeNodesAreAdmittedOutwardFromTheSeedsWhateverTheRowOrder() {
        // rows written deepest-first: c-D, b-c, A-b - each route node still takes the hop and seed of its NEAREST seed
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(
                List.of(connect(1, List.of("A", "D"), List.of(row("c", "D"), row("b", "c"), row("A", "b")))), -1, null);
        assertEquals(Set.of("A", "D", "b", "c"), s.entities.keySet());
        assertEquals(0, s.entities.get("A").hop());
        assertEquals(1, s.entities.get("b").hop());
        assertEquals("A", s.entities.get("b").seed());
        assertEquals(1, s.entities.get("c").hop());
        assertEquals("D", s.entities.get("c").seed());
        assertEquals(3, s.links.size());
        assertTrue(s.highConnectivity.contains("b"), "a sealed hub in the Working Set is flagged");
        assertTrue(s.expanded.isEmpty(), "a connect follows routes, it does not expand anyone");
    }

    @Test
    void aRowTouchingAnExcludedEntityIsDroppedAndAReplayHashesEqual() {
        List<Map<String, Object>> log = List.of(
                Map.of("kind", "op", "op", "exclude", "step", 1, "params", Map.of("ids", List.of("b"), "reason", "r")),
                connect(2, List.of("A", "D"), List.of(row("A", "b"), row("b", "D"))));
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(log, -1, null);
        assertEquals(Set.of("A", "D"), s.entities.keySet());
        assertTrue(s.links.isEmpty());
        assertEquals(s.hash(), InvestigationEvaluator.evaluate(log, -1, null).hash());
    }
}

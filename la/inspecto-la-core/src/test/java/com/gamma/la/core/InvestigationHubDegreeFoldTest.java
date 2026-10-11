package com.gamma.la.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The hub degree ({@code contacts}) an entity carries is the LATEST sealed degree, and it never enters the hash. */
class InvestigationHubDegreeFoldTest {

    private static Map<String, Object> seed(int step, String id) {
        return Map.of("kind", "op", "op", "seed", "step", step, "params", Map.of("ids", List.of(id)));
    }

    private static Map<String, Object> expand(int step, String frontier, List<Map<String, Object>> hubs) {
        Map<String, Object> read = new java.util.HashMap<>();
        read.put("query", Map.of("frontier", List.of(frontier)));
        read.put("rows", List.of(Map.of("source", frontier, "target", "h", "count", 1)));
        if (hubs != null) read.put("hubs", hubs);
        return Map.of("kind", "op", "op", "expand", "step", step, "params", Map.of(), "read", read);
    }

    @Test
    void aLaterReadReflaggingAHubRecordsItsLatestDegreeDeterministicallyAndOutsideTheHash() {
        List<Map<String, Object>> log = new ArrayList<>(List.of(
                seed(1, "a"), expand(2, "a", List.of(Map.of("id", "h", "degree", 7))),
                seed(3, "b"), expand(4, "b", List.of(Map.of("id", "h", "degree", 9)))));
        List<String> hashes = new ArrayList<>();
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(log, -1, hashes);
        assertEquals(9L, s.hubDegree.get("h"), "the latest sealed degree wins");
        assertEquals(s.hubDegree, InvestigationEvaluator.evaluate(log, -1, null).hubDegree, "fold and replay agree");
        assertEquals(7L, InvestigationEvaluator.evaluate(log, 2, null).hubDegree.get("h"), "as of step 2");

        // the same log with the degrees dropped (an older line) hashes identically: contacts stay out of the hash
        List<Map<String, Object>> old = List.of(seed(1, "a"), expand(2, "a", List.of(Map.of("id", "h"))),
                seed(3, "b"), expand(4, "b", List.of(Map.of("id", "h"))));
        List<String> oldHashes = new ArrayList<>();
        InvestigationEvaluator.evaluate(old, -1, oldHashes);
        assertEquals(hashes, oldHashes);
    }
}

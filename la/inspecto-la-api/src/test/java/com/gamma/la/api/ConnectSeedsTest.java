package com.gamma.la.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LA-SEED-CONNECT-1: the pair table on a planted graph - the search itself, with no HTTP. */
class ConnectSeedsTest {

    /** A reader over an undirected edge list: the rows touching the frontier, and the hubs (degree over {@code hubOver}) among the new endpoints. */
    private static ConnectSeeds.LevelReader graph(int hubOver, int[] reads, String... edges) {
        return frontier -> {
            reads[0]++;
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Set<String>> nbr = new LinkedHashMap<>();
            for (String e : edges) {
                String[] p = e.split("-");
                nbr.computeIfAbsent(p[0], k -> new TreeSet<>()).add(p[1]);
                nbr.computeIfAbsent(p[1], k -> new TreeSet<>()).add(p[0]);
                if (frontier.contains(p[0]) || frontier.contains(p[1])) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("source", p[0]);
                    r.put("target", p[1]);
                    r.put("kind", "voice");
                    r.put("count", 1L);
                    rows.add(r);
                }
            }
            List<Map<String, Object>> hubs = new ArrayList<>();
            for (var n : nbr.entrySet())
                if (n.getValue().size() > hubOver && !frontier.contains(n.getKey()))
                    hubs.add(Map.of("id", n.getKey(), "degree", (long) n.getValue().size()));
            return Map.of("rows", rows, "truncated", false, "hubs", hubs);
        };
    }

    private static Map<String, Object> pair(ConnectSeeds.Result r, String a, String b) {
        return r.pairs().stream().filter(p -> p.get("a").equals(a) && p.get("b").equals(b)).findFirst().orElseThrow();
    }

    @Test
    void aPairAtExactlyThreeHopsIsFoundAndAtFourIsNot() {
        int[] reads = {0};
        ConnectSeeds.Result r = ConnectSeeds.compute(List.of("A", "D", "E"), 3, Set.of(),
                graph(99, reads, "A-b", "b-c", "c-D", "D-x", "x-y", "y-E"));
        assertEquals(true, pair(r, "A", "D").get("connected"));
        assertEquals(3, pair(r, "A", "D").get("hops"));
        assertEquals(List.of("A", "b", "c", "D"), pair(r, "A", "D").get("route"));
        assertEquals(false, pair(r, "A", "E").get("connected"), "A to E is 6 hops");
        assertEquals(true, pair(r, "D", "E").get("connected"), "D-x-y-E is 3");
        assertEquals(3, r.pairs().size());
        assertEquals(9, reads[0], "one search per seed, at most 3 level reads each - not one per pair");
    }

    @Test
    void theHopLimitIsHonouredAndNeverRetriedDeeper() {
        ConnectSeeds.Result r = ConnectSeeds.compute(List.of("A", "D"), 2, Set.of(), graph(99, new int[1], "A-b", "b-c", "c-D"));
        assertEquals(false, pair(r, "A", "D").get("connected"));
        assertTrue(r.rows().isEmpty(), "a disconnected pair adds no link");
    }

    @Test
    void routesAreCountedAndOneExampleIsSealed() {
        ConnectSeeds.Result r = ConnectSeeds.compute(List.of("A", "B"), 3, Set.of(), graph(99, new int[1], "A-m", "m-B", "A-n", "n-B", "A-p", "p-q", "q-B"));
        Map<String, Object> p = pair(r, "A", "B");
        assertEquals(2, p.get("hops"));
        assertEquals(2L, p.get("routes"), "the 3-hop route is not a shortest one");
        assertEquals(2, r.rows().size(), "only the example route's two links are sealed");
    }

    @Test
    void aHubOnTheOnlyRouteIsFlaggedAndNotWalked() {
        ConnectSeeds.Result r = ConnectSeeds.compute(List.of("A", "B"), 3, Set.of(),
                graph(2, new int[1], "A-H", "H-B", "H-x", "H-y"));
        Map<String, Object> p = pair(r, "A", "B");
        assertEquals(false, p.get("connected"));
        assertEquals(List.of("H"), p.get("notWalked"));
        assertEquals("H", r.hubs().get(0).get("id"));
    }

    @Test
    void aHubThatIsASeedItselfIsStillConnected() {
        ConnectSeeds.Result r = ConnectSeeds.compute(List.of("A", "H"), 3, Set.of(), graph(2, new int[1], "A-H", "H-x", "H-y"));
        assertEquals(true, pair(r, "A", "H").get("connected"), "a hub is the END of a route, never a step through one");
    }

    @Test
    void anExcludedEntityIsNotRoutedThroughAndATruncatedReadIsFlagged() {
        ConnectSeeds.Result r = ConnectSeeds.compute(List.of("A", "B"), 3, Set.of("m"), graph(99, new int[1], "A-m", "m-B"));
        assertFalse((Boolean) pair(r, "A", "B").get("connected"));
        ConnectSeeds.Result t = ConnectSeeds.compute(List.of("A", "B"), 3, Set.of(),
                f -> Map.of("rows", List.of(), "truncated", true, "hubs", List.of()));
        assertTrue(t.truncated());
        assertEquals(List.of("A", "B"), t.noLinks(), "a seed whose first read found nothing is listed");
    }
}

package com.gamma.la.graph;

import com.gamma.la.graph.GraphAlgorithms.Direction;
import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphPropagation.Factor;
import com.gamma.la.graph.GraphPropagation.Risk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GraphPropagation#propagatedRisk}: hand-computed graphs (no TypeScript twin, so no parity fixture). */
class GraphPropagationTest {

    private static final double TOL = 1e-9;
    private static final List<Double> DEFAULT = List.of(1.0, 0.6, 0.35, 0.15);

    /** A directed chain {@code ids[0] → ids[1] → …}. */
    private static Graph chain(String... ids) {
        List<Node> nodes = new ArrayList<>();
        for (String id : ids) nodes.add(new Node(id, id));
        List<Edge> edges = new ArrayList<>();
        for (int i = 1; i < ids.length; i++) edges.add(new Edge("e" + i, ids[i - 1], ids[i]));
        return new Graph(nodes, edges);
    }

    private static Map<String, Risk> byId(List<Risk> rs) {
        Map<String, Risk> m = new LinkedHashMap<>();
        for (Risk r : rs) m.put(r.id(), r);
        return m;
    }

    private static List<Risk> run(Graph g, Map<String, Double> own, List<String> seeds, List<Double> w, Direction d) {
        return GraphPropagation.propagatedRisk(g, own, seeds, w, d, RunControl.NONE);
    }

    @Test
    void oneFlaggedEndOfAChainDecaysByTheDefaultWeights() {
        List<Risk> rs = run(chain("A", "B", "C", "D", "E"), Map.of("A", 80.0), List.of(), DEFAULT, Direction.BOTH);
        assertEquals(List.of("A", "B", "C", "D", "E"), rs.stream().map(Risk::id).toList(), "raw desc, the A/B tie by id");
        Map<String, Risk> r = byId(rs);
        assertEquals(80, r.get("A").score(), TOL);
        assertEquals(80, r.get("A").own(), TOL);
        assertEquals(0, r.get("A").contributors(), "an origin does not feed itself");
        assertEquals(80, r.get("B").score(), TOL);     // 80 × 1.0
        assertEquals(48, r.get("C").score(), TOL);     // 80 × 0.6
        assertEquals(28, r.get("D").score(), TOL);     // 80 × 0.35
        assertEquals(12, r.get("E").score(), TOL);     // 80 × 0.15
        assertEquals(0, r.get("E").own(), TOL);
        Factor f = r.get("E").factors().get(0);
        assertEquals("A", f.origin());
        assertEquals(4, f.distance());
        assertEquals(0.15, f.weight(), TOL);
        assertEquals(12, f.contribution(), TOL);
        assertEquals(1, r.get("E").contributors());
    }

    @Test
    void twoOriginsSumAndTheFactorsSayWhoContributedWhat() {
        Map<String, Risk> r = byId(run(chain("A", "B", "C"), Map.of("A", 50.0, "C", 30.0), List.of(), DEFAULT, Direction.BOTH));
        assertEquals(80, r.get("B").score(), TOL);     // 50 × 1 + 30 × 1
        assertEquals(68, r.get("A").score(), TOL);     // 50 + 30 × 0.6
        assertEquals(60, r.get("C").score(), TOL);     // 30 + 50 × 0.6
        assertEquals(2, r.get("B").contributors());
        assertEquals(List.of("A", "C"), r.get("B").factors().stream().map(Factor::origin).toList(), "largest contribution first");
        assertEquals(List.of(50.0, 30.0), r.get("B").factors().stream().map(Factor::contribution).toList());
    }

    @Test
    void theScoreIsCappedAt100AndRawKeepsTheSum() {
        Map<String, Risk> r = byId(run(chain("A", "B", "C"), Map.of("A", 90.0, "C", 90.0), List.of(), DEFAULT, Direction.BOTH));
        assertEquals(100, r.get("B").score(), TOL);
        assertEquals(180, r.get("B").raw(), TOL);
        assertEquals(100, r.get("A").score(), TOL);
        assertEquals(144, r.get("A").raw(), TOL);      // 90 + 90 × 0.6
    }

    @Test
    void nothingPropagatesBeyondTheLastWeight() {
        Map<String, Risk> r = byId(run(chain("A", "B", "C", "D", "E", "F"), Map.of("A", 100.0), List.of(), DEFAULT, Direction.BOTH));
        assertEquals(15, r.get("E").score(), TOL);     // distance 4: the last weight
        assertEquals(0, r.get("F").score(), TOL);      // distance 5: out of reach
        assertEquals(0, r.get("F").contributors());
        assertTrue(r.get("F").factors().isEmpty());
    }

    @Test
    void customWeightsSetBothTheDecayAndTheDepth() {
        Map<String, Risk> r = byId(run(chain("A", "B", "C"), Map.of("A", 40.0), List.of(), List.of(0.5), Direction.BOTH));
        assertEquals(20, r.get("B").score(), TOL);     // 40 × 0.5
        assertEquals(0, r.get("C").score(), TOL);      // depth 1
        Map<String, Risk> z = byId(run(chain("A", "B", "C"), Map.of("A", 40.0), List.of(), List.of(0.0, 1.0), Direction.BOTH));
        assertEquals(0, z.get("B").contributors(), "a zero weight reaches but contributes nothing");
        assertEquals(40, z.get("C").score(), TOL, "and the walk goes on past it");
    }

    @Test
    void theDistanceIsTheShortestHopCount() {
        // A→B→C plus the shortcut A→C: C is ONE hop from A, so it takes the full weight, once
        Graph g = new Graph(List.of(new Node("A", "A"), new Node("B", "B"), new Node("C", "C")),
                List.of(new Edge("e1", "A", "B"), new Edge("e2", "B", "C"), new Edge("e3", "A", "C")));
        Risk c = byId(run(g, Map.of("A", 50.0), List.of(), DEFAULT, Direction.BOTH)).get("C");
        assertEquals(50, c.score(), TOL);
        assertEquals(1, c.contributors());
        assertEquals(1, c.factors().get(0).distance());
    }

    @Test
    void directionIsTheWalkFromTheOrigin() {
        Graph g = chain("A", "B", "C");                // A→B→C, C flagged
        Map<String, Risk> out = byId(run(g, Map.of("C", 50.0), List.of(), DEFAULT, Direction.OUT));
        assertEquals(0, out.get("B").score(), TOL, "C has no out-edges");
        Map<String, Risk> in = byId(run(g, Map.of("C", 50.0), List.of(), DEFAULT, Direction.IN));
        assertEquals(50, in.get("B").score(), TOL);
        assertEquals(30, in.get("A").score(), TOL);
    }

    @Test
    void seedsRestrictTheOriginsButEveryOwnScoreStillCounts() {
        Map<String, Risk> r = byId(run(chain("A", "B", "C"), Map.of("A", 50.0, "C", 30.0), List.of("A", "ghost"), DEFAULT, Direction.BOTH));
        assertEquals(50, r.get("A").score(), TOL, "C is not an origin: it does not feed A");
        assertEquals(50, r.get("B").score(), TOL);
        assertEquals(60, r.get("C").score(), TOL);     // own 30 + 50 × 0.6
        Map<String, Risk> unscored = byId(run(chain("A", "B"), Map.of(), List.of("A"), DEFAULT, Direction.BOTH));
        assertEquals(0, unscored.get("B").score(), TOL, "a seed with no own score contributes nothing");
    }

    @Test
    void theFactorListIsTheTopFiveAndContributorsCountsThemAll() {
        List<Node> nodes = new ArrayList<>(List.of(new Node("X", "X")));
        List<Edge> edges = new ArrayList<>();
        Map<String, Double> own = new LinkedHashMap<>();
        for (int i = 1; i <= 7; i++) {
            nodes.add(new Node("L" + i, "L" + i));
            edges.add(new Edge("e" + i, "L" + i, "X"));
            own.put("L" + i, 10.0 * i);
        }
        Risk x = byId(run(new Graph(nodes, edges), own, List.of(), DEFAULT, Direction.BOTH)).get("X");
        assertEquals(280, x.raw(), TOL);              // 10 + 20 + … + 70
        assertEquals(100, x.score(), TOL);
        assertEquals(7, x.contributors());
        assertEquals(List.of("L7", "L6", "L5", "L4", "L3"), x.factors().stream().map(Factor::origin).toList());
    }

    @Test
    void aCancelledRunAborts() {
        RunControl ctl = RunControl.create();
        ctl.cancel();
        assertThrows(GraphAborted.class, () -> GraphPropagation.propagatedRisk(chain("A", "B"), Map.of("A", 1.0), List.of(),
                DEFAULT, Direction.BOTH, ctl));
    }
}

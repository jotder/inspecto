package com.gamma.la.graph;

import com.gamma.la.graph.GraphAlgorithms.Direction;
import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Hop;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.la.graph.GraphAlgorithms.Selection;
import com.gamma.la.graph.GraphCentrality.Method;
import com.gamma.la.graph.GraphCentrality.PredictedLink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-GRAPH-QUADRATIC-1 - {@code kCore}, {@code weightedShortestPath} and {@code linkPrediction} now run on a bucket
 * queue, a binary heap and a 2-hop candidate walk. This test keeps the OLD quadratic code as a private reference and
 * asserts, on many seeded random graphs, that the new output is IDENTICAL (records compare the score as bits via
 * {@code Double.compare}, order included). The graphs carry ties, parallel edges, self-loops, isolated nodes, several
 * components and equal weights; the weights also include zero, negative, -0.0 and infinite values, which the code
 * accepts. TypeScript twin: {@code graph-complexity-equivalence.spec.ts}.
 */
class GraphComplexityEquivalenceTest {

    // ---- the reference: the pre-fix code ------------------------------------------------------------------

    private static List<Score> kCoreRef(Graph g) {
        Map<String, Set<String>> nb = GraphAlgorithms.undirectedNeighbors(g);
        Map<String, Integer> deg = new LinkedHashMap<>();
        nb.forEach((id, set) -> deg.put(id, set.size()));
        Map<String, Integer> core = new LinkedHashMap<>();
        Set<String> remaining = new LinkedHashSet<>(deg.keySet());
        int k = 0;
        while (!remaining.isEmpty()) {
            String min = null;
            int minDeg = Integer.MAX_VALUE;
            for (String id : remaining) {
                if (deg.get(id) < minDeg) {
                    minDeg = deg.get(id);
                    min = id;
                }
            }
            k = Math.max(k, minDeg);
            core.put(min, k);
            remaining.remove(min);
            for (String other : nb.getOrDefault(min, Set.of())) {
                if (remaining.contains(other)) deg.put(other, deg.get(other) - 1);
            }
        }
        return GraphAlgorithms.scored(g, core);
    }

    private static Selection weightedShortestPathRef(Graph g, Map<String, Double> weights, String fromId, String toId,
                                                     Direction direction) {
        if (fromId.equals(toId)) return new Selection(List.of(fromId), List.of());
        GraphAlgorithms.Adjacency adj = GraphAlgorithms.adjacency(g);
        if (!adj.out().containsKey(fromId) || !adj.out().containsKey(toId)) return null;
        Map<String, Double> cost = new LinkedHashMap<>();
        for (Edge e : g.edges()) cost.put(e.id(), 1 / weights.getOrDefault(e.id(), 1.0));
        Map<String, Double> dist = new LinkedHashMap<>();
        dist.put(fromId, 0.0);
        Map<String, Hop> prev = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        for (;;) {
            String cur = null;
            double best = Double.POSITIVE_INFINITY;
            for (Map.Entry<String, Double> en : dist.entrySet()) {
                if (!visited.contains(en.getKey()) && en.getValue() < best) {
                    best = en.getValue();
                    cur = en.getKey();
                }
            }
            if (cur == null || cur.equals(toId)) break;
            visited.add(cur);
            for (Hop h : GraphAlgorithms.neighborsOf(adj, cur, direction)) {
                if (visited.contains(h.node())) continue;
                double nd = best + cost.getOrDefault(h.edge(), 1.0);
                if (nd < dist.getOrDefault(h.node(), Double.POSITIVE_INFINITY)) {
                    dist.put(h.node(), nd);
                    prev.put(h.node(), new Hop(cur, h.edge()));
                }
            }
        }
        if (!prev.containsKey(toId)) return null;
        List<String> nodeIds = new ArrayList<>(List.of(toId));
        List<String> edgeIds = new ArrayList<>();
        String at = toId;
        while (!at.equals(fromId)) {
            Hop p = prev.get(at);
            edgeIds.add(0, p.edge());
            nodeIds.add(0, p.node());
            at = p.node();
        }
        return new Selection(nodeIds, edgeIds);
    }

    private static List<PredictedLink> linkPredictionRef(Graph g, Method method, int limit) {
        Map<String, Set<String>> nb = GraphAlgorithms.undirectedNeighbors(g);
        Map<String, String> label = new LinkedHashMap<>();
        for (Node n : g.nodes()) label.put(n.id(), n.label());
        List<String> ids = new ArrayList<>(nb.keySet());
        ids.sort(Comparator.naturalOrder());
        List<PredictedLink> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String a = ids.get(i);
            Set<String> an = nb.get(a);
            for (int j = i + 1; j < ids.size(); j++) {
                String b = ids.get(j);
                if (an.contains(b)) continue;
                Set<String> bn = nb.get(b);
                double score = 0;
                for (String c : an) {
                    if (!bn.contains(c)) continue;
                    int deg = nb.get(c).size();
                    score += method == Method.ADAMIC_ADAR ? (deg > 1 ? 1 / Math.log(deg) : 0) : 1;
                }
                if (score > 0) {
                    out.add(new PredictedLink(a, b, label.getOrDefault(a, a), label.getOrDefault(b, b), score));
                }
            }
        }
        out.sort(Comparator.comparingDouble(PredictedLink::score).reversed().thenComparing(PredictedLink::source));
        return out.subList(0, Math.min(limit, out.size()));
    }

    // ---- seeded random graphs -------------------------------------------------------------------------------

    private static final String[] PREFIXES = {"n", "N", "acc-", "ACC-", "x_", "é"};
    private static final double[] WEIGHTS = {1, 1, 1, 2, 3, 0.5, 4, 0, -1, -0.0, Double.POSITIVE_INFINITY};

    private record Case(Graph graph, Map<String, Double> weights) {}

    private static Case randomCase(long seed) {
        Random r = new Random(seed);
        int n = 1 + r.nextInt(40);
        Set<String> ids = new LinkedHashSet<>();
        while (ids.size() < n) ids.add(PREFIXES[r.nextInt(PREFIXES.length)] + r.nextInt(n * 2));
        List<String> nodeIds = new ArrayList<>(ids);
        String[] labels = {"L", "M", "N"}; // few labels => label ties
        List<Node> nodes = new ArrayList<>();
        for (String id : nodeIds) nodes.add(new Node(id, r.nextBoolean() ? labels[r.nextInt(3)] : id));
        int m = r.nextInt(n * 3 + 1);
        List<Edge> edges = new ArrayList<>();
        Map<String, Double> weights = new HashMap<>();
        for (int i = 0; i < m; i++) {
            String s = nodeIds.get(r.nextInt(n));
            String t = r.nextDouble() < 0.08 ? s : nodeIds.get(r.nextInt(n)); // self-loops; repeated pairs => parallel
            String id = r.nextDouble() < 0.05 && i > 0 ? "e" + (i - 1) : "e" + i;  // a few duplicate edge ids
            edges.add(new Edge(id, s, t));
            if (r.nextDouble() < 0.85) weights.put(id, WEIGHTS[r.nextInt(WEIGHTS.length)]); // absent => 1.0
        }
        return new Case(new Graph(nodes, edges), weights);
    }

    private static final int SEEDS = 400;

    @Test
    void kCoreEqualsTheMinimumScanReference() {
        for (long seed = 0; seed < SEEDS; seed++) {
            Graph g = randomCase(seed).graph();
            assertEquals(kCoreRef(g), GraphAlgorithms.kCore(g), "seed " + seed);
        }
    }

    @Test
    void weightedShortestPathEqualsTheLinearScanReference() {
        Random q = new Random(99);
        int found = 0, total = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            Case c = randomCase(seed);
            List<Node> nodes = c.graph().nodes();
            for (int i = 0; i < 6; i++) {
                String from = nodes.get(q.nextInt(nodes.size())).id();
                String to = q.nextInt(10) == 0 ? "missing" : nodes.get(q.nextInt(nodes.size())).id();
                for (Direction d : Direction.values()) {
                    Selection expected = weightedShortestPathRef(c.graph(), c.weights(), from, to, d);
                    assertEquals(expected, GraphPaths.weightedShortestPath(c.graph(), c.weights(), from, to, d),
                            "seed " + seed + " " + from + "->" + to + " " + d);
                    total++;
                    if (expected != null && !from.equals(to)) found++;
                }
            }
        }
        assertTrue(found > total / 10, "the corpus must contain real multi-hop answers, found " + found + " of " + total);
    }

    @Test
    void linkPredictionEqualsTheAllPairsReference() {
        int nonEmpty = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            Graph g = randomCase(seed).graph();
            for (Method method : Method.values()) {
                for (int limit : new int[]{0, 3, 20, 100_000}) {
                    List<PredictedLink> expected = linkPredictionRef(g, method, limit);
                    assertEquals(expected, GraphCentrality.linkPrediction(g, method, limit), "seed " + seed + " " + method + " " + limit);
                    if (!expected.isEmpty()) nonEmpty++;
                }
            }
        }
        assertTrue(nonEmpty > SEEDS, "the corpus must produce predictions, got " + nonEmpty);
    }

    @Test
    void theCorpusExercisesTiesLoopsParallelEdgesAndIsolatedNodes() {
        int loops = 0, parallel = 0, isolated = 0, tied = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            Graph g = randomCase(seed).graph();
            Set<String> keys = new HashSet<>();
            Set<String> touched = new HashSet<>();
            for (Edge e : g.edges()) {
                if (e.source().equals(e.target())) loops++;
                String a = e.source().compareTo(e.target()) <= 0 ? e.source() : e.target();
                String b = a.equals(e.source()) ? e.target() : e.source();
                if (!keys.add(a + "|" + b)) parallel++;
                touched.add(e.source());
                touched.add(e.target());
            }
            for (Node n : g.nodes()) if (!touched.contains(n.id())) isolated++;
            List<Score> s = GraphAlgorithms.kCore(g);
            tied += s.size() - (int) s.stream().map(Score::score).distinct().count();
        }
        assertTrue(loops > 50 && parallel > 200 && isolated > 200 && tied > 1000,
                loops + " loops, " + parallel + " parallel, " + isolated + " isolated, " + tied + " tied");
    }
}

package com.gamma.la.graph;

import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Adjacency;
import com.gamma.la.graph.GraphAlgorithms.Direction;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Hop;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphAlgorithms.Score;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Server-side iterative-centrality and community algorithms — a line-for-line port of {@code pageRank},
 * {@code eigenvectorCentrality}, {@code katzCentrality}, {@code hits}, {@code detectCommunities} and
 * {@code louvainCommunities} in {@code inspecto-ui/src/app/inspecto/graph/graph-analysis.ts} (option D, spike D-S4).
 * {@code GraphIterativeParityTest} and {@code graph-iterative-parity.spec.ts} assert the SAME fixture
 * ({@code graph-iterative-parity.fixture.json}), scores to 1e-9.
 *
 * <p>Same deliberate differences as {@link GraphAlgorithms}: no node cap, ordinal label tie-break, closed graphs.
 * The few helpers it needs are private copies — this class does not depend on {@code GraphAlgorithms}' internals.
 * Float arithmetic keeps the TS operation order exactly (summation order matters at the last ulp).
 */
public final class GraphIterative {

    private GraphIterative() {}

    /** TS {@code HitsResult}. */
    public record HitsResult(List<Score> hubs, List<Score> authorities) {}

    private static List<String> ids(Graph g) {
        List<String> ids = new ArrayList<>();
        for (Node n : g.nodes()) ids.add(n.id());
        return ids;
    }

    private static List<String> sortedIds(Graph g) {
        List<String> ids = ids(g);
        ids.sort(null); // UTF-16 order, like JS Array.sort()
        return ids;
    }

    private static Map<String, Double> filled(List<String> ids, double v) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String id : ids) m.put(id, v);
        return m;
    }

    public static List<Score> pageRank(Graph g) {
        return pageRank(g, 0.85, 60);
    }

    /** TS {@code pageRank}: dangling nodes redistribute uniformly; rank flows along edge direction. */
    public static List<Score> pageRank(Graph g, double damping, int iterations) {
        List<String> ids = ids(g);
        int n = ids.size();
        if (n == 0) return List.of();
        Map<String, List<String>> outLinks = new LinkedHashMap<>();
        for (String id : ids) outLinks.put(id, new ArrayList<>());
        for (Edge e : g.edges()) {
            List<String> l = outLinks.get(e.source());
            if (l != null) l.add(e.target());
        }
        Map<String, Integer> outDeg = new LinkedHashMap<>();
        for (String id : ids) outDeg.put(id, outLinks.get(id).size());
        Map<String, Double> pr = filled(ids, 1.0 / n);
        for (int it = 0; it < iterations; it++) {
            Map<String, Double> next = filled(ids, (1 - damping) / n);
            double dangling = 0;
            for (String id : ids) if (outDeg.get(id) == 0) dangling += pr.get(id);
            double danglingShare = (damping * dangling) / n;
            for (String id : ids) {
                int deg = outDeg.get(id);
                if (deg == 0) continue;
                double share = (damping * pr.get(id)) / deg;
                for (String t : outLinks.get(id)) next.put(t, next.get(t) + share);
            }
            for (String id : ids) next.put(id, next.get(id) + danglingShare);
            pr = next;
        }
        return GraphAlgorithms.scored(g, pr);
    }

    /** Callback for {@link #powerIterate}: fill {@code next} from {@code x}. */
    private interface Step {
        void apply(Map<String, Double> x, Map<String, Double> next);
    }

    /** TS {@code powerIterate}: x = 1; repeat next = step(x), L2-normalise, stop early on a zero norm. */
    private static Map<String, Double> powerIterate(Graph g, Step step, int iterations) {
        List<String> ids = ids(g);
        Map<String, Double> x = filled(ids, 1);
        for (int it = 0; it < iterations; it++) {
            Map<String, Double> next = filled(ids, 0);
            step.apply(x, next);
            double norm = 0;
            for (double v : next.values()) norm += v * v;
            norm = Math.sqrt(norm);
            if (norm == 0) break;
            for (Map.Entry<String, Double> en : next.entrySet()) en.setValue(en.getValue() / norm);
            x = next;
        }
        return x;
    }

    public static List<Score> eigenvectorCentrality(Graph g) {
        return eigenvectorCentrality(g, 100);
    }

    /** TS {@code eigenvectorCentrality}: undirected power iteration (a self-loop counts twice, like the browser). */
    public static List<Score> eigenvectorCentrality(Graph g, int iterations) {
        Adjacency adj = GraphAlgorithms.adjacency(g);
        Map<String, Double> x = powerIterate(g, (cur, next) -> {
            for (Map.Entry<String, Double> en : next.entrySet()) {
                double s = 0;
                for (Hop nb : GraphAlgorithms.neighborsOf(adj, en.getKey(), Direction.BOTH)) {
                    Double c = cur.get(nb.node());
                    s += c == null ? 0 : c;
                }
                en.setValue(s);
            }
        }, iterations);
        return GraphAlgorithms.scored(g, x);
    }

    public static List<Score> katzCentrality(Graph g) {
        return katzCentrality(g, 0.1, 1, 100);
    }

    /** TS {@code katzCentrality}: {@code x = beta + alpha * A * x} from x = 0. */
    public static List<Score> katzCentrality(Graph g, double alpha, double beta, int iterations) {
        Adjacency adj = GraphAlgorithms.adjacency(g);
        List<String> ids = ids(g);
        Map<String, Double> x = filled(ids, 0);
        for (int it = 0; it < iterations; it++) {
            Map<String, Double> next = filled(ids, beta);
            for (String id : ids) {
                double s = 0;
                for (Hop nb : GraphAlgorithms.neighborsOf(adj, id, Direction.BOTH)) {
                    Double c = x.get(nb.node());
                    s += c == null ? 0 : c;
                }
                next.put(id, beta + alpha * s);
            }
            x = next;
        }
        return GraphAlgorithms.scored(g, x);
    }

    public static HitsResult hits(Graph g) {
        return hits(g, 100);
    }

    /** TS {@code hits}: directed; authority from incoming hubs, then hub from the NEW authorities; both L2-normalised. */
    public static HitsResult hits(Graph g, int iterations) {
        Adjacency adj = GraphAlgorithms.adjacency(g);
        List<String> ids = ids(g);
        Map<String, Double> hub = filled(ids, 1);
        Map<String, Double> auth = filled(ids, 1);
        for (int it = 0; it < iterations; it++) {
            Map<String, Double> nextAuth = filled(ids, 0);
            for (String id : ids) {
                double s = 0;
                for (Hop src : adj.in().getOrDefault(id, List.of())) {
                    Double h = hub.get(src.node());
                    s += h == null ? 0 : h;
                }
                nextAuth.put(id, s);
            }
            Map<String, Double> nextHub = filled(ids, 0);
            for (String id : ids) {
                double s = 0;
                for (Hop tgt : adj.out().getOrDefault(id, List.of())) {
                    Double a = nextAuth.get(tgt.node());
                    s += a == null ? 0 : a;
                }
                nextHub.put(id, s);
            }
            l2(nextAuth);
            l2(nextHub);
            auth = nextAuth;
            hub = nextHub;
        }
        return new HitsResult(GraphAlgorithms.scored(g, hub), GraphAlgorithms.scored(g, auth));
    }

    /** TS {@code l2} inside {@code hits}: a zero norm divides by 1. */
    private static void l2(Map<String, Double> m) {
        double norm = 0;
        for (double v : m.values()) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) norm = 1;
        for (Map.Entry<String, Double> en : m.entrySet()) en.setValue(en.getValue() / norm);
    }

    public static Map<String, String> detectCommunities(Graph g) {
        return detectCommunities(g, 20);
    }

    /**
     * TS {@code detectCommunities}: synchronous label propagation. The result's ITERATION ORDER is part of the contract
     * (members grouped by community in first-seen label order) — the fixture asserts it as ordered pairs.
     */
    public static Map<String, String> detectCommunities(Graph g, int maxIterations) {
        Adjacency adj = GraphAlgorithms.adjacency(g);
        List<String> ids = sortedIds(g);
        Map<String, String> label = new LinkedHashMap<>();
        for (String id : ids) label.put(id, id);
        for (int it = 0; it < maxIterations; it++) {
            boolean changed = false;
            Map<String, String> next = new LinkedHashMap<>(label);
            for (String id : ids) {
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (Hop nb : GraphAlgorithms.neighborsOf(adj, id, Direction.BOTH)) counts.merge(label.get(nb.node()), 1, Integer::sum);
                if (counts.isEmpty()) continue;
                String own = label.get(id);
                String best = own;
                int bestCount = counts.getOrDefault(own, 0);
                for (Map.Entry<String, Integer> en : counts.entrySet()) {
                    String l = en.getKey();
                    int c = en.getValue();
                    if (c > bestCount || (c == bestCount && !l.equals(own) && !best.equals(own) && l.compareTo(best) < 0)) {
                        best = l;
                        bestCount = c;
                    }
                }
                if (!best.equals(own)) {
                    next.put(id, best);
                    changed = true;
                }
            }
            label.putAll(next);
            if (!changed) break;
        }
        // Absorb noise singletons into the community of their smallest-id neighbour.
        Map<String, Integer> size = new LinkedHashMap<>();
        for (String l : label.values()) size.merge(l, 1, Integer::sum);
        for (String id : ids) {
            String own = label.get(id);
            Integer sz = size.get(own);
            if (sz == null || sz != 1) continue;
            List<String> nbs = new ArrayList<>();
            for (Hop nb : GraphAlgorithms.neighborsOf(adj, id, Direction.BOTH)) nbs.add(nb.node());
            nbs.sort(null);
            if (nbs.isEmpty()) continue;
            String target = label.get(nbs.get(0));
            label.put(id, target);
            size.put(own, 0);
            size.merge(target, 1, Integer::sum);
        }
        // Normalise each community's id to its smallest member.
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (Map.Entry<String, String> en : label.entrySet()) {
            members.computeIfAbsent(en.getValue(), k -> new ArrayList<>()).add(en.getKey());
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (List<String> arr : members.values()) {
            List<String> sorted = new ArrayList<>(arr);
            sorted.sort(null);
            String cid = sorted.get(0);
            for (String id : arr) out.put(id, cid);
        }
        return out;
    }

    /**
     * TS {@code louvainCommunities}: unit-weight Louvain, level nodes in id order, moves only on a strict modularity
     * gain (&gt; 1e-12), then aggregation to convergence. Weights stay integral so they are carried as doubles exactly.
     */
    public static Map<String, String> louvainCommunities(Graph g) {
        List<String> ids = sortedIds(g);
        int n = ids.size();
        if (n == 0) return new LinkedHashMap<>();
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) index.put(ids.get(i), i);

        // Level-0 weighted undirected graph; self[i] = self-loop weight; m2 = sum of degrees = 2m.
        List<Map<Integer, Double>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new LinkedHashMap<>());
        double[] self = new double[n];
        double m2 = 0;
        for (Edge e : g.edges()) {
            Integer a = index.get(e.source());
            Integer b = index.get(e.target());
            if (a == null || b == null) continue;
            if (a.equals(b)) {
                self[a] += 1;
                m2 += 2;
                continue;
            }
            adj.get(a).merge(b, 1.0, Double::sum);
            adj.get(b).merge(a, 1.0, Double::sum);
            m2 += 2;
        }
        if (m2 == 0) { // no edges => singletons
            Map<String, String> singles = new LinkedHashMap<>();
            for (String id : ids) singles.put(id, id);
            return singles;
        }

        int[] membership = new int[n]; // original node idx -> current-level node idx
        for (int i = 0; i < n; i++) membership[i] = i;
        for (;;) {
            int size = adj.size();
            double[] deg = new double[size];
            for (int i = 0; i < size; i++) {
                double d = 2 * self[i];
                for (double w : adj.get(i).values()) d += w;
                deg[i] = d;
            }
            int[] comm = new int[size];
            for (int i = 0; i < size; i++) comm[i] = i;
            double[] commTot = deg.clone(); // sum-tot per community
            boolean improved = true;
            boolean moved = false;
            while (improved) {
                improved = false;
                for (int i = 0; i < size; i++) {
                    int ci = comm[i];
                    Map<Integer, Double> kiIn = new LinkedHashMap<>(); // weight from i to each neighbour community
                    for (Map.Entry<Integer, Double> en : adj.get(i).entrySet()) {
                        kiIn.merge(comm[en.getKey()], en.getValue(), Double::sum);
                    }
                    commTot[ci] -= deg[i]; // detach i
                    int bestC = ci;
                    double bestGain = kiIn.getOrDefault(ci, 0.0) - (deg[i] * commTot[ci]) / m2;
                    for (int c : new TreeMap<>(kiIn).keySet()) {
                        if (c == ci) continue;
                        double gain = kiIn.get(c) - (deg[i] * commTot[c]) / m2;
                        if (gain > bestGain + 1e-12) {
                            bestGain = gain;
                            bestC = c;
                        }
                    }
                    commTot[bestC] += deg[i];
                    if (bestC != ci) {
                        comm[i] = bestC;
                        improved = true;
                        moved = true;
                    }
                }
            }
            // Relabel this level's communities to contiguous ids and fold into the original membership.
            Map<Integer, Integer> remap = new LinkedHashMap<>();
            for (int c : comm) if (!remap.containsKey(c)) remap.put(c, remap.size());
            int newSize = remap.size();
            for (int i = 0; i < n; i++) membership[i] = remap.get(comm[membership[i]]);
            if (!moved || newSize == size) break; // converged

            // Aggregate communities into super-nodes for the next level.
            List<Map<Integer, Double>> nAdj = new ArrayList<>();
            for (int i = 0; i < newSize; i++) nAdj.add(new LinkedHashMap<>());
            double[] nSelf = new double[newSize];
            for (int i = 0; i < size; i++) nSelf[remap.get(comm[i])] += self[i];
            for (int i = 0; i < size; i++) {
                int ci = remap.get(comm[i]);
                for (Map.Entry<Integer, Double> en : adj.get(i).entrySet()) {
                    int j = en.getKey();
                    double w = en.getValue();
                    int cj = remap.get(comm[j]);
                    if (ci == cj) {
                        if (i < j) nSelf[ci] += w;
                    } else nAdj.get(ci).merge(cj, w, Double::sum);
                }
            }
            adj = nAdj;
            self = nSelf;
        }

        // Group by final community, label each by its smallest member id.
        Map<Integer, List<String>> members = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) members.computeIfAbsent(membership[i], k -> new ArrayList<>()).add(ids.get(i));
        Map<String, String> out = new LinkedHashMap<>();
        for (List<String> arr : members.values()) {
            List<String> sorted = new ArrayList<>(arr);
            sorted.sort(null);
            String rep = sorted.get(0);
            for (String id : arr) out.put(id, rep);
        }
        return out;
    }
}

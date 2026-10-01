package com.gamma.la.graph;

import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphAlgorithms.Score;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single-pass float centrality — a line-for-line port of the matching functions in
 * {@code inspecto-ui/src/app/inspecto/graph/graph-analysis.ts} (option D, spike D-S4): betweenness, closeness,
 * Jaccard similarity and link prediction. The arithmetic runs in the browser's order, so the doubles agree to
 * rounding; {@code GraphCentralityParityTest} asserts them at 1e-9. Same deliberate differences as
 * {@link GraphAlgorithms}: no node cap, closed graphs (the tie-break is the shared {@code canonical-v1}).
 *
 * <p>NOT ported: {@code suspicionScore} — it blends {@code pageRank}, {@code kCore} and {@code triangleCount},
 * which live outside this lane.
 *
 * <p>The few helpers below are private copies of {@link GraphAlgorithms}' (its own are private there).
 */
public final class GraphCentrality {

    private GraphCentrality() {}

    public enum Method { COMMON_NEIGHBORS, ADAMIC_ADAR }

    /** A predicted (not-yet-present) link between two nodes, with a likelihood score. */
    public record PredictedLink(String source, String target, String sourceLabel, String targetLabel, double score) {}

    /** TS {@code neighborsOf(adj, id, 'both')}: out-hops then in-hops, parallel edges and self-loops kept. */
    private static Map<String, List<String>> bothNeighbors(Graph g) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Map<String, List<String>> in = new LinkedHashMap<>();
        for (Node n : g.nodes()) {
            out.put(n.id(), new ArrayList<>());
            in.put(n.id(), new ArrayList<>());
        }
        for (Edge e : g.edges()) {
            List<String> o = out.get(e.source());
            if (o != null) o.add(e.target());
            List<String> i = in.get(e.target());
            if (i != null) i.add(e.source());
        }
        Map<String, List<String>> both = new LinkedHashMap<>();
        for (Node n : g.nodes()) {
            List<String> l = new ArrayList<>(out.get(n.id()));
            l.addAll(in.get(n.id()));
            both.put(n.id(), l);
        }
        return both;
    }

    /** TS {@code betweennessCentrality}: Brandes, unweighted, undirected, descending. */
    public static List<Score> betweennessCentrality(Graph g) {
        return betweennessCentrality(g, RunControl.NONE);
    }

    /** As {@link #betweennessCentrality(Graph)}, with a checkpoint per Brandes source. */
    public static List<Score> betweennessCentrality(Graph g, RunControl ctl) {
        Map<String, List<String>> adj = bothNeighbors(g);
        Map<String, Double> bc = new LinkedHashMap<>();
        for (Node n : g.nodes()) bc.put(n.id(), 0.0);
        int total = g.nodes().size(), done = 0;
        for (Node s : g.nodes()) {
            ctl.checkpoint();
            ctl.progress(done++, total);
            List<String> stack = new ArrayList<>();
            Map<String, List<String>> preds = new LinkedHashMap<>();
            Map<String, Double> sigma = new LinkedHashMap<>();
            sigma.put(s.id(), 1.0);
            Map<String, Integer> dist = new LinkedHashMap<>();
            dist.put(s.id(), 0);
            List<String> queue = new ArrayList<>(List.of(s.id()));
            for (int head = 0; head < queue.size(); head++) {
                String v = queue.get(head);
                stack.add(v);
                for (String w : adj.getOrDefault(v, List.of())) {
                    if (!dist.containsKey(w)) {
                        dist.put(w, dist.get(v) + 1);
                        queue.add(w);
                    }
                    if (dist.get(w) == dist.get(v) + 1) {
                        sigma.put(w, sigma.getOrDefault(w, 0.0) + sigma.get(v));
                        preds.computeIfAbsent(w, k -> new ArrayList<>()).add(v);
                    }
                }
            }
            Map<String, Double> delta = new LinkedHashMap<>();
            while (!stack.isEmpty()) {
                String w = stack.remove(stack.size() - 1);
                for (String v : preds.getOrDefault(w, List.of())) {
                    double add = (sigma.get(v) / sigma.get(w)) * (1 + delta.getOrDefault(w, 0.0));
                    delta.put(v, delta.getOrDefault(v, 0.0) + add);
                }
                if (!w.equals(s.id())) bc.put(w, bc.get(w) + delta.getOrDefault(w, 0.0));
            }
        }
        // undirected: every pair counted twice
        bc.replaceAll((k, v) -> v / 2);
        return GraphAlgorithms.scored(g, bc);
    }

    /** TS {@code closenessCentrality}: Wasserman-Faust, so a small component cannot out-score the main one. */
    public static List<Score> closenessCentrality(Graph g) {
        return closenessCentrality(g, RunControl.NONE);
    }

    /** As {@link #closenessCentrality(Graph)}, with a checkpoint per source. */
    public static List<Score> closenessCentrality(Graph g, RunControl ctl) {
        Map<String, List<String>> adj = bothNeighbors(g);
        List<String> ids = g.nodes().stream().map(Node::id).toList();
        int n = ids.size();
        Map<String, Double> close = new LinkedHashMap<>();
        int done = 0;
        for (String s : ids) {
            ctl.checkpoint();
            ctl.progress(done++, n);
            Map<String, Integer> dist = new LinkedHashMap<>();
            dist.put(s, 0);
            List<String> queue = new ArrayList<>(List.of(s));
            double sum = 0;
            int reach = 0;
            for (int head = 0; head < queue.size(); head++) {
                String v = queue.get(head);
                for (String w : adj.getOrDefault(v, List.of())) {
                    if (dist.containsKey(w)) continue;
                    dist.put(w, dist.get(v) + 1);
                    sum += dist.get(w);
                    reach++;
                    queue.add(w);
                }
            }
            close.put(s, reach > 0 && n > 1 ? ((double) reach / (n - 1)) * (reach / sum) : 0.0);
        }
        return GraphAlgorithms.scored(g, close);
    }

    /** TS {@code jaccardSimilarity}: neighbourhood overlap of every other node with {@code nodeId}; empty if absent. */
    public static List<Score> jaccardSimilarity(Graph g, String nodeId) {
        return jaccardSimilarity(g, nodeId, RunControl.NONE);
    }

    /** As {@link #jaccardSimilarity(Graph, String)}, with a checkpoint per compared node. */
    public static List<Score> jaccardSimilarity(Graph g, String nodeId, RunControl ctl) {
        Map<String, Set<String>> nb = GraphAlgorithms.undirectedNeighbors(g);
        Set<String> mine = nb.get(nodeId);
        if (mine == null) return List.of();
        Map<String, Double> sim = new LinkedHashMap<>();
        for (String other : nb.keySet()) {
            if (other.equals(nodeId)) continue;
            ctl.checkpoint();
            Set<String> theirs = nb.get(other);
            int inter = 0;
            for (String x : mine) if (theirs.contains(x)) inter++;
            int union = mine.size() + theirs.size() - inter;
            sim.put(other, union != 0 ? (double) inter / union : 0.0);
        }
        List<Score> out = new ArrayList<>();
        for (Node n : g.nodes()) {
            if (n.id().equals(nodeId)) continue;
            Double s = sim.get(n.id());
            out.add(new Score(n.id(), n.label(), s == null ? 0 : s));
        }
        out.sort(GraphAlgorithms.BY_SCORE_THEN_ID_THEN_LABEL);
        return out;
    }

    /** TS {@code linkPrediction}: non-adjacent pairs with a shared neighbour, top {@code limit} by score. */
    public static List<PredictedLink> linkPrediction(Graph g, Method method, int limit) {
        return linkPrediction(g, method, limit, RunControl.NONE);
    }

    /**
     * As {@link #linkPrediction(Graph, Method, int)}, with a checkpoint per anchor node and per neighbour of it. A pair
     * scores above 0 only if it shares a neighbour, so the partners of an anchor are found by walking its 2-hop
     * neighbourhood instead of scanning every node; they are then scored in ascending id order and each score is summed
     * over the anchor's neighbours in the same order as before, so the output is identical to the all-pairs scan.
     */
    public static List<PredictedLink> linkPrediction(Graph g, Method method, int limit, RunControl ctl) {
        Map<String, Set<String>> nb = GraphAlgorithms.undirectedNeighbors(g);
        Map<String, String> label = new LinkedHashMap<>();
        for (Node n : g.nodes()) label.put(n.id(), n.label());
        List<String> ids = new ArrayList<>(nb.keySet());
        ids.sort(Comparator.naturalOrder());
        List<PredictedLink> out = new ArrayList<>();
        int done = 0;
        for (String a : ids) {
            ctl.checkpoint();
            ctl.progress(done++, ids.size());
            Set<String> an = nb.get(a);
            // partner -> score, accumulated while walking the anchor's neighbours c in their set order: the adds happen in
            // the same order as the old per-pair "for c in an" intersection, so the floating-point sums are bit-identical.
            Map<String, Double> partners = new HashMap<>();
            for (String c : an) {
                ctl.checkpoint();
                Set<String> cn = nb.get(c);
                if (cn == null) continue;
                int deg = cn.size();
                double share = method == Method.ADAMIC_ADAR ? (deg > 1 ? 1 / Math.log(deg) : 0) : 1;
                for (String b : cn) {
                    if (b.compareTo(a) > 0 && !an.contains(b) && nb.containsKey(b)) partners.merge(b, share, Double::sum);
                }
            }
            List<String> order = new ArrayList<>(partners.keySet());
            order.sort(Comparator.naturalOrder());
            for (String b : order) {
                double score = partners.get(b);
                if (score > 0) {
                    out.add(new PredictedLink(a, b, label.getOrDefault(a, a), label.getOrDefault(b, b), score));
                }
            }
        }
        out.sort(Comparator.comparingDouble(PredictedLink::score).reversed().thenComparing(PredictedLink::source));
        return out.subList(0, Math.min(limit, out.size()));
    }
}

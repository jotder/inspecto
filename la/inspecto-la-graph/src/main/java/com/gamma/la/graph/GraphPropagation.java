package com.gamma.la.graph;

import com.gamma.la.graph.GraphAlgorithms.Adjacency;
import com.gamma.la.graph.GraphAlgorithms.Direction;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Hop;
import com.gamma.la.graph.GraphAlgorithms.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Propagated risk - an explainable "guilt by association" score. Server-side only: unlike the sibling classes it has no
 * {@code graph-analysis.ts} twin, so no parity fixture; its tests are hand-computed graphs.
 *
 * <p>Each node has its OWN indicator score (0-100, supplied by the caller). Every <i>origin</i> {@code o} - a node with an own
 * score above 0, or only the stated seeds among those - pushes {@code own(o) × w[d]} to each other node {@code n} it reaches in
 * {@code d} hops ({@code d} = the SHORTEST hop distance from {@code o}, {@code 1 ≤ d ≤ weights.size()}):
 * <pre>raw(n) = own(n) + Σ over origins o ≠ n within reach: own(o) × w[d(o, n)]</pre>
 * {@code score} is {@code raw} capped at 100; {@code raw} is reported as well, so the cap never hides how far over it a node is.
 * Direction is the walk's direction FROM the origin ({@code OUT}: along the edges, {@code IN}: against them, {@code BOTH}:
 * undirected), as {@code neighborhood} reads it from its root.
 *
 * <p><b>Why</b>: each node carries its {@value #FACTOR_LIMIT} largest contributions (origin, distance, weight, contribution;
 * largest first, then nearest, then origin id) and {@code contributors}, the count of ALL origins that contributed - so a cut
 * factor list is said, never silent. Ranked by {@code raw} descending, then {@code canonical-v1} (id, then label).
 */
public final class GraphPropagation {

    private GraphPropagation() {}

    /** How many contributions a node lists. */
    public static final int FACTOR_LIMIT = 5;

    /** One origin's contribution to a node: {@code own(origin) × weight}, {@code weight} being the weight of {@code distance}. */
    public record Factor(String origin, int distance, double weight, double contribution) {}

    /** One node's result. {@code score = min(raw, 100)}; {@code factors} are the top contributions of {@code contributors}. */
    public record Risk(String id, String label, double score, double raw, double own, int contributors, List<Factor> factors) {}

    private static final Comparator<Factor> STRONGEST = Comparator.comparingDouble(Factor::contribution).reversed()
            .thenComparingInt(Factor::distance).thenComparing(Factor::origin);

    /**
     * @param own     node id → own score; an id that is not a node is ignored, a node without an entry scores 0
     * @param seeds   the origins; empty = every node whose own score is above 0. A seed that is not a node, or whose own
     *                score is 0, contributes nothing
     * @param weights the weight of distance 1, 2, …; its size is the maximum depth
     */
    public static List<Risk> propagatedRisk(Graph g, Map<String, Double> own, Collection<String> seeds, List<Double> weights,
                                            Direction direction, RunControl ctl) {
        Map<String, Double> ownOf = new HashMap<>();
        for (Node n : g.nodes()) ownOf.put(n.id(), own.getOrDefault(n.id(), 0.0));
        Set<String> wanted = seeds.isEmpty() ? null : new HashSet<>(seeds);
        List<String> origins = new ArrayList<>();
        for (Node n : g.nodes())
            if (ownOf.get(n.id()) > 0 && (wanted == null || wanted.contains(n.id()))) origins.add(n.id());

        Adjacency adj = GraphAlgorithms.adjacency(g);
        int depth = weights.size();
        Map<String, Double> received = new HashMap<>();
        Map<String, Integer> contributors = new HashMap<>();
        Map<String, List<Factor>> top = new HashMap<>();
        for (int i = 0; i < origins.size(); i++) {
            ctl.checkpoint();
            String o = origins.get(i);
            double from = ownOf.get(o);
            Set<String> seen = new HashSet<>(Set.of(o));
            List<String> frontier = List.of(o);
            for (int d = 1; d <= depth && !frontier.isEmpty(); d++) {
                double w = weights.get(d - 1);
                List<String> next = new ArrayList<>();
                for (String at : frontier)
                    for (Hop h : GraphAlgorithms.neighborsOf(adj, at, direction)) {
                        if (!seen.add(h.node())) continue;
                        next.add(h.node());
                        if (w <= 0) continue;                          // a zero weight reaches but contributes nothing
                        Factor f = new Factor(o, d, w, from * w);
                        received.merge(h.node(), f.contribution(), Double::sum);
                        contributors.merge(h.node(), 1, Integer::sum);
                        List<Factor> t = top.computeIfAbsent(h.node(), k -> new ArrayList<>());
                        t.add(f);
                        if (t.size() > FACTOR_LIMIT) {
                            t.sort(STRONGEST);
                            t.remove(FACTOR_LIMIT);
                        }
                    }
                frontier = next;
            }
            ctl.progress(i + 1, origins.size());
        }

        List<Risk> out = new ArrayList<>(g.nodes().size());
        for (Node n : g.nodes()) {
            double self = ownOf.get(n.id());
            double raw = self + received.getOrDefault(n.id(), 0.0);
            List<Factor> f = new ArrayList<>(top.getOrDefault(n.id(), List.of()));
            f.sort(STRONGEST);
            out.add(new Risk(n.id(), n.label(), Math.min(raw, 100.0), raw, self, contributors.getOrDefault(n.id(), 0), List.copyOf(f)));
        }
        out.sort(Comparator.comparingDouble(Risk::raw).reversed().thenComparing(Risk::id).thenComparing(Risk::label));
        return out;
    }
}

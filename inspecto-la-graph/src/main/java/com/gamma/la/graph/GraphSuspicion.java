package com.gamma.la.graph;

import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphAlgorithms.Score;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composite suspicion score — a line-for-line port of {@code suspicionScore} in
 * {@code inspecto-ui/src/app/inspecto/graph/graph-analysis.ts} (option D, spike D-S4). It blends five normalised
 * factors, each already ported and pinned by its own parity fixture: degree, betweenness, PageRank, k-core and
 * triangles. {@code GraphSuspicionParityTest} and {@code graph-suspicion-parity.spec.ts} assert the SAME fixture.
 * Differences from the browser: no node cap (the caller bounds the input); the tie-break is the shared {@code canonical-v1} (id, then label).
 */
public final class GraphSuspicion {

    private GraphSuspicion() {}

    /** Relative weight of each factor; the browser's default is 1 for each. */
    public record Weights(double degree, double betweenness, double pageRank, double core, double triangles) {
        public static final Weights DEFAULT = new Weights(1, 1, 1, 1, 1);
    }

    public record Factors(double degree, double betweenness, double pageRank, double core, double triangles) {}

    public record Suspicion(String id, String label, double score, Factors factors) {}

    /** TS {@code normalize}: divide by the maximum (floored at 0); an all-zero set stays zero. */
    private static Map<String, Double> normalize(List<Score> scores) {
        double max = 0;
        for (Score s : scores) max = Math.max(max, s.score());
        Map<String, Double> out = new LinkedHashMap<>();
        for (Score s : scores) out.put(s.id(), max > 0 ? s.score() / max : 0.0);
        return out;
    }

    public static List<Suspicion> suspicionScore(Graph g) {
        return suspicionScore(g, Weights.DEFAULT);
    }

    public static List<Suspicion> suspicionScore(Graph g, Weights w) {
        Map<String, Double> deg = normalize(GraphAlgorithms.degreeCentrality(g));
        Map<String, Double> btw = normalize(GraphCentrality.betweennessCentrality(g));
        Map<String, Double> pr = normalize(GraphIterative.pageRank(g));
        Map<String, Double> core = normalize(GraphAlgorithms.kCore(g));
        Map<String, Double> tri = normalize(GraphAlgorithms.triangleCount(g));
        double sum = w.degree() + w.betweenness() + w.pageRank() + w.core() + w.triangles();
        double wSum = sum != 0 ? sum : 1; // TS: `a + b + … || 1`
        List<Suspicion> out = new ArrayList<>();
        for (Node n : g.nodes()) {
            Factors f = new Factors(deg.getOrDefault(n.id(), 0.0), btw.getOrDefault(n.id(), 0.0),
                    pr.getOrDefault(n.id(), 0.0), core.getOrDefault(n.id(), 0.0), tri.getOrDefault(n.id(), 0.0));
            double blend = w.degree() * f.degree()
                    + w.betweenness() * f.betweenness()
                    + w.pageRank() * f.pageRank()
                    + w.core() * f.core()
                    + w.triangles() * f.triangles();
            out.add(new Suspicion(n.id(), n.label(), (blend / wSum) * 100, f));
        }
        out.sort(Comparator.comparingDouble(Suspicion::score).reversed().thenComparing(Suspicion::id).thenComparing(Suspicion::label));
        return out;
    }
}

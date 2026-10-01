package com.gamma.la.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a {@link GraphEngine} runs on: LA-owned, neutral, and independent of where the graph came from (a Working Set
 * today, an edge index at D-3). Nodes {@code (id, label)}, edges {@code (id, source, target)}, and a weight per edge id.
 *
 * <p><b>Closed graph</b> (feasibility plan §7.13): every algorithm assumes each edge endpoint is a node. {@link #of}
 * enforces it — an edge whose endpoint is not a node is DROPPED and COUNTED in {@link #droppedDangling()}, never
 * silently — so the count travels into the result.
 *
 * <p><b>Weights</b>: edge id → weight; an id with no entry weighs 1 (the browser's default). Only the algorithms that
 * {@linkplain Algorithm#needsWeights() need weights} read it.
 */
public record GraphInput(List<Node> nodes, List<Edge> edges, Map<String, Double> weights, int droppedDangling) {

    public record Node(String id, String label) {}

    public record Edge(String id, String source, String target) {}

    public GraphInput {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        weights = Map.copyOf(weights);
    }

    /** Builds a closed graph: edges with an endpoint outside {@code nodes} are dropped and counted. */
    public static GraphInput of(List<Node> nodes, List<Edge> edges, Map<String, Double> weights) {
        Set<String> ids = new HashSet<>();
        for (Node n : nodes) ids.add(n.id());
        List<Edge> kept = new ArrayList<>(edges.size());
        int dropped = 0;
        for (Edge e : edges) {
            if (ids.contains(e.source()) && ids.contains(e.target())) kept.add(e);
            else dropped++;
        }
        return new GraphInput(nodes, kept, weights, dropped);
    }
}

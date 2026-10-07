package com.gamma.la.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a {@link GraphEngine} runs on: LA-owned, neutral, and independent of where the graph came from. Sealed to two
 * cases (D-3 step 7, design 4.3): {@link Materialised} - the graph itself, nodes {@code (id, label)}, edges
 * {@code (id, source, target)} and a weight per edge id (a Working Set today) - and {@link IndexRef}, a REFERENCE to a
 * published edge index that only the index-backed engine can read.
 *
 * <p><b>Closed graph</b> (feasibility plan 7.13): every algorithm assumes each edge endpoint is a node. {@link #of}
 * enforces it for a {@link Materialised} graph - an edge whose endpoint is not a node is DROPPED and COUNTED in
 * {@link Materialised#droppedDangling()}, never silently - so the count travels into the result.
 *
 * <p><b>Weights</b>: edge id to weight; an id with no entry weighs 1 (the browser's default). Only the algorithms that
 * {@linkplain Algorithm#needsWeights() need weights} read it.
 */
public sealed interface GraphInput permits GraphInput.Materialised, GraphInput.IndexRef {

    record Node(String id, String label) {}

    record Edge(String id, String source, String target) {}

    /** {@code "materialised"} or {@code "index"}: the wire name of the input case. */
    String kind();

    /** The size the budget is checked against BEFORE any work: exact for a graph, an estimate for an index reference. */
    int estimateNodes();

    int estimateEdges();

    /**
     * What, beyond the request, distinguishes this input in the result cache's key. Empty for a {@link Materialised} graph
     * (its key is the Working Set's, unchanged); for an {@link IndexRef} the index version and the reference itself.
     */
    String cacheIdentity();

    /** A graph held in memory. */
    record Materialised(List<Node> nodes, List<Edge> edges, Map<String, Double> weights, int droppedDangling) implements GraphInput {

        public Materialised {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
            weights = Map.copyOf(weights);
        }

        @Override
        public String kind() {
            return "materialised";
        }

        @Override
        public int estimateNodes() {
            return nodes.size();
        }

        @Override
        public int estimateEdges() {
            return edges.size();
        }

        @Override
        public String cacheIdentity() {
            return "";
        }
    }

    /**
     * A reference to ONE pinned version of a published edge index. Primitives only - this module does not know how the index
     * is stored. {@code versionDir} is the version directory, {@code indexId} names the index (Dataset + mapping),
     * {@code seeds} the nodes the read starts from, {@code kinds} (empty = all) the link kinds kept, and the two estimates the
     * sum of the seeds' degrees, measured by the route that built the reference. Direction and hops are the algorithm's own
     * parameters and are read from the resolved request, not duplicated here.
     */
    record IndexRef(Path versionDir, long version, String indexId, List<String> seeds, List<String> kinds,
                    int estimatedNodes, int estimatedEdges) implements GraphInput {

        public IndexRef {
            seeds = List.copyOf(seeds);
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
        }

        @Override
        public String kind() {
            return "index";
        }

        @Override
        public int estimateNodes() {
            return estimatedNodes;
        }

        @Override
        public int estimateEdges() {
            return estimatedEdges;
        }

        @Override
        public String cacheIdentity() {
            return "index:" + indexId + "@v" + version + "|seeds=" + String.join("\u0002", seeds) + "|kinds=" + String.join("\u0002", kinds);
        }
    }

    /** Builds a closed graph: edges with an endpoint outside {@code nodes} are dropped and counted. */
    static Materialised of(List<Node> nodes, List<Edge> edges, Map<String, Double> weights) {
        Set<String> ids = new HashSet<>();
        for (Node n : nodes) ids.add(n.id());
        List<Edge> kept = new ArrayList<>(edges.size());
        int dropped = 0;
        for (Edge e : edges) {
            if (ids.contains(e.source()) && ids.contains(e.target())) kept.add(e);
            else dropped++;
        }
        return new Materialised(nodes, kept, weights, dropped);
    }
}

package com.gamma.la.core;

import com.gamma.la.core.GraphResult.Payload;
import com.gamma.la.graph.GraphAlgorithms;
import com.gamma.la.graph.GraphAlgorithms.Direction;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphCentrality;
import com.gamma.la.graph.GraphIterative;
import com.gamma.la.graph.GraphPaths;
import com.gamma.la.graph.GraphPropagation;
import com.gamma.la.graph.GraphStructure;
import com.gamma.la.graph.GraphSuspicion;
import com.gamma.la.graph.RunControl;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The whole of D-4's engine: dispatches each {@link Algorithm} to its {@code inspecto-la-graph} port over the
 * {@link GraphInput} materialised as a {@code Graph}. The dispatch is an exhaustive switch with no {@code default}, so a
 * constant added to {@link Algorithm} without an implementation here does not compile.
 *
 * <p>Results come back in la-graph's order, which is canonical-v1 already; nothing is re-sorted here. Stateless and
 * thread-safe — one instance serves every run.
 */
public final class InMemoryGraphEngine implements GraphEngine {

    @Override
    public String engineId() {
        return "memory";
    }

    @Override
    public Set<Algorithm> supported() {
        return Set.of(Algorithm.values());
    }

    @Override
    public GraphResult run(Algorithm algorithm, Map<String, Object> params, GraphInput in, RunControl ctl) {
        if (!(in instanceof GraphInput.Materialised input))
            throw new IllegalArgumentException("the in-memory engine runs only a materialised graph, not an input of kind '" + in.kind() + "'");
        if (algorithm == null)
            throw new InvalidGraphRequest(InvalidGraphRequest.Reason.UNKNOWN_ALGORITHM, null, "no algorithm named");
        Map<String, Object> p = algorithm.resolve(params);        // refuses BEFORE any work
        RunControl c = ctl == null ? RunControl.NONE : ctl;
        Graph g = toGraph(input);
        long t0 = System.nanoTime();
        Payload payload = dispatch(algorithm, p, g, input.weights(), c);
        return new GraphResult(algorithm, payload, input.droppedDangling(), (System.nanoTime() - t0) / 1_000_000L);
    }

    private static Graph toGraph(GraphInput.Materialised in) {
        var nodes = new ArrayList<GraphAlgorithms.Node>(in.nodes().size());
        for (GraphInput.Node n : in.nodes()) nodes.add(new GraphAlgorithms.Node(n.id(), n.label()));
        var edges = new ArrayList<GraphAlgorithms.Edge>(in.edges().size());
        for (GraphInput.Edge e : in.edges()) edges.add(new GraphAlgorithms.Edge(e.id(), e.source(), e.target()));
        return new Graph(nodes, edges);
    }

    private static Payload dispatch(Algorithm a, Map<String, Object> p, Graph g, Map<String, Double> w, RunControl c) {
        return switch (a) {
            case SHORTEST_PATH -> new GraphResult.OneSelection(
                    GraphAlgorithms.shortestPath(g, str(p, Algorithm.FROM), str(p, Algorithm.TO), dir(p)));
            case NEIGHBORHOOD -> new GraphResult.SubGraph(
                    GraphAlgorithms.neighborhood(g, str(p, Algorithm.NODE), num(p, "hops"), dir(p)));
            case EGO_NETWORK -> new GraphResult.SubGraph(GraphPaths.egoNetwork(g, str(p, Algorithm.NODE), dir(p)));
            case DEGREE_CENTRALITY -> new GraphResult.Scores(GraphAlgorithms.degreeCentrality(g));
            case CONNECTED_COMPONENTS -> new GraphResult.Groups(GraphAlgorithms.connectedComponents(g));
            case K_CORE -> new GraphResult.Scores(GraphAlgorithms.kCore(g, c));
            case TRIANGLE_COUNT -> new GraphResult.Scores(GraphAlgorithms.triangleCount(g, c));
            case ARTICULATION_POINTS -> new GraphResult.Ids(GraphStructure.articulationPoints(g));
            case BRIDGES -> new GraphResult.Ids(GraphStructure.bridges(g));
            case IS_FOREST -> new GraphResult.Flag(GraphStructure.isForest(g));
            case DESCENDANTS -> new GraphResult.Ids(new ArrayList<>(GraphStructure.descendants(g, str(p, Algorithm.NODE))));
            case WEIGHTED_SHORTEST_PATH -> new GraphResult.OneSelection(
                    GraphPaths.weightedShortestPath(g, w, str(p, Algorithm.FROM), str(p, Algorithm.TO), dir(p)));
            case MAXIMUM_SPANNING_FOREST -> new GraphResult.OneSelection(GraphPaths.maximumSpanningForest(g, w));
            case JACCARD_SIMILARITY -> new GraphResult.Scores(GraphCentrality.jaccardSimilarity(g, str(p, Algorithm.NODE), c));
            case PAGE_RANK -> new GraphResult.Scores(GraphIterative.pageRank(g, dbl(p, "damping"), num(p, "iterations"), c));
            case BETWEENNESS_CENTRALITY -> new GraphResult.Scores(GraphCentrality.betweennessCentrality(g, c));
            case CLOSENESS_CENTRALITY -> new GraphResult.Scores(GraphCentrality.closenessCentrality(g, c));
            case SUSPICION_SCORE -> new GraphResult.Suspicions(GraphSuspicion.suspicionScore(g,
                    new GraphSuspicion.Weights(dbl(p, "degree"), dbl(p, "betweenness"), dbl(p, "pageRank"), dbl(p, "core"),
                            dbl(p, "triangles")), c));
            case LOUVAIN_COMMUNITIES -> new GraphResult.Communities(GraphIterative.louvainCommunities(g, c));
            case DETECT_COMMUNITIES -> new GraphResult.Communities(
                    GraphIterative.detectCommunities(g, num(p, "maxIterations"), c));
            case CLIQUES -> new GraphResult.Groups(GraphStructure.cliques(g, num(p, "minSize"), c));
            case FIND_CYCLES -> new GraphResult.Selections(
                    GraphStructure.findCycles(g, num(p, "limit"), num(p, "maxLen"), c));
            case ALL_PATHS -> new GraphResult.Selections(GraphPaths.allPaths(g, str(p, Algorithm.FROM), str(p, Algorithm.TO),
                    num(p, "limit"), num(p, "maxHops"), dir(p), c));
            case MAX_FLOW -> new GraphResult.Flow(
                    GraphPaths.maxFlow(g, w, str(p, Algorithm.FROM), str(p, Algorithm.TO), c));
            case LINK_PREDICTION -> new GraphResult.Links(GraphCentrality.linkPrediction(g,
                    GraphCentrality.Method.valueOf(str(p, "method").toUpperCase(Locale.ROOT).replace('-', '_')),
                    num(p, "limit"), c));
            case EIGENVECTOR_CENTRALITY -> new GraphResult.Scores(
                    GraphIterative.eigenvectorCentrality(g, num(p, "iterations"), c));
            case KATZ_CENTRALITY -> new GraphResult.Scores(GraphIterative.katzCentrality(g, dbl(p, "alpha"), dbl(p, "beta"),
                    num(p, "iterations"), c));
            case HITS -> {
                GraphIterative.HitsResult h = GraphIterative.hits(g, num(p, "iterations"), c);
                yield new GraphResult.Hits(h.hubs(), h.authorities());
            }
            case PROPAGATED_RISK -> new GraphResult.PropagatedRisks(GraphPropagation.propagatedRisk(g,
                    typed(p, "nodeScores"), typed(p, "seeds"), typed(p, "weights"), dir(p), c));
        };
    }

    /** A collection parameter, already typed by {@link Algorithm#resolve}. */
    @SuppressWarnings("unchecked")
    private static <T> T typed(Map<String, Object> p, String k) {
        return (T) p.get(k);
    }

    private static String str(Map<String, Object> p, String k) {
        return (String) p.get(k);
    }

    private static int num(Map<String, Object> p, String k) {
        return (Integer) p.get(k);
    }

    private static double dbl(Map<String, Object> p, String k) {
        return (Double) p.get(k);
    }

    private static Direction dir(Map<String, Object> p) {
        return Direction.valueOf(str(p, "direction").toUpperCase(Locale.ROOT));
    }
}

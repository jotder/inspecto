package com.gamma.la.core;

import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.la.graph.GraphAlgorithms.Selection;
import com.gamma.la.graph.GraphCentrality.PredictedLink;
import com.gamma.la.graph.GraphPaths.MaxFlowResult;
import com.gamma.la.graph.GraphSuspicion.Suspicion;

import java.util.List;
import java.util.Map;

/**
 * One engine run's answer: the {@linkplain Payload payload} (its variant is {@link Algorithm#resultKind()}), how many
 * dangling edges the input dropped ({@link GraphInput#droppedDangling()} — carried so a result never hides a smaller
 * graph than the one asked about), and the wall-clock the run took.
 *
 * <p>Orders are la-graph's, which are canonical-v1 (score descending, then id code units, then label — one comparator in
 * TypeScript and Java), so the same ranking comes back wherever the algorithm ran. The value records inside the
 * payloads are la-graph's own ({@code Score}, {@code Selection}, …): they are the parity-tested shapes.
 */
public record GraphResult(Algorithm algorithm, Payload payload, int dropped, long elapsedMs) {

    /** The answer, by shape. */
    public sealed interface Payload permits Scores, Hits, OneSelection, Selections, Groups, Communities, Ids, Flag, Flow,
            Links, Suspicions, SubGraph {
        Algorithm.ResultKind kind();
    }

    /** Ranked scores (degree, k-core, triangles, betweenness, closeness, Jaccard, PageRank, eigenvector, Katz). */
    public record Scores(List<Score> scores) implements Payload {
        public Algorithm.ResultKind kind() { return Algorithm.ResultKind.SCORES; }
    }

    /** HITS: hubs and authorities, each ranked. */
    public record Hits(List<Score> hubs, List<Score> authorities) implements Payload {
        public Algorithm.ResultKind kind() { return Algorithm.ResultKind.HITS; }
    }

    /** A node/edge selection; {@code selection} is null when there is none (disconnected, or an endpoint is absent). */
    public record OneSelection(Selection selection) implements Payload {
        public Algorithm.ResultKind kind() { return Algorithm.ResultKind.SELECTION; }
    }

    /** Several selections, in the algorithm's discovery order (all paths, cycles). */
    public record Selections(List<Selection> selections) implements Payload {
        public Algorithm.ResultKind kind() { return Algorithm.ResultKind.SELECTIONS; }
    }

    /** Ordered groups of node ids (connected components, cliques). */
    public record Groups(List<List<String>> groups) implements Payload {
        public Algorithm.ResultKind kind() { return Algorithm.ResultKind.GROUPS; }
    }

    /** Node id → community id, in the algorithm's pair order. */
    public record Communities(Map<String, String> communityOf) implements Payload {
        public Algorithm.ResultKind kind() { return Algorithm.ResultKind.COMMUNITIES; }
    }

    /** An ordered id list (articulation points, bridge edge ids, descendants). */
    public record Ids(List<String> ids) implements Payload {
        public Algorithm.ResultKind kind() { return Algorithm.ResultKind.IDS; }
    }

    public record Flag(boolean value) implements Payload {
        public Algorithm.ResultKind kind() { return Algorithm.ResultKind.FLAG; }
    }

    /** Max flow: the value and the saturated minimum cut. */
    public record Flow(MaxFlowResult flow) implements Payload {
        public Algorithm.ResultKind kind() { return Algorithm.ResultKind.FLOW; }
    }

    /** Predicted (non-adjacent) links, best first. */
    public record Links(List<PredictedLink> links) implements Payload {
        public Algorithm.ResultKind kind() { return Algorithm.ResultKind.LINKS; }
    }

    /** Suspicion scores with the five factors. */
    public record Suspicions(List<Suspicion> scores) implements Payload {
        public Algorithm.ResultKind kind() { return Algorithm.ResultKind.SUSPICION; }
    }

    /** A sub-graph (neighborhood, ego network). */
    public record SubGraph(Graph graph) implements Payload {
        public Algorithm.ResultKind kind() { return Algorithm.ResultKind.GRAPH; }
    }
}

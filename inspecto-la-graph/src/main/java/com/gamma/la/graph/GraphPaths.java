package com.gamma.la.graph;

import com.gamma.la.graph.GraphAlgorithms.Direction;
import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Selection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Server-side path and flow algorithms — a line-for-line port of the matching functions in
 * {@code inspecto-ui/projects/link-analysis/src/graph/graph-analysis.ts} (option D, spike D-S4, the paths-and-flow lane).
 * {@code GraphPathsParityTest} and {@code graph-paths-parity.spec.ts} assert the SAME hand-derived fixture
 * ({@code inspecto-ui/projects/link-analysis/src/graph/graph-paths-parity.fixture.json}).
 *
 * <p>Same deliberate differences as {@link GraphAlgorithms}: no node cap, closed
 * graphs (id compares are UTF-16 code-unit order, the same {@code canonical-v1} as the browser). The browser reads an edge's weight from its {@code data}; {@link GraphAlgorithms.Edge} carries none, so the
 * weighted functions take {@code weights} (edge id to {@link #edgeWeight}), a missing id counting as 1.
 */
public final class GraphPaths {

    private GraphPaths() {}

    /** TS {@code MaxFlowResult}: the flow value and the saturated min-cut edges with their endpoints. */
    public record MaxFlowResult(double value, Selection minCut) {}

    /** TS {@code edgeWeight}: a positive numeric {@code count}, else the {@code kind} suffix after {@code " · "}, else 1. */
    public static double edgeWeight(Double count, String kind) {
        if (count != null && count > 0) return count;
        String[] parts = kind.split(" · ", -1);
        String suffix = parts.length > 1 ? parts[1] : null;
        double n = Double.NaN;
        if (suffix != null && !suffix.isEmpty()) {
            try {
                n = Double.parseDouble(suffix.trim());
            } catch (NumberFormatException ex) {
                n = Double.NaN;
            }
        }
        return Double.isFinite(n) && n > 0 ? n : 1;
    }

    /** The DFS state of {@link #allPaths} (the browser's closure over {@code walk}). */
    private static final class PathWalk {
        final GraphAlgorithms.Adjacency adj;
        final String toId;
        final int limit;
        final int maxHops;
        final Direction direction;
        final List<Selection> results = new ArrayList<>();
        final List<String> nodeStack = new ArrayList<>();
        final List<String> edgeStack = new ArrayList<>();
        final Set<String> onPath = new HashSet<>();
        final RunControl ctl;

        PathWalk(GraphAlgorithms.Adjacency adj, String toId, int limit, int maxHops, Direction direction,
                 RunControl ctl) {
            this.ctl = ctl;
            this.adj = adj;
            this.toId = toId;
            this.limit = limit;
            this.maxHops = maxHops;
            this.direction = direction;
        }

        void walk(String cur) {
            ctl.checkpoint();
            if (results.size() >= limit) return;
            if (cur.equals(toId)) {
                results.add(new Selection(new ArrayList<>(nodeStack), new ArrayList<>(edgeStack)));
                return;
            }
            if (nodeStack.size() > maxHops) return;
            for (GraphAlgorithms.Hop h : GraphAlgorithms.neighborsOf(adj, cur, direction)) {
                if (onPath.contains(h.node())) continue;
                onPath.add(h.node());
                nodeStack.add(h.node());
                edgeStack.add(h.edge());
                walk(h.node());
                onPath.remove(h.node());
                nodeStack.remove(nodeStack.size() - 1);
                edgeStack.remove(edgeStack.size() - 1);
            }
        }
    }

    /** TS {@code allPaths}: simple paths, DFS in adjacency order, at most {@code limit}, depth-capped at {@code maxHops}. */
    public static List<Selection> allPaths(Graph g, String fromId, String toId, int limit, int maxHops,
                                           Direction direction) {
        return allPaths(g, fromId, toId, limit, maxHops, direction, RunControl.NONE);
    }

    /** As {@link #allPaths(Graph, String, String, int, int, Direction)}, with a checkpoint per DFS step. */
    public static List<Selection> allPaths(Graph g, String fromId, String toId, int limit, int maxHops,
                                           Direction direction, RunControl ctl) {
        GraphAlgorithms.Adjacency adj = GraphAlgorithms.adjacency(g);
        PathWalk w = new PathWalk(adj, toId, limit, maxHops, direction, ctl);
        w.nodeStack.add(fromId);
        w.onPath.add(fromId);
        if (adj.out().containsKey(fromId) && adj.out().containsKey(toId)) w.walk(fromId);
        return w.results;
    }

    /** TS {@code egoNetwork}: the 1-hop induced subgraph. */
    public static Graph egoNetwork(Graph g, String nodeId, Direction direction) {
        return GraphAlgorithms.neighborhood(g, nodeId, 1, direction);
    }

    private record Frontier(double dist, int seq, String node) {}

    /** TS {@code weightedShortestPath}: Dijkstra with cost {@code 1 / weight}, {@code null} when disconnected. */
    public static Selection weightedShortestPath(Graph g, Map<String, Double> weights, String fromId, String toId,
                                                 Direction direction) {
        if (fromId.equals(toId)) return new Selection(List.of(fromId), List.of());
        GraphAlgorithms.Adjacency adj = GraphAlgorithms.adjacency(g);
        if (!adj.out().containsKey(fromId) || !adj.out().containsKey(toId)) return null;
        Map<String, Double> cost = new LinkedHashMap<>();
        for (Edge e : g.edges()) cost.put(e.id(), 1 / weights.getOrDefault(e.id(), 1.0));
        Map<String, Double> dist = new HashMap<>();
        dist.put(fromId, 0.0);
        // Ties on distance go to the node that entered `dist` first - what the old linear scan over the insertion-ordered
        // map did - so the heap orders on (distance, entry sequence). Primitive < / > keep -0.0 equal to 0.0 as before.
        Map<String, Integer> entered = new HashMap<>();
        entered.put(fromId, 0);
        PriorityQueue<Frontier> heap = new PriorityQueue<>((x, y) -> x.dist < y.dist ? -1 : x.dist > y.dist ? 1 : Integer.compare(x.seq, y.seq));
        heap.add(new Frontier(0.0, 0, fromId));
        Map<String, GraphAlgorithms.Hop> prev = new HashMap<>();
        Set<String> visited = new HashSet<>();
        for (;;) {
            String cur = null;
            double best = Double.POSITIVE_INFINITY;
            while (!heap.isEmpty()) {
                Frontier f = heap.poll();
                if (visited.contains(f.node)) continue; // stale: a shorter entry for this node was popped first
                cur = f.node;
                best = f.dist;
                break;
            }
            if (cur == null || cur.equals(toId)) break;
            visited.add(cur);
            for (GraphAlgorithms.Hop h : GraphAlgorithms.neighborsOf(adj, cur, direction)) {
                if (visited.contains(h.node())) continue;
                double nd = best + cost.getOrDefault(h.edge(), 1.0);
                if (nd < dist.getOrDefault(h.node(), Double.POSITIVE_INFINITY)) {
                    dist.put(h.node(), nd);
                    prev.put(h.node(), new GraphAlgorithms.Hop(cur, h.edge()));
                    Integer known = entered.get(h.node());
                    int seq = known != null ? known : entered.size();
                    entered.putIfAbsent(h.node(), seq);
                    heap.add(new Frontier(nd, seq, h.node()));
                }
            }
        }
        if (!prev.containsKey(toId)) return null;
        List<String> nodeIds = new ArrayList<>(List.of(toId));
        List<String> edgeIds = new ArrayList<>();
        String at = toId;
        while (!at.equals(fromId)) {
            GraphAlgorithms.Hop p = prev.get(at);
            edgeIds.add(0, p.edge());
            nodeIds.add(0, p.node());
            at = p.node();
        }
        return new Selection(nodeIds, edgeIds);
    }

    private static String key(String a, String b) {
        return a + "\0" + b;
    }

    /** TS {@code maxFlow}: Edmonds-Karp, capacity = edge weight, directed; value 0 and an empty cut when an endpoint is absent. */
    public static MaxFlowResult maxFlow(Graph g, Map<String, Double> weights, String sourceId, String sinkId) {
        return maxFlow(g, weights, sourceId, sinkId, RunControl.NONE);
    }

    /** As {@link #maxFlow(Graph, Map, String, String)}, with a checkpoint per BFS node expansion. */
    public static MaxFlowResult maxFlow(Graph g, Map<String, Double> weights, String sourceId, String sinkId,
                                        RunControl ctl) {
        MaxFlowResult empty = new MaxFlowResult(0, new Selection(List.of(), List.of()));
        if (sourceId.equals(sinkId)) return empty;
        Set<String> nodeIds = new HashSet<>();
        g.nodes().forEach(n -> nodeIds.add(n.id()));
        if (!nodeIds.contains(sourceId) || !nodeIds.contains(sinkId)) return empty;
        // Residual capacities keyed "a\0b"; forward edges seed capacity, back edges start at 0.
        Map<String, Double> cap = new LinkedHashMap<>();
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        g.nodes().forEach(n -> adj.put(n.id(), new LinkedHashSet<>()));
        for (Edge e : g.edges()) {
            if (e.source().equals(e.target())) continue;
            cap.put(key(e.source(), e.target()),
                    cap.getOrDefault(key(e.source(), e.target()), 0.0) + weights.getOrDefault(e.id(), 1.0));
            if (!cap.containsKey(key(e.target(), e.source()))) cap.put(key(e.target(), e.source()), 0.0);
            adj.get(e.source()).add(e.target());
            adj.get(e.target()).add(e.source());
        }
        double value = 0;
        for (;;) {
            Map<String, String> prev = new LinkedHashMap<>();
            List<String> queue = new ArrayList<>(List.of(sourceId));
            prev.put(sourceId, sourceId);
            for (int head = 0; head < queue.size(); head++) {
                ctl.checkpoint();
                String u = queue.get(head);
                if (u.equals(sinkId)) break;
                for (String v : adj.getOrDefault(u, Set.of())) {
                    if (!prev.containsKey(v) && cap.getOrDefault(key(u, v), 0.0) > 1e-9) {
                        prev.put(v, u);
                        queue.add(v);
                    }
                }
            }
            if (!prev.containsKey(sinkId)) break;
            double bottleneck = Double.POSITIVE_INFINITY;
            for (String v = sinkId; !v.equals(sourceId); v = prev.get(v)) {
                bottleneck = Math.min(bottleneck, cap.get(key(prev.get(v), v)));
            }
            for (String v = sinkId; !v.equals(sourceId); v = prev.get(v)) {
                String u = prev.get(v);
                cap.put(key(u, v), cap.get(key(u, v)) - bottleneck);
                cap.put(key(v, u), cap.getOrDefault(key(v, u), 0.0) + bottleneck);
            }
            value += bottleneck;
        }
        // Min-cut: nodes reachable from source in the residual graph; cut edges cross that frontier.
        Set<String> reachable = new HashSet<>(Set.of(sourceId));
        List<String> stack = new ArrayList<>(List.of(sourceId));
        while (!stack.isEmpty()) {
            String u = stack.remove(stack.size() - 1);
            for (String v : adj.getOrDefault(u, Set.of())) {
                if (!reachable.contains(v) && cap.getOrDefault(key(u, v), 0.0) > 1e-9) {
                    reachable.add(v);
                    stack.add(v);
                }
            }
        }
        List<Edge> cutEdges = g.edges().stream()
                .filter(e -> reachable.contains(e.source()) && !reachable.contains(e.target())).toList();
        Set<String> cutNodes = new LinkedHashSet<>();
        for (Edge e : cutEdges) {
            cutNodes.add(e.source());
            cutNodes.add(e.target());
        }
        return new MaxFlowResult(value,
                new Selection(new ArrayList<>(cutNodes), cutEdges.stream().map(Edge::id).toList()));
    }

    /** TS {@code maximumSpanningForest}: Kruskal + union-find over the undirected graph, heaviest first, ties by id. */
    public static Selection maximumSpanningForest(Graph g, Map<String, Double> weights) {
        Map<String, String> parent = new LinkedHashMap<>();
        g.nodes().forEach(n -> parent.put(n.id(), n.id()));
        List<Edge> sorted = new ArrayList<>(g.edges().stream().filter(e -> !e.source().equals(e.target())).toList());
        sorted.sort(Comparator.comparingDouble((Edge e) -> weights.getOrDefault(e.id(), 1.0)).reversed()
                .thenComparing(Edge::id));
        List<String> edgeIds = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();
        for (Edge e : sorted) {
            String rs = find(parent, e.source());
            String rt = find(parent, e.target());
            if (rs.equals(rt)) continue;
            parent.put(rs, rt);
            edgeIds.add(e.id());
            nodeIds.add(e.source());
            nodeIds.add(e.target());
        }
        return new Selection(new ArrayList<>(nodeIds), edgeIds);
    }

    private static String find(Map<String, String> parent, String start) {
        String x = start;
        String r = x;
        while (!parent.get(r).equals(r)) r = parent.get(r);
        while (!parent.get(x).equals(r)) {
            String nx = parent.get(x);
            parent.put(x, r);
            x = nx;
        }
        return r;
    }
}

package com.gamma.la.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side graph algorithms — a line-for-line port of the matching functions in
 * {@code inspecto-ui/src/app/inspecto/graph/graph-analysis.ts} (option D, spike D-S4). Keep the two in step:
 * {@code GraphAlgorithmsParityTest} and {@code graph-algorithms-parity.spec.ts} assert the SAME hand-derived fixture
 * ({@code inspecto-ui/src/app/inspecto/graph/graph-algorithms-parity.fixture.json}).
 *
 * <p>Deliberate differences from the browser: no node cap (the caller bounds the input), and ties between equal
 * scores break by ORDINAL label compare where the browser uses {@code localeCompare}. The two agree on lowercase
 * ASCII labels (the fixture's) and may differ on mixed-case or punctuated ones — a known gap, not an oversight.
 * Like the browser, every algorithm assumes a closed graph: each edge endpoint is a node.
 */
public final class GraphAlgorithms {

    private GraphAlgorithms() {}

    public record Node(String id, String label) {}

    public record Edge(String id, String source, String target) {}

    public record Graph(List<Node> nodes, List<Edge> edges) {}

    /** The ids to emphasise: nodes and edges of a path. */
    public record Selection(List<String> nodeIds, List<String> edgeIds) {}

    public record Score(String id, String label, double score) {}

    public enum Direction { OUT, IN, BOTH }

    /* Package-private on purpose: the later D-S4 slices live in sibling classes and share these four helpers. */

    /** An adjacency entry: the neighbour and the edge that reaches it. */
    record Hop(String node, String edge) {}

    record Adjacency(Map<String, List<Hop>> out, Map<String, List<Hop>> in) {}

    static Adjacency adjacency(Graph g) {
        Map<String, List<Hop>> out = new LinkedHashMap<>();
        Map<String, List<Hop>> in = new LinkedHashMap<>();
        for (Node n : g.nodes()) {
            out.put(n.id(), new ArrayList<>());
            in.put(n.id(), new ArrayList<>());
        }
        for (Edge e : g.edges()) {
            List<Hop> o = out.get(e.source());
            if (o != null) o.add(new Hop(e.target(), e.id()));
            List<Hop> i = in.get(e.target());
            if (i != null) i.add(new Hop(e.source(), e.id()));
        }
        return new Adjacency(out, in);
    }

    static List<Hop> neighborsOf(Adjacency adj, String id, Direction direction) {
        List<Hop> out = adj.out().getOrDefault(id, List.of());
        List<Hop> in = adj.in().getOrDefault(id, List.of());
        return switch (direction) {
            case OUT -> out;
            case IN -> in;
            case BOTH -> {
                List<Hop> both = new ArrayList<>(out);
                both.addAll(in);
                yield both;
            }
        };
    }

    /** Undirected simple-graph neighbours: self-loops dropped, parallel edges collapsed. */
    static Map<String, Set<String>> undirectedNeighbors(Graph g) {
        Map<String, Set<String>> nb = new LinkedHashMap<>();
        for (Node n : g.nodes()) nb.put(n.id(), new LinkedHashSet<>());
        for (Edge e : g.edges()) {
            if (e.source().equals(e.target())) continue;
            Set<String> s = nb.get(e.source());
            if (s != null) s.add(e.target());
            Set<String> t = nb.get(e.target());
            if (t != null) t.add(e.source());
        }
        return nb;
    }

    /** canonical-v1 (D-4 Decision 3): score descending, ties by id (UTF-16 code units), then label. TS twin: {@code compareCanonicalV1}. */
    static final Comparator<Score> BY_SCORE_THEN_ID_THEN_LABEL =
            Comparator.comparingDouble(Score::score).reversed().thenComparing(Score::id).thenComparing(Score::label);

    /** Descending by score, ties by label; stable, like the browser's {@code Array.sort}. */
    static List<Score> scored(Graph g, Map<String, ? extends Number> score) {
        List<Score> out = new ArrayList<>();
        for (Node n : g.nodes()) {
            Number s = score.get(n.id());
            out.add(new Score(n.id(), n.label(), s == null ? 0 : s.doubleValue()));
        }
        out.sort(BY_SCORE_THEN_ID_THEN_LABEL);
        return out;
    }

    /** TS {@code shortestPath}: BFS, endpoints included, {@code null} when disconnected. */
    public static Selection shortestPath(Graph g, String fromId, String toId, Direction direction) {
        if (fromId.equals(toId)) return new Selection(List.of(fromId), List.of());
        Adjacency adj = adjacency(g);
        if (!adj.out().containsKey(fromId) || !adj.out().containsKey(toId)) return null;
        Map<String, Hop> prev = new LinkedHashMap<>(); // node -> (predecessor, edge reaching the node)
        List<String> queue = new ArrayList<>(List.of(fromId));
        Set<String> seen = new HashSet<>(Set.of(fromId));
        for (int head = 0; head < queue.size(); head++) {
            String cur = queue.get(head);
            for (Hop h : neighborsOf(adj, cur, direction)) {
                if (!seen.add(h.node())) continue;
                prev.put(h.node(), new Hop(cur, h.edge()));
                if (h.node().equals(toId)) {
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
                queue.add(h.node());
            }
        }
        return null;
    }

    /** TS {@code neighborhood}: the N-hop subgraph around a node (root included; edges within the kept set). */
    public static Graph neighborhood(Graph g, String nodeId, int hops, Direction direction) {
        Adjacency adj = adjacency(g);
        Set<String> keep = new HashSet<>(Set.of(nodeId));
        List<String> frontier = List.of(nodeId);
        for (int h = 0; h < hops && !frontier.isEmpty(); h++) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                for (Hop nb : neighborsOf(adj, id, direction)) {
                    if (keep.add(nb.node())) next.add(nb.node());
                }
            }
            frontier = next;
        }
        return new Graph(
                g.nodes().stream().filter(n -> keep.contains(n.id())).toList(),
                g.edges().stream().filter(e -> keep.contains(e.source()) && keep.contains(e.target())).toList());
    }

    /** TS {@code degreeCentrality}: in + out, a self-loop counting twice, descending. */
    public static List<Score> degreeCentrality(Graph g) {
        Map<String, Integer> deg = new LinkedHashMap<>();
        for (Edge e : g.edges()) {
            deg.merge(e.source(), 1, Integer::sum);
            deg.merge(e.target(), 1, Integer::sum);
        }
        return scored(g, deg);
    }

    /** TS {@code connectedComponents}: undirected, each as a BFS-ordered node-id list, largest first (stable). */
    public static List<List<String>> connectedComponents(Graph g) {
        Adjacency adj = adjacency(g);
        Set<String> seen = new HashSet<>();
        List<List<String>> comps = new ArrayList<>();
        for (Node n : g.nodes()) {
            if (seen.contains(n.id())) continue;
            List<String> comp = new ArrayList<>();
            List<String> queue = new ArrayList<>(List.of(n.id()));
            seen.add(n.id());
            for (int head = 0; head < queue.size(); head++) {
                String cur = queue.get(head);
                comp.add(cur);
                for (Hop nb : neighborsOf(adj, cur, Direction.BOTH)) {
                    if (seen.add(nb.node())) queue.add(nb.node());
                }
            }
            comps.add(comp);
        }
        comps.sort(Comparator.comparingInt((List<String> c) -> c.size()).reversed());
        return comps;
    }

    /** TS {@code kCore}: each node's core number on the undirected simple graph, descending. */
    public static List<Score> kCore(Graph g) {
        Map<String, Set<String>> nb = undirectedNeighbors(g);
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
        return scored(g, core);
    }

    /** TS {@code triangleCount}: triangles each node participates in, on the undirected simple graph, descending. */
    public static List<Score> triangleCount(Graph g) {
        Map<String, Set<String>> nb = undirectedNeighbors(g);
        Map<String, Integer> tri = new LinkedHashMap<>();
        for (Node n : g.nodes()) tri.put(n.id(), 0);
        for (Map.Entry<String, Set<String>> en : nb.entrySet()) {
            List<String> arr = new ArrayList<>(en.getValue());
            int count = 0;
            for (int i = 0; i < arr.size(); i++) {
                for (int j = i + 1; j < arr.size(); j++) {
                    if (nb.getOrDefault(arr.get(i), Set.of()).contains(arr.get(j))) count++;
                }
            }
            tri.put(en.getKey(), count);
        }
        return scored(g, tri);
    }
}

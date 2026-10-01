package com.gamma.geolink;

import com.gamma.geolink.GraphAlgorithms.Edge;
import com.gamma.geolink.GraphAlgorithms.Graph;
import com.gamma.geolink.GraphAlgorithms.Hop;
import com.gamma.geolink.GraphAlgorithms.Node;
import com.gamma.geolink.GraphAlgorithms.Selection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side graph STRUCTURE algorithms — a line-for-line port of the matching functions in
 * {@code inspecto-ui/src/app/inspecto/graph/graph-analysis.ts} (option D, spike D-S4, structure lane). Keep the two in
 * step: {@code GraphStructureParityTest} and {@code graph-structure-parity.spec.ts} assert the SAME hand-derived fixture
 * ({@code inspecto-ui/src/app/inspecto/graph/graph-structure-parity.fixture.json}).
 *
 * <p>Same deliberate differences as {@link GraphAlgorithms}: no node cap ({@code cliques} does not throw), ordinal
 * string compares where the browser uses {@code localeCompare} (they agree on lowercase ASCII), and every algorithm
 * assumes a closed graph.
 */
public final class GraphStructure {

    private GraphStructure() {}

    /** TS {@code biconnected} result. */
    private record Biconnected(Set<String> articulation, Set<String> bridges) {}

    /** One DFS frame: the node, the edge it was entered by, and how many of its neighbours were visited. */
    private static final class Frame {
        final String node;
        final String parentEdge;
        int iter = 0;

        Frame(String node, String parentEdge) {
            this.node = node;
            this.parentEdge = parentEdge;
        }
    }

    private static Biconnected biconnected(Graph g) {
        Map<String, List<Hop>> und = new LinkedHashMap<>();
        for (Node n : g.nodes()) und.put(n.id(), new ArrayList<>());
        for (Edge e : g.edges()) {
            if (e.source().equals(e.target())) continue; // self-loops are irrelevant to cut points
            List<Hop> s = und.get(e.source());
            if (s != null) s.add(new Hop(e.target(), e.id()));
            List<Hop> t = und.get(e.target());
            if (t != null) t.add(new Hop(e.source(), e.id()));
        }
        Map<String, Integer> disc = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Set<String> articulation = new LinkedHashSet<>();
        Set<String> bridges = new LinkedHashSet<>();
        int timer = 0;
        for (Node root : g.nodes()) {
            if (disc.containsKey(root.id())) continue;
            List<Frame> stack = new ArrayList<>();
            stack.add(new Frame(root.id(), null));
            disc.put(root.id(), timer);
            low.put(root.id(), timer);
            timer++;
            int rootChildren = 0;
            while (!stack.isEmpty()) {
                Frame frame = stack.get(stack.size() - 1);
                List<Hop> nbrs = und.getOrDefault(frame.node, List.of());
                if (frame.iter < nbrs.size()) {
                    Hop h = nbrs.get(frame.iter);
                    String next = h.node();
                    String edgeId = h.edge();
                    frame.iter++;
                    if (edgeId.equals(frame.parentEdge)) continue;
                    if (disc.containsKey(next)) {
                        low.put(frame.node, Math.min(low.get(frame.node), disc.get(next)));
                    } else {
                        disc.put(next, timer);
                        low.put(next, timer);
                        timer++;
                        if (frame.parentEdge == null) rootChildren++;
                        stack.add(new Frame(next, edgeId));
                    }
                } else {
                    stack.remove(stack.size() - 1);
                    Frame parent = stack.isEmpty() ? null : stack.get(stack.size() - 1);
                    if (parent != null) {
                        low.put(parent.node, Math.min(low.get(parent.node), low.get(frame.node)));
                        if (low.get(frame.node) > disc.get(parent.node)) bridges.add(frame.parentEdge);
                        if (parent.parentEdge != null && low.get(frame.node) >= disc.get(parent.node)) {
                            articulation.add(parent.node);
                        }
                    }
                }
            }
            if (rootChildren > 1) articulation.add(root.id());
        }
        return new Biconnected(articulation, bridges);
    }

    /** TS {@code articulationPoints}: nodes whose removal increases the component count, sorted. */
    public static List<String> articulationPoints(Graph g) {
        return biconnected(g).articulation().stream().sorted().toList();
    }

    /** TS {@code bridges}: edge ids whose removal increases the component count, sorted. */
    public static List<String> bridges(Graph g) {
        return biconnected(g).bridges().stream().sorted().toList();
    }

    /** TS {@code isForest}: every node has at most one parent and there is no cycle (Kahn's peel); empty graph is false. */
    public static boolean isForest(Graph g) {
        if (g.nodes().isEmpty()) return false;
        Map<String, Integer> indeg = new LinkedHashMap<>();
        Map<String, List<String>> out = new HashMap<>();
        for (Node n : g.nodes()) {
            indeg.put(n.id(), 0);
            out.put(n.id(), new ArrayList<>());
        }
        for (Edge e : g.edges()) {
            if (!indeg.containsKey(e.source()) || !indeg.containsKey(e.target())) continue;
            indeg.put(e.target(), indeg.get(e.target()) + 1);
            out.get(e.source()).add(e.target());
        }
        if (indeg.values().stream().anyMatch(d -> d > 1)) return false; // a 2-parent node isn't a tree
        List<String> queue = new ArrayList<>();
        indeg.forEach((id, d) -> {
            if (d == 0) queue.add(id);
        });
        Set<String> seen = new HashSet<>();
        for (int head = 0; head < queue.size(); head++) {
            String id = queue.get(head);
            if (seen.contains(id)) continue;
            seen.add(id);
            for (String t : out.getOrDefault(id, List.of())) {
                indeg.put(t, indeg.get(t) - 1);
                if (indeg.get(t) == 0) queue.add(t);
            }
        }
        return seen.size() == g.nodes().size(); // leftover => a cycle
    }

    /** TS {@code descendants}: all nodes strictly downstream of {@code rootId}, BFS order, the root excluded. */
    public static Set<String> descendants(Graph g, String rootId) {
        Map<String, List<String>> out = new HashMap<>();
        for (Edge e : g.edges()) out.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
        Set<String> seen = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(out.getOrDefault(rootId, List.of()));
        for (int head = 0; head < queue.size(); head++) {
            String id = queue.get(head);
            if (id.equals(rootId) || seen.contains(id)) continue;
            seen.add(id);
            queue.addAll(out.getOrDefault(id, List.of()));
        }
        return seen;
    }

    /** TS {@code findCycles}: every simple directed cycle (canonical start = smallest member), up to limit, each at most maxLen hops. */
    public static List<Selection> findCycles(Graph g, int limit, int maxLen) {
        GraphAlgorithms.Adjacency adj = GraphAlgorithms.adjacency(g);
        List<Selection> results = new ArrayList<>();
        List<String> nodePath = new ArrayList<>();
        List<String> edgePath = new ArrayList<>();
        Set<String> onPath = new HashSet<>();
        for (Node n : g.nodes()) {
            if (results.size() >= limit) break;
            onPath.add(n.id());
            nodePath.add(n.id());
            walkCycles(adj, n.id(), n.id(), limit, maxLen, results, nodePath, edgePath, onPath);
            onPath.remove(n.id());
            nodePath.remove(nodePath.size() - 1);
        }
        return results;
    }

    /** TS {@code findCycles} with its defaults: limit 50, maxLen 8. */
    public static List<Selection> findCycles(Graph g) {
        return findCycles(g, 50, 8);
    }

    private static void walkCycles(GraphAlgorithms.Adjacency adj, String start, String cur, int limit, int maxLen,
                                   List<Selection> results, List<String> nodePath, List<String> edgePath,
                                   Set<String> onPath) {
        for (Hop h : adj.out().getOrDefault(cur, List.of())) {
            String next = h.node();
            if (results.size() >= limit) return;
            if (next.equals(start)) {
                List<String> edges = new ArrayList<>(edgePath);
                edges.add(h.edge());
                results.add(new Selection(new ArrayList<>(nodePath), edges));
                continue;
            }
            if (next.compareTo(start) < 0 || onPath.contains(next) || nodePath.size() >= maxLen) continue;
            onPath.add(next);
            nodePath.add(next);
            edgePath.add(h.edge());
            walkCycles(adj, start, next, limit, maxLen, results, nodePath, edgePath, onPath);
            onPath.remove(next);
            nodePath.remove(nodePath.size() - 1);
            edgePath.remove(edgePath.size() - 1);
        }
    }

    /** TS {@code cliques}: maximal cliques of size >= minSize (Bron-Kerbosch with pivoting), largest first. */
    public static List<List<String>> cliques(Graph g, int minSize) {
        Map<String, Set<String>> nb = GraphAlgorithms.undirectedNeighbors(g);
        List<List<String>> found = new ArrayList<>();
        bronKerbosch(nb, minSize, new LinkedHashSet<>(), new LinkedHashSet<>(nb.keySet()), new LinkedHashSet<>(), found);
        found.sort(Comparator.comparingInt((List<String> c) -> c.size()).reversed().thenComparing(c -> c.get(0)));
        return found;
    }

    /** TS {@code cliques} with its default minSize 3. */
    public static List<List<String>> cliques(Graph g) {
        return cliques(g, 3);
    }

    private static void bronKerbosch(Map<String, Set<String>> nb, int minSize, Set<String> r, Set<String> p,
                                     Set<String> x, List<List<String>> found) {
        if (p.isEmpty() && x.isEmpty()) {
            if (r.size() >= minSize) found.add(r.stream().sorted().toList());
            return;
        }
        // Pivot = the vertex in P∪X with the most neighbors in P.
        String pivot = null;
        int bestDeg = -1;
        List<String> px = new ArrayList<>(p);
        px.addAll(x);
        for (String u : px) {
            int d = 0;
            for (String w : nb.getOrDefault(u, Set.of())) if (p.contains(w)) d++;
            if (d > bestDeg) {
                bestDeg = d;
                pivot = u;
            }
        }
        Set<String> pivotNb = pivot == null ? null : nb.get(pivot);
        List<String> candidates = new ArrayList<>();
        for (String v : p) if (!(pivotNb != null && pivotNb.contains(v))) candidates.add(v);
        for (String v : candidates) {
            Set<String> vn = nb.getOrDefault(v, Set.of());
            Set<String> r2 = new LinkedHashSet<>(r);
            r2.add(v);
            Set<String> p2 = new LinkedHashSet<>();
            for (String w : p) if (vn.contains(w)) p2.add(w);
            Set<String> x2 = new LinkedHashSet<>();
            for (String w : x) if (vn.contains(w)) x2.add(w);
            bronKerbosch(nb, minSize, r2, p2, x2, found);
            p.remove(v);
            x.add(v);
        }
    }
}

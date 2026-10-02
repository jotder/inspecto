package com.gamma.la.storage;

import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphInput;
import com.gamma.la.core.GraphResult;
import com.gamma.la.core.InMemoryGraphEngine;
import com.gamma.la.core.IndexCapExceeded;
import com.gamma.la.core.InvalidGraphRequest;
import com.gamma.la.core.LinkIds;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-3 step 7 - the index engine answers what the in-memory engine answers over the SAME graph. The graph is a VALUES relation with
 * parallel rows, a second kind on one pair, a NULL kind, a self-loop, a cycle, a mutual pair, NULL endpoints (never indexed) and ids
 * that differ only by case or a trailing space (no normalisation on either side). The in-memory side is built from the same rows the
 * way the Working Set adapter builds a graph (distinct {@code (source, target, kind)}, id {@link LinkIds#encode}, the literal
 * {@code "null"} for a NULL kind), then ordered canonically (nodes and edges by id) because that is the order the index engine returns.
 */
class SqlGraphEngineParityTest {

    private static final String[][] ROWS = {
            {"A", "B", "call"}, {"A", "B", "call"}, {"A", "B", "sms"},          // parallel rows + a second kind on the pair
            {"A", "C", null}, {"C", "A", "call"},                              // NULL kind, mutual pair
            {"B", "B", "call"},                                                // self-loop
            {"D", "A", "call"}, {"C", "D", "call"},                            // cycle A>C>D>A
            {"D", "E2", "call"},
            {"E", null, "call"}, {null, "F", "call"},                          // NULL endpoints: not indexed
            {"X y", "A ", "call"}, {"a", "A", "call"},                         // ids differing by a space / by case
    };

    private static String relation() {
        StringBuilder sb = new StringBuilder("SELECT * FROM (VALUES ");
        for (int i = 0; i < ROWS.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('(').append(lit(ROWS[i][0])).append(',').append(lit(ROWS[i][1])).append(',').append(lit(ROWS[i][2]))
                    .append(", TIMESTAMP '2026-01-01 10:00:00', 1.0)");
        }
        return sb.append(") AS v(s,t,k,ts,w)").toString();
    }

    private static String lit(String s) {
        return s == null ? "CAST(NULL AS VARCHAR)" : "'" + s + "'";
    }

    private static IndexBuilder.Result build(Path root, String rel) {
        IndexMapping m = new IndexMapping("s", "t", "k", "ts", null, "w", List.of());
        return IndexBuilder.build(new IndexBuilder.Request("g", m, rel, new IndexStore(root, "g", m.hash()), "fp", null));
    }

    /** The graph a Working Set over the same rows would give, in canonical order. */
    private static GraphInput.Materialised materialised(String[][] rows) {
        TreeSet<String> ids = new TreeSet<>();
        TreeMap<String, GraphInput.Edge> edges = new TreeMap<>();
        for (String[] r : rows) {
            if (r[0] == null || r[1] == null) continue;
            ids.add(r[0]);
            ids.add(r[1]);
            String id = LinkIds.encode(r[0], r[1], String.valueOf(r[2]));
            edges.put(id, new GraphInput.Edge(id, r[0], r[1]));
        }
        List<GraphInput.Node> nodes = new ArrayList<>();
        for (String id : ids) nodes.add(new GraphInput.Node(id, id));
        return GraphInput.of(nodes, new ArrayList<>(edges.values()), Map.of());
    }

    private static final InMemoryGraphEngine MEMORY = new InMemoryGraphEngine();
    private static final SqlGraphEngine INDEX = new SqlGraphEngine(SqlSandboxPolicy.defaultPolicy());

    private static GraphInput.IndexRef ref(IndexBuilder.Result built, List<String> seeds, List<String> kinds) {
        return new GraphInput.IndexRef(built.directory(), built.manifest().version(), "g:test", seeds, kinds, 0, 0);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void neighbourhoodAndEgoNetworkEqualTheInMemoryEngineInEveryDirection(@TempDir Path tmp) {
        IndexBuilder.Result built = build(tmp, relation());
        GraphInput.Materialised mem = materialised(ROWS);
        int compared = 0;
        for (String node : List.of("A", "B", "C", "D", "E2", "X y", "A ", "a", "E", "F", "nobody")) {
            for (String dir : List.of("out", "in", "both")) {
                for (int hops : List.of(0, 1, 2)) {
                    GraphResult want = MEMORY.run(Algorithm.NEIGHBORHOOD, params("node", node, "hops", hops, "direction", dir), mem, null);
                    GraphResult got = INDEX.run(Algorithm.NEIGHBORHOOD, params("node", node, "hops", hops, "direction", dir),
                            ref(built, List.of(node), List.of()), null);
                    assertEquals(want.payload(), got.payload(), "neighborhood " + node + " hops " + hops + " " + dir);
                    compared++;
                }
                GraphResult want = MEMORY.run(Algorithm.EGO_NETWORK, params("node", node, "direction", dir), mem, null);
                GraphResult got = INDEX.run(Algorithm.EGO_NETWORK, params("node", node, "direction", dir), ref(built, List.of(node), List.of()), null);
                assertEquals(want.payload(), got.payload(), "ego " + node + " " + dir);
                compared++;
            }
        }
        assertEquals(11 * 3 * 4, compared);
        // droppedDangling is 0 on the index: an index edge has both endpoints by construction (the NULL-endpoint rows were never indexed)
        assertEquals(0, INDEX.run(Algorithm.EGO_NETWORK, params("node", "A"), ref(built, List.of("A"), List.of()), null).dropped());
        // the planted shape is really in the answer: the self-loop, both kinds of the parallel pair, the NULL-kind link
        GraphResult.SubGraph ego = (GraphResult.SubGraph) INDEX.run(Algorithm.EGO_NETWORK, params("node", "B", "direction", "both"),
                ref(built, List.of("B"), List.of()), null).payload();
        assertTrue(ego.graph().edges().stream().anyMatch(e -> e.source().equals("B") && e.target().equals("B")), "self-loop");
        assertEquals(1, ego.graph().edges().stream().filter(e -> e.id().equals(LinkIds.encode("A", "B", "sms"))).count());
        assertTrue(INDEX.run(Algorithm.EGO_NETWORK, params("node", "A", "direction", "out"), ref(built, List.of("A"), List.of()), null).payload()
                instanceof GraphResult.SubGraph sg && sg.graph().edges().stream().anyMatch(e -> e.id().equals(LinkIds.encode("A", "C", "null"))), "NULL kind is the literal null");
    }

    @Test
    void theKindListKeepsLinksByTheSameStringTestTheWorkingSetAdapterUses(@TempDir Path tmp) {
        IndexBuilder.Result built = build(tmp, relation());
        for (List<String> kinds : List.of(List.of("call"), List.of("sms"), List.of("null"), List.of("call", "null"))) {
            List<String[]> kept = new ArrayList<>();
            for (String[] r : ROWS) if (kinds.contains(String.valueOf(r[2]))) kept.add(r);
            // the Working Set keeps every entity as a node and filters the LINKS; the index knows only nodes that have a row
            GraphInput.Materialised all = materialised(ROWS);
            GraphInput.Materialised filtered = new GraphInput.Materialised(all.nodes(), materialised(kept.toArray(new String[0][])).edges(), Map.of(), 0);
            for (String dir : List.of("out", "in", "both")) {
                GraphResult want = MEMORY.run(Algorithm.NEIGHBORHOOD, params("node", "A", "hops", 2, "direction", dir), filtered, null);
                GraphResult got = INDEX.run(Algorithm.NEIGHBORHOOD, params("node", "A", "hops", 2, "direction", dir), ref(built, List.of("A"), kinds), null);
                assertEquals(want.payload(), got.payload(), "kinds " + kinds + " " + dir);
            }
        }
    }

    @Test
    void degreeCentralityFromTheIndexScoresTheSeedsOnlyWithTheInMemoryFigures(@TempDir Path tmp) {
        IndexBuilder.Result built = build(tmp, relation());
        GraphInput.Materialised mem = materialised(ROWS);
        List<Score> everyone = ((GraphResult.Scores) MEMORY.run(Algorithm.DEGREE_CENTRALITY, Map.of(), mem, null).payload()).scores();
        for (List<String> seeds : List.of(List.of("A"), List.of("B", "A", "C"), List.of("B", "nobody", "E"), List.of("X y", "A ", "a", "D", "E2"))) {
            List<Score> want = everyone.stream().filter(s -> seeds.contains(s.id())).toList();      // the same order: the comparator is total
            List<Score> got = ((GraphResult.Scores) INDEX.run(Algorithm.DEGREE_CENTRALITY, Map.of(), ref(built, seeds, List.of()), null).payload()).scores();
            assertEquals(want, got, "seeds " + seeds);
        }
        // seeds only: asking for one node never returns another
        assertEquals(1, ((GraphResult.Scores) INDEX.run(Algorithm.DEGREE_CENTRALITY, Map.of(), ref(built, List.of("A"), List.of()), null).payload()).scores().size());
        // a self-loop counts twice (B: A>B call, A>B sms, B>B as source and target)
        assertEquals(4.0, ((GraphResult.Scores) INDEX.run(Algorithm.DEGREE_CENTRALITY, Map.of(), ref(built, List.of("B"), List.of()), null).payload()).scores().get(0).score());
    }

    @Test
    void theCapsRefuseAndNameThemAndNothingIsRerouted(@TempDir Path tmp) {
        // a hub with 25 distinct neighbours, and a node whose first level has 21 keys
        StringBuilder sb = new StringBuilder("SELECT * FROM (VALUES ");
        for (int i = 0; i < 25; i++) sb.append(i > 0 ? "," : "").append("('hub','n").append(String.format("%02d", i)).append("','call', TIMESTAMP '2026-01-01 10:00:00', 1.0)");
        sb.append(",('p','q','call', TIMESTAMP '2026-01-01 10:00:00', 1.0)");
        String rel = sb.append(") AS v(s,t,k,ts,w)").toString();
        IndexBuilder.Result built = build(tmp, rel);
        GraphInput.IndexRef hub = ref(built, List.of("hub"), List.of());

        // the walk's first level is ONE key and is served; the pass that collects the edges among the 26 kept nodes looks up 26 keys - over the cap
        IndexCapExceeded cap = assertThrows(IndexCapExceeded.class,
                () -> INDEX.run(Algorithm.EGO_NETWORK, params("node", "hub"), hub, null));
        assertEquals(SqlGraphEngine.FRONTIER_CAP, cap.ceiling());
        assertEquals(26, cap.reached());
        assertTrue(cap.getMessage().contains("exceeds the cap of 20"), cap.getMessage());
        // two hops from a leaf reach the hub (level 2 holds 1 key) and then its 25 neighbours - the induced pass is over the cap too
        assertThrows(IndexCapExceeded.class, () -> INDEX.run(Algorithm.NEIGHBORHOOD, params("node", "n00", "hops", 2, "direction", "both"),
                ref(built, List.of("n00"), List.of()), null));
        // a small neighbourhood next to it is served
        assertEquals(2, ((GraphResult.SubGraph) INDEX.run(Algorithm.NEIGHBORHOOD, params("node", "p", "hops", 2), ref(built, List.of("p"), List.of()), null)
                .payload()).graph().nodes().size());
        // 21 seeds are over the cap for a degree ranking
        List<String> seeds = new ArrayList<>();
        for (int i = 0; i < 21; i++) seeds.add("n" + String.format("%02d", i));
        assertThrows(IndexCapExceeded.class, () -> INDEX.run(Algorithm.DEGREE_CENTRALITY, Map.of(), ref(built, seeds, List.of()), null));
        // depth: 3 hops is refused as a bad request naming the cap, before any read
        InvalidGraphRequest deep = assertThrows(InvalidGraphRequest.class,
                () -> INDEX.run(Algorithm.NEIGHBORHOOD, params("node", "p", "hops", 3), ref(built, List.of("p"), List.of()), null));
        assertTrue(deep.getMessage().contains("index cap of 2"), deep.getMessage());
    }

    @Test
    void anAlgorithmTheIndexCannotRunAndAWrongInputAreRefused(@TempDir Path tmp) {
        IndexBuilder.Result built = build(tmp, relation());
        InvalidGraphRequest no = assertThrows(InvalidGraphRequest.class,
                () -> INDEX.run(Algorithm.SHORTEST_PATH, params("from", "A", "to", "B"), ref(built, List.of("A"), List.of()), null));
        assertTrue(no.getMessage().contains("shortestPath"), no.getMessage());
        assertEquals(Set.of(Algorithm.NEIGHBORHOOD, Algorithm.EGO_NETWORK, Algorithm.DEGREE_CENTRALITY), INDEX.supported());
        // the two engines never run the other's input
        assertThrows(IllegalArgumentException.class, () -> INDEX.run(Algorithm.NEIGHBORHOOD, params("node", "A"), materialised(ROWS), null));
        assertThrows(IllegalArgumentException.class,
                () -> MEMORY.run(Algorithm.NEIGHBORHOOD, params("node", "A"), ref(built, List.of("A"), List.of()), null));
    }

    @Test
    void theRoutingEngineDispatchesByInputTypeAndNamesTheEngineThatRan(@TempDir Path tmp) {
        IndexBuilder.Result built = build(tmp, relation());
        RoutingGraphEngine routing = new RoutingGraphEngine(MEMORY, INDEX);
        GraphInput.IndexRef ref = ref(built, List.of("A"), List.of());
        assertEquals("memory", routing.engineIdFor(materialised(ROWS)));
        assertEquals("index", routing.engineIdFor(ref));
        assertEquals("memory", routing.engineId(), "the catalogue's single engine field is unchanged");
        assertEquals(INDEX.run(Algorithm.EGO_NETWORK, params("node", "A"), ref, null).payload(),
                routing.run(Algorithm.EGO_NETWORK, params("node", "A"), ref, null).payload());
        assertInstanceOf(GraphResult.SubGraph.class,
                routing.run(Algorithm.EGO_NETWORK, params("node", "A"), materialised(ROWS), null).payload());
        // an index-run algorithm the index cannot do is still refused by the index engine - the router does not reroute it
        assertThrows(InvalidGraphRequest.class, () -> routing.run(Algorithm.PAGE_RANK, Map.of(), ref, null));
    }
}

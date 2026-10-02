package com.gamma.la.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.la.graph.GraphAlgorithms.Selection;
import com.gamma.la.graph.GraphCentrality.PredictedLink;
import com.gamma.la.graph.GraphPaths;
import com.gamma.la.graph.GraphSuspicion.Suspicion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-4 step 4, engine == la-graph == the browser. Every one of the 28 algorithms is run THROUGH {@link InMemoryGraphEngine}
 * (never the static port) on the graphs of the six parity fixtures {@code graph-*-parity.fixture.json} — the SAME files the
 * TypeScript specs and la-graph's own parity tests read — and asserted against the fixture's hand-derived {@code expected}
 * (exact for discrete answers, the fixture's own tolerance for floats). {@link #everyAlgorithmWasExercised} fails if one of
 * the 28 never went through {@code engine.run} here.
 */
class GraphEngineParityTest {

    private static final Path DIR = Path.of("..", "inspecto-ui", "projects", "link-analysis", "src", "graph");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final InMemoryGraphEngine ENGINE = new InMemoryGraphEngine();
    private static final Set<Algorithm> EXERCISED = EnumSet.noneOf(Algorithm.class);

    private static JsonNode fixture(String name) throws Exception {
        return JSON.readTree(Files.readString(DIR.resolve(name)));
    }

    /** The fixture's node ids are also their labels; an edge is {@code [id, source, target, count?, kind?]}. */
    private static GraphInput graph(JsonNode g) {
        List<GraphInput.Node> nodes = new ArrayList<>();
        g.get("nodes").forEach(n -> nodes.add(n.isObject()
                ? new GraphInput.Node(n.get("id").asText(), n.get("label").asText())
                : new GraphInput.Node(n.asText(), n.asText())));
        List<GraphInput.Edge> edges = new ArrayList<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        g.get("edges").forEach(e -> {
            edges.add(new GraphInput.Edge(e.get(0).asText(), e.get(1).asText(), e.get(2).asText()));
            if (e.size() > 4) weights.put(e.get(0).asText(), GraphPaths.edgeWeight(e.get(3).isNull() ? null : e.get(3).asDouble(), e.get(4).asText()));
        });
        return GraphInput.of(nodes, edges, weights);
    }

    private static GraphResult.Payload run(Algorithm a, GraphInput in, Object... kv) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) params.put((String) kv[i], kv[i + 1]);
        EXERCISED.add(a);
        GraphResult r = ENGINE.run(a, params, in, null);
        assertEquals(a, r.algorithm());
        assertEquals(a.resultKind(), r.payload().kind(), a.id() + " payload kind");
        assertEquals(0, r.dropped());
        return r.payload();
    }

    private static List<Score> scores(GraphResult.Payload p) {
        return ((GraphResult.Scores) p).scores();
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    private static void assertScores(JsonNode expected, List<Score> actual, double tol, String label) {
        assertEquals(expected.size(), actual.size(), label + " size");
        for (int i = 0; i < actual.size(); i++) {
            assertEquals(expected.get(i).get(0).asText(), actual.get(i).id(), label + " id at rank " + i);
            assertEquals(expected.get(i).get(1).asDouble(), actual.get(i).score(), tol, label + " score of " + actual.get(i).id());
        }
    }

    private static void assertPairs(JsonNode expected, Map<String, String> actual, String label) {
        List<String> exp = new ArrayList<>();
        expected.forEach(p -> exp.add(p.get(0).asText() + "=" + p.get(1).asText()));
        List<String> act = new ArrayList<>();
        actual.forEach((k, v) -> act.add(k + "=" + v));
        assertEquals(exp, act, label);
    }

    private static void assertSelection(JsonNode nodeIds, JsonNode edgeIds, GraphResult.Payload p, String label) {
        Selection s = ((GraphResult.OneSelection) p).selection();
        if (nodeIds.isNull()) {
            assertNull(s, label);
        } else {
            assertEquals(strings(nodeIds), s.nodeIds(), label + " nodes");
            assertEquals(strings(edgeIds), s.edgeIds(), label + " edges");
        }
    }

    // ── graph-algorithms ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void algorithmsFixture() throws Exception {
        JsonNode fx = fixture("graph-algorithms-parity.fixture.json");
        GraphInput in = graph(fx.get("graph"));
        JsonNode e = fx.get("expected");
        assertScores(e.get("degree"), scores(run(Algorithm.DEGREE_CENTRALITY, in)), 0.0, "degree");
        assertScores(e.get("kCore"), scores(run(Algorithm.K_CORE, in)), 0.0, "kCore");
        assertScores(e.get("triangles"), scores(run(Algorithm.TRIANGLE_COUNT, in)), 0.0, "triangles");
        List<List<String>> comps = ((GraphResult.Groups) run(Algorithm.CONNECTED_COMPONENTS, in)).groups();
        assertEquals(e.get("components").size(), comps.size());
        for (int i = 0; i < comps.size(); i++) assertEquals(strings(e.get("components").get(i)), comps.get(i));
        assertTrue(e.get("shortestPath").size() >= 5);
        for (JsonNode c : e.get("shortestPath"))
            assertSelection(c.get("nodeIds"), c.get("edgeIds"), run(Algorithm.SHORTEST_PATH, in, "from", c.get("from").asText(),
                    "to", c.get("to").asText(), "direction", c.get("direction").asText()), c.toString());
        for (JsonNode c : e.get("neighborhood")) {
            var r = ((GraphResult.SubGraph) run(Algorithm.NEIGHBORHOOD, in, "node", c.get("node").asText(),
                    "hops", c.get("hops").asInt(), "direction", c.get("direction").asText())).graph();
            assertEquals(strings(c.get("nodeIds")), r.nodes().stream().map(n -> n.id()).toList(), c + " nodes");
            assertEquals(strings(c.get("edgeIds")), r.edges().stream().map(x -> x.id()).toList(), c + " edges");
        }
    }

    /** canonical-v1 (D-4 Decision 3): equal scores rank by id code units, then label — through the engine too. */
    @Test
    void canonicalV1Ties() throws Exception {
        JsonNode c = fixture("graph-algorithms-parity.fixture.json").get("canonicalV1");
        GraphInput in = graph(c.get("graph"));
        assertScores(c.get("expected").get("degree"), scores(run(Algorithm.DEGREE_CENTRALITY, in)), 0.0, "canonical degree");
        assertScores(c.get("expected").get("kCore"), scores(run(Algorithm.K_CORE, in)), 0.0, "canonical kCore");
    }

    // ── graph-centrality ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void centralityFixture() throws Exception {
        JsonNode fx = fixture("graph-centrality-parity.fixture.json");
        GraphInput in = graph(fx.get("graph"));
        double tol = fx.get("tolerance").asDouble();
        JsonNode e = fx.get("expected");
        assertScores(e.get("betweenness"), scores(run(Algorithm.BETWEENNESS_CENTRALITY, in)), tol, "betweenness");
        assertScores(e.get("closeness"), scores(run(Algorithm.CLOSENESS_CENTRALITY, in)), tol, "closeness");
        assertTrue(e.get("jaccard").size() >= 3);
        for (JsonNode c : e.get("jaccard"))
            assertScores(c.get("scores"), scores(run(Algorithm.JACCARD_SIMILARITY, in, "node", c.get("node").asText())), tol,
                    "jaccard " + c.get("node").asText());
        assertTrue(e.get("linkPrediction").size() >= 3);
        for (JsonNode c : e.get("linkPrediction")) {
            int limit = c.get("limit").isNull() ? 20 : c.get("limit").asInt();
            List<PredictedLink> actual = ((GraphResult.Links) run(Algorithm.LINK_PREDICTION, in, "method", c.get("method").asText(),
                    "limit", limit)).links();
            JsonNode exp = c.get("links");
            String label = c.get("method").asText() + " limit " + limit;
            assertEquals(exp.size(), actual.size(), label + " size");
            for (int i = 0; i < actual.size(); i++) {
                assertEquals(exp.get(i).get(0).asText(), actual.get(i).source(), label + " source at " + i);
                assertEquals(exp.get(i).get(1).asText(), actual.get(i).target(), label + " target at " + i);
                assertEquals(exp.get(i).get(2).asDouble(), actual.get(i).score(), tol, label + " score at " + i);
            }
        }
    }

    // ── graph-iterative ─────────────────────────────────────────────────────────────────────────────────────────────

    private static final List<String> ITERATIVE_GRAPHS = List.of("main", "cliques", "hitsGraph", "edgeless", "empty",
            "louvainRand0", "louvainRand296");

    private static int eachGraph(JsonNode fx, String key, BiConsumer<GraphInput, JsonNode> check) {
        int n = 0;
        for (String name : ITERATIVE_GRAPHS) {
            JsonNode e = fx.get("expected").get(name).get(key);
            if (e == null) continue;
            check.accept(graph(fx.get("graphs").get(name)), e);
            n++;
        }
        return n;
    }

    @Test
    void iterativeFixture() throws Exception {
        JsonNode fx = fixture("graph-iterative-parity.fixture.json");
        double eps = fx.get("epsilon").asDouble();
        assertTrue(eachGraph(fx, "pageRank", (g, e) -> assertScores(e, scores(run(Algorithm.PAGE_RANK, g)), eps, "pageRank")) >= 4);
        assertTrue(eachGraph(fx, "eigenvector", (g, e) -> assertScores(e, scores(run(Algorithm.EIGENVECTOR_CENTRALITY, g)), eps, "eigenvector")) >= 4);
        assertTrue(eachGraph(fx, "katz", (g, e) -> assertScores(e, scores(run(Algorithm.KATZ_CENTRALITY, g)), eps, "katz")) >= 4);
        assertTrue(eachGraph(fx, "hits", (g, e) -> {
            var h = (GraphResult.Hits) run(Algorithm.HITS, g);
            assertScores(e.get("hubs"), h.hubs(), eps, "hubs");
            assertScores(e.get("authorities"), h.authorities(), eps, "authorities");
        }) >= 4);
        assertTrue(eachGraph(fx, "communities", (g, e) -> assertPairs(e,
                ((GraphResult.Communities) run(Algorithm.DETECT_COMMUNITIES, g)).communityOf(), "communities")) >= 4);
        assertTrue(eachGraph(fx, "louvain", (g, e) -> assertPairs(e,
                ((GraphResult.Communities) run(Algorithm.LOUVAIN_COMMUNITIES, g)).communityOf(), "louvain")) >= 4);
    }

    @Test
    void iterativeCustomParameters() throws Exception {
        JsonNode fx = fixture("graph-iterative-parity.fixture.json");
        double eps = fx.get("epsilon").asDouble();
        JsonNode c = fx.get("expected").get("mainCustom");
        GraphInput g = graph(fx.get("graphs").get("main"));
        JsonNode pr = c.get("pageRank");
        assertScores(pr.get("scores"), scores(run(Algorithm.PAGE_RANK, g, "damping", pr.get("damping").asDouble(),
                "iterations", pr.get("iterations").asInt())), eps, "pageRank custom");
        JsonNode kz = c.get("katz");
        assertScores(kz.get("scores"), scores(run(Algorithm.KATZ_CENTRALITY, g, "alpha", kz.get("alpha").asDouble(),
                "beta", kz.get("beta").asDouble(), "iterations", kz.get("iterations").asInt())), eps, "katz custom");
        JsonNode ev = c.get("eigenvector");
        assertScores(ev.get("scores"), scores(run(Algorithm.EIGENVECTOR_CENTRALITY, g, "iterations", ev.get("iterations").asInt())),
                eps, "eigenvector custom");
        JsonNode h = c.get("hits");
        var hr = (GraphResult.Hits) run(Algorithm.HITS, g, "iterations", h.get("iterations").asInt());
        assertScores(h.get("hubs"), hr.hubs(), eps, "hits custom hubs");
        assertScores(h.get("authorities"), hr.authorities(), eps, "hits custom authorities");
        JsonNode cm = c.get("communities");
        assertPairs(cm.get("pairs"), ((GraphResult.Communities) run(Algorithm.DETECT_COMMUNITIES, g, "maxIterations",
                cm.get("maxIterations").asInt())).communityOf(), "communities custom");
    }

    // ── graph-paths ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void pathsFixture() throws Exception {
        JsonNode fx = fixture("graph-paths-parity.fixture.json");
        GraphInput in = graph(fx.get("graph"));
        JsonNode e = fx.get("expected");
        for (JsonNode c : e.get("allPaths")) {
            List<Selection> r = ((GraphResult.Selections) run(Algorithm.ALL_PATHS, in, "from", c.get("from").asText(),
                    "to", c.get("to").asText(), "limit", c.get("limit").asInt(), "maxHops", c.get("maxHops").asInt(),
                    "direction", c.get("direction").asText())).selections();
            JsonNode paths = c.get("paths");
            assertEquals(paths.size(), r.size(), c + " count");
            for (int i = 0; i < r.size(); i++) {
                assertEquals(strings(paths.get(i).get(0)), r.get(i).nodeIds(), c + " nodes #" + i);
                assertEquals(strings(paths.get(i).get(1)), r.get(i).edgeIds(), c + " edges #" + i);
            }
        }
        assertTrue(e.get("egoNetwork").size() >= 5);
        for (JsonNode c : e.get("egoNetwork")) {
            var r = ((GraphResult.SubGraph) run(Algorithm.EGO_NETWORK, in, "node", c.get("node").asText(),
                    "direction", c.get("direction").asText())).graph();
            assertEquals(strings(c.get("nodeIds")), r.nodes().stream().map(n -> n.id()).toList(), c + " nodes");
            assertEquals(strings(c.get("edgeIds")), r.edges().stream().map(x -> x.id()).toList(), c + " edges");
        }
        assertTrue(e.get("weightedShortestPath").size() >= 5);
        for (JsonNode c : e.get("weightedShortestPath"))
            assertSelection(c.get("nodeIds"), c.get("edgeIds"), run(Algorithm.WEIGHTED_SHORTEST_PATH, in, "from", c.get("from").asText(),
                    "to", c.get("to").asText(), "direction", c.get("direction").asText()), c.toString());
        assertTrue(e.get("maxFlow").size() >= 5);
        for (JsonNode c : e.get("maxFlow")) {
            var flow = ((GraphResult.Flow) run(Algorithm.MAX_FLOW, in, "from", c.get("from").asText(), "to", c.get("to").asText())).flow();
            assertEquals(c.get("value").asDouble(), flow.value(), 0.0, c + " value");
            assertEquals(strings(c.get("nodeIds")), flow.minCut().nodeIds(), c + " cut nodes");
            assertEquals(strings(c.get("edgeIds")), flow.minCut().edgeIds(), c + " cut edges");
        }
        JsonNode msf = e.get("maximumSpanningForest");
        assertSelection(msf.get("nodeIds"), msf.get("edgeIds"), run(Algorithm.MAXIMUM_SPANNING_FOREST, in), "msf");
    }

    // ── graph-structure ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void structureFixture() throws Exception {
        JsonNode fx = fixture("graph-structure-parity.fixture.json");
        GraphInput in = graph(fx.get("graph"));
        JsonNode e = fx.get("expected");
        assertEquals(strings(e.get("articulationPoints")), ((GraphResult.Ids) run(Algorithm.ARTICULATION_POINTS, in)).ids());
        assertEquals(strings(e.get("bridges")), ((GraphResult.Ids) run(Algorithm.BRIDGES, in)).ids());
        assertTrue(e.get("isForest").size() >= 7);
        for (JsonNode c : e.get("isForest"))
            assertEquals(c.get("forest").asBoolean(),
                    ((GraphResult.Flag) run(Algorithm.IS_FOREST, c.has("graph") ? graph(c.get("graph")) : in)).value(), c.get("name").asText());
        for (JsonNode c : e.get("descendants"))
            assertEquals(strings(c.get("ids")), ((GraphResult.Ids) run(Algorithm.DESCENDANTS, in, "node", c.get("root").asText())).ids(),
                    "descendants of " + c.get("root").asText());
        assertCycles(in, e.get("cycles"));
        assertCycles(graph(e.get("cyclesTwoInOneWalk").get("graph")), e.get("cyclesTwoInOneWalk").get("cases"));
        for (JsonNode c : e.get("cliques")) {
            JsonNode o = c.get("opts");
            List<List<String>> r = o.has("minSize")
                    ? ((GraphResult.Groups) run(Algorithm.CLIQUES, in, "minSize", o.get("minSize").asInt())).groups()
                    : ((GraphResult.Groups) run(Algorithm.CLIQUES, in)).groups();
            JsonNode exp = c.get("cliques");
            assertEquals(exp.size(), r.size(), c.get("name").asText() + " count");
            for (int i = 0; i < r.size(); i++) assertEquals(strings(exp.get(i)), r.get(i), c.get("name").asText() + " clique " + i);
        }
    }

    private static void assertCycles(GraphInput g, JsonNode cases) {
        for (JsonNode c : cases) {
            JsonNode o = c.get("opts");
            List<Selection> r = ((GraphResult.Selections) run(Algorithm.FIND_CYCLES, g,
                    "limit", o.has("limit") ? o.get("limit").asInt() : 50, "maxLen", o.has("maxLen") ? o.get("maxLen").asInt() : 8)).selections();
            JsonNode exp = c.get("cycles");
            assertEquals(exp.size(), r.size(), c.get("name").asText() + " count");
            for (int i = 0; i < r.size(); i++) {
                assertEquals(strings(exp.get(i).get("nodeIds")), r.get(i).nodeIds(), c.get("name").asText() + " nodes " + i);
                assertEquals(strings(exp.get(i).get("edgeIds")), r.get(i).edgeIds(), c.get("name").asText() + " edges " + i);
            }
        }
    }

    // ── graph-suspicion ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void suspicionFixture() throws Exception {
        JsonNode fx = fixture("graph-suspicion-parity.fixture.json");
        double tol = fx.get("tolerance").asDouble();
        assertTrue(fx.get("cases").size() >= 4);
        for (JsonNode c : fx.get("cases")) {
            JsonNode w = c.get("weights");
            List<Suspicion> actual = ((GraphResult.Suspicions) run(Algorithm.SUSPICION_SCORE, graph(fx.get("graphs").get(c.get("graph").asText())),
                    "degree", w.get("degree").asDouble(), "betweenness", w.get("betweenness").asDouble(), "pageRank", w.get("pageRank").asDouble(),
                    "core", w.get("core").asDouble(), "triangles", w.get("triangles").asDouble())).scores();
            JsonNode exp = c.get("expected");
            String label = c.get("name").asText();
            assertEquals(exp.size(), actual.size(), label + " size");
            for (int i = 0; i < actual.size(); i++) {
                Suspicion s = actual.get(i);
                assertEquals(exp.get(i).get(0).asText(), s.id(), label + " id at rank " + i);
                assertEquals(exp.get(i).get(1).asDouble(), s.score(), tol, label + " score of " + s.id());
                JsonNode f = exp.get(i).get(2);
                assertEquals(f.get(0).asDouble(), s.factors().degree(), tol, label + " degree");
                assertEquals(f.get(1).asDouble(), s.factors().betweenness(), tol, label + " betweenness");
                assertEquals(f.get(2).asDouble(), s.factors().pageRank(), tol, label + " pageRank");
                assertEquals(f.get(3).asDouble(), s.factors().core(), tol, label + " core");
                assertEquals(f.get(4).asDouble(), s.factors().triangles(), tol, label + " triangles");
            }
        }
    }

    /** No silent gap: every one of the 28 catalogued algorithms went through {@code engine.run} in this class. */
    @AfterAll
    static void everyAlgorithmWasExercised() {
        Set<Algorithm> missing = EnumSet.allOf(Algorithm.class);
        missing.removeAll(EXERCISED);
        assertTrue(missing.isEmpty(), "never run through the engine by a fixture test: " + missing);
    }
}

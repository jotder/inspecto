package com.gamma.la.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.la.graph.GraphIterative.HitsResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-S4 parity — the server half, tranche A, iterative + communities slice. Runs
 * {@code graph-iterative-parity.fixture.json}, the SAME file {@code graph-iterative-parity.spec.ts} feeds the browser
 * algorithms. Scores are asserted to the fixture's {@code epsilon} (1e-9) in rank order; communities are asserted as
 * EXACT ordered (node, community) pairs — the community id is its smallest member, so label equality and partition
 * equality coincide, and the pair order (members grouped by first-seen label) is part of the browser contract.
 * The fixture's {@code expected} for the float algorithms was computed by an independent Python transcription of the
 * TS (the communities were checked by hand); a graph's key is absent where its result is an ordering coin-flip.
 */
class GraphIterativeParityTest {

    private static final Path FIXTURE = Path.of("..", "inspecto-ui", "src", "app", "inspecto", "graph",
            "graph-iterative-parity.fixture.json");
    private static final List<String> GRAPHS = List.of("main", "cliques", "hitsGraph", "edgeless", "empty",
            "louvainRand0", "louvainRand296");

    private static JsonNode fx;
    private static double eps;

    private static void load() throws Exception {
        if (fx != null) return;
        fx = new ObjectMapper().readTree(Files.readString(FIXTURE));
        eps = fx.get("epsilon").asDouble();
    }

    private static Graph graph(String name) {
        JsonNode g = fx.get("graphs").get(name);
        List<Node> nodes = new ArrayList<>();
        g.get("nodes").forEach(n -> nodes.add(new Node(n.asText(), n.asText())));
        List<Edge> edges = new ArrayList<>();
        g.get("edges").forEach(e -> edges.add(new Edge(e.get(0).asText(), e.get(1).asText(), e.get(2).asText())));
        return new Graph(nodes, edges);
    }

    private static void assertScores(JsonNode expected, List<Score> actual, String label) {
        assertEquals(expected.size(), actual.size(), label + " size");
        for (int i = 0; i < actual.size(); i++) {
            assertEquals(expected.get(i).get(0).asText(), actual.get(i).id(), label + " id at rank " + i);
            assertEquals(expected.get(i).get(1).asDouble(), actual.get(i).score(), eps,
                    label + " score of " + actual.get(i).id());
        }
    }

    private static void assertPairs(JsonNode expected, Map<String, String> actual, String label) {
        List<String> exp = new ArrayList<>();
        expected.forEach(p -> exp.add(p.get(0).asText() + "=" + p.get(1).asText()));
        List<String> act = new ArrayList<>();
        actual.forEach((k, v) -> act.add(k + "=" + v));
        assertEquals(exp, act, label);
    }

    /** Runs {@code check} for every fixture graph that carries {@code key}; returns how many did. */
    private static int eachGraph(String key, java.util.function.BiConsumer<String, JsonNode> check) {
        int n = 0;
        for (String name : GRAPHS) {
            JsonNode e = fx.get("expected").get(name).get(key);
            if (e == null) continue;
            check.accept(name, e);
            n++;
        }
        return n;
    }

    private static void scoresOnEach(String key, Function<Graph, List<Score>> algo) {
        int n = eachGraph(key, (name, e) -> assertScores(e, algo.apply(graph(name)), key + "/" + name));
        assertTrue(n >= 4, key + " covered " + n + " graphs");
    }

    @Test
    void pageRank() throws Exception {
        load();
        scoresOnEach("pageRank", GraphIterative::pageRank);
    }

    @Test
    void eigenvectorCentrality() throws Exception {
        load();
        scoresOnEach("eigenvector", GraphIterative::eigenvectorCentrality);
    }

    @Test
    void katzCentrality() throws Exception {
        load();
        scoresOnEach("katz", GraphIterative::katzCentrality);
    }

    @Test
    void hits() throws Exception {
        load();
        int n = eachGraph("hits", (name, e) -> {
            HitsResult r = GraphIterative.hits(graph(name));
            assertScores(e.get("hubs"), r.hubs(), "hubs/" + name);
            assertScores(e.get("authorities"), r.authorities(), "authorities/" + name);
        });
        assertTrue(n >= 4);
    }

    @Test
    void customParameters() throws Exception {
        load();
        JsonNode c = fx.get("expected").get("mainCustom");
        Graph g = graph("main");
        JsonNode pr = c.get("pageRank");
        assertScores(pr.get("scores"), GraphIterative.pageRank(g, pr.get("damping").asDouble(), pr.get("iterations").asInt()), "pageRank custom");
        JsonNode kz = c.get("katz");
        assertScores(kz.get("scores"), GraphIterative.katzCentrality(g, kz.get("alpha").asDouble(), kz.get("beta").asDouble(),
                kz.get("iterations").asInt()), "katz custom");
        JsonNode ev = c.get("eigenvector");
        assertScores(ev.get("scores"), GraphIterative.eigenvectorCentrality(g, ev.get("iterations").asInt()), "eigenvector custom");
        JsonNode h = c.get("hits");
        HitsResult hr = GraphIterative.hits(g, h.get("iterations").asInt());
        assertScores(h.get("hubs"), hr.hubs(), "hits custom hubs");
        assertScores(h.get("authorities"), hr.authorities(), "hits custom authorities");
        JsonNode cm = c.get("communities");
        assertPairs(cm.get("pairs"), GraphIterative.detectCommunities(g, cm.get("maxIterations").asInt()), "communities custom");
    }

    @Test
    void detectCommunities() throws Exception {
        load();
        int n = eachGraph("communities", (name, e) -> assertPairs(e, GraphIterative.detectCommunities(graph(name)), "communities/" + name));
        assertTrue(n >= 4);
    }

    @Test
    void louvainCommunities() throws Exception {
        load();
        int n = eachGraph("louvain", (name, e) -> assertPairs(e, GraphIterative.louvainCommunities(graph(name)), "louvain/" + name));
        assertTrue(n >= 4);
    }
}

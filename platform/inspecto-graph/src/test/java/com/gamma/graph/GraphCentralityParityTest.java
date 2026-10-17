package com.gamma.la.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.la.graph.GraphCentrality.Method;
import com.gamma.la.graph.GraphCentrality.PredictedLink;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-S4 parity — the server half, single-pass float centrality lane. Runs {@code graph-centrality-parity.fixture.json},
 * the SAME file {@code graph-centrality-parity.spec.ts} feeds the browser algorithms; both assert its hand-derived
 * {@code expected}, floats at the fixture's {@code tolerance}.
 */
class GraphCentralityParityTest {

    private static final Path FIXTURE = Path.of("..", "..", "inspecto-ui", "projects", "link-analysis", "src", "graph",
            "graph-centrality-parity.fixture.json");

    private static JsonNode fx;
    private static Graph graph;
    private static double tol;

    private static void load() throws Exception {
        if (fx != null) return;
        fx = new ObjectMapper().readTree(Files.readString(FIXTURE));
        List<Node> nodes = new ArrayList<>();
        fx.get("graph").get("nodes").forEach(n -> nodes.add(new Node(n.asText(), n.asText())));
        List<Edge> edges = new ArrayList<>();
        fx.get("graph").get("edges").forEach(e -> edges.add(new Edge(e.get(0).asText(), e.get(1).asText(), e.get(2).asText())));
        graph = new Graph(nodes, edges);
        tol = fx.get("tolerance").asDouble();
    }

    private static void assertScores(JsonNode expected, List<Score> actual, String label) {
        assertEquals(expected.size(), actual.size(), label + " size");
        for (int i = 0; i < actual.size(); i++) {
            assertEquals(expected.get(i).get(0).asText(), actual.get(i).id(), label + " id at rank " + i);
            assertEquals(expected.get(i).get(1).asDouble(), actual.get(i).score(), tol, label + " score of " + actual.get(i).id());
        }
    }

    @Test
    void betweennessCentrality() throws Exception {
        load();
        assertScores(fx.get("expected").get("betweenness"), GraphCentrality.betweennessCentrality(graph), "betweenness");
    }

    @Test
    void closenessCentrality() throws Exception {
        load();
        assertScores(fx.get("expected").get("closeness"), GraphCentrality.closenessCentrality(graph), "closeness");
    }

    @Test
    void jaccardSimilarity() throws Exception {
        load();
        JsonNode cases = fx.get("expected").get("jaccard");
        assertTrue(cases.size() >= 3);
        for (JsonNode c : cases) {
            String node = c.get("node").asText();
            assertScores(c.get("scores"), GraphCentrality.jaccardSimilarity(graph, node), "jaccard " + node);
        }
    }

    @Test
    void linkPrediction() throws Exception {
        load();
        JsonNode cases = fx.get("expected").get("linkPrediction");
        assertTrue(cases.size() >= 3);
        for (JsonNode c : cases) {
            Method method = Method.valueOf(c.get("method").asText().toUpperCase().replace('-', '_'));
            int limit = c.get("limit").isNull() ? 20 : c.get("limit").asInt();
            String label = c.get("method").asText() + " limit " + limit;
            List<PredictedLink> actual = GraphCentrality.linkPrediction(graph, method, limit);
            JsonNode expected = c.get("links");
            assertEquals(expected.size(), actual.size(), label + " size");
            for (int i = 0; i < actual.size(); i++) {
                assertEquals(expected.get(i).get(0).asText(), actual.get(i).source(), label + " source at " + i);
                assertEquals(expected.get(i).get(1).asText(), actual.get(i).target(), label + " target at " + i);
                assertEquals(expected.get(i).get(2).asDouble(), actual.get(i).score(), tol, label + " score at " + i);
            }
        }
    }
}

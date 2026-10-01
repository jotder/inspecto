package com.gamma.la.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.la.graph.GraphAlgorithms.Direction;
import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.la.graph.GraphAlgorithms.Selection;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-S4 parity — the server half, tranche A slice 1. Runs {@code graph-algorithms-parity.fixture.json}, the SAME file
 * {@code graph-algorithms-parity.spec.ts} feeds the browser algorithms; both assert its one hand-derived
 * {@code expected}. A change to either implementation that moves a result turns one of the two red.
 */
class GraphAlgorithmsParityTest {

    private static final Path FIXTURE = Path.of("..", "inspecto-ui", "src", "app", "inspecto", "graph",
            "graph-algorithms-parity.fixture.json");

    private static JsonNode fx;
    private static Graph graph;

    private static void load() throws Exception {
        if (fx != null) return;
        fx = new ObjectMapper().readTree(Files.readString(FIXTURE));
        List<Node> nodes = new ArrayList<>();
        fx.get("graph").get("nodes").forEach(n -> nodes.add(new Node(n.asText(), n.asText())));
        List<Edge> edges = new ArrayList<>();
        fx.get("graph").get("edges").forEach(e -> edges.add(new Edge(e.get(0).asText(), e.get(1).asText(), e.get(2).asText())));
        graph = new Graph(nodes, edges);
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    private static void assertScores(JsonNode expected, List<Score> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < actual.size(); i++) {
            assertEquals(expected.get(i).get(0).asText(), actual.get(i).id(), "id at rank " + i);
            assertEquals(expected.get(i).get(1).asDouble(), actual.get(i).score(), 0.0, "score of " + actual.get(i).id());
        }
    }

    private static Direction dir(String s) {
        return Direction.valueOf(s.toUpperCase());
    }

    @Test
    void degreeCentrality() throws Exception {
        load();
        assertScores(fx.get("expected").get("degree"), GraphAlgorithms.degreeCentrality(graph));
    }

    @Test
    void kCore() throws Exception {
        load();
        assertScores(fx.get("expected").get("kCore"), GraphAlgorithms.kCore(graph));
    }

    @Test
    void triangleCount() throws Exception {
        load();
        assertScores(fx.get("expected").get("triangles"), GraphAlgorithms.triangleCount(graph));
    }

    @Test
    void connectedComponents() throws Exception {
        load();
        List<List<String>> actual = GraphAlgorithms.connectedComponents(graph);
        JsonNode expected = fx.get("expected").get("components");
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < actual.size(); i++) assertEquals(strings(expected.get(i)), actual.get(i));
    }

    @Test
    void shortestPath() throws Exception {
        load();
        JsonNode cases = fx.get("expected").get("shortestPath");
        assertTrue(cases.size() >= 5);
        for (JsonNode c : cases) {
            Selection r = GraphAlgorithms.shortestPath(graph, c.get("from").asText(), c.get("to").asText(),
                    dir(c.get("direction").asText()));
            String label = c.get("from").asText() + ">" + c.get("to").asText() + " " + c.get("direction").asText();
            if (c.get("nodeIds").isNull()) {
                assertNull(r, label);
            } else {
                assertEquals(strings(c.get("nodeIds")), r.nodeIds(), label + " nodes");
                assertEquals(strings(c.get("edgeIds")), r.edgeIds(), label + " edges");
            }
        }
    }

    @Test
    void neighborhood() throws Exception {
        load();
        for (JsonNode c : fx.get("expected").get("neighborhood")) {
            Graph r = GraphAlgorithms.neighborhood(graph, c.get("node").asText(), c.get("hops").asInt(),
                    dir(c.get("direction").asText()));
            String label = c.get("node").asText() + " " + c.get("hops").asInt() + " " + c.get("direction").asText();
            assertEquals(strings(c.get("nodeIds")), r.nodes().stream().map(Node::id).toList(), label + " nodes");
            assertEquals(strings(c.get("edgeIds")), r.edges().stream().map(Edge::id).toList(), label + " edges");
        }
    }
}

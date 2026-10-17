package com.gamma.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.graph.GraphAlgorithms.Direction;
import com.gamma.graph.GraphAlgorithms.Edge;
import com.gamma.graph.GraphAlgorithms.Graph;
import com.gamma.graph.GraphAlgorithms.Node;
import com.gamma.graph.GraphAlgorithms.Selection;
import com.gamma.graph.GraphPaths.MaxFlowResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-S4 parity — the server half, paths-and-flow lane. Runs {@code graph-paths-parity.fixture.json}, the SAME file
 * {@code graph-paths-parity.spec.ts} feeds the browser algorithms; both assert its hand-derived {@code expected}.
 */
class GraphPathsParityTest {

    private static final Path FIXTURE = Path.of("..", "..", "inspecto-ui", "projects", "link-analysis", "src", "graph",
            "graph-paths-parity.fixture.json");

    private static JsonNode fx;
    private static Graph graph;
    private static Map<String, Double> weights;

    private static void load() throws Exception {
        if (fx != null) return;
        fx = new ObjectMapper().readTree(Files.readString(FIXTURE));
        List<Node> nodes = new ArrayList<>();
        fx.get("graph").get("nodes").forEach(n -> nodes.add(new Node(n.asText(), n.asText())));
        List<Edge> edges = new ArrayList<>();
        fx.get("graph").get("edges").forEach(e -> edges.add(new Edge(e.get(0).asText(), e.get(1).asText(), e.get(2).asText())));
        // The weights are the fixture's hand-derived expected.edgeWeight table: reading them off the "kind · N" labels is
        // Link Analysis' convention (WorkingSetGraphInput.edgeWeight in inspecto-la-core, pinned by EdgeWeightParityTest
        // against this same table), not this library's (D-AN2).
        Map<String, Double> w = new LinkedHashMap<>();
        fx.get("expected").get("edgeWeight").forEach(x -> w.put(x.get(0).asText(), x.get(1).asDouble()));
        graph = new Graph(nodes, edges);
        weights = w;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    private static Direction dir(String s) {
        return Direction.valueOf(s.toUpperCase());
    }

    @Test
    void allPaths() throws Exception {
        load();
        for (JsonNode c : fx.get("expected").get("allPaths")) {
            List<Selection> r = GraphPaths.allPaths(graph, c.get("from").asText(), c.get("to").asText(),
                    c.get("limit").asInt(), c.get("maxHops").asInt(), dir(c.get("direction").asText()));
            String label = c.get("from").asText() + ">" + c.get("to").asText() + " " + c.get("direction").asText()
                    + " limit " + c.get("limit").asInt() + " maxHops " + c.get("maxHops").asInt();
            JsonNode paths = c.get("paths");
            assertEquals(paths.size(), r.size(), label + " count");
            for (int i = 0; i < r.size(); i++) {
                assertEquals(strings(paths.get(i).get(0)), r.get(i).nodeIds(), label + " nodes #" + i);
                assertEquals(strings(paths.get(i).get(1)), r.get(i).edgeIds(), label + " edges #" + i);
            }
        }
    }

    @Test
    void egoNetwork() throws Exception {
        load();
        JsonNode cases = fx.get("expected").get("egoNetwork");
        assertTrue(cases.size() >= 5);
        for (JsonNode c : cases) {
            Graph r = GraphPaths.egoNetwork(graph, c.get("node").asText(), dir(c.get("direction").asText()));
            String label = c.get("node").asText() + " " + c.get("direction").asText();
            assertEquals(strings(c.get("nodeIds")), r.nodes().stream().map(Node::id).toList(), label + " nodes");
            assertEquals(strings(c.get("edgeIds")), r.edges().stream().map(Edge::id).toList(), label + " edges");
        }
    }

    @Test
    void weightedShortestPath() throws Exception {
        load();
        JsonNode cases = fx.get("expected").get("weightedShortestPath");
        assertTrue(cases.size() >= 5);
        for (JsonNode c : cases) {
            Selection r = GraphPaths.weightedShortestPath(graph, weights, c.get("from").asText(), c.get("to").asText(),
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
    void maxFlow() throws Exception {
        load();
        JsonNode cases = fx.get("expected").get("maxFlow");
        assertTrue(cases.size() >= 5);
        for (JsonNode c : cases) {
            MaxFlowResult r = GraphPaths.maxFlow(graph, weights, c.get("from").asText(), c.get("to").asText());
            String label = c.get("from").asText() + ">" + c.get("to").asText();
            assertEquals(c.get("value").asDouble(), r.value(), 0.0, label + " value");
            assertEquals(strings(c.get("nodeIds")), r.minCut().nodeIds(), label + " cut nodes");
            assertEquals(strings(c.get("edgeIds")), r.minCut().edgeIds(), label + " cut edges");
        }
    }

    @Test
    void maximumSpanningForest() throws Exception {
        load();
        JsonNode expected = fx.get("expected").get("maximumSpanningForest");
        Selection r = GraphPaths.maximumSpanningForest(graph, weights);
        assertEquals(strings(expected.get("nodeIds")), r.nodeIds());
        assertEquals(strings(expected.get("edgeIds")), r.edgeIds());
    }
}

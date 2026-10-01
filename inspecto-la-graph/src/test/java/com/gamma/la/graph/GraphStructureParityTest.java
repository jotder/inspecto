package com.gamma.la.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphAlgorithms.Selection;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-S4 parity — the server half, structure lane. Runs {@code graph-structure-parity.fixture.json}, the SAME file
 * {@code graph-structure-parity.spec.ts} feeds the browser algorithms; both assert its hand-derived {@code expected}.
 */
class GraphStructureParityTest {

    private static final Path FIXTURE = Path.of("..", "inspecto-ui", "src", "app", "inspecto", "graph",
            "graph-structure-parity.fixture.json");

    private static JsonNode fx;
    private static Graph graph;

    private static Graph toGraph(JsonNode g) {
        List<Node> nodes = new ArrayList<>();
        g.get("nodes").forEach(n -> nodes.add(new Node(n.asText(), n.asText())));
        List<Edge> edges = new ArrayList<>();
        g.get("edges").forEach(e -> edges.add(new Edge(e.get(0).asText(), e.get(1).asText(), e.get(2).asText())));
        return new Graph(nodes, edges);
    }

    private static void load() throws Exception {
        if (fx != null) return;
        fx = new ObjectMapper().readTree(Files.readString(FIXTURE));
        graph = toGraph(fx.get("graph"));
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    @Test
    void articulationPoints() throws Exception {
        load();
        assertEquals(strings(fx.get("expected").get("articulationPoints")), GraphStructure.articulationPoints(graph));
    }

    @Test
    void bridges() throws Exception {
        load();
        assertEquals(strings(fx.get("expected").get("bridges")), GraphStructure.bridges(graph));
    }

    @Test
    void isForest() throws Exception {
        load();
        JsonNode cases = fx.get("expected").get("isForest");
        assertTrue(cases.size() >= 7);
        for (JsonNode c : cases) {
            Graph g = c.has("graph") ? toGraph(c.get("graph")) : graph;
            assertEquals(c.get("forest").asBoolean(), GraphStructure.isForest(g), c.get("name").asText());
        }
    }

    @Test
    void descendants() throws Exception {
        load();
        for (JsonNode c : fx.get("expected").get("descendants")) {
            String root = c.get("root").asText();
            assertEquals(strings(c.get("ids")), List.copyOf(GraphStructure.descendants(graph, root)), "descendants of " + root);
        }
    }

    @Test
    void findCycles() throws Exception {
        load();
        for (JsonNode c : fx.get("expected").get("cycles")) {
            JsonNode o = c.get("opts");
            List<Selection> r = GraphStructure.findCycles(graph, o.has("limit") ? o.get("limit").asInt() : 50,
                    o.has("maxLen") ? o.get("maxLen").asInt() : 8);
            String label = c.get("name").asText();
            JsonNode expected = c.get("cycles");
            assertEquals(expected.size(), r.size(), label + " count");
            for (int i = 0; i < r.size(); i++) {
                assertEquals(strings(expected.get(i).get("nodeIds")), r.get(i).nodeIds(), label + " nodes " + i);
                assertEquals(strings(expected.get(i).get("edgeIds")), r.get(i).edgeIds(), label + " edges " + i);
            }
        }
    }

    @Test
    void cliques() throws Exception {
        load();
        for (JsonNode c : fx.get("expected").get("cliques")) {
            JsonNode o = c.get("opts");
            List<List<String>> r = GraphStructure.cliques(graph, o.has("minSize") ? o.get("minSize").asInt() : 3);
            String label = c.get("name").asText();
            JsonNode expected = c.get("cliques");
            assertEquals(expected.size(), r.size(), label + " count");
            for (int i = 0; i < r.size(); i++) assertEquals(strings(expected.get(i)), r.get(i), label + " clique " + i);
        }
    }
}

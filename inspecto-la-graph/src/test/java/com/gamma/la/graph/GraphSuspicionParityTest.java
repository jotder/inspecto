package com.gamma.la.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphSuspicion.Suspicion;
import com.gamma.la.graph.GraphSuspicion.Weights;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-S4 parity — the server half, suspicion score. Runs {@code graph-suspicion-parity.fixture.json}, the SAME file
 * {@code graph-suspicion-parity.spec.ts} feeds the browser; both assert its {@code expected} (score and the five
 * factors, in rank order) at the fixture's {@code tolerance}.
 */
class GraphSuspicionParityTest {

    private static final Path FIXTURE = Path.of("..", "inspecto-ui", "src", "app", "inspecto", "graph",
            "graph-suspicion-parity.fixture.json");

    private static Graph graph(JsonNode g) {
        List<Node> nodes = new ArrayList<>();
        g.get("nodes").forEach(n -> nodes.add(new Node(n.asText(), n.asText())));
        List<Edge> edges = new ArrayList<>();
        g.get("edges").forEach(e -> edges.add(new Edge(e.get(0).asText(), e.get(1).asText(), e.get(2).asText())));
        return new Graph(nodes, edges);
    }

    @Test
    void suspicionScoreMatchesTheFixture() throws Exception {
        JsonNode fx = new ObjectMapper().readTree(Files.readString(FIXTURE));
        double tol = fx.get("tolerance").asDouble();
        assertTrue(fx.get("cases").size() >= 4);
        for (JsonNode c : fx.get("cases")) {
            String label = c.get("name").asText();
            JsonNode w = c.get("weights");
            List<Suspicion> actual = GraphSuspicion.suspicionScore(graph(fx.get("graphs").get(c.get("graph").asText())),
                    new Weights(w.get("degree").asDouble(), w.get("betweenness").asDouble(), w.get("pageRank").asDouble(),
                            w.get("core").asDouble(), w.get("triangles").asDouble()));
            JsonNode expected = c.get("expected");
            assertEquals(expected.size(), actual.size(), label + " size");
            for (int i = 0; i < actual.size(); i++) {
                Suspicion s = actual.get(i);
                JsonNode e = expected.get(i);
                assertEquals(e.get(0).asText(), s.id(), label + " id at rank " + i);
                assertEquals(e.get(1).asDouble(), s.score(), tol, label + " score of " + s.id());
                JsonNode f = e.get(2);
                String at = label + " " + s.id() + " factor ";
                assertEquals(f.get(0).asDouble(), s.factors().degree(), tol, at + "degree");
                assertEquals(f.get(1).asDouble(), s.factors().betweenness(), tol, at + "betweenness");
                assertEquals(f.get(2).asDouble(), s.factors().pageRank(), tol, at + "pageRank");
                assertEquals(f.get(3).asDouble(), s.factors().core(), tol, at + "core");
                assertEquals(f.get(4).asDouble(), s.factors().triangles(), tol, at + "triangles");
            }
        }
    }
}

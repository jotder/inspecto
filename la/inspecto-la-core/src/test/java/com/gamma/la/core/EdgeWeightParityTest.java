package com.gamma.la.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D-S4 parity for the {@code "kind · N"} edge-label weight parse, {@link WorkingSetGraphInput#edgeWeight}: the server half
 * of {@code edgeWeight()} in {@code graph-analysis.ts}. Runs {@code graph-paths-parity.fixture.json}, the SAME file
 * {@code graph-paths-parity.spec.ts} feeds the browser, and asserts its hand-derived {@code expected.edgeWeight} and
 * {@code expected.edgeWeightExtra}. The parse moved here from the graph library (D-AN2), which now takes those weights as
 * a plain edge-id map and reads the same table in {@code GraphPathsParityTest}.
 */
class EdgeWeightParityTest {

    private static final Path FIXTURE = Path.of("..", "..", "inspecto-ui", "projects", "link-analysis", "src", "graph",
            "graph-paths-parity.fixture.json");

    private static Double count(JsonNode n) {
        return n.isNull() ? null : n.asDouble();
    }

    @Test
    void edgeWeightMatchesTheHandDerivedTable() throws Exception {
        JsonNode fx = new ObjectMapper().readTree(Files.readString(FIXTURE));
        JsonNode edges = fx.get("graph").get("edges");
        JsonNode expected = fx.get("expected").get("edgeWeight");
        assertEquals(expected.size(), edges.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode e = edges.get(i);
            String id = expected.get(i).get(0).asText();
            assertEquals(id, e.get(0).asText());
            assertEquals(expected.get(i).get(1).asDouble(), WorkingSetGraphInput.edgeWeight(count(e.get(3)), e.get(4).asText()),
                    0.0, "weight of " + id);
        }
        for (JsonNode c : fx.get("expected").get("edgeWeightExtra")) {
            assertEquals(c.get("weight").asDouble(), WorkingSetGraphInput.edgeWeight(count(c.get("count")), c.get("kind").asText()),
                    0.0, c.get("kind").asText());
        }
    }
}

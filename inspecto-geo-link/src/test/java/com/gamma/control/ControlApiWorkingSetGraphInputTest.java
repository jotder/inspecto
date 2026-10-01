package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphInput;
import com.gamma.la.core.GraphResult;
import com.gamma.la.core.InMemoryGraphEngine;
import com.gamma.la.core.WorkingSetGraphInput;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-4 step 4 — the adapter's one hard requirement, proved against a REAL served Working Set: the edge ids of the
 * {@link GraphInput} built from the {@code entities} and {@code links} rows of {@code GET …/working-set} are EXACTLY the
 * {@code linkId}s that same response carries (D-U9), the weights are the served {@code count}s, and an engine answer names
 * links by those ids.
 */
class ControlApiWorkingSetGraphInputTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001", B = "27820000002", C = "27820000003";
    private static final String CREATE = "{\"id\":\"case-a\",\"purpose\":\"Fraud referral FR-7\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/case-a";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('" + A + "','" + B + "','voice'),('" + A + "','" + C
                            + "','sms'),('" + B + "','" + C + "','voice'),('" + A + "','" + B + "','voice')) AS t(caller,callee,channel)",
                    "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private JsonNode ok(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        HttpResponse<String> r = client.send(b.build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), method + " " + path + " → " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static List<Map<String, Object>> rows(JsonNode ws) {
        return JSON.convertValue(ws.get("rows"), new TypeReference<>() {});
    }

    @Test
    void theEdgeIdsAreTheLinkIdsTheWorkingSetResponseCarries(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE);
            ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}");
            ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}");
            JsonNode entities = ok(c, "GET", INV + "/working-set?of=entities", null);
            JsonNode links = ok(c, "GET", INV + "/working-set?of=links", null);
            assertTrue(links.get("rows").size() >= 2, links.toString());

            GraphInput in = WorkingSetGraphInput.from(rows(entities), rows(links), null);

            Map<String, JsonNode> served = new java.util.LinkedHashMap<>();       // linkId -> its row, as served
            for (JsonNode r : links.get("rows")) served.put(r.get("linkId").asText(), r);
            assertEquals(served.keySet(), in.edges().stream().map(GraphInput.Edge::id).collect(Collectors.toSet()),
                    "every edge id IS a served linkId, and every served link is an edge");
            assertEquals(served.size(), in.edges().size());
            for (GraphInput.Edge e : in.edges()) {
                JsonNode row = served.get(e.id());
                assertEquals(row.get("source").asText(), e.source());
                assertEquals(row.get("target").asText(), e.target());
                assertEquals(row.get("count").asDouble(), in.weights().get(e.id()), 0.0, "the weight is the served count");
            }
            assertTrue(served.values().stream().anyMatch(r -> r.get("count").asInt() == 2), "a folded link has count > 1: " + links);
            assertEquals(entities.get("rows").size(), in.nodes().size());
            assertEquals(Set.of(A, B, C), in.nodes().stream().map(GraphInput.Node::id).collect(Collectors.toSet()));
            assertEquals(0, in.droppedDangling());

            // the kinds filter is applied in the adapter, to those same served rows
            GraphInput voice = WorkingSetGraphInput.from(rows(entities), rows(links), List.of("voice"));
            assertEquals(served.values().stream().filter(r -> "voice".equals(r.get("kind").asText())).count(), voice.edges().size());

            // and an engine answer names links by the served ids
            GraphResult r = new InMemoryGraphEngine().run(Algorithm.SHORTEST_PATH,
                    Map.of("from", A, "to", C, "direction", "out"), in, null);
            var sel = ((GraphResult.OneSelection) r.payload()).selection();
            assertEquals(List.of(A, C), sel.nodeIds(), "the direct A>C link is the shortest path");
            assertTrue(served.keySet().containsAll(sel.edgeIds()), sel.edgeIds() + " must be served linkIds");
        }
    }
}

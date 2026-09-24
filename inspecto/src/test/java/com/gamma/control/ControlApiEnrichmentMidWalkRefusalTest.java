package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ENRICHMENT-MIDWALK-LANES-DISAGREE-1}, over real HTTP. An enrichment is a post-commit Stage-2 job, so
 * a node downstream of one in the authored graph ran differently in the two lanes: the graph lane
 * ({@code PipelineExecutor.execute}) skipped the enrichment and starved everything below it, while the flat
 * lane ({@code PipelineEditable.lower}) dropped the node and fed its downstream from its upstream. Operator
 * decision 2026-09-24: the save refuses it by name ({@code ENRICHMENT_NOT_TERMINAL}). A terminal enrichment
 * and the derived {@code companion} edge (sink → enrichment, display-only) are unaffected.
 */
class ControlApiEnrichmentMidWalkRefusalTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    /** The mini pipeline ({@code mini_etl}) plus a {@code daily} companion, registered from {@code dir} (the write root). */
    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        Files.writeString(dir.resolve("daily_enrich.toon"), """
                name: daily
                transform: SELECT 1
                triggers:
                  on_pipeline: mini_etl
                """);
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", dir.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private ObjectNode raw(Ctx c) throws Exception {
        HttpResponse<String> r = send(c.port, "GET", "/pipelines/mini_etl/graph/raw", null);
        assertEquals(200, r.statusCode(), r.body());
        return (ObjectNode) V1Body.of(r.body());
    }

    /** The {@code data} edge into the persistent sink — the one an author would splice an enrichment into. */
    private static ObjectNode edgeIntoSink(JsonNode g) {
        String sink = null;
        for (JsonNode n : g.get("nodes"))
            if ("sink.persistent".equals(n.path("type").asText()) && n.path("config").has("database"))
                sink = n.get("id").asText();
        for (JsonNode e : g.get("edges"))
            if ("data".equals(e.path("rel").asText()) && e.path("to").asText().equals(sink)) return (ObjectNode) e;
        return null;
    }

    private static void addEnrichment(ObjectNode g, String id) {
        ((ArrayNode) g.get("nodes")).addObject().put("id", id).put("type", "enrichment").put("use", "enrichment/daily");
    }

    private static void addEdge(ObjectNode g, String from, String to) {
        ((ArrayNode) g.get("edges")).addObject().put("from", from).put("rel", "data").put("to", to);
    }

    @Test
    void anEnrichmentFeedingASinkIsRefused422NamingTheNode(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            byte[] before = Files.readAllBytes(dir.resolve("mini_pipeline.toon"));
            ObjectNode g = raw(c);
            ObjectNode into = edgeIntoSink(g);
            assertNotNull(into, "the lifted graph has a data edge into its sink: " + g);
            String upstream = into.get("from").asText(), sink = into.get("to").asText();
            addEnrichment(g, "enr");
            into.put("to", "enr");        // upstream → enr → sink: the enrichment is mid-walk
            addEdge(g, "enr", sink);

            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/mini_etl/graph", M.writeValueAsString(g));

            assertEquals(422, r.statusCode(), r.body());
            JsonNode body = V1Body.envelope(r.body()).get("error").get("details");   // what the canvas reads
            assertFalse(body.path("written").asBoolean(true), r.body());
            JsonNode refusal = null;
            for (JsonNode x : body.withArray("refusals"))
                if ("ENRICHMENT_NOT_TERMINAL".equals(x.path("code").asText())) refusal = x;
            assertNotNull(refusal, "a named refusal, rendered by the canvas next to the node: " + r.body());
            assertEquals("enr", refusal.get("nodeId").asText(), r.body());
            String msg = refusal.get("message").asText();
            assertTrue(msg.contains("'" + sink + "'") && msg.contains("post-commit"), msg);
            assertArrayEquals(before, Files.readAllBytes(dir.resolve("mini_pipeline.toon")),
                    "a refused save writes nothing (upstream was '" + upstream + "')");
        }
    }

    @Test
    void anEnrichmentAsATerminalNodeIsAccepted(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            ObjectNode g = raw(c);
            ObjectNode into = edgeIntoSink(g);
            assertNotNull(into, g.toString());
            addEnrichment(g, "enr");
            addEdge(g, into.get("from").asText(), "enr");   // fed, but feeds nothing

            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/mini_etl/graph", M.writeValueAsString(g));

            assertEquals(200, r.statusCode(), r.body());
            assertFalse(r.body().contains("ENRICHMENT_NOT_TERMINAL"), r.body());
        }
    }

    /** The derived sink → enrichment {@code companion} edge is display-only, not data flow: still saves. */
    @Test
    void theDerivedCompanionEdgeStillSaves200(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            ObjectNode g = raw(c);
            boolean hasCompanion = false;
            for (JsonNode e : g.get("edges"))
                if ("companion".equals(e.path("rel").asText()) && "daily".equals(e.path("to").asText())) hasCompanion = true;
            assertTrue(hasCompanion, "the fixture draws the companion edge: " + g);

            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/mini_etl/graph", M.writeValueAsString(g));

            assertEquals(200, r.statusCode(), r.body());
        }
    }
}

package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GRAPH-RAW-COMPANION-ENRICHMENT-ILLEGAL-EMIT-1}, over real HTTP. {@code GET /graph/raw} draws each
 * {@code *_enrich.toon} companion as a node joined to the persistent sink by a <b>derived, display-only</b>
 * edge ({@code rel: companion, derived: true}). It used to be a {@code data} edge, which
 * {@code PipelineValidator} refuses {@code ILLEGAL_EMIT} — so an untouched open → save, and the candidate dry
 * run, of any Pipeline with a companion answered 422. The save path now drops the derived edge before it
 * parses; a hand-authored {@code data} edge out of the sink is still refused.
 */
class ControlApiPipelineGraphCompanionTest {

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

    /** The mini pipeline ({@code mini_etl}) registered from {@code dir}, which is also the write root. */
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

    private JsonNode raw(Ctx c) throws Exception {
        HttpResponse<String> r = send(c.port, "GET", "/pipelines/mini_etl/graph/raw", null);
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static JsonNode companionEdge(JsonNode graph) {
        for (JsonNode e : graph.get("edges")) if ("daily".equals(e.path("to").asText())) return e;
        return null;
    }

    @Test
    void theCompanionIsDrawnWithADerivedCompanionEdge(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode e = companionEdge(raw(c));
            assertNotNull(e, "the companion stays on the canvas");
            assertEquals("companion", e.get("rel").asText());
            assertTrue(e.get("derived").asBoolean(), "marked derived — display-only, never data flow");
        }
    }

    /** (1) + (4): the untouched round trip saves, and the companion file is not rewritten. */
    @Test
    void anUntouchedOpenThenSaveIs200AndLeavesTheCompanionAlone(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            byte[] before = Files.readAllBytes(dir.resolve("daily_enrich.toon"));
            JsonNode g = raw(c);
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/mini_etl/graph", M.writeValueAsString(g));
            assertEquals(200, r.statusCode(), r.body());
            assertArrayEquals(before, Files.readAllBytes(dir.resolve("daily_enrich.toon")),
                    "a graph save must never rewrite the companion's *_enrich.toon");
            assertTrue(companionEdge(raw(c)) != null, "the derived edge is re-drawn on the next read");
        }
    }

    /** (2): the candidate dry run of the same graph is not refused by the validator. */
    @Test
    void theCandidateDryRunWithACompanionIsNot422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            ObjectNode body = M.createObjectNode();
            body.set("pipeline", raw(c));
            body.putArray("sampleRows").addObject().put("ID", "1").put("AMT", "2.5").put("EVENT_DATE", "2026-09-24");
            HttpResponse<String> r = send(c.port, "POST", "/pipelines/authored/mini_etl/dry-run",
                    M.writeValueAsString(body));
            assertNotEquals(422, r.statusCode(), r.body());
            assertEquals(200, r.statusCode(), r.body());
        }
    }

    /** (3): the rule is not weakened — a REAL sink → enrichment data edge is still ILLEGAL_EMIT. */
    @Test
    void aHandAuthoredSinkDataEdgeIsStillIllegalEmit(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode g = raw(c);
            ObjectNode e = (ObjectNode) companionEdge(g);
            e.put("rel", "data");   // a real data edge, even one still carrying the derived flag
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/mini_etl/graph", M.writeValueAsString(g));
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("ILLEGAL_EMIT"), r.body());
            e.remove("derived");
            r = send(c.port, "PUT", "/pipelines/mini_etl/graph", M.writeValueAsString(g));
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("ILLEGAL_EMIT"), r.body());
        }
    }
}

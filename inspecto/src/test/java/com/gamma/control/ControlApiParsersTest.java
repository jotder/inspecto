package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
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
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-HTTP tests for the parser-catalog routes: {@code GET /parsers} (the self-describing
 * registry — id/label/hierarchical/ingestable + grammar schema) and
 * {@code POST /parsers/&#123;id&#125;/preview} (stateless grammar preview, table or tree).
 * Covers each fail-closed step: bad body 400, unknown id 404, caps 400, caller errors 422,
 * plus both result kinds. The parse mechanics themselves live in {@code ParsersTest} /
 * {@code XmlParserPluginTest} — what can only be checked here is the HTTP contract.
 */
class ControlApiParsersTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg) throws Exception {
        Path toon = TestConfigs.csv(cfg, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void catalogServesTheBuiltinsPlusXmlWithSchemasAndHonestIngestability(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            JsonNode list = json(send(c.port, "GET", "/parsers", null));
            assertEquals(5, list.size(), list.toString());
            assertEquals("delimited", list.get(0).get("id").asText());
            assertEquals("xml", list.get(4).get("id").asText());
            for (JsonNode p : list) {
                assertTrue(p.get("grammarSchema").size() > 0, p.get("id").asText() + " has no schema");
                assertTrue(p.get("grammarSchema").get(0).hasNonNull("path"));
            }
            JsonNode xml = list.get(4);
            assertTrue(xml.get("hierarchical").asBoolean());
            assertFalse(xml.get("ingestable").asBoolean(),
                    "tree data cannot load to Tables before the flatten config");
            assertTrue(list.get(0).get("ingestable").asBoolean());
        }
    }

    @Test
    void builtinPreviewReturnsATable(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            JsonNode r = json(send(c.port, "POST", "/parsers/delimited/preview",
                    "{\"grammar\":{\"delimited\":{\"has_header\":true}},\"sample_text\":\"id,qty\\n1001,3\\n\"}"));
            assertEquals("table", r.get("kind").asText());
            assertEquals("id", r.get("columns").get(0).asText());
            assertEquals(1, r.get("rowCount").asInt());
        }
    }

    @Test
    void xmlPreviewReturnsATreeAndAcceptsBase64Samples(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            String doc = "<orders><order id=\"1\"><amount>42.5</amount></order>"
                    + "<order id=\"2\"><amount>17</amount></order></orders>";
            String b64 = Base64.getEncoder().encodeToString(doc.getBytes());
            JsonNode r = json(send(c.port, "POST", "/parsers/xml/preview", "{\"sample_b64\":\"" + b64 + "\"}"));
            assertEquals("tree", r.get("kind").asText());
            assertEquals(2, r.get("recordCount").asInt());
            JsonNode rec = r.get("nodes").get(0);
            assertEquals("order", rec.get("label").asText());
            assertEquals("@id", rec.get("children").get(0).get("label").asText());
            assertEquals("42.5", rec.get("children").get(1).get("value").asText());
        }
    }

    @Test
    void unknownParserIs404(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            HttpResponse<String> res = send(c.port, "POST", "/parsers/asn1/preview",
                    "{\"sample_text\":\"x\"}");
            assertEquals(404, res.statusCode(), res.body());
        }
    }

    @Test
    void aMissingSampleIs400AndAnOversizedOneIsRefused(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            assertEquals(400, send(c.port, "POST", "/parsers/delimited/preview", "{}").statusCode());
            String big = "x".repeat(1_000_001);
            HttpResponse<String> res = send(c.port, "POST", "/parsers/delimited/preview",
                    "{\"sample_text\":\"" + big + "\"}");
            assertEquals(400, res.statusCode(), res.body());
            assertTrue(res.body().contains("too large"), res.body());
        }
    }

    @Test
    void aCallerParseProblemIs422WithTheReasonNeverA500(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            // Malformed XML sample.
            HttpResponse<String> bad = send(c.port, "POST", "/parsers/xml/preview",
                    "{\"sample_text\":\"<a><oops\"}");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("not well-formed"), bad.body());
            // A grammar naming an unknown encoding.
            HttpResponse<String> enc = send(c.port, "POST", "/parsers/delimited/preview",
                    "{\"grammar\":{\"encoding\":\"NOPE-8\"},\"sample_text\":\"a,b\\n\"}");
            assertEquals(422, enc.statusCode(), enc.body());
            assertTrue(enc.body().contains("unknown encoding"), enc.body());
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────

    // ⚠ Every route is served under `/api/v1` — a bare `/api` returns "unknown API version", which
    // presents as the ROUTE being unregistered rather than the request being misaddressed.
    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** ⚠ Unwrap the v1 envelope — reading the body directly yields `{data,metadata,…}`, not the payload. */
    private JsonNode json(HttpResponse<String> res) throws Exception {
        assertTrue(res.statusCode() < 300, "expected 2xx, got " + res.statusCode() + ": " + res.body());
        return V1Body.of(res.body());
    }
}

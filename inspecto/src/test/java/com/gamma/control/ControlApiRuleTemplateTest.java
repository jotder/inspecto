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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-HTTP tests for {@code POST /rule-templates/{id}/simulate} — the execution half of the
 * {@code rule-template} component kind, which had none. Covers each fail-closed gate in order.
 *
 * <p>The bind mechanics themselves live in {@code RuleTemplateTest} (the {@code :name} → {@code ?} rewrite)
 * and {@code QueryExecutorBindsTest} (that the driver binds it). What can only be checked here is that the
 * route reports an author's mistake as an actionable 4xx rather than a 500 — an undeclared placeholder and a
 * template with no SQL are both authoring errors, and telling them apart is the point.
 */
class ControlApiRuleTemplateTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(cfg, PipelineConfigBatchTest.miniSchema()).write();
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    /** Persist a rule-template through the generic component CRUD the authoring UI already uses. */
    private void saveTemplate(int port, String id, String json) throws Exception {
        HttpResponse<String> res = send(port, "POST", "/components/rule-template", json);
        assertTrue(res.statusCode() < 300, "could not seed template " + id + ": " + res.body());
    }

    @Test
    void unknownTemplateIs404(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            HttpResponse<String> res = send(c.port, "POST", "/rule-templates/nope/simulate", "{}");
            assertEquals(404, res.statusCode(), res.body());
        }
    }

    @Test
    void anUndeclaredPlaceholderIs422AndNamesIt(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            saveTemplate(c.port, "sneaky", "{\"id\":\"sneaky\",\"name\":\"sneaky\",\"source\":\"ds\","
                    + "\"paramSql\":\"SELECT * FROM ds WHERE a > :declared AND b = :sneaky\","
                    + "\"params\":[{\"name\":\"declared\",\"field\":\"a\",\"operator\":\"gt\",\"value\":\"1\"}]}");

            HttpResponse<String> res = send(c.port, "POST", "/rule-templates/sneaky/simulate", "{}");
            assertEquals(422, res.statusCode(), res.body());
            // Fail closed, and say WHICH hole — an unbound placeholder would otherwise bind by position
            // against the wrong value.
            assertTrue(res.body().contains(":sneaky"), res.body());
        }
    }

    @Test
    void aTemplateCarryingNoSqlIsItsOwnDistinct422(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            saveTemplate(c.port, "empty", "{\"id\":\"empty\",\"name\":\"empty\",\"source\":\"ds\",\"params\":[]}");

            HttpResponse<String> res = send(c.port, "POST", "/rule-templates/empty/simulate", "{}");
            assertEquals(422, res.statusCode(), res.body());
            assertTrue(res.body().contains("no SQL"), res.body());
        }
    }

    @Test
    void aTemplateReadingAnUnknownDatasetIs404NotA500(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            saveTemplate(c.port, "orphan", "{\"id\":\"orphan\",\"name\":\"orphan\",\"source\":\"ghost_dataset\","
                    + "\"paramSql\":\"SELECT * FROM ghost_dataset WHERE a > :t\","
                    + "\"params\":[{\"name\":\"t\",\"field\":\"a\",\"operator\":\"gt\",\"value\":\"1\"}]}");

            HttpResponse<String> res = send(c.port, "POST", "/rule-templates/orphan/simulate", "{}");
            assertEquals(404, res.statusCode(), res.body());
            assertTrue(res.body().contains("ghost_dataset"), res.body());
        }
    }

    @Test
    void suppliedParamsAreEchoedSoASimulationIsReviewable(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            // No `source`, so execution needs no dataset relation and the run reaches the executor.
            saveTemplate(c.port, "inline", "{\"id\":\"inline\",\"name\":\"inline\","
                    + "\"paramSql\":\"SELECT * FROM (SELECT 1 AS n UNION ALL SELECT 9) t WHERE n > :floor\","
                    + "\"params\":[{\"name\":\"floor\",\"field\":\"n\",\"operator\":\"gt\",\"value\":\"0\"}]}");

            JsonNode dflt = json(send(c.port, "POST", "/rule-templates/inline/simulate", "{}"));
            assertEquals(2, dflt.get("matched").asInt(), dflt.toString());
            assertEquals("0", dflt.get("boundTo").get(0).get("value").asText());

            // A supplied value must win over the authored default, and be visible in the echo.
            JsonNode raised = json(send(c.port, "POST", "/rule-templates/inline/simulate",
                    "{\"params\":{\"floor\":\"5\"}}"));
            assertEquals(1, raised.get("matched").asInt(), raised.toString());
            assertEquals("5", raised.get("boundTo").get(0).get("value").asText());
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

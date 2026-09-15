package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.Diagnosis;
import com.gamma.assist.spi.AssistAgent;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.service.ReadModel;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the v3.3.0 assist route over real HTTP (P0): {@code POST /assist/{intent}}
 * (scope assist.read) delegating to the in-process {@link AssistAgent}. Core holds only the seam —
 * these tests register a tiny stub agent (the SPI lives in core) to exercise the status mapping:
 * no agent → 503, unknown intent → 404, model-unavailable → 503, OK → 200 with the result body.
 */
class ControlApiAssistTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** A minimal in-core agent: answers {@code echo}, reports {@code down} unavailable, else unsupported. */
    private static final class StubAgent implements AssistAgent {
        @Override public String name() { return "stub"; }
        @Override public void init(ReadModel service) { /* no handles needed */ }
        @Override public AssistResult assist(AssistRequest req) {
            return switch (req.intent()) {
                case "echo" -> AssistResult.answer("echo", "you said: " + req.userText(),
                        List.of(new AssistResult.Citation("test", "node:1")), List.of("http://x/1"));
                case "down" -> AssistResult.unavailable("down", "model offline");
                case "draft" -> AssistResult.draft("draft", "every weekday at 06:00",
                        List.of(new AssistResult.Citation("catalog", "stream:adjustment_etl")), List.of(),
                        Map.of("cron", "0 6 * * MON-FRI",
                               "onPipeline", "adjustment_etl",
                               "nextRuns", List.of("2026-06-01 06:00:00", "2026-06-02 06:00:00"),
                               "draftToon", "job:\n  name: nightly\n  cron: \"0 6 * * MON-FRI\"\n"));
                default -> AssistResult.unsupported(req.intent());
            };
        }
        @Override public List<Diagnosis> recentDiagnoses(int limit) {
            return limit <= 0 ? List.of() : List.of(new Diagnosis(
                    "B7", "mini_etl", Diagnosis.Severity.CRITICAL,
                    "all member files rejected: schema selector mismatch",
                    null, true, 1_000L,
                    List.of(new AssistResult.Citation("catalog", "stream:mini_etl"))));
        }
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Open a service+API; when {@code withAgent}, register the stub agent before serving. */
    private Ctx open(Path dir, boolean withAgent) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        if (withAgent) svc.registerAgent(new StubAgent());
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        return client.send(b.GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> postAs(int port, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", auth).header("Content-Type", "application/json");
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> getAs(int port, String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", auth);
        return client.send(b.GET().build(), BodyHandlers.ofString());
    }

    /** Bearer valid → a Subject WITH the authoring capability; Bearer plain → a Subject WITHOUT any. */
    private static void armAuthenticator() {
        Authenticators.forTest(ex -> "Bearer valid".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("jdoe", Set.of("canAuthorWorkbench")))
                : "Bearer plain".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("nobody", Set.of()))
                : Optional.empty());
    }

    /**
     * ROUTE-UNGATED-DEFAULT-1 (gated 2026-09-15). The settings are SERVER-WIDE and {@code /test} makes a real
     * outbound call to whatever baseUrl was last saved, so the two are a request-forgery-shaped PAIR and are
     * gated together — this test pins BOTH under one Subject so they cannot drift apart. ⚠ The 403 case (a
     * present Subject lacking the capability) is the assertion that proves a gate rather than a login; the
     * read stays open by policy. With the stub agent present the permitted path is 200 (the SPI defaults
     * answer {@code supported:false}), so a pass is a real pass and not an accidental 503.
     */
    @Test
    void settingsPairRequiresCanAuthorWorkbench(@TempDir Path dir) throws Exception {
        armAuthenticator();
        try (Ctx c = open(dir, true)) {
            assertEquals(401, post(c.port, "/assist/settings", "{}").statusCode(), "no credential → 401");

            HttpResponse<String> write = postAs(c.port, "/assist/settings", "{\"provider\":\"x\"}", "Bearer plain");
            assertEquals(403, write.statusCode(), "the WRITE is the injection point: " + write.body());
            assertTrue(write.body().contains("canAuthorWorkbench"), "the refusal names the capability");
            assertEquals(403, postAs(c.port, "/assist/settings/test", "{}", "Bearer plain").statusCode(),
                    "the TEST is the trigger — gated with the write, never apart from it");

            assertEquals(200, getAs(c.port, "/assist/settings", "Bearer plain").statusCode(),
                    "reads stay open by policy: the masked settings view needs no capability");

            assertEquals(200, postAs(c.port, "/assist/settings", "{\"provider\":\"x\"}", "Bearer valid").statusCode());
            assertEquals(200, postAs(c.port, "/assist/settings/test", "{}", "Bearer valid").statusCode());
        } finally {
            Authenticators.forTest(null);
        }
    }

    /**
     * The gate runs BEFORE {@code assistAgentOr503}: a caller without the capability sees 403 whether or not
     * the optional module is present. Pinned because the opposite order would make the refusal depend on
     * packaging — 503 on Personal, 403 on Standard — and an auditor reading "no agent → 503" would be told
     * the route is protected by absence, which is not protection.
     */
    @Test
    void settingsGateRunsBeforeTheAbsentModule503(@TempDir Path dir) throws Exception {
        armAuthenticator();
        try (Ctx c = open(dir, false)) {
            assertEquals(403, postAs(c.port, "/assist/settings", "{}", "Bearer plain").statusCode(),
                    "capability refused before the module lookup");
            assertEquals(503, postAs(c.port, "/assist/settings", "{}", "Bearer valid").statusCode(),
                    "with the capability, the absent module is the only remaining refusal");
        } finally {
            Authenticators.forTest(null);
        }
    }

    @Test
    void noAgentRegisteredReturns503(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            HttpResponse<String> r = post(c.port, "/assist/echo", "{\"userText\":\"hi\"}");
            assertEquals(503, r.statusCode(), "auth passes, but no agent on the classpath");
            assertTrue(V1Body.of(r.body()).get("error").get("message").asText().contains("not available"));
        }
    }

    @Test
    void okIntentReturnsResultBody(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = post(c.port, "/assist/echo", "{\"userText\":\"hello\"}");
            assertEquals(200, r.statusCode());
            JsonNode out = V1Body.of(r.body());
            assertEquals("echo", out.get("intent").asText());
            assertEquals("OK", out.get("status").asText());
            assertEquals("you said: hello", out.get("answer").asText());
            assertEquals("node:1", out.get("citations").get(0).get("ref").asText());
            assertTrue(out.get("validated").asBoolean());
            assertTrue(out.get("applyVia").isNull(), "read-only skill carries no write endpoint");
        }
    }

    @Test
    void draftResultCarriesStructuredDataPayload(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = post(c.port, "/assist/draft", "{\"userText\":\"weekdays 6am\"}");
            assertEquals(200, r.statusCode());
            JsonNode out = V1Body.of(r.body());
            assertEquals("OK", out.get("status").asText());
            assertTrue(out.get("validated").asBoolean(), "draft ran through the oracle");
            assertTrue(out.get("applyVia").isNull(), "draft-only: no write endpoint (V-9)");
            // The additive 'data' payload (since 3.4.0) round-trips through the route as JSON.
            JsonNode data = out.get("data");
            assertNotNull(data, "structured data payload present on the wire");
            assertEquals("0 6 * * MON-FRI", data.get("cron").asText());
            assertEquals("adjustment_etl", data.get("onPipeline").asText());
            assertEquals(2, data.get("nextRuns").size());
            assertTrue(data.get("draftToon").asText().contains("0 6 * * MON-FRI"),
                    "the saveable draft .toon is carried in the payload");
        }
    }

    @Test
    void unknownIntentIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = post(c.port, "/assist/no-such-skill", "{}");
            assertEquals(404, r.statusCode());
            assertTrue(V1Body.of(r.body()).get("error").get("message").asText().contains("unknown assist intent"));
        }
    }

    @Test
    void modelUnavailableIs503WithMessage(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = post(c.port, "/assist/down", "{}");
            assertEquals(503, r.statusCode());
            assertEquals("model offline", V1Body.of(r.body()).get("error").get("message").asText());
        }
    }

    // ── v3.7.0: GET /assist/diagnoses (read-only failure diagnoses; scope assist.read) ──

    @Test
    void diagnosesRouteIsScopedAndReturnsAgentDiagnoses(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = get(c.port, "/assist/diagnoses");
            assertEquals(200, r.statusCode());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.isArray() && out.size() == 1, "the agent's recent diagnoses come through as JSON");
            JsonNode d = out.get(0);
            assertEquals("B7", d.get("batchId").asText());
            assertEquals("CRITICAL", d.get("severity").asText());
            assertTrue(d.get("heuristicOnly").asBoolean());
            assertEquals("stream:mini_etl", d.get("citations").get(0).get("ref").asText());
        }
    }

    @Test
    void diagnosesRouteReturnsEmptyWhenNoAgent(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            HttpResponse<String> r = get(c.port, "/assist/diagnoses");
            assertEquals(200, r.statusCode(), "no agent -> empty list, not an error");
            assertTrue(V1Body.of(r.body()).isArray());
            assertEquals(0, V1Body.of(r.body()).size());
        }
    }
}

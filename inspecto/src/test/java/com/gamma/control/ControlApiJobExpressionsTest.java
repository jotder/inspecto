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
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /jobs/expressions} over real HTTP (job-parameter-contract §4.3, step 6): the Expression
 * catalog that drives the authoring form's token picker. The point of the route is that it is
 * <em>generated from the {@code ExpressionRegistry}</em> — so what it serves is what a Run will actually
 * resolve, and a {@code contextFree} token's {@code preview} is the engine's own evaluator output rather
 * than a second implementation in the browser.
 */
class ControlApiJobExpressionsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        svc.jobServiceOrCreate();
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void servesTheRegisteredVocabularyWithItsRenderingContract(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode data = json(get(c.port, "/jobs/expressions"));

            assertEquals(15, data.size(), "the fifteen built-in tokens (§2), generated from the registry");
            JsonNode today = byToken(data, "$today");
            assertEquals("LITERAL", today.get("form").asText());
            assertEquals("DATE", today.get("yields").asText(), "so a picker can filter by field type");
            assertTrue(today.get("contextFree").asBoolean());
            assertFalse(today.get("description").asText().isBlank(), "picker text is always present");

            JsonNode signal = byToken(data, "$signal.");
            assertEquals("PREFIX", signal.get("form").asText());
            assertEquals(List.of("on_signal"), toList(signal.get("availableIn")),
                    "$signal.* is meaningless on a cron fire, and the picker filters on this");
            assertEquals(4, toList(byToken(data, "$day(n)").get("availableIn")).size(),
                    "a fire-time token is offered on every trigger kind");
        }
    }

    @Test
    void contextFreeTokensPreviewThroughTheEngineEvaluator(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode data = json(get(c.port, "/jobs/expressions"));

            // Evaluated at request time by the same evaluator a Run uses — never a canned string.
            assertEquals(LocalDate.now().toString(), byToken(data, "$today").get("preview").asText());
            assertEquals(LocalDate.now().minusDays(1).toString(), byToken(data, "$yesterday").get("preview").asText());
            assertEquals(LocalDate.now().minusDays(1).toString(), byToken(data, "$day(n)").get("preview").asText(),
                    "a FUNCTION previews through its typeable example, $day(-1) — the token is only a shape");
        }
    }

    @Test
    void contextBoundTokensShowTheirSampleRatherThanAFabricatedValue(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode data = json(get(c.port, "/jobs/expressions"));

            // There is no firing Run at request time. Inventing one would show the author a value their
            // Job will never see, so these fall back to the declared worked sample.
            for (String token : List.of("$run.id", "$run.actor", "$job.last_success_time", "$signal.")) {
                JsonNode d = byToken(data, token);
                assertFalse(d.get("contextFree").asBoolean(), token + " needs a firing context");
                assertEquals(d.get("example").asText(), d.get("preview").asText(),
                        token + " previews as its sample, not as a made-up resolution");
            }
        }
    }

    @Test
    void theFixedSubPathWinsOverTheJobNameRoute(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // /jobs/{name} is a single-segment regex too, so registration order is load-bearing: a 404
            // "no job 'expressions'" here would mean the catalog got shadowed.
            assertEquals(200, get(c.port, "/jobs/expressions").statusCode());
        }
    }

    private static JsonNode byToken(JsonNode data, String token) {
        return StreamSupport.stream(data.spliterator(), false)
                .filter(n -> token.equals(n.get("token").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + token + "' in the catalog"));
    }

    private static List<String> toList(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.envelope(r.body()).get("data");
    }
}

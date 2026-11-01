package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DEGREES-TRIM-2, over real HTTP: the {@code group} the client puts on every {@code expand} of one Expand is stored as
 * given and served back in the log, so a reopened Investigation knows which steps were one Expand. An expand without it
 * seals as before; a malformed one is a 422; the evaluator never reads it, so the steps replay equivalent.
 * Fixture: {@code a→b→c}.
 */
class ControlApiInvestigationExpandGroupTest {
    @BeforeAll static void raiseLinkAnalysisBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreLinkAnalysisBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
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
            Files.writeString(writeRoot.resolve("link-analysis.toon"), "index:\n  enabled: false\n");
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee FROM (VALUES ('a','b'),('b','c')) AS t(caller,callee)", "2026-11-01T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "p_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private HttpResponse<String> op(Ctx c, String body) throws Exception {
        return send(c, "POST", "/inv/investigations/g/ops", body);
    }

    private void seeded(Ctx c) throws Exception {
        data(send(c, "POST", "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"g\",\"dataset\":\"p_ds\","
                + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}"));
        data(op(c, "{\"op\":\"seed\",\"ids\":[\"a\"]}"));
    }

    @Test
    void theGroupIsStoredAsGivenAndServedBackInTheLog(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seeded(c);
            data(op(c, "{\"op\":\"expand\",\"group\":\"x1-ab\"}"));
            data(op(c, "{\"op\":\"expand\",\"group\":\"x1-ab\"}"));
            data(op(c, "{\"op\":\"expand\"}"));
            JsonNode log = data(send(c, "GET", "/inv/investigations/g/log", null));
            assertEquals("x1-ab", log.at("/entries/1/params/group").asText(), log.toString());
            assertEquals("x1-ab", log.at("/entries/2/params/group").asText(), log.toString());
            assertTrue(log.at("/entries/3/params/group").isMissingNode(), "an expand without a group seals none: " + log.at("/entries/3"));
            String sealed = Files.readString(root.resolve("audit/snapshots/investigations/g/log.jsonl"));
            assertTrue(sealed.contains("\"group\":\"x1-ab\""), sealed);

            JsonNode replay = data(send(c, "POST", "/inv/investigations/g/replay", "{\"reread\":true}"));
            assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
        }
    }

    @Test
    void aMalformedGroupIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seeded(c);
            assertEquals(422, op(c, "{\"op\":\"expand\",\"group\":7}").statusCode());
            assertEquals(422, op(c, "{\"op\":\"expand\",\"group\":\" \"}").statusCode());
            assertEquals(422, op(c, "{\"op\":\"expand\",\"group\":\"" + "g".repeat(65) + "\"}").statusCode());
        }
    }
}

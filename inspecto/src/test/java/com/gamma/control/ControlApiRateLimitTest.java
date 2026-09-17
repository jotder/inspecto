package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for the per-subject token-bucket throttle on the expensive routes
 * ({@code NO-RATE-LIMIT-EXPENSIVE-ROUTES-1}): {@code /db/query}, {@code /bi/query}, {@code /recon/*},
 * {@code /agent/*}. Uses {@code GET /agent/cases} as the probe — it needs no write root or seeded
 * data and answers a stable status (503, the {@code inspecto-intelligence} module is absent here)
 * regardless of throttling, so every response below 429 proves the request reached the handler.
 * Mirrors {@link ControlApiBiQueryTest}'s minimal single-space boot.
 */
class ControlApiRateLimitTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> agentCases(int port) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/agent/cases"))
                .GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> health(int port) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                .GET().build(), BodyHandlers.ofString());
    }

    @Test
    void underTheLimitSucceedsThenExhaustedBucketIs429ButUnrelatedRoutesStayUnaffected(@TempDir Path cfg)
            throws Exception {
        try (Ctx c = open(cfg)) {
            // (a) requests under the limit (the default burst capacity is 20) all reach the handler —
            // none is 429, whatever the handler itself answers (503: no intelligence module on this classpath).
            for (int i = 0; i < 20; i++) {
                HttpResponse<String> r = agentCases(c.port);
                assertNotEquals(429, r.statusCode(), "request " + i + " should not be throttled yet");
            }
            // (b) the 21st request in the same burst finds the bucket empty.
            HttpResponse<String> exhausted = agentCases(c.port);
            assertEquals(429, exhausted.statusCode(), "21st request should be throttled");
            assertTrue(exhausted.body().contains("rate limit"), "body: " + exhausted.body());

            // (c) an unrelated route (not in the throttled prefix set) is unaffected by the exhausted bucket.
            HttpResponse<String> h = health(c.port);
            assertEquals(200, h.statusCode(), "unrelated route must not be throttled");
        }
    }
}

package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HA-RUNLEASE-DB-CREDENTIALS-1 (operator 2026-10-10): a node whose Spaces are CONFIGURED but none could boot must
 * not report ready, and must say which Space and why on {@code /health/details} - with no secrets. A node with
 * genuinely no Spaces stays ready (the server must take traffic so a Space can be created).
 */
class ControlApiSpaceBootReadinessTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private static void withProps(Map<String, String> props, ThrowingRunnable body) throws Exception {
        Map<String, String> prior = new java.util.HashMap<>();
        props.forEach((k, v) -> { prior.put(k, System.getProperty(k)); System.setProperty(k, v); });
        try {
            body.run();
        } finally {
            prior.forEach((k, v) -> { if (v == null) System.clearProperty(k); else System.setProperty(k, v); });
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                BodyHandlers.ofString());
    }

    @Test
    void aConfiguredSpaceThatCannotBootMakesTheNodeNotReady(@TempDir Path spacesRoot) throws Exception {
        Files.createDirectories(spacesRoot.resolve("drill").resolve("config"));
        // Nothing listens on port 1: the schema creation for the Space's run lease fails at connect time.
        Map<String, String> props = Map.of("run.lease.backend", "postgres",
                "run.lease.db.url", "jdbc:postgresql://127.0.0.1:1/x?connectTimeout=2",
                "run.lease.db.user", "lease", "run.lease.db.password", "hunter2");
        withProps(props, () -> {
            try (SpaceManager sm = SpaceManager.discover(spacesRoot); ControlApi api = new ControlApi(sm, 0)) {
                api.start();
                assertEquals(0, sm.size(), "the Space must not have booted");
                assertEquals(1, sm.skipped().size());

                HttpResponse<String> ready = get(api.port(), "/ready");
                assertEquals(503, ready.statusCode(), ready.body());
                assertFalse(ready.body().contains("hunter2"), ready.body());

                HttpResponse<String> health = get(api.port(), "/health");
                assertEquals(200, health.statusCode(), "liveness stays 200 - restarting would not help");
                assertEquals("DEGRADED", V1Body.of(health.body()).get("status").asText(), health.body());
                assertFalse(health.body().contains("drill"), "public route: a count, never Space ids");

                HttpResponse<String> details = get(api.port(), "/api/v1/health/details");
                assertEquals(200, details.statusCode(), details.body());
                JsonNode body = V1Body.of(details.body());
                assertEquals("DOWN", body.get("status").asText(), body.toString());
                JsonNode skipped = body.get("spaces").get("skipped").get(0);
                assertEquals("drill", skipped.get("space").asText());
                assertEquals("SPACE_SCHEMA_CREATE_FAILED", skipped.get("code").asText());
                assertFalse(details.body().contains("hunter2"), details.body());
            }
        });
    }

    @Test
    void zeroConfiguredSpacesStaysReady(@TempDir Path spacesRoot) throws Exception {
        try (SpaceManager sm = SpaceManager.discover(spacesRoot); ControlApi api = new ControlApi(sm, 0)) {
            api.start();
            assertEquals(0, sm.size());
            assertEquals(200, get(api.port(), "/ready").statusCode());
            HttpResponse<String> health = get(api.port(), "/health");
            assertEquals("UP", V1Body.of(health.body()).get("status").asText(), health.body());
            HttpResponse<String> details = get(api.port(), "/api/v1/health/details");
            assertEquals(200, details.statusCode(), "details must answer with no Space booted: " + details.body());
            assertEquals(0, V1Body.of(details.body()).get("spaces").get("skipped").size());
        }
    }
}

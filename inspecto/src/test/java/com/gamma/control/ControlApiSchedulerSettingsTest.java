package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.inspector.ConcurrencyBroker;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.AfterEach;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SchedulerRoutes} over real HTTP: provenance-reporting GETs, the PUT round-trip persisting
 * {@code scheduler.toon} (server-wide at the spaces root, per-space in the space's config tree), the
 * 422 bounds gate, and — the point of the routes — <b>hot-apply</b>: a PUT is visible on
 * {@link ConcurrencyBroker#shared()} without any restart, and a boot with the file already present
 * installs the caps without any request.
 */
class ControlApiSchedulerSettingsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void resetBroker() {
        ConcurrencyBroker.use(null);
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port(), root);
    }

    @Test
    void systemCapRoundTripsWithProvenanceAndHotApplies(@TempDir Path root) throws Exception {
        ConcurrencyBroker.use(null);
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            // Before any save: default provenance, unbounded, live snapshot present.
            JsonNode def = json(send(c.port, "GET", "/system/scheduler", null));
            assertEquals("default", def.get("system").get("source").asText());
            assertEquals(0, def.get("system").get("maxConcurrentConsignments").asInt());
            assertTrue(def.get("cores").asInt() >= 1);
            assertTrue(def.has("live"), "live broker snapshot missing");

            // PUT round-trip: persisted at the spaces container root, provenance flips to file,
            // and the cap is live on the shared broker with no restart.
            HttpResponse<String> put = send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":16}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("file", json(put).get("system").get("source").asText());
            assertEquals(16, json(put).get("system").get("maxConcurrentConsignments").asInt());
            assertTrue(Files.exists(root.resolve("scheduler.toon")), "system doc not at the spaces root");
            assertEquals(16, ConcurrencyBroker.shared().systemCap(), "hot-apply failed");
        }
    }

    @Test
    void spaceCapRoundTripsPerSpaceAndHotApplies(@TempDir Path root) throws Exception {
        ConcurrencyBroker.use(null);
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());

            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/scheduler", null));
            assertEquals("default", def.get("source").asText());

            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/scheduler",
                    "{\"maxConcurrentConsignments\":12}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("file", json(put).get("source").asText());
            assertEquals("acme", json(put).get("id").asText());
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("scheduler.toon")));
            assertEquals(12, ConcurrencyBroker.shared().spaceCap("acme"), "hot-apply failed");

            // 0 removes the tier (back to unbounded).
            assertEquals(200, send(c.port, "PUT", "/spaces/acme/settings/scheduler",
                    "{\"maxConcurrentConsignments\":0}").statusCode());
            assertEquals(0, ConcurrencyBroker.shared().spaceCap("acme"));
        }
    }

    @Test
    void boundsGateRefusesBadValuesWith422(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // Gate order: with NO space bound, the write gate fires first — 503, never a 500.
            assertEquals(503, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":1}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/system/scheduler", "{}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":-1}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":\"lots\"}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":8589934592}").statusCode());
        }
    }

    @Test
    void configuredFilesInstallAtBootWithoutAnyRequest(@TempDir Path root) throws Exception {
        // Seed both documents on disk, then boot: caps must be live before any HTTP call.
        Files.writeString(root.resolve("scheduler.toon"), "max_concurrent_consignments: 5\n");
        Files.createDirectories(root.resolve("acme").resolve("config"));
        Files.writeString(root.resolve("acme").resolve("config").resolve("scheduler.toon"),
                "max_concurrent_consignments: 3\n");
        ConcurrencyBroker.use(null);
        try (Ctx c = open(root)) {
            assertEquals(5, ConcurrencyBroker.shared().systemCap(), "system cap not installed at boot");
            assertEquals(3, ConcurrencyBroker.shared().spaceCap("acme"), "space cap not installed at boot");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return V1Body.of(r.body()); }
}

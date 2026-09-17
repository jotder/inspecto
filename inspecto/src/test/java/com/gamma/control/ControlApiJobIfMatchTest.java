package com.gamma.control;

import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Optimistic concurrency on {@code PUT /jobs/{name}} — {@code IFMATCH-COVERAGE-GAP-1}.
 *
 * <p>The Scheduler's job edit form reads {@code GET /jobs/{name}}, mutates fields client-side, and
 * PUTs the full config back — a genuine read-modify-write where two concurrent editors can silently
 * clobber one another. {@code GET /jobs/{name}} now publishes a strong content ETag over the raw
 * (unmasked) stored config, and {@code PUT /jobs/{name}} honours an optional {@code If-Match} against
 * it, mirroring the {@code ETags}/{@code CONFLICT_STALE_VERSION} pattern
 * {@code PipelineGraphRoutes}/{@code ComponentRoutes} already use.
 */
class ControlApiJobIfMatchTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
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
        return new Ctx(spaces, api, api.port());
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String ifMatch)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (ifMatch != null) b.header("If-Match", ifMatch);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private String etagOf(int port, String base, String name) throws Exception {
        HttpResponse<String> r = send(port, "GET", base + "/jobs/" + name, null, null);
        assertEquals(200, r.statusCode());
        return r.headers().firstValue("ETag").orElseThrow(
                () -> new AssertionError("GET .../jobs/" + name + " served no ETag"));
    }

    @Test
    void aStalePreconditionIsRefusedWith409RatherThanClobbering(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}", null).statusCode());
            String base = "/spaces/acme";
            assertEquals(200, send(c.port, "POST", base + "/jobs", """
                    {"name":"nightly","type":"maintenance","task":"cleanup","retention_days":"30"}""", null)
                    .statusCode());

            String held = etagOf(c.port, base, "nightly");   // editor A reads

            // editor B saves first
            assertEquals(200, send(c.port, "PUT", base + "/jobs/nightly", """
                    {"name":"nightly","type":"maintenance","task":"cleanup","retention_days":"45"}""", held)
                    .statusCode());

            // editor A saves on its now-stale read
            HttpResponse<String> stale = send(c.port, "PUT", base + "/jobs/nightly", """
                    {"name":"nightly","type":"maintenance","task":"cleanup","retention_days":"60"}""", held);
            assertEquals(409, stale.statusCode(), stale.body());
            assertTrue(stale.body().contains("CONFLICT_STALE_VERSION"), stale.body());
        }
    }

    @Test
    void aWriteWithNoPreconditionStillSucceeds(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}", null).statusCode());
            String base = "/spaces/acme";
            assertEquals(200, send(c.port, "POST", base + "/jobs", """
                    {"name":"nightly","type":"maintenance","task":"cleanup","retention_days":"30"}""", null)
                    .statusCode());

            HttpResponse<String> r = send(c.port, "PUT", base + "/jobs/nightly", """
                    {"name":"nightly","type":"maintenance","task":"cleanup","retention_days":"45"}""", null);
            assertEquals(200, r.statusCode(), r.body());
        }
    }

    @Test
    void aFreshPreconditionSucceeds(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}", null).statusCode());
            String base = "/spaces/acme";
            assertEquals(200, send(c.port, "POST", base + "/jobs", """
                    {"name":"nightly","type":"maintenance","task":"cleanup","retention_days":"30"}""", null)
                    .statusCode());

            String fresh = etagOf(c.port, base, "nightly");
            HttpResponse<String> r = send(c.port, "PUT", base + "/jobs/nightly", """
                    {"name":"nightly","type":"maintenance","task":"cleanup","retention_days":"45"}""", fresh);
            assertEquals(200, r.statusCode(), r.body());
        }
    }
}

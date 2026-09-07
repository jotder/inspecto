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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EDG-01 cell 3a — the public route SPI, proven end to end rather than by reading {@code ControlApi}.
 *
 * <p>Two facts, and both have to hold for a route-based cell (CP-09, SEC-10, CP-11) to be extractable:
 * <ol>
 *   <li>a {@link RouteModule} that is NOT in {@code ControlApi}'s hard-coded list, and is registered only
 *       through {@code META-INF/services}, answers over real HTTP — the ServiceLoader append works;</li>
 *   <li>registering the same {@code (method, pattern)} twice is REFUSED at boot. Matching is first-match,
 *       so without this a duplicate would simply never run — silently, with the loser decided by module
 *       order — which is exactly what swallowed {@code DELETE /notifications/suppressions} on 2026-09-07.</li>
 * </ol>
 */
class RouteModuleDiscoveryTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void aRouteModuleRegisteredOnlyViaServiceLoaderAnswersOverHttp(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + c.port + "/api/v1" + TestDiscoveredRoutes.PATH)).GET().build(),
                    BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            // V1Body.of already peels the envelope's `data` — do not reach for .get("data") on top of it.
            JsonNode body = V1Body.of(r.body());
            assertTrue(body.get("discovered").asBoolean(), body.toString());
            assertEquals("META-INF/services", body.get("via").asText());
        }
    }

    /**
     * The guard, falsified in the direction that matters: a SECOND registration of a pattern the boot
     * already owns must throw, naming the route. The probe registers a built-in path on a live context.
     */
    @Test
    void registeringTheSameMethodAndPatternTwiceIsRefused(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> c.api.get(TestDiscoveredRoutes.PATH, (ex, m) -> "shadow"),
                    "the discovered route is already registered; a second GET on the same pattern must be refused");
            assertTrue(e.getMessage().contains("GET " + TestDiscoveredRoutes.PATH), e.getMessage());
            assertTrue(e.getMessage().contains("registered twice"), e.getMessage());
            // ⚠ The negative test needs a probe that would otherwise SUCCEED: a different method on the same
            // pattern is a different route and must still be allowed, or the guard is over-broad.
            assertDoesNotThrow(() -> c.api.post(TestDiscoveredRoutes.PATH, (ex, m) -> "ok"));
        }
    }

    /**
     * 🔴 The bug the first 3b build shipped, in miniature: a stub occupied a pattern and {@code hasRoute}
     * counted it, so {@code /bootstrap} reported the module present on a Personal build. A stub must answer
     * (here: 503) WITHOUT being a "real" route, and a real registration over a stub must be refused rather
     * than silently losing every match to the stub registered before it.
     */
    @Test
    void aStubAnswersButDoesNotCountAsARealRoute(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String path = "/test-discovered/stubbed";
            c.api.stub("GET", path, (e, m) -> { throw new ApiException(503, "stubbed"); });

            assertFalse(c.api.hasRoute("GET", path), "a stub is not a real route — a derived feature flag must read false");
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + c.port + "/api/v1" + path)).GET().build(), BodyHandlers.ofString());
            assertEquals(503, r.statusCode(), "…yet the path answers, so a client sees 'not installed', not 404");

            assertThrows(IllegalStateException.class, () -> c.api.get(path, (e, m) -> "real"),
                    "a real handler registered AFTER the stub would never match — refuse it loudly");
            assertThrows(IllegalStateException.class, () -> c.api.stub("GET", TestDiscoveredRoutes.PATH, (e, m) -> null),
                    "and a stub over a real route would shadow it — refuse that too");
        }
    }
}

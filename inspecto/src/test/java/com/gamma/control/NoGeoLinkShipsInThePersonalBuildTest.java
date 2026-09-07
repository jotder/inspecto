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

import static org.junit.jupiter.api.Assertions.*;

/**
 * EDG-01 cell 3b — the assertion that makes the CP-09 gating REAL rather than claimed, on the DEFAULT
 * (Personal) build, over real HTTP.
 *
 * <p>Three facts, each of which a plausible-looking regression would break on its own:
 * <ol>
 *   <li>every geo/inv path answers <b>503 with the edition message</b> — not 404 (which would mean
 *       {@link AbsentGeoLinkRoutes} lost a path), not 200 (which would mean the module is back in Personal);</li>
 *   <li>{@code /bootstrap} reports {@code features.geoLink == false}, so the SPA hides the entries;</li>
 *   <li>{@code ServiceLoader} finds NO {@code RouteModule} except the test-tree one — the module has not
 *       leaked onto this classpath.</li>
 * </ol>
 *
 * <p>⛔ Do not "fix" a failure here by deleting this test. A 200 means an optional module is in the Personal
 * bundle; a 404 means the stub and the module's surface drifted apart. Both are the defect, not the test.
 */
class NoGeoLinkShipsInThePersonalBuildTest {

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

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void everyGeoLinkPathAnswers503NotInstalledOnThePersonalBuild(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String[][] surface = {
                    {"POST", "/geo/projection"}, {"POST", "/geo/routes"},
                    {"POST", "/inv/projection"}, {"POST", "/inv/projection/neighbors"},
                    {"GET", "/inv/schema/relationships"},
            };
            for (String[] r : surface) {
                HttpResponse<String> res = send(c.port, r[0], r[1], "{}");
                assertEquals(503, res.statusCode(), r[0] + " " + r[1] + " -> " + res.body());
                JsonNode err = V1Body.of(res.body()).get("error");
                assertNotNull(err, r[1] + " must carry the v1 error object: " + res.body());
                assertTrue(err.get("message").asText().contains("inspecto-geo-link"),
                        "the refusal names the module that would fix it: " + err);
            }
        }
    }

    @Test
    void bootstrapReportsGeoLinkAbsentSoTheSpaHidesTheEntries(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode boot = V1Body.of(send(c.port, "GET", "/bootstrap", null).body());
            JsonNode features = boot.get("features");
            assertNotNull(features, boot.toString());
            assertTrue(features.has("geoLink"), "the flag must be PRESENT and false, never merely absent: " + features);
            assertFalse(features.get("geoLink").asBoolean(), features.toString());
        }
    }

    @Test
    void noOptionalRouteModuleIsOnThePersonalClasspath() {
        List<String> found = new java.util.ArrayList<>();
        for (RouteModule m : java.util.ServiceLoader.load(RouteModule.class)) found.add(m.getClass().getName());
        // The one discovered module is this module's own test vehicle (RouteModuleDiscoveryTest).
        assertEquals(List.of(TestDiscoveredRoutes.class.getName()), found,
                "an unexpected RouteModule on the default classpath means an optional module leaked into Personal");
    }
}

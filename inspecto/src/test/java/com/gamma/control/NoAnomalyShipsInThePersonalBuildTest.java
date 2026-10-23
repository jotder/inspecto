package com.gamma.control;

import com.gamma.spi.http.RouteModule;
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
 * ANOMALY-UI-FINDINGS-1 (j): the contract the SPA's hide-on-503 path (the entity anomaly panel) leans on, pinned on the
 * DEFAULT (Basic) build over real HTTP: with the Anomaly module absent every {@code /anomaly-scores*} path
 * answers 503 {@code CAPABILITY_UNAVAILABLE} naming the module. The SPA hides the panel on that 503 (there is no
 * {@code /bootstrap} feature flag for Anomaly). {@code anomaly-model} is a component KIND, not a module route: with
 * the module absent {@code GET /components/anomaly-model} is an ordinary empty list (200), not a 503.
 */
class NoAnomalyShipsInThePersonalBuildTest {

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

    private HttpResponse<String> send(int port, String method, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString("{}"));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void everyAnomalyScorePathAnswers503CapabilityUnavailable(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            List<String[]> surface = AbsentModuleRoutes.surface("anomaly");
            assertEquals(List.of("GET /anomaly-scores/([^/]+)/([^/]+)", "POST /anomaly-scores/preview"),
                    surface.stream().map(r -> r[0] + " " + r[1]).toList());
            for (String[] r : surface) {
                HttpResponse<String> res = send(c.port, r[0], r[1].replace("([^/]+)", "probe"));
                assertEquals(503, res.statusCode(), r[0] + " " + r[1] + " -> " + res.body());
                JsonNode err = V1Body.of(res.body()).get("error");
                assertNotNull(err, res.body());
                assertEquals("CAPABILITY_UNAVAILABLE", err.get("errorCode").asText(), err.toString());
                assertTrue(err.get("message").asText().contains("inspecto-anomaly"), err.toString());
            }
            HttpResponse<String> kind = send(c.port, "GET", "/components/anomaly-model");
            assertEquals(200, kind.statusCode(), kind.body());
            assertEquals(0, V1Body.of(kind.body()).size(), kind.body());
        }
    }
}

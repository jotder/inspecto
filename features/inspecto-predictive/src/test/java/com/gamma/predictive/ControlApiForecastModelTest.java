package com.gamma.predictive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code forecast-model} save gate over real HTTP with an ARMED Subject: the module's validator is discovered
 * through {@code META-INF/services} and runs in the core's component save gate, so a model past the horizon limit or
 * over a non-time column is a 422 and nothing is stored, a good one saves, and a Dataset under the reserved prefix is
 * refused.
 */
class ControlApiForecastModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> "Bearer author".equals(ex.getRequestHeaders().getFirst("Authorization"))
            ? Optional.of(new Subject("au", Set.of("canAuthorWorkbench"))) : Optional.empty();

    @AfterEach
    void tearDown() { Authenticators.forTest(null); }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    private Ctx open(Path root) throws Exception {
        Path base = root.resolve("s1");
        Path config = base.resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        ForecastCorpus.plant(config, base.resolve("data"));
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config);
    }

    private HttpResponse<String> post(int port, String kind, String id, Map<String, Object> content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(content);
        body.put("id", id);
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/spaces/s1/components/" + kind))
                .header("Content-Type", "application/json").header("Authorization", "Bearer author")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aRefusedModelIs422AndNothingIsStoredWhileAGoodOneSaves(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            ComponentStore store = new ComponentStore(c.config.resolve("registry"));
            Map<String, Object> far = new LinkedHashMap<>(ForecastCorpus.demand());
            far.put("horizon", 91);
            HttpResponse<String> r = post(c.port, ForecastModel.KIND, "far", far);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("horizon must be a whole number in 1..90"), r.body());
            assertFalse(store.exists(ForecastModel.KIND, "far"), "nothing stored");

            Map<String, Object> wrongTime = new LinkedHashMap<>(ForecastCorpus.demand());
            wrongTime.put("time", "qty");
            r = post(c.port, ForecastModel.KIND, "wrongtime", wrongTime);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("must be a DATE or TIMESTAMP"), "names the type problem: " + r.body());
            assertFalse(store.exists(ForecastModel.KIND, "wrongtime"));

            r = post(c.port, ForecastModel.KIND, "good", ForecastCorpus.demand());
            assertTrue(r.statusCode() < 300, r.body());
            assertTrue(store.exists(ForecastModel.KIND, "good"));

            r = post(c.port, "dataset", "sneaky", Map.of("physicalRef", "forecasts_good"));
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("reserved prefix 'forecasts_'"), r.body());
        }
    }
}

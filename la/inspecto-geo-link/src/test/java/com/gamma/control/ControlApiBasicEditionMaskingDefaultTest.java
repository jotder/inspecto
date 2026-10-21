package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Operator 2026-10-21: in <b>Basic</b> (no {@link EditionFeatures#INVESTIGATIONS}) a Space that states no {@code masking_mode}
 * masks nothing - the projection answers raw ids and {@code GET /settings/link-analysis} says {@code maskingModeInForce: none};
 * in <b>Standard</b> the shipped {@code typed} holds and a typed column is masked. A Space that STATES a mode keeps it in Basic.
 * Over real HTTP; the bound column is classified MSISDN, an Entity Type {@code typed} masks.
 *
 * <p>Basic is simulated by pinning the edition features empty; Standard by the classpath's {@link InvestigationsEditionForTests}.
 */
class ControlApiBasicEditionMaskingDefaultTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001", B = "27820000002";
    private static final String PROJECTION = "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void restore() {
        EditionFeatures.overrideForTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee FROM (VALUES ('" + A + "','" + B + "')) AS t(caller,callee)", "2026-10-21T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view",
                    "columns", List.of(Map.of("name", "caller", "classification", "MSISDN"))));
            return new Ctx(svc, api, writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private JsonNode data(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.api().port() + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = body == null ? b.GET() : b.method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = client.send(b.build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), path + " -> " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void basicMasksNothingByDefault(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root)) {
            assertEquals("none", data(c, "GET", "/settings/link-analysis", null).get("maskingModeInForce").asText());
            JsonNode proj = data(c, "POST", "/inv/projection", PROJECTION);
            assertEquals("none", proj.at("/masking/mode").asText());
            assertFalse(proj.at("/masking/masked").asBoolean());
            assertEquals(A, proj.get("rows").get(0).get("source").asText(), "Basic answers the raw id");
        }
    }

    @Test
    void basicKeepsAModeTheSpaceStates(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root)) {
            Files.writeString(c.root().resolve("link-analysis.toon"), "masking_mode: typed\n");
            assertEquals("typed", data(c, "GET", "/settings/link-analysis", null).get("maskingModeInForce").asText());
            JsonNode proj = data(c, "POST", "/inv/projection", PROJECTION);
            assertTrue(proj.get("rows").get(0).get("source").asText().startsWith("masked:"), proj.toString());
        }
    }

    @Test
    void standardMasksTypedByDefault(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals("typed", data(c, "GET", "/settings/link-analysis", null).get("maskingModeInForce").asText());
            JsonNode proj = data(c, "POST", "/inv/projection", PROJECTION);
            assertEquals("typed", proj.at("/masking/mode").asText());
            assertTrue(proj.get("rows").get(0).get("source").asText().startsWith("masked:"), proj.toString());
            assertFalse(proj.toString().contains(A), "Standard never answers the typed raw id: " + proj);
        }
    }
}

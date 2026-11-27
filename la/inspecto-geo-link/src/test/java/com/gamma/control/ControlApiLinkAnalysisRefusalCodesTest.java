package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.spec.FindingCodes;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REFUSAL-CODES-1 over real HTTP: the Link Analysis query shapes the W4 golden runs refused most (la-01, la-02, adv-06 —
 * {@code /inv/traversal/recursive-paths}, {@code /inv/projection/neighbors[/preview]}) name the refusal in
 * {@code error.details.findings[0]}: a missing body field, a value outside its set, a column the Dataset lacks.
 */
class ControlApiLinkAnalysisRefusalCodesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(Ctx c, String route, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + route))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** A view-backed Dataset {@code links_ds} over (who, other). */
    private static void seed(Path root) throws Exception {
        new com.gamma.pipeline.ViewStore(root.resolve("views")).write(new com.gamma.pipeline.ViewDefinition("links_view",
                "flow-x", List.of(), "SELECT * FROM (VALUES ('alice','bob'),('bob','carol')) AS t(who,other)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(root.resolve("registry")).write("dataset", "links_ds", Map.of("view", "links_view"));
    }

    private static void assertCoded(HttpResponse<String> r, String code, String fieldPath) throws Exception {
        assertEquals(422, r.statusCode(), r.body());
        JsonNode error = JSON.readTree(r.body()).get("error");
        assertEquals("CONFIG_VALIDATION_FAILED", error.get("errorCode").asText(), r.body());
        JsonNode findings = error.path("details").path("findings");
        assertEquals(1, findings.size(), r.body());
        assertEquals(code, findings.get(0).get("code").asText(), r.body());
        if (fieldPath != null) assertEquals(fieldPath, findings.get(0).get("fieldPath").asText(), r.body());
    }

    @Test
    void recursivePathsRefusalsCarryTheirCodes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String cols = "\"sourceCol\":\"who\",\"targetCol\":\"other\"";
            assertCoded(post(c, "/inv/traversal/recursive-paths", "{" + cols + ",\"startNode\":\"alice\"}"),
                    FindingCodes.ERR_REQUEST_FIELD_MISSING, "dataset");
            assertCoded(post(c, "/inv/traversal/recursive-paths", "{\"dataset\":\"links_ds\",\"targetCol\":\"other\",\"startNode\":\"alice\"}"),
                    FindingCodes.ERR_REQUEST_FIELD_MISSING, "sourceCol");
            assertCoded(post(c, "/inv/traversal/recursive-paths", "{\"dataset\":\"links_ds\"," + cols + "}"),
                    FindingCodes.ERR_REQUEST_FIELD_MISSING, "startNode");
            assertCoded(post(c, "/inv/traversal/recursive-paths", "{\"dataset\":\"links_ds\"," + cols
                    + ",\"startNode\":\"alice\",\"direction\":\"SIDEWAYS\"}"),
                    FindingCodes.ERR_REQUEST_VALUE_NOT_ALLOWED, "direction");
        }
    }

    @Test
    void anUnknownColumnIsNamed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(root);
            assertCoded(post(c, "/inv/traversal/recursive-paths",
                    "{\"dataset\":\"links_ds\",\"sourceCol\":\"who\",\"targetCol\":\"nobody\",\"startNode\":\"alice\"}"),
                    FindingCodes.ERR_COLUMN_UNKNOWN, null);
        }
    }

    @Test
    void neighboursPreviewNamesAMissingDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertCoded(post(c, "/inv/projection/neighbors/preview", "{\"sourceCol\":\"who\",\"targetCol\":\"other\",\"ids\":[\"alice\"]}"),
                    FindingCodes.ERR_REQUEST_FIELD_MISSING, "dataset");
        }
    }
}

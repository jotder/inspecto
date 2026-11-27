package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.spec.FindingCodes;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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
 * REFUSAL-CODES-1 over real HTTP: the refusals agents hit most carry a code of their own as the one finding of the
 * 422 ({@code error.details.findings[0].code}, with its {@code fieldPath}), while {@code error.errorCode} stays
 * {@code CONFIG_VALIDATION_FAILED}; a refusal with no code keeps the body it always had.
 */
class ControlApiRefusalCodesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** A view-backed Dataset {@code sales_ds} with declared columns, and {@code broken_ds} whose view reads no table. */
    private void seed(Ctx c) throws Exception {
        ViewStore views = new ViewStore(c.root.resolve("views"));
        views.write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU',10.0),('US',5.0)) AS t(region,amount)", "2026-07-08T00:00:00Z"));
        ComponentStore store = new ComponentStore(c.root.resolve("registry"));
        store.write("dataset", "sales_ds", Map.of("view", "sales_view",
                "columns", List.of(Map.of("name", "region"), Map.of("name", "amount"))));
        store.write("dataset", "calc_ds", Map.of("view", "sales_view",
                "calculated", List.of(Map.of("name", "double_amount"))));
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    /** The v1 error object of a 422, asserting the shared envelope code. */
    private static JsonNode refused422(HttpResponse<String> r) throws Exception {
        assertEquals(422, r.statusCode(), r.body());
        JsonNode error = JSON.readTree(r.body()).get("error");
        assertEquals("CONFIG_VALIDATION_FAILED", error.get("errorCode").asText(), "the envelope code is unchanged");
        return error;
    }

    /** The one coded finding of a refusal, asserting its code and fieldPath. */
    private static void assertCoded(JsonNode error, String code, String fieldPath) {
        JsonNode findings = error.path("details").path("findings");
        assertEquals(1, findings.size(), error.toString());
        JsonNode f = findings.get(0);
        assertEquals(code, f.get("code").asText(), error.toString());
        assertEquals("ERROR", f.get("severity").asText());
        if (fieldPath != null) assertEquals(fieldPath, f.get("fieldPath").asText(), error.toString());
        assertEquals(error.get("message").asText(), f.get("message").asText(), "the finding repeats the message");
    }

    @Test
    void biQueryRefusalsCarryTheirCodes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c);
            assertCoded(refused422(send(c.port, "POST", "/api/v1/bi/query", """
                    {"dataset":"sales_ds","measures":[{"agg":"count"}],
                     "filters":[{"field":"region","op":"neq","value":"EU"}]}""")),
                    FindingCodes.ERR_BI_FILTER_OP_UNKNOWN, "filters");
            assertCoded(refused422(send(c.port, "POST", "/api/v1/bi/query", """
                    {"dataset":"sales_ds","measures":[{"agg":"count"}],"groupBy":["region"],
                     "orderBy":[{"field":"nope","dir":"desc"}]}""")),
                    FindingCodes.ERR_COLUMN_UNKNOWN, "orderBy");
            // an uncoded refusal keeps the body it always had: no findings
            HttpResponse<String> missing = send(c.port, "POST", "/api/v1/bi/query",
                    "{\"dataset\":\"no_such_ds\",\"measures\":[{\"agg\":\"count\"}]}");
            assertEquals(404, missing.statusCode(), missing.body());
            assertTrue(JSON.readTree(missing.body()).get("error").path("details").isMissingNode(), missing.body());
        }
    }

    @Test
    void datasetRefusalsCarryTheirCodesAtSaveValidateAndRead(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c);
            String noSource = "{\"sql\":\"SELECT * FROM orders\"}";
            assertCoded(refused422(send(c.port, "POST", "/api/v1/components/dataset",
                            "{\"id\":\"virtual_ds\",\"kind\":\"virtual\",\"sql\":\"SELECT * FROM orders\"}")),
                    FindingCodes.ERR_DATASET_SQL_NO_SOURCE, "sourceName");
            // POST /validate (what an agent calls before propose_change) names the same code as a finding
            HttpResponse<String> v = send(c.port, "POST", "/api/v1/validate",
                    "{\"type\":\"dataset\",\"id\":\"virtual_ds\",\"config\":" + noSource + "}");
            assertEquals(200, v.statusCode(), v.body());
            JsonNode found = V1Body.of(v.body()).get("findings");
            assertTrue(found.toString().contains(FindingCodes.ERR_DATASET_SQL_NO_SOURCE), found.toString());
            assertFalse(found.toString().contains("COMPONENT_INVALID"), "the coded refusal replaces the generic code");

            assertCoded(refused422(send(c.port, "GET", "/api/v1/datasets/calc_ds/rows", null)),
                    FindingCodes.ERR_CALCULATED_COLUMN_INVALID, "calculated[0].expr");
        }
    }

    @Test
    void aFailedDatasetReadCarriesItsCode(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            new ViewStore(root.resolve("views")).write(new ViewDefinition("broken_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES (1)) AS t(x) WHERE no_such_column = 1", "2026-07-08T00:00:00Z"));
            new ComponentStore(root.resolve("registry")).write("dataset", "broken_ds", Map.of("view", "broken_view"));
            assertCoded(refused422(send(c.port, "GET", "/api/v1/datasets/broken_ds/rows", null)),
                    FindingCodes.ERR_DATASET_READ_FAILED, null);
        }
    }

    @Test
    void decisionRuleRefusalsCarryTheirCodes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String consequence = "\"consequences\":[{\"action\":\"quarantine\",\"destination\":\"x\"}]";
            assertCoded(refused422(send(c.port, "POST", "/api/v1/decision-rules", "{\"name\":\"r1\"}")),
                    FindingCodes.ERR_DECISION_RULE_NO_CONSEQUENCE, "consequences");
            assertCoded(refused422(send(c.port, "POST", "/api/v1/decision-rules", "{" + consequence + "}")),
                    FindingCodes.ERR_DECISION_RULE_NO_NAME, "name");
            assertCoded(refused422(send(c.port, "POST", "/api/v1/decision-rules", "{\"name\":\"r1\"," + consequence
                    + ",\"when\":{\"kind\":\"condition\",\"field\":\"cost\",\"operator\":\">\",\"value\":\"1\"}}")),
                    FindingCodes.ERR_DECISION_RULE_WHEN_INVALID, "when");
        }
    }
}

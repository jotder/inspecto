package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.spec.FindingCodes;
import com.gamma.etl.PipelineConfigBatchTest;
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
 * REFUSAL-CODES-1 over real HTTP: a Reconciliation save or validate refused for its cardinality or a compare column
 * carries that refusal's own code ({@code error.details.findings[0].code}); the envelope code stays
 * {@code CONFIG_VALIDATION_FAILED}. The agent-builder spike learned both shapes from 422 text alone.
 */
class ControlApiReconRefusalCodesTest {

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

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static String recon(String extra) {
        return "{\"id\":\"r1\",\"datasets\":[\"a_ds\",\"b_ds\"],\"keyColumns\":[\"id\"]," + extra + "}";
    }

    private static void assertCoded(HttpResponse<String> r, String code, String fieldPath) throws Exception {
        assertEquals(422, r.statusCode(), r.body());
        JsonNode error = JSON.readTree(r.body()).get("error");
        assertEquals("CONFIG_VALIDATION_FAILED", error.get("errorCode").asText());
        JsonNode f = error.path("details").path("findings").get(0);
        assertNotNull(f, r.body());
        assertEquals(code, f.get("code").asText(), r.body());
        assertEquals(fieldPath, f.get("fieldPath").asText(), r.body());
    }

    @Test
    void aReconciliationSaveNamesItsRefusal(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertCoded(send(c.port, "POST", "/components/reconciliation", recon("\"cardinality\":\"one_to_some\"")),
                    FindingCodes.ERR_RECON_CARDINALITY_UNKNOWN, "cardinality");
            assertCoded(send(c.port, "POST", "/components/reconciliation",
                            recon("\"compareColumns\":[{\"column\":\"amount\",\"toleranceType\":\"abs\"}]")),
                    FindingCodes.ERR_RECON_COMPARE_COLUMN_INVALID, "compareColumns");
            assertCoded(send(c.port, "POST", "/components/reconciliation",
                            recon("\"compareColumns\":[{\"column\":\"id\"}]")),
                    FindingCodes.ERR_RECON_COMPARE_COLUMN_INVALID, "compareColumns");
        }
    }

    @Test
    void validateNamesTheSameCode(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "POST", "/validate", "{\"type\":\"reconciliation\",\"id\":\"r1\",\"config\":"
                    + recon("\"cardinality\":\"one_to_some\"") + "}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode findings = JSON.readTree(r.body()).get("data").get("findings");
            assertTrue(findings.toString().contains(FindingCodes.ERR_RECON_CARDINALITY_UNKNOWN), findings.toString());
        }
    }
}

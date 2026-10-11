package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.io.ConfigLoader;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code AUTHORING-REDESIGN-1} (e) over real HTTP: a {@code sql} Step's {@code fields[]} rows may carry a schema
 * field's column metadata; {@code POST /config/write} stores well-formed metadata and refuses (422,
 * {@code ERR_STEP_COLUMN_METADATA_INVALID}) a non-string value or metadata on a row naming no distinct column.
 */
class ControlApiStepColumnMetadataTest {

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

    private HttpResponse<String> write(int port, String fields) throws Exception {
        String body = """
                {"type":"pipeline","config":{
                   "name":"orders",
                   "dirs":{"poll":"in","database":"out"},
                   "steps":[{"sql":{"sql":"SELECT ID AS ID, MSISDN AS MSISDN FROM input","fields":%s}}]}}"""
                .formatted(fields);
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/config/write"))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static JsonNode finding(String body, String fieldPath) throws Exception {
        for (JsonNode f : V1Body.envelope(body).get("error").get("details").get("findings"))
            if (FindingCodes.ERR_STEP_COLUMN_METADATA_INVALID.equals(f.path("code").asText())
                    && fieldPath.equals(f.get("fieldPath").asText())) return f;
        return null;
    }

    @Test
    void wellFormedColumnMetadataIsStoredOnTheRow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = write(c.port, """
                    [{"id":"a","name":"ID","from":"ID","fn":"keep","args":{}},
                     {"id":"b","name":"MSISDN","from":"MSISDN","fn":"keep","args":{},
                      "description":"Subscriber number","unit":"","classification":"MSISDN","synonym":"msisdn"}]""");
            assertEquals(200, r.statusCode(), r.body());
            Map<String, Object> decoded = ConfigLoader.filesystem().decode(root.resolve("orders_pipeline.toon").toString());
            Map<?, ?> sql = (Map<?, ?>) ((Map<?, ?>) ((List<?>) decoded.get("steps")).get(0)).get("sql");
            Map<?, ?> row = (Map<?, ?>) ((List<?>) sql.get("fields")).get(1);
            assertEquals("MSISDN", row.get("classification"));
            assertEquals("Subscriber number", row.get("description"));
        }
    }

    @Test
    void aNonStringMetadataValueIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = write(c.port, """
                    [{"id":"a","name":"ID","from":"ID","fn":"keep","args":{},"classification":{"x":"PII"}}]""");
            assertEquals(422, r.statusCode(), r.body());
            assertNotNull(finding(r.body(), "steps[0].sql.fields[0].classification"), r.body());
            assertFalse(Files.exists(root.resolve("orders_pipeline.toon")), "a refused write persists nothing");
        }
    }

    @Test
    void metadataOnARowWithNoOutputColumnIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = write(c.port, """
                    [{"id":"a","name":"","from":"ID","fn":"keep","args":{},"description":"orphan"}]""");
            assertEquals(422, r.statusCode(), r.body());
            assertNotNull(finding(r.body(), "steps[0].sql.fields[0].name"), r.body());
        }
    }

    @Test
    void metadataOnADuplicatedOutputColumnIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = write(c.port, """
                    [{"id":"a","name":"ID","from":"ID","fn":"keep","args":{},"classification":"PII"},
                     {"id":"b","name":"id","from":"MSISDN","fn":"keep","args":{}}]""");
            assertEquals(422, r.statusCode(), r.body());
            assertNotNull(finding(r.body(), "steps[0].sql.fields[0].name"), r.body());
        }
    }
}

package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.io.ConfigLoader;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@code GET/POST /pipelines/{name}/settings} (D8, pipeline-graph backlog) over
 * real HTTP — the dedicated authoring surface for the pipeline-level {@code produces}/{@code reference}
 * block, which {@link com.gamma.pipeline.PipelineEditable} never models and so cannot be reached through
 * {@code PUT .../graph}.
 */
class ControlApiPipelineSettingsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path root) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(root, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).build(),
                BodyHandlers.ofString());
    }

    private static void deleteRecursive(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (var s = Files.list(p)) { s.forEach(ControlApiPipelineSettingsTest::deleteRecursive); }
            }
            Files.deleteIfExists(p);
        } catch (Exception ignored) { }
    }

    @Test
    void absentBlockReadsAsTheParsersOwnDefault() throws Exception {
        Path root = Files.createTempDirectory("pipeline-settings-get");
        try (Ctx c = open(root)) {
            HttpResponse<String> r = get(c.port, "/pipelines/mini_etl/settings");
            assertEquals(200, r.statusCode());
            JsonNode body = V1Body.of(r.body());
            assertEquals("stream", body.get("produces").asText());
            assertTrue(body.get("reference").isNull());
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void unknownPipelineIs404() throws Exception {
        Path root = Files.createTempDirectory("pipeline-settings-404");
        try (Ctx c = open(root)) {
            assertEquals(404, get(c.port, "/pipelines/nope/settings").statusCode());
            assertEquals(404, post(c.port, "/pipelines/nope/settings", "{}").statusCode());
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void savingAValidReferenceBlockPersistsAndReadsBack() throws Exception {
        Path root = Files.createTempDirectory("pipeline-settings-save");
        try (Ctx c = open(root)) {
            String reqBody = """
                {"produces":"reference","reference":{"load":"upsert","key":["msisdn"],"refresh_seconds":60}}
                """;
            HttpResponse<String> r = post(c.port, "/pipelines/mini_etl/settings", reqBody);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode written = V1Body.of(r.body());
            assertTrue(written.get("written").asBoolean());
            assertEquals("reference", written.get("produces").asText());

            HttpResponse<String> readBack = get(c.port, "/pipelines/mini_etl/settings");
            JsonNode reread = V1Body.of(readBack.body());
            assertEquals("reference", reread.get("produces").asText());
            assertEquals("upsert", reread.get("reference").get("load").asText());
            assertEquals(60, reread.get("reference").get("refresh_seconds").asInt());

            // survives through the real TOON codec, not just the in-memory map
            Path pipe = c.root.resolve("mini_pipeline.toon");
            Map<String, Object> onDisk = ConfigLoader.filesystem().decode(pipe.toString());
            assertEquals("reference", onDisk.get("produces"));
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void upsertWithoutKeyIntroducesAnErrorAndIsRefused() throws Exception {
        Path root = Files.createTempDirectory("pipeline-settings-no-key");
        try (Ctx c = open(root)) {
            String reqBody = """
                {"produces":"reference","reference":{"load":"upsert"}}
                """;
            HttpResponse<String> r = post(c.port, "/pipelines/mini_etl/settings", reqBody);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("\"written\":false"));

            // refused, so the config on disk must be untouched
            Path pipe = c.root.resolve("mini_pipeline.toon");
            Map<String, Object> onDisk = ConfigLoader.filesystem().decode(pipe.toString());
            assertNull(onDisk.get("reference"));
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void clearingAPreviouslySavedReferenceBlockRestoresTheDefault() throws Exception {
        Path root = Files.createTempDirectory("pipeline-settings-clear");
        try (Ctx c = open(root)) {
            post(c.port, "/pipelines/mini_etl/settings",
                    "{\"produces\":\"reference\",\"reference\":{\"load\":\"scd2\",\"key\":[\"id\"]}}");

            HttpResponse<String> r = post(c.port, "/pipelines/mini_etl/settings",
                    "{\"produces\":\"stream\",\"reference\":null}");
            assertEquals(200, r.statusCode(), r.body());

            HttpResponse<String> readBack = get(c.port, "/pipelines/mini_etl/settings");
            JsonNode reread = V1Body.of(readBack.body());
            assertEquals("stream", reread.get("produces").asText());
            assertTrue(reread.get("reference").isNull());
        } finally {
            deleteRecursive(root);
        }
    }
}

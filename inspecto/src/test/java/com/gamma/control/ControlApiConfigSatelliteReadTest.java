package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /config/{type}/{name}} for a pipeline's SATELLITE configs — a schema/mapping/enrichment
 * that lives beside its pipeline rather than at the write root.
 *
 * <p>🔴 Regression cover for BACKLOG MOCK-GONE-1(a). {@code resolveRegisteredConfigFile} already let a
 * PIPELINE be found wherever its file is (every sample pipeline lives in
 * {@code config/<name>/<name>_pipeline.toon}); its satellites got no such treatment, so the Pipelines
 * editor — which reads a node's saved output schema by bare name with no {@code subdir} — 404'd on
 * every pipeline laid out that way and silently proposed a NEW schema over the saved one.
 *
 * <p>The rule under test: resolve a satellite elsewhere ONLY when the match is unique, only for reads,
 * and never over an explicit {@code subdir}.
 */
class ControlApiConfigSatelliteReadTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static final String SCHEMA_TOON = """
            name: orders_schema
            fields[1]{name,type}:
              ORDER_ID,VARCHAR
            """;

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

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .GET().build(), BodyHandlers.ofString());
    }

    /** Write `<root>/<sub>/<name>.toon`, creating the subdirectory. */
    private static void writeSchema(Path root, String sub, String name) throws Exception {
        Path dir = sub.isEmpty() ? root : root.resolve(sub);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".toon"), SCHEMA_TOON);
    }

    @Test
    void schemaBesideItsPipelineIsFoundWithoutASubdir(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeSchema(root, "orders", "orders_schema");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = get(c.port, "/config/schema/orders_schema");
            assertEquals(200, r.statusCode(), "a schema one directory down must resolve: " + r.body());
            JsonNode data = JSON.readTree(r.body()).path("data");
            assertEquals("orders/orders_schema.toon", data.path("path").asText().replace('\\', '/'));
            assertEquals("orders_schema", data.path("config").path("name").asText());
        }
    }

    @Test
    void theWriteRootStillWinsWhenTheFileIsThere(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeSchema(root, "", "orders_schema");        // at the root
        writeSchema(root, "orders", "orders_schema");  // and beside a pipeline
        try (Ctx c = open(cfg, root)) {
            JsonNode data = JSON.readTree(get(c.port, "/config/schema/orders_schema").body()).path("data");
            assertEquals("orders_schema.toon", data.path("path").asText().replace('\\', '/'),
                    "the conventional location is never overridden by the scan");
        }
    }

    @Test
    void anAmbiguousNameIs404_NotAGuess(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeSchema(root, "orders", "shared_schema");
        writeSchema(root, "payments", "shared_schema");
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, get(c.port, "/config/schema/shared_schema").statusCode(),
                    "two pipelines own a same-named schema — serving either one would be a guess");
        }
    }

    @Test
    void anExplicitSubdirIsNeverSecondGuessed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeSchema(root, "orders", "orders_schema");
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, get(c.port, "/config/schema/orders_schema?subdir=payments").statusCode(),
                    "the caller said WHERE; looking elsewhere would ignore them");
        }
    }

    @Test
    void aGenuinelyMissingSchemaStill404s(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, get(c.port, "/config/schema/ghost_schema").statusCode());
        }
    }

    @Test
    void deleteIsNOTWidened(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeSchema(root, "orders", "orders_schema");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + c.port + "/api/v1/config/schema/orders_schema")).DELETE().build(),
                    BodyHandlers.ofString());
            assertEquals(404, r.statusCode(),
                    "a read that finds the wrong file shows wrong data; a delete that does destroys it");
            assertTrue(Files.isRegularFile(root.resolve("orders").resolve("orders_schema.toon")),
                    "the satellite must still be on disk");
        }
    }
}

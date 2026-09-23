package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
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
 * {@code PIPELINE-LOAD-FAILURE-INVISIBLE-1} over real HTTP: a Pipeline whose schema has an unquoted
 * {@code DECIMAL(18,2)} tabular row does not load. It used to vanish from {@code GET /pipelines} with only a
 * server-log WARN; by operator decision (2026-09-23) it stays as a ROW carrying
 * {@code loadError {file, line?, message}} — and ONLY there: every surface that runs, schedules or counts
 * Pipelines still reads the loaded registry, so none of them can see (or try to run) the broken one.
 */
class ControlApiPipelineLoadErrorTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** One healthy Pipeline (MINI_ETL) and one broken one ({@code orders_pipeline.toon}, bad schema row). */
    private Ctx open(Path dir) throws Exception {
        Path good = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("good")), "");
        Path badDir = Files.createDirectories(dir.resolve("bad"));
        Path bad = Files.move(PipelineConfigBatchTest.writePipeline(badDir, ""), badDir.resolve("orders_pipeline.toon"));
        Files.writeString(badDir.resolve("mini_schema.toon"), PipelineConfigBatchTest.miniSchema()
                .replace("AMT,\"1\",DOUBLE", "AMT,\"1\",DECIMAL(18,2)"));
        CollectorService svc = new CollectorService(List.of(good, bad), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> send(int port, String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    }

    @Test
    void aPipelineThatDoesNotLoadIsListedWithItsLoadError(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = send(c.port, "GET", "/pipelines");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = V1Body.of(r.body());
            assertEquals(2, rows.size(), "the healthy row AND the broken one: " + rows);

            JsonNode healthy = rows.get(0);
            assertEquals("mini_etl", healthy.get("name").asText(), "healthy rows come first, unchanged");
            assertFalse(healthy.has("loadError"), "a Pipeline that loads carries no loadError key at all");

            JsonNode broken = rows.get(1);
            assertEquals("orders", broken.get("name").asText(), "named after its file — its declared name never parsed");
            assertTrue(broken.get("path").asText().endsWith("orders_pipeline.toon"), broken.toString());
            JsonNode err = broken.get("loadError");
            assertNotNull(err, broken.toString());
            assertTrue(err.get("file").asText().endsWith("mini_schema.toon"),
                    "the file holding the bad row (the schema), not the pipeline: " + err);
            assertEquals(7, err.get("line").asInt(), err.toString());
            String m = err.get("message").asText();
            assertTrue(m.contains("4 values") && m.contains("3 columns") && m.contains("\"DECIMAL(18,2)\""),
                    "the loader's own named-line message: " + m);
            assertFalse(broken.has("nodeCount"), "no graph fields are invented for a Pipeline that never lifted");
        }
    }

    @Test
    void noOtherPipelineConsumerSeesTheBrokenRow(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode runs = V1Body.of(send(c.port, "GET", "/runs").body());
            assertFalse(runs.toString().contains("orders"), "the Runs list ignores it: " + runs);

            JsonNode ready = V1Body.of(send(c.port, "GET", "/ready").body());
            assertEquals(1, ready.get("pipelines").asInt(), "counts ignore it: " + ready);

            JsonNode combined = V1Body.of(send(c.port, "GET", "/pipelines/combined").body());
            assertEquals(1, combined.get("flows").size(), "the combined topology ignores it: " + combined);

            assertEquals(404, send(c.port, "POST", "/runs/orders/trigger").statusCode(),
                    "nothing can run it");
            assertEquals(404, send(c.port, "GET", "/pipelines/orders/graph").statusCode(),
                    "and it is not openable in the editor");
        }
    }
}

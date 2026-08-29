package com.gamma.control;

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
 * {@code GET /config/schema/derived} over real HTTP (step-workbench S5) — the pipeline's output schema
 * derived by {@code DESCRIBE}, without executing the transform, so an author stops restating a schema
 * the engine already knows.
 *
 * <p>Gates covered here: a missing {@code pipeline} parameter → 400 · a path where a bare name belongs
 * → 403 · an unknown pipeline → 404 · a Schema/Mapping that does not bind → 422 · the happy path.
 *
 * <p>⚠ The {@code typedSource}/plugin-path half is covered in {@code TypeFlowTest} rather than here:
 * the shared control-plane fixtures build CSV pipelines only, and a fake plugin ingester wired just
 * for a route test would prove the fixture, not the route.
 */
class ControlApiDerivedSchemaTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots)
            implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    private Ctx open(Path dir, String schemaToon) throws Exception {
        Path toon = TestConfigs.csv(dir, schemaToon).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), priorRoots);
    }

    private String firstPipeline(int port) throws Exception {
        return V1Body.of(send(port, "/pipelines").body()).get(0).get("name").asText();
    }

    // ── gates ─────────────────────────────────────────────────────────────────

    @Test
    void aMissingPipelineParameterIs400(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, PipelineConfigBatchTest.miniSchema())) {
            assertEquals(400, send(c.port, "/config/schema/derived").statusCode());
        }
    }

    @Test
    void aPathWhereABareNameBelongsIs403(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, PipelineConfigBatchTest.miniSchema())) {
            // Refused as a shape, before any lookup — a traversal must not reach the config loader
            // and come back as a 404, which would confirm what is and is not on disk.
            assertEquals(403, send(c.port, "/config/schema/derived?pipeline=../etc/passwd").statusCode());
            assertEquals(403, send(c.port, "/config/schema/derived?pipeline=a/b").statusCode());
        }
    }

    @Test
    void anUnknownPipelineIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, PipelineConfigBatchTest.miniSchema())) {
            assertEquals(404, send(c.port, "/config/schema/derived?pipeline=ghost").statusCode());
        }
    }

    @Test
    void aSchemaThatDoesNotBindIs422AndNamesTheCause(@TempDir Path dir) throws Exception {
        // An EXPR over a function that does not exist: the config loads (nothing evaluates SQL at
        // load), and DuckDB's binder is what refuses — which is the whole point of deriving by
        // DESCRIBE. The author learns at authoring time instead of at the first batch.
        String badSchema = PipelineConfigBatchTest.miniSchema()
                .replace("AMT,AMT,DIRECT", "AMT,\"no_such_function(AMT)\",EXPR");
        try (Ctx c = open(dir, badSchema)) {
            HttpResponse<String> r = send(c.port, "/config/schema/derived?pipeline=" + firstPipeline(c.port));

            assertEquals(422, r.statusCode(), "a non-binding transform is the caller's config, not a server fault");
            assertTrue(r.body().contains("no_such_function") || r.body().toLowerCase().contains("function"),
                    "the refusal carries DuckDB's own message so the author can find the rule: " + r.body());
        }
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void derivesTheSinkColumnsWithoutExecutingAnything(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, PipelineConfigBatchTest.miniSchema())) {
            HttpResponse<String> r = send(c.port, "/config/schema/derived?pipeline=" + firstPipeline(c.port));
            assertEquals(200, r.statusCode());

            JsonNode data = V1Body.of(r.body());
            JsonNode columns = data.get("schemas").get(0).get("columns");

            List<String> names = columns.findValuesAsText("name");
            // ⚠ The derived SINK shape carries the partition columns; the Parquet FILE FOOTER will
            // not, because Hive encodes them as directories. Both are correct answers to different
            // questions, and this route answers the written-table one — so assert the partition
            // columns are PRESENT rather than quietly expecting the footer's narrower shape.
            assertEquals(List.of("ID", "AMT", "EVENT_DATE", "year", "month", "day"), names,
                    "the written table's shape, in SELECT order, with the lineage tag dropped");
            assertFalse(names.contains("__src_id"), "__src_id is internal and never part of the written shape");

            assertEquals("VARCHAR", columns.get(0).get("type").asText());
            assertEquals("DOUBLE", columns.get(1).get("type").asText(), "DuckDB is the type authority, not the author");
            assertEquals("DATE", columns.get(2).get("type").asText());
        }
    }

    @Test
    void reportsWhichSourcePathItAssumed(@TempDir Path dir) throws Exception {
        // 🔴 The trap this route exists to avoid: typedSource decides whether raw columns are
        // all-VARCHAR (CSV) or carry declared types (plugin). Getting it wrong yields types that
        // look authoritative and are wrong, so the answer must SAY which path it assumed rather
        // than leaving a reader to guess.
        try (Ctx c = open(dir, PipelineConfigBatchTest.miniSchema())) {
            JsonNode data = V1Body.of(send(c.port, "/config/schema/derived?pipeline=" + firstPipeline(c.port)).body());

            assertEquals("csv", data.get("sourcePath").asText());
            assertFalse(data.get("typedSource").asBoolean(), "a CSV pipeline reads every raw column as VARCHAR");
            assertTrue(data.get("ingesterClass").isNull(), "no plugin ingester on the built-in CSV path");
        }
    }

    private HttpResponse<String> send(int port, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method("GET", BodyPublishers.noBody()).build();
        return client.send(req, BodyHandlers.ofString());
    }
}

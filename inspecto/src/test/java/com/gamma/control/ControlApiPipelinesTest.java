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
 * Integration tests over real HTTP for the read-only flow-graph projection (doc §6, T31): the
 * {@code GET /pipelines} list, the {@code GET /pipelines/node-types} editor palette, and
 * {@code GET /pipelines/{id}/graph} (a registered pipeline lifted to a {@code PipelineGraph} and projected).
 */
class ControlApiPipelinesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = pipeline(dir);
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /** A minimal single-schema pipeline (reuses the mini schema); read-only projection needs no run/active gate. */
    private Path pipeline(Path dir) throws Exception {
        PipelineConfigBatchTest.writePipeline(dir, "");          // creates dir/mini_schema.toon
        Path schema = dir.resolve("mini_schema.toon");
        String toon = """
            name: FLOW_ETL
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              temp: %1$s/temp
              errors: %1$s/errors
              status_dir: %1$s/status
              log_dir: %1$s/logs
            collector:
              connector: db
              connection: warehouse
            output:
              format: CSV
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%2$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, schema.toString().replace("\\", "/"));
        Path p = dir.resolve("flow_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
    }

    @Test
    void flowsListProjectsRegisteredPipelines(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = get(c.port, "/pipelines");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode arr = V1Body.of(r.body());
            assertTrue(arr.isArray() && arr.size() >= 1, "one entry per registered pipeline");
            JsonNode row = arr.get(0);
            assertEquals("flow_etl", row.get("name").asText());   // registry normalises the name
            assertTrue(row.get("nodeCount").asInt() >= 4);
            assertTrue(row.get("produces").isArray() && row.get("produces").size() >= 1);
        }
    }

    @Test
    void nodeTypesCatalogIsServed(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode arr = V1Body.of(get(c.port, "/pipelines/node-types").body());
            assertTrue(arr.isArray());
            boolean hasView = false, hasAcq = false;
            for (JsonNode t : arr) {
                if ("sink.view".equals(t.get("type").asText())) {
                    hasView = true;
                    assertEquals("SINK", t.get("category").asText());
                    // The palette needs save-ability up front; sink.view cannot be lowered.
                    assertFalse(t.get("lowerable").asBoolean(), "sink.view is not lowerable");
                }
                if ("acquisition".equals(t.get("type").asText())) {
                    hasAcq = true;
                    assertEquals("SOURCE", t.get("category").asText());
                    assertTrue(t.get("lowerable").asBoolean(), "acquisition is lowerable");
                }
            }
            assertTrue(hasView && hasAcq, "palette carries sink.view + acquisition with their categories");
        }
    }

    /** ELT amendment Phase 5: the recipe-verb palette is served beside node-types (S4 dual-read). */
    @Test
    void stepTypesCatalogServesTheRecipeVerbs(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode arr = V1Body.of(get(c.port, "/pipelines/step-types").body());
            assertTrue(arr.isArray());
            java.util.List<String> verbs = new java.util.ArrayList<>();
            for (JsonNode t : arr) {
                verbs.add(t.get("verb").asText());
                assertTrue(t.get("lowerable").asBoolean(), t.get("verb").asText() + " must author a saveable type");
            }
            // A verb appears once per SHAPE it authors: `transform` twice (filter, join — the recipe
            // spells a join `transform: {join: …}` and RecipeCompiler has no `join` verb), and since
            // 2026-08-31 `parse` once per FORMAT (pipeline spec gap 2). `type` is the unique key, never
            // `verb`. ⚠ Asserted as the DISTINCT sequence for the same reason as
            // StepTypesContractTest: the multiplicity is expected to change, the pipeline ORDER is not,
            // and pinning the flat list made a deliberate widening read as a regression.
            assertEquals(java.util.List.of("collect", "parse", "map", "dedup", "transform", "summarize",
                    "route", "sink"), verbs.stream().distinct().toList(), "verbs in order: " + verbs);
            // dedup serves its specs (§5: specs reach the verbs, not just the raw node-type catalog)
            for (JsonNode t : arr)
                if ("dedup".equals(t.get("verb").asText()))
                    assertEquals("keys", t.get("attributes").get(0).get("key").asText());
            // and so does join, whose spec existed for days while the palette served no way to reach it
            JsonNode join = null;
            for (JsonNode t : arr) if ("transform.join".equals(t.get("type").asText())) join = t;
            assertNotNull(join, "the transform verb must offer the join shape: " + arr);
            assertEquals("reference", join.get("attributes").get(0).get("key").asText());
        }
    }

    @Test
    void flowGraphProjectionRendersNodesAndEdges(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = get(c.port, "/pipelines/flow_etl/graph");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode g = V1Body.of(r.body());
            assertEquals("flow_etl", g.get("name").asText());
            assertTrue(g.get("nodes").isArray() && g.get("nodes").size() >= 4);
            assertTrue(g.get("edges").isArray() && g.get("edges").size() >= 3);
            boolean persistentSink = false;
            for (JsonNode n : g.get("nodes")) {
                if ("sink.persistent".equals(n.get("type").asText())) {
                    persistentSink = true;
                    assertEquals("persistent", n.get("sinkKind").asText());
                    assertTrue(n.get("restsOnDisk").asBoolean());
                }
            }
            assertTrue(persistentSink, "graph carries a persistent sink");
            assertTrue(g.get("produces").size() >= 1);
        }
    }

    @Test
    void unknownFlowIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, get(c.port, "/pipelines/nope/graph").statusCode());
        }
    }

    @Test
    void combinedTopologyProjectsFlowsNodesEdgesAndLinks(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = get(c.port, "/pipelines/combined");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode g = V1Body.of(r.body());
            assertTrue(g.get("flows").isArray() && g.get("flows").size() >= 1);
            assertTrue(g.get("nodes").isArray() && g.get("nodes").size() >= 4);
            assertTrue(g.get("edges").isArray());
            assertTrue(g.has("links"));   // superimposition (empty for a lone legacy pipeline with no consumer)
            // the single registered pipeline's nodes are namespaced by flow, and its store appears as a node
            boolean namespaced = false, storeNode = false;
            for (JsonNode n : g.get("nodes")) {
                if (n.get("id").asText().startsWith("flow_etl/")) namespaced = true;
                if ("STORE".equals(n.path("category").asText())) storeNode = true;
            }
            assertTrue(namespaced, "flow nodes are namespaced by flow id");
            assertTrue(storeNode, "the produced store is projected as a synthetic store node");
        }
    }
}

package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optimistic concurrency on {@code PUT /pipelines/{name}/graph} — {@code STORE-CONFLICT-DETECTION-1}.
 *
 * <p><b>The defect this closes.</b> Two editors of the same authored pipeline in the graph editor were
 * last-write-wins: {@link PipelineGraphRoutes#saveGraph} always overlaid the existing {@code
 * *_pipeline.toon} with no version check, exactly the gap the backlog row named. Grounding: the row cited
 * {@code PipelineStore.java}/{@code ViewStore.java}, but those stores are not the live write path any more —
 * {@code PipelineStore.write} now backs only the grandfathered {@code *_flow.toon} bundle-import path
 * ({@code BundleRoutes.PipelineBundleSource}), whose per-item {@code actions} map is a deliberate, different
 * conflict contract; {@code ViewStore.write} is written only by {@code MaterializeTask} (one job run, no
 * concurrent human editors — there is no PUT route for a view at all). The actual concurrent-editor risk is
 * on the graph editor's real save route, so the fix extends {@link ETags}/{@code CONFLICT_STALE_VERSION} —
 * the same pattern {@code ComponentRoutes} already applies to the component registry — to
 * {@code PUT /pipelines/{name}/graph}, mirroring {@link ControlApiConfigIfMatchTest}.
 */
class ControlApiPipelineGraphIfMatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();
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

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String ifMatch)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (ifMatch != null) b.header("If-Match", ifMatch);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }

    /** The registered pipeline's server-side name (from the summary list). */
    private String pipelineName(int port) throws Exception {
        return json(send(port, "GET", "/pipelines", null, null)).get(0).get("name").asText();
    }

    private static Path schemaFile(Path dir) throws Exception {
        Path schema = dir.resolve("brand_new_schema.toon");
        Files.writeString(schema, "raw:\n  fields[1]{name,selector,type}:\n    ID, \"0\", VARCHAR\n");
        return schema;
    }

    private HttpResponse<String> readGraph(int port, String name) throws Exception {
        return send(port, "GET", "/pipelines/" + name + "/graph/raw", null, null);
    }

    private String etagOf(int port, String name) throws Exception {
        HttpResponse<String> r = readGraph(port, name);
        assertEquals(200, r.statusCode(), "the graph must be readable to have a precondition at all");
        return r.headers().firstValue("ETag").orElseThrow(
                () -> new AssertionError("GET /pipelines/" + name + "/graph/raw served no ETag"));
    }

    @Test
    void theReadPublishesAStrongContentEtagThatTracksTheContent(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir)) {
            String name = pipelineName(c.port);
            String first = etagOf(c.port, name);
            assertTrue(first.startsWith("\"sha256:"), "a strong content ETag, not an opaque token: " + first);
            assertEquals(first, etagOf(c.port, name), "unchanged content => the same ETag");

            JsonNode g = json(readGraph(c.port, name));
            ((ObjectNode) g).put("active", false);
            assertEquals(200, send(c.port, "PUT", "/pipelines/" + name + "/graph", g.toString(), first)
                    .statusCode());
            assertNotEquals(first, etagOf(c.port, name), "an edited graph must not keep its ETag");
        }
    }

    /** A stale precondition is the whole point: the second editor is refused instead of clobbering. */
    @Test
    void aStalePreconditionIsRefusedWith409RatherThanClobbering(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir)) {
            String name = pipelineName(c.port);
            JsonNode g = json(readGraph(c.port, name));
            String held = etagOf(c.port, name);   // editor A reads

            // editor B saves first (its own fresh read) — flip active off, a detectable, always-legal edit
            JsonNode bGraph = g.deepCopy();
            ((ObjectNode) bGraph).put("active", false);
            assertEquals(200, send(c.port, "PUT", "/pipelines/" + name + "/graph", bGraph.toString(), held)
                    .statusCode());

            // editor A saves on its now-stale read
            HttpResponse<String> stale = send(c.port, "PUT", "/pipelines/" + name + "/graph", g.toString(), held);
            assertEquals(409, stale.statusCode(), stale.body());
            assertEquals("CONFLICT_STALE_VERSION",
                    JSON.readTree(stale.body()).get("error").get("errorCode").asText(),
                    "a non-2xx body is {error:{...}}, not the envelope");
        }
    }

    /** Honoured, not required: every existing caller sends no If-Match and must keep working unchanged. */
    @Test
    void aWriteWithNoPreconditionStillSucceeds(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir)) {
            String name = pipelineName(c.port);
            JsonNode g = json(readGraph(c.port, name));
            ((ObjectNode) g).put("active", false);

            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/" + name + "/graph", g.toString(), null);
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(json(r).get("written").asBoolean());
        }
    }

    /**
     * A CREATE cannot be stale — a brand-new pipeline (registered from outside the write root, so the
     * first save targets a canonical file that does not exist yet) has nothing to be stale against.
     */
    @Test
    void aPreconditionOnAPipelineWithNoExistingFileDoesNotRefuseTheCreate(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String name = "brand_new_" + System.nanoTime();
            String b = dir.toString().replace('\\', '/');
            String graph = """
                {"active":false,
                 "nodes":[{"id":"acq","type":"acquisition","config":{"poll":"%s/in"}},
                          {"id":"p","type":"parser","config":{"schema_file":"%s"}},
                          {"id":"sink","type":"sink.persistent","config":{"database":"%s/db"}}],
                 "edges":[{"from":"acq","rel":"data","to":"p"},{"from":"p","rel":"data","to":"sink"}]}"""
                    .formatted(b, schemaFile(dir).toString().replace('\\', '/'), b);
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/" + name + "/graph", graph,
                    "\"sha256:whatever\"");
            assertEquals(200, r.statusCode(), r.body());
        }
    }
}

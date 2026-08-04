package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineStore;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The W5 authoring contract over real HTTP: the graph editor round-trips the CANONICAL
 * {@code *_pipeline.toon} ({@code GET /pipelines/{name}/graph/raw} → edit → {@code PUT
 * /pipelines/{name}/graph}), unrepresentable topologies 422 with named {@code refusals[]}, and the
 * old {@code *_flow.toon} authoring writes are GONE — grandfathered flows stay readable / runnable /
 * deletable, never newly written.
 */
class ControlApiPipelineCrudTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** A valid 2-node flow: acquisition --data--> persistent sink (grandfathered *_flow.toon fixture). */
    private static final String VALID = """
        {"name":"demo_flow","active":false,
         "nodes":[{"id":"acq","type":"acquisition"},
                  {"id":"sink","type":"sink.persistent","config":{"store":"out"}}],
         "edges":[{"from":"acq","rel":"data","to":"sink"}]}""";

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
        String prior = System.getProperty("assist.write.root");
        // the safety gate is evaluated per request — allow the temp dir for the Ctx's lifetime
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    /** Grandfathered flows are seeded straight into the store — no HTTP write path exists any more. */
    @SuppressWarnings("unchecked")
    private static void seedFlow(Path writeRoot, String flowJson) throws Exception {
        Map<String, Object> raw = JSON.readValue(flowJson, Map.class);
        new PipelineStore(writeRoot.resolve("flows")).write(String.valueOf(raw.get("name")),
                PipelineCodec.fromMap(raw));
    }

    // ── the W5 canonical round-trip ─────────────────────────────────────────────

    @Test
    void editableGraphRoundTripsThroughTheCanonicalConfig(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            // the registered pipeline's server-side (normalised) name, from the summary list
            String name = json(send(c.port, "GET", "/pipelines", null)).get(0).get("name").asText();

            // lift: the registered pipeline's LOSSLESS editable graph, file vocabulary end to end
            HttpResponse<String> raw = send(c.port, "GET", "/pipelines/" + name + "/graph/raw", null);
            assertEquals(200, raw.statusCode(), raw.body());
            JsonNode g = json(raw);
            JsonNode acq = nodeOfType(g.get("nodes"), "acquisition");
            assertTrue(acq.get("config").get("poll").asText().endsWith("/inbox"), "acq owns dirs.poll");
            JsonNode parser = nodeOfType(g.get("nodes"), "parser");
            assertTrue(parser.get("config").has("schema_file"), "parser owns processing.schema_file");
            JsonNode sink = nodeOfType(g.get("nodes"), "sink.persistent");
            assertTrue(sink.get("config").has("database"), "sink owns dirs.database");

            // lower: PUT the same graph back — writes the canonical <name>_pipeline.toon
            HttpResponse<String> put = send(c.port, "PUT", "/pipelines/" + name + "/graph", g.toString());
            assertEquals(200, put.statusCode(), put.body());
            assertTrue(json(put).get("written").asBoolean());
            Path written = wr.resolve(name + "_pipeline.toon");
            assertTrue(Files.exists(written), "the canonical config file was written");

            // the written file is a RUNNABLE PipelineConfig carrying the graph's sections
            PipelineConfig cfg = PipelineConfig.load(written.toString());
            assertTrue(cfg.dirs().poll().endsWith("/inbox") || cfg.dirs().poll().endsWith("\\inbox"));
            assertTrue(cfg.active(), "active travelled through the round-trip");

            assertEquals(404, send(c.port, "GET", "/pipelines/ghost/graph/raw", null).statusCode());
        }
    }

    @Test
    void unrepresentableTopologiesRefuseWithNamedCodes(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String derive = """
                {"active":true,
                 "nodes":[{"id":"acq","type":"acquisition","config":{"poll":"in"}},
                          {"id":"d1","type":"transform.derive"},
                          {"id":"p","type":"parser","config":{"schema_file":"s.toon"}},
                          {"id":"out","type":"sink.persistent","config":{"database":"db"}}],
                 "edges":[{"from":"acq","rel":"data","to":"d1"},{"from":"d1","rel":"data","to":"p"},
                          {"from":"p","rel":"data","to":"out"}]}""";
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/refuse_a/graph", derive);
            assertEquals(422, r.statusCode(), r.body());
            // v1 errors carry the rejected write's payload under error.details
            JsonNode refusal = V1Body.envelope(r.body()).get("error").get("details").get("refusals").get(0);
            assertEquals("UNSUPPORTED_NODE", refusal.get("code").asText());
            assertEquals("d1", refusal.get("nodeId").asText());
        }
    }

    /** Two distinct-database sinks are no longer refused — they save as a multi-destination sinks: pipeline. */
    @Test
    void twoDistinctDatabasesSaveAsAMultiSinkPipeline(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String b = dir.toString().replace('\\', '/');
            String twoDbs = """
                {"active":false,
                 "nodes":[{"id":"acq","type":"acquisition","config":{"poll":"%1$s/in"}},
                          {"id":"p","type":"parser","config":{"schema_file":"%1$s/s.toon"}},
                          {"id":"s1","type":"sink.persistent","config":{"database":"%1$s/db_a","format":"PARQUET"}},
                          {"id":"s2","type":"sink.persistent","config":{"database":"%1$s/db_b","format":"CSV"}}],
                 "edges":[{"from":"acq","rel":"data","to":"p"},{"from":"p","rel":"data","to":"s1"},
                          {"from":"p","rel":"data","to":"s2"}]}""".formatted(b);
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/two_sink/graph", twoDbs);
            assertEquals(200, r.statusCode(), r.body());
        }
    }

    @Test
    void inactiveDraftMayBePartialAndPreservesTheRest(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            Path schema = dir.resolve("draft_schema.toon");
            Files.writeString(schema, "raw:\n  fields[1]{name,selector,type}:\n    ID, \"0\", VARCHAR\n");
            String full = """
                {"active":false,
                 "nodes":[{"id":"acq","type":"acquisition","config":{"poll":"%1$s/in"}},
                          {"id":"p","type":"parser","config":{"schema_file":"%2$s"}},
                          {"id":"out","type":"sink.persistent","config":{"database":"%1$s/db"}}],
                 "edges":[{"from":"acq","rel":"data","to":"p"},{"from":"p","rel":"data","to":"out"}]}"""
                    .formatted(dir.toString().replace('\\', '/'), schema.toString().replace('\\', '/'));
            // a brand-new file must be complete even when inactive (there is nothing to overlay onto)
            assertEquals(200, send(c.port, "PUT", "/pipelines/draft1/graph", full).statusCode());

            // …but a partial re-save of the existing inactive draft only overlays what its nodes own
            String partial = """
                {"active":false,
                 "nodes":[{"id":"acq","type":"acquisition","config":{"connector":"sftp","poll":"%s/custom_in"}}],
                 "edges":[]}""".formatted(dir.toString().replace('\\', '/'));
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/draft1/graph", partial);
            assertEquals(200, r.statusCode(), r.body());

            String toon = Files.readString(wr.resolve("draft1_pipeline.toon"));
            assertTrue(toon.contains("custom_in"), "acq is present — it owns dirs.poll");
            assertTrue(toon.contains("connector: sftp"), "the draft gained the collector block");
            assertTrue(toon.contains("/db"), "no sink node in the partial save — database preserved");
        }
    }

    @Test
    void invalidGraphIsRejected(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            // dangling edge → PipelineValidator error → 422
            String dangling = "{\"nodes\":[{\"id\":\"acq\",\"type\":\"acquisition\"}],"
                    + "\"edges\":[{\"from\":\"acq\",\"rel\":\"data\",\"to\":\"ghost\"}]}";
            assertEquals(422, send(c.port, "PUT", "/pipelines/bad/graph", dangling).statusCode());
            // malformed shape → 400
            assertEquals(400, send(c.port, "PUT", "/pipelines/bad/graph", "{\"nodes\":42}").statusCode());
        }
    }

    @Test
    void writesAreGatedOnTheWriteRoot(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            assertEquals(503, send(c.port, "PUT", "/pipelines/x/graph", VALID).statusCode());
            assertEquals(0, V1Body.of(send(c.port, "GET", "/pipelines/authored", null).body()).size());
        }
    }

    // ── grandfathered *_flow.toon: readable / deletable, never newly written ──────

    @Test
    void grandfatheredFlowsStayReadableButTheWriteRoutesAreGone(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        seedFlow(wr, VALID);
        try (Ctx c = open(dir, wr)) {
            assertEquals(1, json(send(c.port, "GET", "/pipelines/authored", null)).size());
            assertEquals(2, json(send(c.port, "GET", "/pipelines/authored/demo_flow", null)).get("nodes").size());
            JsonNode rawSink = node(json(send(c.port, "GET", "/pipelines/authored/demo_flow/raw", null))
                    .get("nodes"), "sink");
            assertEquals("out", rawSink.get("config").get("store").asText(), "raw stays lossless");

            // the authoring writes retired with W5 — 405 where the path still serves reads/deletes,
            // 404 where the whole path is gone
            assertEquals(405, send(c.port, "POST", "/pipelines/authored", VALID).statusCode());
            assertEquals(405, send(c.port, "PUT", "/pipelines/authored/demo_flow", VALID).statusCode());
            assertEquals(404, send(c.port, "POST", "/pipelines/authored/demo_flow/nodes",
                    "{\"id\":\"p\",\"type\":\"parser\"}").statusCode());
            assertEquals(404, send(c.port, "POST", "/pipelines/authored/demo_flow/edges",
                    "{\"from\":\"acq\",\"rel\":\"data\",\"to\":\"sink\"}").statusCode());

            // delete (retiring an old flow) still works
            assertEquals(200, send(c.port, "DELETE", "/pipelines/authored/demo_flow", null).statusCode());
            assertEquals(404, send(c.port, "GET", "/pipelines/authored/demo_flow", null).statusCode());

            // unsafe / missing ids stay honest 404s on the read side
            assertEquals(404, send(c.port, "GET", "/pipelines/authored/ghost/raw", null).statusCode());
            assertEquals(404, send(c.port, "GET", "/pipelines/authored/__nope__/raw", null).statusCode());
        }
    }

    @Test
    void dryRunReturnsPerNodeAndPerSinkCounts(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        seedFlow(wr, """
            {"name":"dr_flow","active":false,
             "nodes":[{"id":"acq","type":"acquisition"},
                      {"id":"flt","type":"transform.filter","config":{"where":"CAST(amt AS INT) >= 100"}},
                      {"id":"sink","type":"sink.persistent","config":{"store":"big"}}],
             "edges":[{"from":"acq","rel":"data","to":"flt"},{"from":"flt","rel":"data","to":"sink"}]}""");
        try (Ctx c = open(dir, wr)) {
            HttpResponse<String> r = send(c.port, "POST", "/pipelines/authored/dr_flow/dry-run",
                    "{\"sampleRows\":[{\"id\":\"1\",\"amt\":\"150\"},{\"id\":\"2\",\"amt\":\"50\"},{\"id\":\"3\",\"amt\":\"200\"}]}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = json(r);
            assertEquals("acq", body.get("seedNode").asText());
            assertEquals(2, body.get("sinks").get(0).get("rowCount").asInt(), body.toString());

            assertEquals(404, send(c.port, "POST", "/pipelines/authored/ghost/dry-run", "{\"sampleRows\":[{}]}").statusCode());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static JsonNode node(JsonNode nodes, String id) {
        for (JsonNode n : nodes) if (id.equals(n.get("id").asText())) return n;
        throw new AssertionError("no node '" + id + "' in " + nodes);
    }

    private static JsonNode nodeOfType(JsonNode nodes, String type) {
        for (JsonNode n : nodes) if (type.equals(n.get("type").asText())) return n;
        throw new AssertionError("no node of type '" + type + "' in " + nodes);
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }
}

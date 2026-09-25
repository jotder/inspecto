package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUNDLE-AUTHORED-PIPELINE-STORE-1, option B (operator 2026-09-25) over real HTTP with a real Subject:
 * the {@code pipeline} Metadata Bundle kind carries a REGISTERED {@code *_pipeline.toon} and its sidecars
 * inside the item, and its import is {@code PipelineBundleRoutes}' own import core (one set of §21 gates);
 * {@code authored-pipeline} is read-only — a grandfathered {@code PipelineStore} graph still exports, but
 * is never newly written (W5).
 */
class ControlApiPipelineKindBundleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer author} → canAuthorWorkbench; {@code Bearer plain} → no capability at all. */
    private static final Authenticator AUTH = ex -> {
        String auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
        if ("Bearer author".equals(auth)) return Optional.of(new Subject("jdoe", Set.of("canAuthorWorkbench")));
        if ("Bearer plain".equals(auth)) return Optional.of(new Subject("nobody", Set.of()));
        return Optional.empty();
    };

    @AfterEach
    void restoreAuthenticator() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    /** One instance: {@code toon} registered, {@code writeRoot} its config root, {@code roots} the allowed roots. */
    private Ctx open(Path roots, Path writeRoot, Path toon) throws Exception {
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", roots.toString());
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

    private static Path pipeline(Path dir, String name) throws Exception {
        return TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).name(name).write();
    }

    /** A Stage-2 companion at the write root that triggers on {@code test_etl}. */
    private static void companion(Path writeRoot, String pipelineDb) throws Exception {
        Files.createDirectories(writeRoot);
        Files.writeString(writeRoot.resolve("test_daily_enrich.toon"), """
                name: TEST_DAILY
                version: 1

                input:
                  database: %s
                  format: PARQUET

                output:
                  database: data/reports/test_daily
                  format: PARQUET

                triggers:
                  on_pipeline: test_etl
                  schedule_seconds: 3600

                transform: "SELECT * FROM input"
                """.formatted(pipelineDb));
    }

    private static final String EXPORT_TEST_ETL = "{\"items\":[{\"kind\":\"pipeline\",\"id\":\"test_etl\"}]}";

    /** Export test_etl (+ its schema satellite and companion) from a source instance, as a v2 envelope. */
    private String exportFromSource(Path dir) throws Exception {
        Path src = dir.resolve("src");
        Path toon = pipeline(src, "test_etl");
        Map<String, Object> raw = ConfigCodec.toMap(Files.readString(toon));
        companion(src.resolve("wr"), String.valueOf(((Map<?, ?>) raw.get("dirs")).get("database")));
        try (Ctx c = open(dir, src.resolve("wr"), toon)) {
            HttpResponse<String> r = send(c.port, "POST", "/bundle/export", EXPORT_TEST_ETL, "Bearer plain");
            assertEquals(200, r.statusCode(), "export is a read, open to any subject: " + r.body());
            JsonNode out = V1Body.of(r.body());
            assertEquals(0, out.get("missing").size(), "a REGISTERED pipeline is exportable: " + out);
            // on the source itself the carried content is exactly what is stored — the idempotence baseline
            JsonNode preview = V1Body.of(send(c.port, "POST", "/bundle/preview",
                    JSON.writeValueAsString(out.get("bundle")), "Bearer plain").body());
            assertEquals("unchanged", preview.get("items").get(0).get("status").asText(), preview.toString());
            return JSON.writeValueAsString(out.get("bundle"));
        }
    }

    // ── the round trip ───────────────────────────────────────────────────────────

    @Test
    void aRegisteredPipelineRoundTripsIntoAFreshSpaceWithItsSidecars(@TempDir Path dir) throws Exception {
        Authenticators.forTest(AUTH);
        String bundle = exportFromSource(dir);

        JsonNode item = JSON.readTree(bundle).get("items").get(0);
        assertEquals("pipeline", item.get("kind").asText());
        assertTrue(item.get("content").get("nodes").isArray(), "the editable graph travels for Import as draft");
        JsonNode closure = item.get("content").get("closure");
        assertEquals("inspecto-pipeline-bundle", closure.get("manifest").get("format").asText());
        assertFalse(closure.get("manifest").has("exported_at"),
                "no timestamp inside the content, or every export would hash as drifted");
        JsonNode files = closure.get("files");
        assertTrue(files.has("test_etl_pipeline.toon"), files.toString());
        assertTrue(files.has("test_daily_enrich.toon"), "the companion travels INSIDE the item: " + files);
        String schemaBase = closure.get("manifest").get("satellites").get(0).get("path").asText();
        assertTrue(files.has(schemaBase), "the schema satellite travels INSIDE the item: " + files);

        Path tgt = dir.resolve("tgt");
        Path wr = tgt.resolve("wr");
        try (Ctx c = open(dir, wr, pipeline(tgt, "other"))) {
            assertEquals("new", V1Body.of(send(c.port, "POST", "/bundle/preview", bundle, "Bearer plain").body())
                    .get("items").get(0).get("status").asText());
            assertEquals(403, send(c.port, "POST", "/bundle/import", bundle, "Bearer plain").statusCode(),
                    "the write door is gated (a real Subject is attached)");

            HttpResponse<String> imp = send(c.port, "POST", "/bundle/import", bundle, "Bearer author");
            assertEquals(200, imp.statusCode(), imp.body());
            JsonNode out = V1Body.of(imp.body());
            assertEquals(1, out.get("imported").asInt(), out.toString());

            // FILE-level truth: the §21 layout, inactive, satellite byte-identical, companion retargeted
            Path written = wr.resolve("test_etl").resolve("test_etl_pipeline.toon");
            assertTrue(Files.exists(written), "the registered-file layout <wr>/<id>/<id>_pipeline.toon");
            Map<String, Object> landed = ConfigCodec.toMap(Files.readString(written));
            assertEquals(Boolean.FALSE, landed.get("active"), "an import is ALWAYS an inactive draft");
            assertEquals(schemaBase, ((Map<?, ?>) landed.get("processing")).get("schema_file"));
            assertEquals(files.get(schemaBase).asText(), Files.readString(wr.resolve("test_etl").resolve(schemaBase)));
            try (var ls = Files.list(wr.resolve("test_etl"))) {
                assertTrue(ls.anyMatch(p -> p.getFileName().toString().endsWith("_enrich.toon")),
                        "the companion landed beside the pipeline");
            }
            assertFalse(Files.exists(wr.resolve("pipelines").resolve("test_etl_flow.toon")),
                    "nothing lands in PipelineStore");

            // registered, listed, and it runs
            JsonNode listed = V1Body.of(send(c.port, "GET", "/pipelines", null, "Bearer plain").body());
            assertTrue(listed.toString().contains("\"test_etl\""), "listed: " + listed);
            assertEquals(200, send(c.port, "GET", "/pipelines/test_etl/graph/raw", null, "Bearer plain").statusCode());
            HttpResponse<String> dry = send(c.port, "POST", "/pipelines/authored/test_etl/dry-run",
                    "{\"sampleRows\":[{\"ID\":\"a1\",\"AMT\":\"1.0\",\"EVENT_DATE\":\"2020-04-03\"}]}", "Bearer author");
            assertEquals(200, dry.statusCode(), "the imported pipeline runs: " + dry.body());

            // existing ⇒ skipped by default; overwrite opt-in goes through the same core onto the registered file
            JsonNode again = V1Body.of(send(c.port, "POST", "/bundle/import", bundle, "Bearer author").body());
            assertEquals(1, again.get("skipped").asInt(), again.toString());
            String overwrite = "{\"bundle\":" + bundle + ",\"actions\":{\"pipeline/test_etl\":\"overwrite\"}}";
            JsonNode over = V1Body.of(send(c.port, "POST", "/bundle/import", overwrite, "Bearer author").body());
            assertEquals(1, over.get("overwritten").asInt(), over.toString());
            assertTrue(Files.exists(written), "overwrite lands on the registered file, never a second one");
        }
    }

    // ── the same §21 gates, per item ──────────────────────────────────────────────

    @Test
    void aTamperedOrContentlessItemFailsWithoutWritingAnything(@TempDir Path dir) throws Exception {
        Authenticators.forTest(AUTH);
        String bundle = exportFromSource(dir);
        ObjectNode env = (ObjectNode) JSON.readTree(bundle);
        ObjectNode content = (ObjectNode) env.get("items").get(0).get("content");
        ObjectNode files = (ObjectNode) content.get("closure").get("files");
        String schemaBase = content.get("closure").get("manifest").get("satellites").get(0).get("path").asText();
        files.put(schemaBase, files.get(schemaBase).asText() + "\n");   // no longer matches its manifest sha256

        Path tgt = dir.resolve("tgt");
        Path wr = tgt.resolve("wr");
        try (Ctx c = open(dir, wr, pipeline(tgt, "other"))) {
            JsonNode out = V1Body.of(send(c.port, "POST", "/bundle/import", JSON.writeValueAsString(env),
                    "Bearer author").body());
            assertEquals(1, out.get("failed").asInt(), out.toString());
            assertTrue(out.get("results").get(0).get("message").asText().contains("sha256"), out.toString());
            assertEquals(404, send(c.port, "GET", "/pipelines/test_etl/graph/raw", null, "Bearer plain").statusCode(),
                    "a refused item registers nothing");
            assertFalse(Files.exists(wr.resolve("test_etl")), "and writes nothing");

            content.remove("closure");
            JsonNode bare = V1Body.of(send(c.port, "POST", "/bundle/import", JSON.writeValueAsString(env),
                    "Bearer author").body());
            assertEquals(1, bare.get("failed").asInt(), "a graph alone is not a transferable pipeline: " + bare);
        }
    }

    @Test
    void importNeedsAWriteRoot(@TempDir Path dir) throws Exception {
        Authenticators.forTest(AUTH);
        String bundle = exportFromSource(dir);
        try (Ctx c = open(dir, null, pipeline(dir.resolve("tgt"), "other"))) {
            assertEquals(503, send(c.port, "POST", "/bundle/import", bundle, "Bearer author").statusCode());
        }
    }

    // ── authored-pipeline is read-only (W5: grandfathered graphs are never newly written) ──

    @Test
    void authoredPipelineStillExportsButIsNeverImported(@TempDir Path dir) throws Exception {
        Authenticators.forTest(AUTH);
        Path wr = dir.resolve("wr");
        new PipelineStore(wr.resolve("pipelines")).write("legacy_flow", PipelineCodec.fromMap(JSON.readValue(
                "{\"name\":\"legacy_flow\",\"active\":false,\"nodes\":[],\"edges\":[]}", Map.class)));
        try (Ctx c = open(dir, wr, pipeline(dir.resolve("cfg"), "test_etl"))) {
            JsonNode exp = V1Body.of(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"authored-pipeline\",\"id\":\"legacy_flow\"}]}", "Bearer plain").body());
            assertEquals(1, exp.get("bundle").get("items").size(), "a grandfathered graph still exports: " + exp);

            String fresh = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":["
                    + "{\"kind\":\"authored-pipeline\",\"id\":\"brand_new\",\"content\":"
                    + "{\"name\":\"brand_new\",\"active\":false,\"nodes\":[],\"edges\":[]}}]}";
            HttpResponse<String> r = send(c.port, "POST", "/bundle/import", fresh, "Bearer author");
            assertEquals(422, r.statusCode(), r.body());
            String message = JSON.readTree(r.body()).get("error").get("message").asText();
            assertTrue(message.contains("read-only") && message.contains("'pipeline'"),
                    "the refusal names the rule and the kind to use instead: " + message);
            assertFalse(new PipelineStore(wr.resolve("pipelines")).exists("brand_new"), "nothing written");
            // the shadowing hazard: an id a registered pipeline holds is refused just the same
            String shadow = fresh.replace("brand_new", "test_etl");
            assertEquals(422, send(c.port, "POST", "/bundle/import", shadow, "Bearer author").statusCode());
            assertFalse(new PipelineStore(wr.resolve("pipelines")).exists("test_etl"));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private HttpResponse<String> send(int port, String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", bearer);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}

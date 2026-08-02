package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.io.ConfigLoader;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@code POST /pipelines/{name}/save-as-template} and
 * {@code POST /pipelines/{name}/label} (v5.4.0) over real HTTP.
 *
 * <p>The load-bearing assertion is {@link #templateSharesNoEnvironmentBindingWithItsSource}: a template must
 * not inherit a single writable binding from the pipeline it was copied from, because that — not the
 * {@code template: true} flag — is what protects the original once an operator clears the flag to promote the
 * copy. The flag is covered separately by the not-runnable tests.
 *
 * <p>The fixture pipeline is {@code MINI_ETL} (display name) / {@code mini_etl} (derived identity), which also
 * exercises the display-name-vs-identity split the label route depends on.
 */
class ControlApiPipelineTemplateTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /**
     * Boot a server whose single registered pipeline lives at {@code configDir}. {@code writeRoot==null} ⇒
     * writes disabled. Pass {@code configDir == writeRoot} when the test needs the source config to be
     * writable in place (the label route jails the config it rewrites).
     */
    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);   // the write root is captured in the constructor
            api.start();
            return new Ctx(svc, api, api.port());
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

    // ── save-as-template: gates ─────────────────────────────────────────────────

    @Test
    void noWriteRootIs503(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, post(c.port, "/pipelines/mini_etl/save-as-template", "{\"id\":\"copy\"}").statusCode(),
                    "no -Dassist.write.root ⇒ writes disabled");
        }
    }

    @Test
    void unknownSourcePipelineIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, post(c.port, "/pipelines/nope/save-as-template", "{\"id\":\"copy\"}").statusCode());
        }
    }

    @Test
    void missingIdIs400AndInvalidIdIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(400, post(c.port, "/pipelines/mini_etl/save-as-template", "{}").statusCode(),
                    "id is required");
            assertEquals(422, post(c.port, "/pipelines/mini_etl/save-as-template",
                    "{\"id\":\"Not-An-Id!\"}").statusCode(), "id must match [a-z0-9][a-z0-9_]*");
        }
    }

    @Test
    void reusingARegisteredIdIs409(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(409, post(c.port, "/pipelines/mini_etl/save-as-template",
                    "{\"id\":\"mini_etl\"}").statusCode(), "the source's own id is taken");
        }
    }

    // ── save-as-template: the isolation guarantee ───────────────────────────────

    @Test
    void templateSharesNoEnvironmentBindingWithItsSource(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/pipelines/mini_etl/save-as-template",
                    "{\"id\":\"mini_etl_copy\",\"name\":\"Mini ETL (EU)\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean());
            assertTrue(out.get("template").asBoolean());
            assertEquals("mini_etl_copy_pipeline.toon", out.get("path").asText());

            Map<String, Object> tpl = ConfigLoader.filesystem().decode(
                    root.resolve("mini_etl_copy_pipeline.toon").toString());
            Map<String, Object> src = ConfigLoader.filesystem().decode(
                    cfg.resolve("mini_pipeline.toon").toString());

            // Identity + gating
            assertEquals("mini_etl_copy", String.valueOf(tpl.get("id")));
            assertEquals("Mini ETL (EU)", String.valueOf(tpl.get("name")));
            assertEquals("true", String.valueOf(tpl.get("template")));
            assertEquals("false", String.valueOf(tpl.get("active")), "a template is never armed");

            // THE guarantee: not one directory is shared with the source, and every one is sandboxed.
            Map<?, ?> srcDirs = (Map<?, ?>) src.get("dirs");
            Map<?, ?> tplDirs = (Map<?, ?>) tpl.get("dirs");
            assertEquals(srcDirs.keySet(), tplDirs.keySet(), "every dir the source declared is repointed");
            for (Object k : srcDirs.keySet()) {
                assertNotEquals(String.valueOf(srcDirs.get(k)), String.valueOf(tplDirs.get(k)),
                        "dirs." + k + " must not be shared with the source");
                assertTrue(String.valueOf(tplDirs.get(k)).startsWith("templates/mini_etl_copy"),
                        "dirs." + k + " must land in the template sandbox, was " + tplDirs.get(k));
            }

            // The Catalog Stream and the acquisition ledger's source_id both key off the id — pinned
            // explicitly so neither can re-derive onto the source's.
            assertEquals("mini_etl_copy", String.valueOf(tpl.get("stream")));
            Map<?, ?> col = (Map<?, ?>) tpl.get("collector");
            assertEquals("mini_etl_copy", String.valueOf(col.get("id")), "ledger dedup key is its own");

            // The schema is copied, so editing the template's schema cannot edit the source's.
            @SuppressWarnings("unchecked")
            Map<String, Object> processing = (Map<String, Object>) tpl.get("processing");
            assertEquals("mini_etl_copy_schema.toon", String.valueOf(processing.get("schema_file")));
            assertTrue(Files.exists(root.resolve("mini_etl_copy_schema.toon")), "schema copied beside it");
            @SuppressWarnings("unchecked")
            Map<String, Object> srcProcessing = (Map<String, Object>) src.get("processing");
            assertNotEquals(String.valueOf(srcProcessing.get("schema_file")),
                    String.valueOf(processing.get("schema_file")));

            // The shape worth replicating is carried over verbatim.
            assertEquals(String.valueOf(srcProcessing.get("threads")), String.valueOf(processing.get("threads")));
            assertEquals(String.valueOf(srcProcessing.get("file_pattern")),
                    String.valueOf(processing.get("file_pattern")));
        }
    }

    @Test
    void templateIsListedAndFlaggedButNotRunnable(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, post(c.port, "/pipelines/mini_etl/save-as-template",
                    "{\"id\":\"tpl_one\"}").statusCode());

            // Visible in the list (so it can be found and promoted) and flagged as a template.
            JsonNode list = V1Body.of(get(c.port, "/pipelines").body());
            JsonNode row = null;
            for (JsonNode n : list) if ("tpl_one".equals(n.path("name").asText())) row = n;
            assertNotNull(row, "the template is listed: " + list);
            assertTrue(row.path("template").asBoolean(), "flagged template in the summary");
            assertFalse(row.path("active").asBoolean());

            // Refused on every manual run path — this is the "ran it by mistake" case.
            assertEquals(409, post(c.port, "/runs/tpl_one/trigger", "").statusCode(),
                    "triggering a template is refused, not queued");
            assertEquals(409, post(c.port, "/runs/tpl_one/reprocess", "{\"batchId\":\"b1\"}").statusCode(),
                    "reprocessing a template is refused");
        }
    }

    @Test
    void templateCannotBeArmedThroughConfigWrite(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String armed = """
                    {"type":"pipeline","config":{
                       "name":"armed_tpl","template":true,"active":true,
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            HttpResponse<String> r = post(c.port, "/config/write", armed);
            assertEquals(422, r.statusCode(), r.body());
            assertFalse(Files.exists(root.resolve("armed_tpl_pipeline.toon")), "nothing written");
        }
    }

    // ── label (identity-preserving rename) ──────────────────────────────────────

    @Test
    void labelStampsIdentityThenRelabelsWithoutMovingAnything() throws Exception {
        Path root = Files.createTempDirectory("label-root");
        try (Ctx c = open(root, root)) {   // source must be writable in place
            // Named for the fixture, deliberately NOT <id>_pipeline.toon — a relabel must not rename it.
            Path configFile = root.resolve("mini_pipeline.toon");
            Map<String, Object> before = ConfigLoader.filesystem().decode(configFile.toString());
            assertNull(before.get("id"), "the fixture has no explicit id — the migration-risk case");
            Object dirsBefore = before.get("dirs");

            // 200 even though the fixture's dirs sit outside the default allowed roots: those findings
            // pre-date the relabel, and the route only blocks on findings the new name introduces. A
            // deployment whose data lives off the write root must still be renameable.
            HttpResponse<String> r = post(c.port, "/pipelines/mini_etl/label",
                    "{\"name\":\"Retail Orders (EU)\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("stampedId").asBoolean(), "identity pinned on first relabel");
            assertEquals("mini_etl", out.get("id").asText());
            assertEquals("Retail Orders (EU)", out.get("name").asText());

            // The file keeps its identity-derived name; only the label changed.
            assertTrue(Files.exists(configFile), "the config file is NOT renamed");
            Map<String, Object> after = ConfigLoader.filesystem().decode(configFile.toString());
            assertEquals("mini_etl", String.valueOf(after.get("id")), "identity now explicit");
            assertEquals("Retail Orders (EU)", String.valueOf(after.get("name")));
            assertEquals(String.valueOf(dirsBefore), String.valueOf(after.get("dirs")),
                    "dirs untouched — no audit trail, ledger key or Stream moves");

            // A second relabel is a pure label edit now that the id is pinned.
            JsonNode again = V1Body.of(post(c.port, "/pipelines/mini_etl/label",
                    "{\"name\":\"Retail Orders (APAC)\"}").body());
            assertFalse(again.get("stampedId").asBoolean(), "stamping is idempotent");
            assertEquals("mini_etl", again.get("id").asText(), "identity still addresses the pipeline");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    @Test
    void labelRequiresANameAndAKnownPipeline(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, post(c.port, "/pipelines/nope/label", "{\"name\":\"X\"}").statusCode());
        }
        Path both = Files.createTempDirectory("label-root2");
        try (Ctx c = open(both, both)) {
            assertEquals(400, post(c.port, "/pipelines/mini_etl/label", "{}").statusCode(),
                    "name is required");
        }
    }

    @Test
    void labelRefusesAConfigOutsideTheWriteRoot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {   // config lives outside the write root
            assertEquals(403, post(c.port, "/pipelines/mini_etl/label", "{\"name\":\"X\"}").statusCode(),
                    "rewriting a config outside the write-root jail is refused");
        }
    }
}

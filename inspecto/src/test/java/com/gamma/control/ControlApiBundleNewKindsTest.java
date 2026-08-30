package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Metadata Bundle v2 (2026-07-18 follow-up): the three own-store kinds {@code authored-pipeline}/
 * {@code job}/{@code saved-view} over real HTTP — export, preview and import round-trip through the
 * {@code BundleSource} seam, exactly like the pre-existing {@link ComponentStore} kinds.
 *
 * <p>Since 2026-07-25 (BACKLOG D2) {@code connection} is the fourth own-store kind, carried
 * <b>reference-only with secrets stripped</b>: a {@code ${ENV:…}} reference travels verbatim, a literal
 * credential is omitted entirely (never masked to {@code ***}), and an import carrying a raw secret fails
 * that item. {@code collector} stands in as the still-unsupported kind in the boundary assertions.
 */
class ControlApiBundleNewKindsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    private static String bundleOf(String kind, String id, String contentJson) {
        return "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"exportedAt\":\"2026-07-18T00:00:00Z\","
                + "\"sourceSpace\":null,\"items\":[{\"kind\":\"" + kind + "\",\"id\":\"" + id + "\","
                + "\"content\":" + contentJson + "}]}";
    }

    // ── authored-pipeline ────────────────────────────────────────────────────────

    @Test
    void authoredPipelineImportsAndExportsRoundTrip(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String content = "{\"name\":\"p1\",\"active\":false,\"nodes\":[],\"edges\":[]}";
            JsonNode imp = json(send(c.port, "POST", "/bundle/import", bundleOf("authored-pipeline", "p1", content)));
            assertEquals(1, imp.get("imported").asInt(), imp.toString());
            assertEquals(200, send(c.port, "GET", "/pipelines/authored/p1", null).statusCode(),
                    "landed in PipelineStore, readable via the real CRUD route");

            JsonNode exp = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"authored-pipeline\",\"id\":\"p1\"}]}"));
            assertEquals(1, exp.get("bundle").get("items").size());
            assertEquals("p1", exp.get("bundle").get("items").get(0).get("content").get("name").asText());

            // re-import identical content → idempotent
            JsonNode imp2 = json(send(c.port, "POST", "/bundle/import", bundleOf("authored-pipeline", "p1", content)));
            assertEquals(1, imp2.get("unchanged").asInt(), imp2.toString());
        }
    }

    // ── job ──────────────────────────────────────────────────────────────────────

    @Test
    void jobImportHotRegistersAndExportsRoundTrip(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String content = "{\"name\":\"nightly_cleanup\",\"type\":\"maintenance\",\"task\":\"cleanup\",\"cron\":\"0 3 * * *\"}";
            JsonNode imp = json(send(c.port, "POST", "/bundle/import", bundleOf("job", "nightly_cleanup", content)));
            assertEquals(1, imp.get("imported").asInt(), imp.toString());

            JsonNode detail = json(send(c.port, "GET", "/jobs/nightly_cleanup", null));
            assertEquals("maintenance", detail.get("type").asText(), "hot-registered on the live JobService");
            assertEquals("0 3 * * *", detail.get("cron").asText());

            JsonNode exp = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"job\",\"id\":\"nightly_cleanup\"}]}"));
            assertEquals("nightly_cleanup", exp.get("bundle").get("items").get(0).get("content").get("name").asText());

            JsonNode imp2 = json(send(c.port, "POST", "/bundle/import", bundleOf("job", "nightly_cleanup", content)));
            assertEquals(1, imp2.get("unchanged").asInt(), imp2.toString());
        }
    }

    // ── enrichment (pipeline spec gap 6b) ────────────────────────────────────────

    /**
     * An enrichment is the Stage-2 companion a bundle used to leave behind: not a {@code ComponentStore}
     * kind, and until now with no {@code BundleSource}, so exporting a pipeline carried none of its
     * derived columns.
     *
     * <p>⚠ The load-bearing half is that an import <b>registers</b>, not merely writes.
     * {@code EnrichmentService} has no mtime hot-reload, so a file-only import would land an enrichment
     * that does nothing until the next restart — a silent half-import. This asserts the live
     * {@code /enrichment} surface sees it, exactly as the job kind asserts {@code /jobs} does.
     */
    @Test
    void enrichmentImportRegistersAndExportsRoundTrip(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            String parts = "\"partitions\":[\"year\",\"month\",\"day\"]";
            String content = "{\"name\":\"daily_rollup\",\"input\":{\"database\":\"" + db(dir, "in")
                    + "\",\"format\":\"PARQUET\"," + parts + "},\"output\":{\"database\":\"" + db(dir, "out")
                    + "\",\"format\":\"PARQUET\"," + parts + "},\"transform\":\"SELECT 1 AS n\"}";

            JsonNode imp = json(send(c.port, "POST", "/bundle/import", bundleOf("enrichment", "daily_rollup", content)));
            assertEquals(1, imp.get("imported").asInt(), imp.toString());

            // ⚠ the suffix is not decoration — ServiceBootstrap indexes enrichments BY it, so a file
            // written without it silently drops out of the scan on the next restart
            assertTrue(java.nio.file.Files.isRegularFile(wr.resolve("daily_rollup_enrich.toon")),
                    "written as <id>_enrich.toon at the write root");

            JsonNode listed = json(send(c.port, "GET", "/enrichment", null));
            assertTrue(listed.toString().contains("daily_rollup"),
                    "hot-registered on the live EnrichmentService, not just written: " + listed);

            JsonNode exp = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"enrichment\",\"id\":\"daily_rollup\"}]}"));
            assertEquals(1, exp.get("bundle").get("items").size());
            assertEquals("daily_rollup",
                    exp.get("bundle").get("items").get(0).get("content").get("name").asText());

            JsonNode imp2 = json(send(c.port, "POST", "/bundle/import", bundleOf("enrichment", "daily_rollup", content)));
            assertEquals(1, imp2.get("unchanged").asInt(), imp2.toString());
        }
    }

    /** Malformed content fails THAT item rather than the whole import — the per-item contract. */
    @Test
    void anInvalidEnrichmentFailsItsOwnItem(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            // no input/output sections ⇒ EnrichmentConfig.load refuses
            JsonNode imp = json(send(c.port, "POST", "/bundle/import",
                    bundleOf("enrichment", "broken", "{\"name\":\"broken\",\"transform\":\"SELECT 1\"}")));
            assertEquals(0, imp.get("imported").asInt(), imp.toString());
            assertEquals("failed", imp.get("results").get(0).get("status").asText(), imp.toString());
        }
    }

    /** ⚠ An enrichment NAMES the pipeline it triggers on, so it must be applied after one. */
    @Test
    void enrichmentIsAppliedAfterTheAuthoredPipeline() {
        assertTrue(BundleRoutes.APPLY_ORDER.indexOf("enrichment")
                        > BundleRoutes.APPLY_ORDER.indexOf("authored-pipeline"),
                BundleRoutes.APPLY_ORDER.toString());
    }

    /** A path under the temp dir, JSON-escaped for embedding in the bundle literal. */
    private static String db(Path dir, String leaf) {
        return dir.resolve(leaf).toString().replace("\\", "/");
    }

    // ── saved-view ───────────────────────────────────────────────────────────────

    @Test
    void savedViewImportsAndExportsRoundTrip(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String content = "{\"name\":\"errors_only\",\"filters\":{\"level\":\"ERROR\"},\"createdAt\":1000}";
            JsonNode imp = json(send(c.port, "POST", "/bundle/import", bundleOf("saved-view", "errors_only", content)));
            assertEquals(1, imp.get("imported").asInt(), imp.toString());

            JsonNode list = json(send(c.port, "GET", "/events/views", null));
            assertEquals(1, list.size());
            assertEquals("ERROR", list.get(0).get("filters").get("level").asText());

            JsonNode exp = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"saved-view\",\"id\":\"errors_only\"}]}"));
            assertEquals("errors_only", exp.get("bundle").get("items").get(0).get("content").get("name").asText());

            JsonNode imp2 = json(send(c.port, "POST", "/bundle/import", bundleOf("saved-view", "errors_only", content)));
            assertEquals(1, imp2.get("unchanged").asInt(), imp2.toString());
        }
    }

    // ── connection: reference-only, secrets stripped (BACKLOG D2) ────────────────

    @Test
    void connectionExportStripsLiteralSecretsAndKeepsReferences(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            // A profile a user inlined a literal credential into, alongside a proper ${ENV:…} reference.
            assertEquals(200, send(c.port, "POST", "/connections",
                    "{\"id\":\"pg\",\"connector\":\"sftp\",\"host\":\"h1\",\"port\":22,\"username\":\"u\","
                    + "\"password\":\"hunter2\",\"options\":{\"api_token\":\"raw-token\",\"mode\":\"fast\"},"
                    + "\"tunnel\":{\"host\":\"bastion\",\"port\":2222,\"password\":\"${ENV:TUN_PW}\"}}")
                    .statusCode());

            HttpResponse<String> raw = send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"connection\",\"id\":\"pg\"}]}");
            JsonNode content = json(raw).get("bundle").get("items").get(0).get("content");

            assertFalse(content.has("password"), "a literal password is OMITTED, not masked: " + content);
            assertFalse(content.get("options").has("api_token"), "a literal secret-ish option is omitted");
            assertEquals("fast", content.get("options").get("mode").asText(), "non-secret options travel");
            assertEquals("${ENV:TUN_PW}", content.get("tunnel").get("password").asText(),
                    "a ${ENV:…} reference travels verbatim");
            assertEquals("h1", content.get("host").asText());
            assertEquals("u", content.get("username").asText());
            assertFalse(raw.body().contains("hunter2"), "no secret value anywhere in the bundle");
            assertFalse(raw.body().contains("raw-token"), "no secret value anywhere in the bundle");
            assertFalse(raw.body().contains("***"), "and no mask sentinel either — a sentinel is a persisted lie");
        }
    }

    @Test
    void connectionImportRoundTripsReferencesAndHotRegisters(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String content = "{\"id\":\"pg\",\"connector\":\"sftp\",\"host\":\"h1\",\"port\":22,"
                    + "\"username\":\"u\",\"password\":\"${ENV:PG_PW}\",\"options\":{\"mode\":\"fast\"}}";
            JsonNode imp = json(send(c.port, "POST", "/bundle/import", bundleOf("connection", "pg", content)));
            assertEquals(1, imp.get("imported").asInt(), imp.toString());

            JsonNode detail = json(send(c.port, "GET", "/connections/pg", null));
            assertEquals("sftp", detail.get("connector").asText(), "hot-registered on the live service");
            assertEquals("${ENV:PG_PW}", detail.get("password").asText(), "reference preserved end to end");

            JsonNode exp = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"connection\",\"id\":\"pg\"}]}"));
            assertEquals("${ENV:PG_PW}",
                    exp.get("bundle").get("items").get(0).get("content").get("password").asText());

            JsonNode imp2 = json(send(c.port, "POST", "/bundle/import", bundleOf("connection", "pg", content)));
            assertEquals(1, imp2.get("unchanged").asInt(), imp2.toString());
        }
    }

    @Test
    void connectionImportRejectsARawSecret(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String content = "{\"id\":\"pg\",\"connector\":\"sftp\",\"host\":\"h1\",\"password\":\"hunter2\"}";
            JsonNode r = json(send(c.port, "POST", "/bundle/import", bundleOf("connection", "pg", content)));
            assertEquals(1, r.get("failed").asInt(), r.toString());
            assertEquals(0, r.get("imported").asInt(), r.toString());
            assertTrue(r.get("results").get(0).get("message").asText().contains("${…} secret reference"),
                    r.toString());
            assertFalse(r.toString().contains("hunter2"), "the rejection must not echo the secret back");
            assertEquals(404, send(c.port, "GET", "/connections/pg", null).statusCode(), "nothing persisted");

            // …and the same guard covers the masked-sentinel and options paths.
            String masked = "{\"id\":\"pg2\",\"connector\":\"sftp\",\"host\":\"h1\",\"password\":\"***\"}";
            assertEquals(1, json(send(c.port, "POST", "/bundle/import", bundleOf("connection", "pg2", masked)))
                    .get("failed").asInt());
            String opt = "{\"id\":\"pg3\",\"connector\":\"sftp\",\"host\":\"h1\","
                    + "\"options\":{\"api_token\":\"raw-token\"}}";
            assertEquals(1, json(send(c.port, "POST", "/bundle/import", bundleOf("connection", "pg3", opt)))
                    .get("failed").asInt());
        }
    }

    // ── preview + requires across the new kinds ─────────────────────────────────

    @Test
    void previewClassifiesNewKindsAndUnknownKindStaysUnsupported(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String content = "{\"name\":\"p2\",\"active\":false,\"nodes\":[],\"edges\":[]}";
            send(c.port, "POST", "/bundle/import", bundleOf("authored-pipeline", "p2", content));

            JsonNode preview = json(send(c.port, "POST", "/bundle/preview",
                    "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":["
                    + "{\"kind\":\"authored-pipeline\",\"id\":\"p2\",\"content\":" + content + "},"
                    + "{\"kind\":\"authored-pipeline\",\"id\":\"brand_new\",\"content\":" + content + "},"
                    + "{\"kind\":\"collector\",\"id\":\"pg\",\"content\":{}}]}"));
            assertEquals("unchanged", preview.get("items").get(0).get("status").asText());
            assertEquals("new", preview.get("items").get(1).get("status").asText());
            assertEquals("unsupported", preview.get("items").get(2).get("status").asText());
        }
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

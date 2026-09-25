package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.io.ConfigLoader;
import com.gamma.etl.PipelineConfig;
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
 * A Pipeline's {@code processing.schema_file} and the schema file the authoring surfaces write must be
 * the SAME file (SCHEMA-FILE-NAME-1, found by driving the UI 2026-09-25: a new Pipeline {@code shop_orders}
 * whose Parse Apply wrote {@code shop_orders.toon} while its node named {@code shop_orders_schema.toon},
 * so save/validate/activate all succeeded and the runtime then logged "Schema file not found").
 *
 * <p>Over real HTTP, the three halves: the write lands where the reference points; an armed pipeline
 * whose reference resolves nowhere is refused rather than reported as live; and a registered pipeline
 * that does not load can still be opened for repair.
 */
class ControlApiSchemaFileRefTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot with {@code pipelines} registered and {@code writeRoot} as the write root. */
    private Ctx open(Path writeRoot, List<Path> pipelines) throws Exception {
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(pipelines, 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        return open(writeRoot, List.of(PipelineConfigBatchTest.writePipeline(cfg, "")));
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        b = body == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** The draft the Parse pane writes: the declared names are the PIPELINE's, never the file's. */
    private static String parseSchemaDraft(String fileKey) {
        return """
                {"type":"schema",%s"overwrite":true,"config":{
                   "raw":{"name":"shop_orders","format":"CSV","types":"auto",
                      "fields":[{"name":"ORDER_ID","selector":"0","type":"BIGINT"},
                                {"name":"AMOUNT","selector":"1","type":"DOUBLE"}]},
                   "mapping":{"canonicalName":"shop_orders","rawName":"shop_orders",
                      "fields":[{"name":"ORDER_ID","from":"ORDER_ID","fn":"keep"},
                                {"name":"AMOUNT","from":"AMOUNT","fn":"keep"}]}}}"""
                .formatted(fileKey == null ? "" : "\"file\":\"" + fileKey + "\",");
    }

    private static String armedPipeline(String schemaFile) {
        return """
                {"type":"pipeline","overwrite":true,"config":{
                   "name":"shop_orders","id":"shop_orders","active":true,
                   "dirs":{"poll":"in","database":"out"},
                   "processing":{"schema_file":"%s","threads":1}}}""".formatted(schemaFile);
    }

    // ── (a) one naming convention: the file written is the file referenced ─────────────────────────────

    /**
     * 🔴 The UI flow, end to end: the Parse pane writes its schema under the file the node references
     * (keeping {@code raw.name} = the pipeline's source identity), then the pipeline is saved naming it
     * — and the pipeline LOADS. Before the fix the schema landed at {@code <raw.name>.toon}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aSchemaWrittenUnderItsReferencedFileMakesThePipelineLoad(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> s = send(c.port, "POST", "/config/write", parseSchemaDraft("shop_orders_schema"));
            assertEquals(200, s.statusCode(), s.body());
            JsonNode out = V1Body.of(s.body());
            assertEquals("shop_orders_schema.toon", out.get("path").asText(),
                    "the file is the one the node references, not <raw.name>.toon");
            assertEquals("shop_orders_schema", out.get("name").asText(), "the name a read addresses it by");
            assertFalse(Files.exists(root.resolve("shop_orders.toon")), "no stray <raw.name>.toon");
            Map<String, Object> onDisk = ConfigLoader.filesystem().decode(root.resolve("shop_orders_schema.toon").toString());
            assertEquals("shop_orders", ((Map<String, Object>) onDisk.get("raw")).get("name"),
                    "raw.name keeps the declared source identity (SCHEMA-NAME-1)");

            HttpResponse<String> read = send(c.port, "GET", "/config/schema/shop_orders_schema", null);
            assertEquals(200, read.statusCode(), "the schema reads back by its file name: " + read.body());

            HttpResponse<String> p = send(c.port, "POST", "/config/write", armedPipeline("shop_orders_schema.toon"));
            assertEquals(200, p.statusCode(), p.body());
            assertFalse(V1Body.of(p.body()).get("findings").toString().contains("does not resolve"),
                    "the reference resolves: " + p.body());

            PipelineConfig loaded = PipelineConfig.loadForValidation(root.resolve("shop_orders_pipeline.toon").toString());
            assertEquals("shop_orders", loaded.identity().pipelineName(), "the saved pipeline loads");
        }
    }

    /** Without {@code file} a schema is still named by {@code raw.name} — every other writer is unchanged. */
    @Test
    void withoutAFileKeyASchemaIsStillNamedByItsRawName(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> s = send(c.port, "POST", "/config/write", parseSchemaDraft(null));
            assertEquals(200, s.statusCode(), s.body());
            assertEquals("shop_orders.toon", V1Body.of(s.body()).get("path").asText());
        }
    }

    /** {@code file} names a SCHEMA's file only; on any other type it is malformed, never silently ignored. */
    @Test
    void aFileKeyOnANonSchemaTypeIsRefused400(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String body = """
                    {"type":"pipeline","file":"elsewhere","config":{"name":"p1",
                       "dirs":{"poll":"in","database":"out"},"processing":{"threads":1}}}""";
            HttpResponse<String> r = send(c.port, "POST", "/config/write", body);
            assertEquals(400, r.statusCode(), r.body());
            assertFalse(Files.exists(root.resolve("elsewhere.toon")));
            assertFalse(Files.exists(root.resolve("p1_pipeline.toon")));
        }
    }

    /** A {@code file} that could traverse is refused like any unsafe name (422), before anything is written. */
    @Test
    void anUnsafeFileKeyIsRefused422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "POST", "/config/write", parseSchemaDraft("../escape"));
            assertEquals(422, r.statusCode(), r.body());
            assertFalse(Files.exists(root.getParent().resolve("escape.toon")));
        }
    }
}

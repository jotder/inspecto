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
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the stream-onboarding draft lifecycle (v5.1.0) over real HTTP:
 * {@code POST /config/write} (create) → {@code POST /runs} (register) → the draft shows in
 * {@code GET /catalog/streams} with {@code active:false} → {@code GET /config/{type}/{name}}
 * (resume read-back) → overwrite (stage save) → {@code DELETE} (discard). Plus the read route's
 * fail-closed gates and the stateless {@code POST /config/preview/parsing} / {@code .../schema}
 * sample previews.
 */
class ControlApiOnboardingLifecycleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a server. {@code writeRoot==null} ⇒ writes disabled. */
    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
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

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .DELETE().build(), BodyHandlers.ofString());
    }

    @Test
    void draftLifecycleCreateRegisterResumeOverwriteDiscard(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // 1. Create: the guided editor's first write — schema-less, inactive, minimal.
            String draft = """
                    {"type":"pipeline","config":{
                       "name":"orders_feed",
                       "description":"orders drop from the ERP",
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            HttpResponse<String> w = post(c.port, "/config/write", draft);
            assertEquals(200, w.statusCode(), w.body());
            String path = V1Body.of(w.body()).get("path").asText();
            // The bootstrap scan only indexes *_pipeline.toon — a guided draft MUST follow the
            // convention or it silently drops out of the registry on the next service restart.
            assertTrue(path.endsWith("orders_feed_pipeline.toon"), "scan-convention filename: " + path);
            assertEquals("orders_feed", V1Body.of(w.body()).get("name").asText());

            // 2. Register so the running service indexes it (write alone is not enough).
            HttpResponse<String> reg = post(c.port, "/runs", "{\"configPath\":\"" + path + "\"}");
            assertEquals(2, reg.statusCode() / 100, "schema-less inactive draft registers: " + reg.body());

            // 3. The draft is catalog-visible immediately, as a Draft (active:false).
            JsonNode streams = JSON.readTree(get(c.port, "/catalog/streams").body()).get("data");
            JsonNode draftRow = null;
            for (JsonNode n : streams)
                if ("orders_feed".equals(n.get("attrs").get("pipeline").asText())) draftRow = n;
            assertNotNull(draftRow, "draft appears in /catalog/streams: " + streams);
            assertFalse(draftRow.get("attrs").get("active").asBoolean(), "listed as a draft");

            // 4. Resume: read the config back (decoded), exactly as written.
            HttpResponse<String> r = get(c.port, "/config/pipeline/orders_feed");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode read = V1Body.of(r.body());
            assertEquals("orders_feed", read.get("name").asText());
            assertEquals("orders_feed", read.get("config").get("name").asText());
            assertEquals("in", read.get("config").get("dirs").get("poll").asText());
            assertTrue(read.get("config").path("parsing").isMissingNode(), "no parsing stage yet");

            // 5. Stage save: overwrite with a parsing block attached.
            String withParsing = """
                    {"type":"pipeline","overwrite":true,"config":{
                       "name":"orders_feed",
                       "description":"orders drop from the ERP",
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1},
                       "parsing":{"frontend":"delimited","delimited":{"delimiter":"|","has_header":true}}}}""";
            HttpResponse<String> w2 = post(c.port, "/config/write", withParsing);
            assertEquals(200, w2.statusCode(), w2.body());
            assertTrue(V1Body.of(w2.body()).get("overwritten").asBoolean());
            JsonNode reread = V1Body.of(get(c.port, "/config/pipeline/orders_feed").body());
            assertEquals("delimited", reread.get("config").get("parsing").get("frontend").asText());

            // 6. Discard: an inactive draft deletes cleanly.
            assertEquals(200, delete(c.port, "/config/pipeline/orders_feed").statusCode());
            assertEquals(404, get(c.port, "/config/pipeline/orders_feed").statusCode(), "gone after discard");
        }
    }

    @Test
    void readDisabledWithoutWriteRoot(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, get(c.port, "/config/pipeline/anything").statusCode());
        }
    }

    @Test
    void readUnknownTypeIs404AndSpecRouteIsNotShadowed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, get(c.port, "/config/nonsense/x").statusCode());
            HttpResponse<String> spec = get(c.port, "/config/spec/pipeline");
            assertEquals(200, spec.statusCode(), "/config/spec/{type} still served: " + spec.body());
        }
    }

    @Test
    void readMissingFileIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, get(c.port, "/config/pipeline/ghost").statusCode());
        }
    }

    @Test
    void parsingPreviewParsesASampleStatelessly(@TempDir Path cfg) throws Exception {
        // No write root on purpose: the preview is stateless compute, not a config mutation.
        try (Ctx c = open(cfg, null)) {
            String body = """
                    {"config":{
                       "name":"p","dirs":{"poll":"in","database":"out"},"processing":{"threads":1},
                       "parsing":{"frontend":"delimited","delimited":{"delimiter":"|","has_header":true}}},
                     "sample_text":"id|city\\n1|london\\n2|paris\\n"}""";
            HttpResponse<String> r = post(c.port, "/config/preview/parsing", body);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertEquals("delimited", out.get("frontend").asText());
            assertEquals(2, out.get("rowCount").asInt());
            assertEquals("id", out.get("columns").get(0).asText());
            assertEquals("london", out.get("rows").get(0).get("city").asText());
        }
    }

    @Test
    void parsingPreviewGates400And422(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(400, post(c.port, "/config/preview/parsing",
                    "{\"config\":{\"name\":\"p\"}}").statusCode(), "missing sample_text");
            HttpResponse<String> bad = post(c.port, "/config/preview/parsing",
                    "{\"config\":{\"name\":\"p\"},\"sample_text\":\"x\"}");
            assertEquals(422, bad.statusCode(), "draft without dirs/processing does not parse: " + bad.body());
        }
    }

    @Test
    void schemaPreviewCastsSampleRowsAgainstTypedFields(@TempDir Path cfg) throws Exception {
        // No write root: the schema-preview is stateless compute, not a config mutation — same as
        // the parsing preview above.
        try (Ctx c = open(cfg, null)) {
            String body = """
                    {"config":{"raw":{"fields":[
                       {"name":"ORDER_ID","type":"VARCHAR"},
                       {"name":"QUANTITY","type":"DOUBLE"}]}},
                     "sampleRows":[
                       {"ORDER_ID":"1001","QUANTITY":"3"},
                       {"ORDER_ID":"1002","QUANTITY":"abc"}]}""";
            HttpResponse<String> r = post(c.port, "/config/preview/schema", body);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertEquals(1, out.get("okCount").asInt());
            assertEquals(1, out.get("rejectedCount").asInt());
            assertEquals("1002", out.get("rejectedRows").get(0).get("ORDER_ID").asText());
            assertNull(out.get("mappedRows"),
                    "no mapping rules posted → the response keeps its pre-B1 shape");
        }
    }

    /**
     * B1 (definition-surface unification P4): a draft carrying {@code mapping.rules} also gets the compiled
     * mapped output — TARGET columns over the rows that passed the cast — which is what the Load drawer's
     * "mapped output" table renders. An {@code EXPR} rule is the case that cannot be shown any other way.
     */
    @Test
    void schemaPreviewReturnsMappedRowsWhenTheDraftDeclaresMappingRules(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            String body = """
                    {"config":{"raw":{"fields":[
                       {"name":"ORDER_ID","type":"VARCHAR"},
                       {"name":"QUANTITY","type":"DOUBLE"}]},
                      "mapping":{"rules":[
                       {"targetColumn":"order_ref","sourceExpression":"ORDER_ID","transformType":"DIRECT"},
                       {"targetColumn":"qty_x2","sourceExpression":"TRY_CAST(QUANTITY AS DOUBLE) * 2","transformType":"EXPR"}]}},
                     "sampleRows":[
                       {"ORDER_ID":"1001","QUANTITY":"3"},
                       {"ORDER_ID":"1002","QUANTITY":"abc"}]}""";
            HttpResponse<String> r = post(c.port, "/config/preview/schema", body);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());

            assertEquals(1, out.get("okCount").asInt());
            assertEquals(1, out.get("mappedCount").asInt(), "only the cast-passing row maps");
            assertEquals("order_ref", out.get("mappedColumns").get(0).asText());
            assertEquals("qty_x2", out.get("mappedColumns").get(1).asText());
            JsonNode mapped = out.get("mappedRows").get(0);
            assertEquals("1001", mapped.get("order_ref").asText());
            assertEquals(6.0, mapped.get("qty_x2").asDouble(), 1e-9, "the EXPR rule actually ran");
        }
    }

    /**
     * The scratch source is VARCHAR — exactly as the ingested table production maps over is, which is why
     * {@code TransformCompiler.direct} casts at all. An {@code EXPR} is emitted <b>verbatim</b> and "the
     * author owns validity and any explicit cast", so an uncast arithmetic expression is an authoring error
     * that fails the DuckDB binder here in precisely the way it would at run time. Surfacing that as a 422
     * while the rule is being written IS the point of B1; it must not be silently swallowed to a null cell.
     */
    @Test
    void schemaPreviewRefusesAnUncastExprTheSameWayProductionWould(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            String body = """
                    {"config":{"raw":{"fields":[{"name":"QUANTITY","type":"DOUBLE"}]},
                      "mapping":{"rules":[
                       {"targetColumn":"qty_x2","sourceExpression":"QUANTITY * 2","transformType":"EXPR"}]}},
                     "sampleRows":[{"QUANTITY":"3"}]}""";
            HttpResponse<String> r = post(c.port, "/config/preview/schema", body);
            assertEquals(422, r.statusCode(), "an unbindable expression is the author's error, said up front");
            assertTrue(r.body().contains("Binder Error"), r.body());
        }
    }

    /**
     * G1 ({@code consignment-chain-plan.md} S4): {@code POST /config/suggest/schema} drafts typed
     * {@code raw.fields} + identity mapping rules from the SAME {@code sampleRows} shape the parsing
     * preview emits, so the two routes chain: parse the sample, then suggest from what parsed. A
     * draft for the schema editor — the route never writes anything (stateless compute, no write root).
     */
    @Test
    void schemaSuggestDraftsTypedFieldsFromSampleRows(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            String body = """
                    {"sampleRows":[
                       {"ORDER_ID":"1001","QUANTITY":"3.5","ORDER_DAY":"2026-01-01"},
                       {"ORDER_ID":"1002","QUANTITY":"2","ORDER_DAY":"2026-01-02"}]}""";
            HttpResponse<String> r = post(c.port, "/config/suggest/schema", body);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertEquals("ORDER_ID", out.get("fields").get(0).get("name").asText());
            assertEquals("BIGINT", out.get("fields").get(0).get("type").asText());
            assertEquals("DOUBLE", out.get("fields").get(1).get("type").asText());
            assertEquals("DATE", out.get("fields").get(2).get("type").asText(),
                    "date-only strings demote from TIMESTAMP to DATE");
            assertEquals("ORDER_ID", out.get("mapping").get("rules").get(0).get("targetColumn").asText());
            assertEquals("DIRECT", out.get("mapping").get("rules").get(0).get("transformType").asText());

            assertEquals(400, post(c.port, "/config/suggest/schema", "{}").statusCode(),
                    "no sampleRows is the caller's error, said up front");
            assertNull(V1Body.of(r.body()).get("drift"),
                    "no draft posted → nothing to have drifted from, and the pre-B3 shape stands");
        }
    }

    /**
     * B3 (definition-surface unification P4): posting the draft the caller holds alongside the sample adds
     * a {@code drift} diff — the backing for §5.2's drift indicator and its merge-don't-clobber re-sync.
     * Informational only: it gates nothing, unlike the BACKWARD compatibility save-gate.
     */
    @Test
    void schemaSuggestDiffsThePostedDraftAgainstTheCurrentSample(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            // Draft: QUANTITY declared BIGINT, plus a field whose source column is gone.
            // Sample now votes QUANTITY=DOUBLE and carries a column the draft never had.
            String body = """
                    {"config":{"raw":{"fields":[
                       {"name":"qty","selector":"QUANTITY","type":"BIGINT"},
                       {"name":"legacy","selector":"OLD_COL","type":"VARCHAR"}]}},
                     "sampleRows":[
                       {"QUANTITY":"1.5","NOTE":"hi"},
                       {"QUANTITY":"2.5","NOTE":"there"}]}""";
            HttpResponse<String> r = post(c.port, "/config/suggest/schema", body);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode drift = V1Body.of(r.body()).get("drift");

            assertTrue(drift.get("drifted").asBoolean());
            assertEquals("NOTE", drift.get("added").get(0).get("name").asText());
            assertEquals("legacy", drift.get("missing").get(0).get("name").asText(),
                    "missing is keyed by the draft's own field name");
            assertEquals("qty", drift.get("typeChanged").get(0).get("name").asText());
            assertEquals("BIGINT", drift.get("typeChanged").get(0).get("declared").asText());
            assertEquals("DOUBLE", drift.get("typeChanged").get(0).get("suggested").asText());
        }
    }

    /** A draft still matching its sample reports no drift — the indicator must stay dark. */
    @Test
    void schemaSuggestReportsNoDriftForAnUpToDateDraft(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            String body = """
                    {"config":{"raw":{"fields":[
                       {"name":"account","selector":"ORDER_ID","type":"BIGINT"}]}},
                     "sampleRows":[{"ORDER_ID":"1001"},{"ORDER_ID":"1002"}]}""";
            HttpResponse<String> r = post(c.port, "/config/suggest/schema", body);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode drift = V1Body.of(r.body()).get("drift");

            assertFalse(drift.get("drifted").asBoolean(),
                    "renaming the OUTPUT column is deliberate, not drift — the join key is the selector");
            assertEquals(0, drift.get("added").size());
            assertEquals(0, drift.get("missing").size());
            assertEquals(0, drift.get("typeChanged").size());
        }
    }

    @Test
    void schemaPreviewGates400And422(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(400, post(c.port, "/config/preview/schema",
                    "{\"config\":{\"raw\":{\"fields\":[]}}}").statusCode(), "missing sampleRows");
            HttpResponse<String> noFields = post(c.port, "/config/preview/schema",
                    "{\"config\":{},\"sampleRows\":[{\"a\":\"1\"}]}");
            assertEquals(422, noFields.statusCode(), "schema with no typed fields: " + noFields.body());
        }
    }
}

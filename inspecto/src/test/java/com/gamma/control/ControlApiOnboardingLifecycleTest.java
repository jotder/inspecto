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
                       {"ORDER_ID":"1001","QUANTITY":"3.5","ORDER_DAY":"2026-01-01","SOLD":"March 22,2025"},
                       {"ORDER_ID":"1002","QUANTITY":"2","ORDER_DAY":"2026-01-02","SOLD":"April 1,2025"}]}""";
            HttpResponse<String> r = post(c.port, "/config/suggest/schema", body);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertEquals("ORDER_ID", out.get("fields").get(0).get("name").asText());
            assertEquals("BIGINT", out.get("fields").get(0).get("type").asText());
            assertNull(out.get("fields").get(0).get("format"), "only a proven DATE carries a format");
            assertEquals("DOUBLE", out.get("fields").get(1).get("type").asText());
            assertEquals("DATE", out.get("fields").get(2).get("type").asText(),
                    "date-only strings demote from TIMESTAMP to DATE");
            assertEquals("%Y-%m-%d", out.get("fields").get(2).get("format").asText());
            assertEquals("DATE", out.get("fields").get(3).get("type").asText(), "a human date is a DATE");
            assertEquals("%B %d,%Y", out.get("fields").get(3).get("format").asText(),
                    "…with the strptime format the engine needs to land it");
            // MAPPING-GEN-1: both generators speak the Record Transformer field list; the legacy rules[]
            // (and the transformType this route alone used to stamp) are read-only history.
            assertNull(out.get("mapping").get("rules"), "no legacy rules[] in a generated mapping");
            assertEquals("ORDER_ID", out.get("mapping").get("fields").get(0).get("name").asText());
            assertEquals("ORDER_ID", out.get("mapping").get("fields").get(0).get("from").asText());
            assertEquals("keep", out.get("mapping").get("fields").get(0).get("fn").asText(),
                    "the fn marker is what RecordTransform.isFieldList keys on");

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

    /**
     * LA-ONBOARD-FIXES-1 (F-4), the live repro end to end: parse a delimited CSV sample, ask for a schema,
     * write that schema and a pipeline naming it, then register the pipeline. The suggestion used to propose
     * {@code selector = <column name>}, which the write accepted and registration then refused ("a selector
     * must be a zero-based column index"). Now the suggestion writes indexes and every step succeeds.
     */
    @Test
    void aSuggestedSchemaForACsvSampleWritesAndRegisters(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String parsing = "\"parsing\":{\"frontend\":\"delimited\",\"delimited\":{\"delimiter\":\",\",\"has_header\":true}}";
            HttpResponse<String> parsed = post(c.port, "/config/preview/parsing", """
                    {"config":{"name":"p","dirs":{"poll":"in","database":"out"},"processing":{"threads":1},%s},
                     "sample_text":"txn_id,amount,booked\\nT1,10.5,2026-01-01\\nT2,7,2026-01-02\\n"}""".formatted(parsing));
            assertEquals(200, parsed.statusCode(), parsed.body());
            JsonNode rows = V1Body.of(parsed.body()).get("rows");

            HttpResponse<String> suggested = post(c.port, "/config/suggest/schema", "{\"sampleRows\":" + rows + "}");
            assertEquals(200, suggested.statusCode(), suggested.body());
            JsonNode s = V1Body.of(suggested.body());
            JsonNode fields = s.get("fields");
            assertEquals(3, fields.size(), fields.toString());
            for (int i = 0; i < 3; i++)
                assertEquals(String.valueOf(i), fields.get(i).get("selector").asText(),
                        "a delimited selector is the zero-based column index: " + fields);
            assertEquals("txn_id", fields.get(0).get("name").asText(), "the NAME stays the column's name");

            HttpResponse<String> schema = post(c.port, "/config/write", """
                    {"type":"schema","config":{"raw":{"name":"txn_schema","format":"CSV","fields":%s},"mapping":%s}}"""
                    .formatted(fields, s.get("mapping")));
            assertEquals(200, schema.statusCode(), schema.body());

            HttpResponse<String> pipeline = post(c.port, "/config/write", """
                    {"type":"pipeline","config":{"name":"txn_feed","dirs":{"poll":"in","database":"out"},
                       "processing":{"schema_file":"txn_schema.toon","threads":1},%s}}""".formatted(parsing));
            assertEquals(200, pipeline.statusCode(), pipeline.body());
            String path = V1Body.of(pipeline.body()).get("path").asText();

            HttpResponse<String> reg = post(c.port, "/runs", "{\"configPath\":\"" + path + "\"}");
            assertEquals(2, reg.statusCode() / 100, "the suggested schema registers: " + reg.body());
        }
    }

    /** The suggestion keeps names for a name-addressed frontend, and refuses a frontend it does not know. */
    @Test
    void schemaSuggestKeepsNamesForANameAddressedFrontend(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            String rows = "[{\"txn_id\":\"T1\",\"amount\":\"10\"}]";
            HttpResponse<String> json = post(c.port, "/config/suggest/schema",
                    "{\"frontend\":\"json\",\"sampleRows\":" + rows + "}");
            assertEquals(200, json.statusCode(), json.body());
            assertEquals("amount", V1Body.of(json.body()).get("fields").get(1).get("selector").asText());

            HttpResponse<String> fixed = post(c.port, "/config/suggest/schema",
                    "{\"frontend\":\"fixedwidth\",\"sampleRows\":" + rows + "}");
            assertEquals("1", V1Body.of(fixed.body()).get("fields").get(1).get("selector").asText(),
                    "fixed-width addresses a slice by index too");

            assertEquals(400, post(c.port, "/config/suggest/schema",
                    "{\"frontend\":\"cobol\",\"sampleRows\":" + rows + "}").statusCode(), "an unknown frontend is the caller's error");
        }
    }

    /**
     * The write half of F-4: a delimited pipeline whose schema names its columns would not load, so
     * {@code /config/write} refuses it with the load's own message instead of accepting what registration
     * then refuses. A json pipeline over the same schema is untouched — names are its selectors.
     */
    @Test
    void writingADelimitedPipelineOverANameSelectedSchemaIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, post(c.port, "/config/write", """
                    {"type":"schema","config":{"raw":{"name":"named_schema","format":"CSV",
                       "fields":[{"name":"txn_id","selector":"txn_id","type":"VARCHAR"}]}}}""").statusCode());

            HttpResponse<String> refused = post(c.port, "/config/write", """
                    {"type":"pipeline","config":{"name":"named_feed","dirs":{"poll":"in","database":"out"},
                       "processing":{"schema_file":"named_schema.toon","threads":1}}}""");
            assertEquals(422, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("ERR_SELECTOR_NOT_INDEX"), refused.body());
            assertTrue(refused.body().contains("a selector must be a zero-based column index"), refused.body());
            assertEquals(404, get(c.port, "/config/pipeline/named_feed").statusCode(), "nothing was written");

            HttpResponse<String> json = post(c.port, "/config/write", """
                    {"type":"pipeline","config":{"name":"named_json","dirs":{"poll":"in","database":"out"},
                       "processing":{"schema_file":"named_schema.toon","threads":1},
                       "parsing":{"frontend":"json"}}}""");
            assertEquals(200, json.statusCode(), "a json pipeline selects by name: " + json.body());
            assertFalse(json.body().contains("ERR_SELECTOR_NOT_INDEX"), json.body());
        }
    }

    /**
     * LA-ONBOARD-DRIFT-1, the schema's side of F-4: a schema written ON ITS OWN that a delimited Pipeline already reads
     * must keep index selectors, or that Pipeline stops loading. Both doors (/config/write, /config/patch), an existing
     * schema and one a draft Pipeline names before it exists. The BACKWARD gate is overridden throughout, so only the new
     * check can refuse. Falsified: with the {@code referencedSchemaSelectorFindings} calls removed, every write here
     * answers 200.
     */
    @Test
    void aSchemaWrittenAloneUnderADelimitedPipelineMustKeepIndexSelectors(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, post(c.port, "/config/write", """
                    {"type":"schema","config":{"raw":{"name":"idx_schema","format":"CSV",
                       "fields":[{"name":"txn_id","selector":"0","type":"VARCHAR"}]}}}""").statusCode());
            assertEquals(200, post(c.port, "/config/write", """
                    {"type":"pipeline","config":{"name":"idx_feed","dirs":{"poll":"in","database":"out"},
                       "processing":{"schema_file":"idx_schema.toon","threads":1}}}""").statusCode());
            String before = java.nio.file.Files.readString(root.resolve("idx_schema.toon"));

            HttpResponse<String> named = post(c.port, "/config/write", """
                    {"type":"schema","overwrite":true,"compatibility":"none","config":{"raw":{"name":"idx_schema","format":"CSV",
                       "fields":[{"name":"txn_id","selector":"txn_id","type":"VARCHAR"}]}}}""");
            assertEquals(422, named.statusCode(), named.body());
            assertTrue(named.body().contains("ERR_SELECTOR_NOT_INDEX") && named.body().contains("idx_feed")
                    && named.body().contains("selector 'txn_id'"), named.body());
            HttpResponse<String> patched = post(c.port, "/config/patch", """
                    {"type":"schema","name":"idx_schema","compatibility":"none","patch":{"raw":{
                       "fields":[{"name":"txn_id","selector":"txn_id","type":"VARCHAR"}]}}}""");
            assertEquals(422, patched.statusCode(), patched.body());
            assertTrue(patched.body().contains("ERR_SELECTOR_NOT_INDEX"), patched.body());
            assertEquals(before, java.nio.file.Files.readString(root.resolve("idx_schema.toon")), "nothing was written");
            try (var left = java.nio.file.Files.list(root)) {
                assertTrue(left.noneMatch(p -> p.getFileName().toString().startsWith(".selector-check-")), "no scratch file is left");
            }

            // a draft Pipeline names its schema before the schema exists: the schema's first write is judged too
            assertEquals(200, post(c.port, "/config/write", """
                    {"type":"pipeline","config":{"name":"later_feed","active":false,"dirs":{"poll":"in","database":"out"},
                       "processing":{"schema_file":"later_schema.toon","threads":1}}}""").statusCode());
            HttpResponse<String> first = post(c.port, "/config/write", """
                    {"type":"schema","config":{"raw":{"name":"later_schema","format":"CSV",
                       "fields":[{"name":"txn_id","selector":"txn_id","type":"VARCHAR"}]}}}""");
            assertEquals(422, first.statusCode(), first.body());
            assertTrue(first.body().contains("later_feed"), first.body());
            assertEquals(200, post(c.port, "/config/write", """
                    {"type":"schema","config":{"raw":{"name":"later_schema","format":"CSV",
                       "fields":[{"name":"txn_id","selector":"0","type":"VARCHAR"}]}}}""").statusCode(), "index selectors write");
        }
    }

    /**
     * LA-ONBOARD-DRIFT-1: the drift check of a delimited schema (index selectors, the shape the suggestion writes and the
     * load requires) matches each selector to the sample column at that POSITION. It used to look for a column called
     * {@code "0"}, so an up-to-date schema showed every field missing and every column added.
     */
    @Test
    void anIndexSelectedDelimitedDraftMatchesTheSampleByPosition(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            String rows = "[{\"txn_id\":\"T1\",\"amount\":\"10.5\",\"note\":\"x\"},{\"txn_id\":\"T2\",\"amount\":\"7.25\",\"note\":\"y\"}]";
            JsonNode upToDate = V1Body.of(post(c.port, "/config/suggest/schema", """
                    {"config":{"raw":{"fields":[
                       {"name":"id","selector":"0","type":"VARCHAR"},
                       {"name":"amt","selector":"1","type":"DOUBLE"},
                       {"name":"note","selector":"2","type":"VARCHAR"}]}},
                     "sampleRows":%s}""".formatted(rows)).body()).get("drift");
            assertFalse(upToDate.get("drifted").asBoolean(), upToDate.toString());

            JsonNode drift = V1Body.of(post(c.port, "/config/suggest/schema", """
                    {"frontend":"delimited","config":{"raw":{"fields":[
                       {"name":"id","selector":"0","type":"VARCHAR"},
                       {"name":"amt","selector":"1","type":"BIGINT"},
                       {"name":"gone","selector":"5","type":"VARCHAR"}]}},
                     "sampleRows":%s}""".formatted(rows)).body()).get("drift");
            assertEquals("note", drift.get("added").get(0).get("name").asText(), drift.toString());
            assertEquals(1, drift.get("added").size(), drift.toString());
            assertEquals("gone", drift.get("missing").get(0).get("name").asText(), "a position past the sample is missing");
            assertEquals(1, drift.get("missing").size(), drift.toString());
            assertEquals("amt", drift.get("typeChanged").get(0).get("name").asText());
            assertEquals("DOUBLE", drift.get("typeChanged").get(0).get("suggested").asText());

            // a name-addressed frontend still reads a selector as a column name
            JsonNode json = V1Body.of(post(c.port, "/config/suggest/schema", """
                    {"frontend":"json","config":{"raw":{"fields":[{"name":"id","selector":"0","type":"VARCHAR"}]}},
                     "sampleRows":%s}""".formatted(rows)).body()).get("drift");
            assertEquals("id", json.get("missing").get(0).get("name").asText(), json.toString());
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

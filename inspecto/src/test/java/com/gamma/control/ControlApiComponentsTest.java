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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Component registry CRUD (T19, §7.1) over real HTTP: create / list / get / update / delete
 * grammar/schema/transform/sink under {@code <write-root>/registry}, the write-root 503 gate, and the
 * unknown-type / not-found guards.
 */
class ControlApiComponentsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Open a service + API; when {@code writeRoot} is non-null, enable component writes jailed under it. */
    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);   // captures the write root at construction
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    @Test
    void crudLifecycleForAGrammarComponent(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            // create
            HttpResponse<String> created = send(c.port, "POST", "/components/grammar",
                    "{\"id\":\"pipe\",\"delimiter\":\"|\",\"has_header\":true}");
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("grammar/pipe", json(created).get("ref").asText());
            assertEquals("|", json(created).get("content").get("delimiter").asText());
            // it lands on disk under registry/grammars/
            assertTrue(Files.exists(wr.resolve("registry/grammars/pipe.toon")));

            // duplicate → 409
            assertEquals(409, send(c.port, "POST", "/components/grammar", "{\"id\":\"pipe\",\"delimiter\":\",\"}").statusCode());

            // list + get
            JsonNode list = json(send(c.port, "GET", "/components/grammar", null));
            assertEquals(1, list.size());
            assertEquals("pipe", list.get(0).get("name").asText());
            assertEquals("|", json(send(c.port, "GET", "/components/grammar/pipe", null)).get("content").get("delimiter").asText());

            // update (PUT) replaces content
            assertEquals(200, send(c.port, "PUT", "/components/grammar/pipe", "{\"delimiter\":\";\"}").statusCode());
            assertEquals(";", json(send(c.port, "GET", "/components/grammar/pipe", null)).get("content").get("delimiter").asText());

            // update a missing one → 404
            assertEquals(404, send(c.port, "PUT", "/components/grammar/ghost", "{\"delimiter\":\",\"}").statusCode());

            // delete (no flow references it → allowed)
            assertEquals(200, send(c.port, "DELETE", "/components/grammar/pipe", null).statusCode());
            assertEquals(0, json(send(c.port, "GET", "/components/grammar", null)).size());
            assertEquals(404, send(c.port, "GET", "/components/grammar/pipe", null).statusCode());
        }
    }

    @Test
    void unknownTypeAndConnectionAreRejected(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(400, send(c.port, "GET", "/components/bogus", null).statusCode());
            // connection has its own secret-aware CRUD — not managed here
            assertEquals(400, send(c.port, "POST", "/components/connection", "{\"id\":\"x\"}").statusCode());
        }
    }

    @Test
    void transformPreviewRunsSampleThroughTheProductionShaper(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            // a transform.filter component
            assertEquals(200, send(c.port, "POST", "/components/transform",
                    "{\"id\":\"big-only\",\"type\":\"transform.filter\",\"where\":\"CAST(amt AS INT) >= 100\"}").statusCode());

            // dry-run it over sample rows
            HttpResponse<String> r = send(c.port, "POST", "/components/transform/big-only/test",
                    "{\"sampleRows\":[{\"id\":\"1\",\"amt\":\"150\"},{\"id\":\"2\",\"amt\":\"50\"}]}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = json(r);
            JsonNode rels = body.get("relations");
            int data = 0, dropped = 0;
            for (JsonNode rel : rels) {
                if ("data".equals(rel.get("rel").asText())) data = rel.get("rowCount").asInt();
                if ("dropped".equals(rel.get("rel").asText())) dropped = rel.get("rowCount").asInt();
            }
            assertEquals(1, data, body.toString());        // id 1 (amt 150) kept
            assertEquals(1, dropped);                       // id 2 (amt 50) dropped

            // preview of a missing component → 404
            assertEquals(404, send(c.port, "POST", "/components/transform/ghost/test", "{\"sampleRows\":[]}").statusCode());
        }
    }

    /**
     * A preview whose rows carry a temporal column must serialise, not 500. DuckDB hands a {@code DATE}
     * back as a {@link java.time.LocalDate}, and a bare Jackson mapper refuses those
     * ({@code REQUIRE_HANDLERS_FOR_JAVA8_TIMES}) — so a dry-run that had already produced its rows died
     * in the response writer. Pins the ISO-8601 text form the {@code ApiContext.JSON} serialiser emits.
     */
    @Test
    void aTemporalColumnSerialisesAsIsoTextRatherThanFailingTheResponse(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/components/transform",
                    "{\"id\":\"dated\",\"type\":\"transform.map\",\"columns\":["
                    + "{\"name\":\"id\",\"expr\":\"id\"},"
                    + "{\"name\":\"d\",\"expr\":\"CAST('2026-01-15' AS DATE)\"}]}").statusCode());

            HttpResponse<String> r = send(c.port, "POST", "/components/transform/dated/test",
                    "{\"sampleRows\":[{\"id\":\"1\"}]}");
            assertEquals(200, r.statusCode(), r.body());

            JsonNode rows = null;
            for (JsonNode rel : json(r).get("relations"))
                if ("data".equals(rel.get("rel").asText())) rows = rel.get("rows");
            assertNotNull(rows, r.body());
            assertEquals("2026-01-15", rows.get(0).get("d").asText(), r.body());
        }
    }

    @Test
    void grammarPreviewParsesRawSampleText(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/components/grammar",
                    "{\"id\":\"pipe\",\"delimiter\":\"|\",\"has_header\":true}").statusCode());

            HttpResponse<String> r = send(c.port, "POST", "/components/grammar/pipe/test",
                    "{\"sampleText\":\"a|b|c\\n1|2|3\\n4|5|6\\n\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = json(r);
            assertEquals(2, body.get("rowCount").asInt(), body.toString());
            assertEquals(0, body.get("rejectedRows").asInt());
            assertEquals("a", body.get("columns").get(0).asText());

            assertEquals(404, send(c.port, "POST", "/components/grammar/ghost/test", "{\"sampleText\":\"x\"}").statusCode());
        }
    }

    /**
     * Schema returned as a registry component in ELT amendment Phase 1 slice 3 — the W1 objection
     * (a registry schema nothing could run) is resolved by the {@code schema/<id>} reference wiring
     * in {@code PipelineConfigParser.resolveSchemaRef}. CRUD works again; the old {@code /test}
     * route stays gone (the TRY_CAST split lives on {@code POST /config/preview/schema}).
     * The {@code mapping} kind (same slice) is CSV-backed: {@code registry/mappings/<id>.csv}.
     */
    @Test
    void schemaAndMappingAreComponentKinds(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/components/schema",
                    "{\"id\":\"typed\",\"raw\":{\"name\":\"typed\",\"format\":\"CSV\"}}").statusCode(),
                    "slice 3: a schema component persists again");
            assertEquals(200, send(c.port, "GET", "/components/schema", null).statusCode());
            assertEquals(404, send(c.port, "POST", "/components/schema/typed/test",
                    "{\"sampleRows\":[]}").statusCode(), "the schema /test route stays unregistered");

            assertEquals(200, send(c.port, "POST", "/components/mapping",
                    "{\"id\":\"std\",\"rules\":[{\"targetColumn\":\"A\",\"sourceExpression\":\"A\",\"transformType\":\"DIRECT\"}]}")
                    .statusCode(), "slice 3: the CSV-backed mapping kind persists");
            HttpResponse<String> one = send(c.port, "GET", "/components/mapping/std", null);
            assertEquals(200, one.statusCode());
            assertTrue(one.body().contains("targetColumn"), one.body());
        }
    }

    @Test
    void sinkPreviewValidatesConfigAgainstSample(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/components/sink",
                    "{\"id\":\"out\",\"store\":\"results\",\"format\":\"parquet\",\"partitions\":[\"year\"]}").statusCode());

            HttpResponse<String> r = send(c.port, "POST", "/components/sink/out/test",
                    "{\"sampleRows\":[{\"id\":\"1\"},{\"id\":\"2\"}]}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = json(r);
            assertEquals("results", body.get("store").asText());
            assertEquals(2, body.get("rowCount").asInt());
            assertEquals(1, body.get("warnings").size(), body.toString());   // missing partition column 'year'
        }
    }

    @Test
    void writesAreGatedOnTheWriteRoot(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {                       // no write root configured
            assertEquals(503, send(c.port, "POST", "/components/grammar", "{\"id\":\"pipe\"}").statusCode());
            assertEquals(0, V1Body.of(send(c.port, "GET", "/components/grammar", null).body()).size());
        }
    }

    @Test
    void versionHistoryListsPriorCopiesAndRestores(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            // create → no history yet (nothing was overwritten)
            assertEquals(200, send(c.port, "POST", "/components/grammar", "{\"id\":\"pipe\",\"delimiter\":\"|\"}").statusCode());
            assertEquals(0, json(send(c.port, "GET", "/components/grammar/pipe/versions", null)).size(), "no history on create");

            // two edits → two archived pre-edit copies
            assertEquals(200, send(c.port, "PUT", "/components/grammar/pipe", "{\"delimiter\":\",\"}").statusCode());
            assertEquals(200, send(c.port, "PUT", "/components/grammar/pipe", "{\"delimiter\":\";\"}").statusCode());

            JsonNode versions = json(send(c.port, "GET", "/components/grammar/pipe/versions", null));
            assertEquals(2, versions.size(), versions.toString());
            assertEquals(2, versions.get(0).get("version").asInt(), "newest first");
            assertEquals(",", versions.get(0).get("content").get("delimiter").asText());   // v2 archived the ',' edit
            assertEquals(1, versions.get(1).get("version").asInt());
            assertEquals("|", versions.get(1).get("content").get("delimiter").asText());    // v1 archived the original
            assertNotNull(versions.get(0).get("contentHash").asText());

            // current is the latest edit
            assertEquals(";", json(send(c.port, "GET", "/components/grammar/pipe", null)).get("content").get("delimiter").asText());

            // restore v1 (the original '|') → current reverts, and the outgoing ';' is archived as a new version
            HttpResponse<String> restored = send(c.port, "POST", "/components/grammar/pipe/versions/1/restore", "");
            assertEquals(200, restored.statusCode(), restored.body());
            assertEquals("|", json(restored).get("content").get("delimiter").asText());
            assertEquals("|", json(send(c.port, "GET", "/components/grammar/pipe", null)).get("content").get("delimiter").asText());
            assertEquals(3, json(send(c.port, "GET", "/components/grammar/pipe/versions", null)).size(),
                    "restore archives the outgoing copy");

            // a missing version → 404; a non-integer version → 400
            assertEquals(404, send(c.port, "POST", "/components/grammar/pipe/versions/99/restore", "").statusCode());
            assertEquals(400, send(c.port, "POST", "/components/grammar/pipe/versions/abc/restore", "").statusCode());
            // versions of a missing component → 404
            assertEquals(404, send(c.port, "GET", "/components/grammar/ghost/versions", null).statusCode());
        }
    }

    @Test
    void versionHistoryIsPrunedToTheKeepBound(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {   // default keep = 10
            assertEquals(200, send(c.port, "POST", "/components/grammar", "{\"id\":\"pipe\",\"delimiter\":\"x0\"}").statusCode());
            for (int i = 1; i <= 13; i++)
                assertEquals(200, send(c.port, "PUT", "/components/grammar/pipe", "{\"delimiter\":\"x" + i + "\"}").statusCode());
            JsonNode versions = json(send(c.port, "GET", "/components/grammar/pipe/versions", null));
            assertEquals(10, versions.size(), "pruned to the keep bound");
            assertEquals(13, versions.get(0).get("version").asInt(), "newest kept");
            assertEquals(4, versions.get(9).get("version").asInt(), "oldest kept = 13 - 10 + 1");
        }
    }

    @Test
    void versionHistoryIsInvisibleToTheRegistryScan(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            assertEquals(200, send(c.port, "POST", "/components/grammar", "{\"id\":\"pipe\",\"delimiter\":\"|\"}").statusCode());
            assertEquals(200, send(c.port, "PUT", "/components/grammar/pipe", "{\"delimiter\":\",\"}").statusCode());
            // the archived copy sits in registry/grammars/.history/, never scanned as a component
            assertTrue(Files.exists(wr.resolve("registry/grammars/.history/pipe.v1.toon")), "archived under .history/");
            JsonNode list = json(send(c.port, "GET", "/components/grammar", null));
            assertEquals(1, list.size(), "history copies do not appear as components");
            assertEquals("pipe", list.get(0).get("name").asText());
        }
    }

    // ── the INLINE preview arm: POST /components/{family}/preview ─────────────────────

    /**
     * The gap this closes: a pipeline node authors its config INLINE, so before this route the only
     * configs an operator could test were the registered ones — precisely not the one being written.
     * Run with NO write root at all, because that is the proof it needs no registry: the by-id arm
     * would 503/404 here.
     */
    @Test
    void inlinePreviewsRunWithoutAnyRegistry(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {                       // no write root — nothing is stored
            HttpResponse<String> t = send(c.port, "POST", "/components/transform/preview",
                    "{\"config\":{\"type\":\"transform.filter\",\"where\":\"CAST(amt AS INT) >= 100\"},"
                    + "\"sampleRows\":[{\"id\":\"1\",\"amt\":\"150\"},{\"id\":\"2\",\"amt\":\"50\"}]}");
            assertEquals(200, t.statusCode(), t.body());
            int data = 0, dropped = 0;
            for (JsonNode rel : json(t).get("relations")) {
                if ("data".equals(rel.get("rel").asText())) data = rel.get("rowCount").asInt();
                if ("dropped".equals(rel.get("rel").asText())) dropped = rel.get("rowCount").asInt();
            }
            assertEquals(1, data, t.body());
            assertEquals(1, dropped, t.body());

            HttpResponse<String> g = send(c.port, "POST", "/components/grammar/preview",
                    "{\"config\":{\"delimiter\":\"|\",\"has_header\":true},"
                    + "\"sampleText\":\"a|b|c\\n1|2|3\\n4|5|6\\n\"}");
            assertEquals(200, g.statusCode(), g.body());
            assertEquals(2, json(g).get("rowCount").asInt(), g.body());
            assertEquals("a", json(g).get("columns").get(0).asText());

            HttpResponse<String> k = send(c.port, "POST", "/components/sink/preview",
                    "{\"config\":{\"store\":\"results\",\"format\":\"parquet\",\"partitions\":[\"year\"]},"
                    + "\"sampleRows\":[{\"id\":\"1\"},{\"id\":\"2\"}]}");
            assertEquals(200, k.statusCode(), k.body());
            assertEquals("results", json(k).get("store").asText());
            assertEquals(1, json(k).get("warnings").size(), k.body());   // missing partition column 'year'
        }
    }

    /**
     * S1 over real HTTP: the route publishes the DERIVED output schema and the SQL the config compiled
     * to, so an author never restates the schema. Both are additive keys — a client that ignores them
     * sees the pre-S1 response unchanged.
     */
    @Test
    void inlineTransformPreviewPublishesDerivedTypesAndTheCompiledSql(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            HttpResponse<String> t = send(c.port, "POST", "/components/transform/preview",
                    "{\"config\":{\"type\":\"transform.map\",\"columns\":[{\"name\":\"ident\",\"expr\":\"id\"},{\"name\":\"amt_d\",\"expr\":\"CAST(amt AS DOUBLE)\"}]},\"sampleRows\":[{\"id\":\"1\",\"amt\":\"150\"},{\"id\":\"2\",\"amt\":\"50\"}]}");
            assertEquals(200, t.statusCode(), t.body());

            JsonNode data = null;
            for (JsonNode rel : json(t).get("relations"))
                if ("data".equals(rel.get("rel").asText())) data = rel;
            assertNotNull(data, t.body());

            // The derived schema: DuckDB's own types, in the authored column order.
            JsonNode types = data.get("columnTypes");
            assertEquals(2, types.size(), t.body());
            assertEquals("ident", types.get(0).get("name").asText());
            assertEquals("VARCHAR", types.get(0).get("type").asText(), "a passthrough stays VARCHAR");
            assertEquals("amt_d", types.get(1).get("name").asText());
            assertEquals("DOUBLE", types.get(1).get("type").asText(), t.body());

            // The compiled SQL, as executed — the author's own expression appears in it.
            JsonNode sql = json(t).get("sql");
            assertTrue(sql.size() > 0, t.body());
            assertTrue(sql.toString().contains("CAST(amt AS DOUBLE)"), t.body());
        }
    }

    /**
     * 🔴 A transform preview runs AUTHOR-SUPPLIED SQL, so its connection is sealed. Before S1 a `where`
     * containing read_csv was a live file read on the server — proven by removing the seal, where a
     * readable file was read successfully. The route must now refuse it, as a 422 rather than a 500.
     */
    @Test
    void authorSqlInAPreviewCannotReachTheFilesystem(@TempDir Path dir) throws Exception {
        Path readable = Files.createTempFile("route_seal_probe_", ".csv");
        Files.writeString(readable, "a\n1\n");
        try (Ctx c = open(dir, null)) {
            String path = readable.toString().replace("\\", "/").replace("'", "''");
            HttpResponse<String> t = send(c.port, "POST", "/components/transform/preview",
                    String.format("{\"config\":{\"type\":\"transform.filter\",\"where\":\"(SELECT count(*) FROM read_csv('%s')) >= 0\"},\"sampleRows\":[{\"id\":\"1\",\"amt\":\"150\"}]}", path));
            assertEquals(422, t.statusCode(), t.body());
            assertTrue(t.body().toLowerCase().contains("permission")
                            || t.body().toLowerCase().contains("not allowed")
                            || t.body().toLowerCase().contains("disabled"),
                    "the seal should refuse external access: " + t.body());
        } finally {
            Files.deleteIfExists(readable);
        }
    }

    /** An inline preview reports the SAME result as the by-id arm — one code path, two ways in. */
    @Test
    void inlineAndRegisteredPreviewsAgree(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String cfg = "{\"type\":\"transform.filter\",\"where\":\"CAST(amt AS INT) >= 100\"}";
            String rows = "\"sampleRows\":[{\"id\":\"1\",\"amt\":\"150\"},{\"id\":\"2\",\"amt\":\"50\"}]";
            assertEquals(200, send(c.port, "POST", "/components/transform",
                    "{\"id\":\"big-only\",\"type\":\"transform.filter\",\"where\":\"CAST(amt AS INT) >= 100\"}")
                    .statusCode());

            JsonNode byId = json(send(c.port, "POST", "/components/transform/big-only/test", "{" + rows + "}"));
            JsonNode inline = json(send(c.port, "POST", "/components/transform/preview",
                    "{\"config\":" + cfg + "," + rows + "}"));
            assertEquals(byId.get("relations").toString(), inline.get("relations").toString());
        }
    }

    @Test
    void inlinePreviewGates(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            assertEquals(400, send(c.port, "POST", "/components/transform/preview", "{\"sampleRows\":[]}").statusCode(),
                    "no 'config' in the body");
            assertEquals(400, send(c.port, "POST", "/components/grammar/preview",
                    "{\"config\":\"not-an-object\",\"sampleText\":\"x\"}").statusCode());
            assertEquals(422, send(c.port, "POST", "/components/transform/preview",
                    "{\"config\":{\"type\":\"sink.persistent\"},\"sampleRows\":[]}").statusCode(),
                    "a non-transform type is refused, same as the by-id arm");
        }
    }

    /**
     * JAVA-6: `/components/schema/{id}` and `POST /config/write {type:"schema"}` write the SAME FILE —
     * `registry/schemas/<id>.toon`, which the engine loads for a `schema_file: schema/<id>` ref — but only
     * the /config/write side ran the structural + safety gates. This route was an ungated back door to a
     * live, engine-executed artifact. The UI already avoided it by convention (the schema editor's own
     * comment says so); `component_apply` did not, so the gate belongs on the server.
     */
    @Test
    void schemaComponentIsGatedLikeItsConfigWriteSibling(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            // a schema with no `raw.name` is structurally invalid — its sibling 422s, so this must too
            HttpResponse<String> bad = send(c.port, "POST", "/components/schema",
                    "{\"id\":\"orders\",\"raw\":{\"format\":\"CSV\"}}");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("raw.name"), bad.body());
            assertFalse(Files.exists(wr.resolve("registry/schemas/orders.toon")),
                    "a refused schema must not reach the file the engine parses");

            // a structurally valid one still writes — the gate matches its sibling, it is not stricter
            HttpResponse<String> ok = send(c.port, "POST", "/components/schema",
                    "{\"id\":\"orders\",\"raw\":{\"name\":\"orders\",\"format\":\"CSV\"}}");
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(Files.exists(wr.resolve("registry/schemas/orders.toon")));

            // and the gate is on the UPDATE path too, not only create — same back door, different verb
            HttpResponse<String> badUpdate = send(c.port, "PUT", "/components/schema/orders",
                    "{\"raw\":{\"format\":\"CSV\"}}");
            assertEquals(422, badUpdate.statusCode(), badUpdate.body());
        }
    }

    /**
     * The other half of the /config/write parity: a BACKWARD-breaking schema edit is refused on the
     * component route too, with the same cell-level findings — and the override is a QUERY parameter,
     * because a component body IS the content (a `compatibility` key there would be persisted into the
     * schema). Create and restore are exempt for their own reasons, asserted elsewhere.
     */
    @Test
    void schemaComponentUpdateIsBackwardGatedWithAQueryParamOverride(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            String v1 = "{\"id\":\"orders\",\"raw\":{\"name\":\"orders\",\"format\":\"CSV\","
                    + "\"fields\":[{\"name\":\"ID\",\"selector\":\"0\",\"type\":\"VARCHAR\"},"
                    + "{\"name\":\"AMT\",\"selector\":\"1\",\"type\":\"VARCHAR\"}]}}";
            assertEquals(200, send(c.port, "POST", "/components/schema", v1).statusCode());

            // dropping AMT is BACKWARD-breaking
            String v2 = "{\"raw\":{\"name\":\"orders\",\"format\":\"CSV\","
                    + "\"fields\":[{\"name\":\"ID\",\"selector\":\"0\",\"type\":\"VARCHAR\"}]}}";
            HttpResponse<String> refused = send(c.port, "PUT", "/components/schema/orders", v2);
            assertEquals(422, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("BACKWARD"), refused.body());
            assertTrue(refused.body().contains("findings"), refused.body());
            // …and the refusal did not write: AMT is still there
            assertTrue(send(c.port, "GET", "/components/schema/orders", null).body().contains("AMT"));

            // the override is on the QUERY STRING, not in the content
            HttpResponse<String> forced = send(c.port, "PUT",
                    "/components/schema/orders?compatibility=none", v2);
            assertEquals(200, forced.statusCode(), forced.body());
            String after = send(c.port, "GET", "/components/schema/orders", null).body();
            assertFalse(after.contains("AMT"), "the breaking edit landed once overridden");
            assertFalse(after.contains("compatibility"),
                    "the override is a query param and must never be persisted into the schema");
        }
    }

    /**
     * The compat gate's restore EXEMPTION, pinned directly rather than inferred. A rollback is a recovery
     * action whose target was itself a valid schema, so gating it would let a bad edit lock an operator
     * out of the version that fixes it. The restore below IS backward-breaking relative to current (it
     * drops a field that was added after it) and must still succeed — if someone moves the gate from
     * `updateComponent` into `writeComponent`, this is the test that goes red.
     */
    @Test
    void schemaRestoreIsExemptFromTheBackwardGate(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            String oneField = "{\"id\":\"orders\",\"raw\":{\"name\":\"orders\",\"format\":\"CSV\","
                    + "\"fields\":[{\"name\":\"ID\",\"selector\":\"0\",\"type\":\"VARCHAR\"}]}}";
            assertEquals(200, send(c.port, "POST", "/components/schema", oneField).statusCode());

            // ADDING a field is backward-compatible, so this update passes the gate normally
            String twoFields = "{\"raw\":{\"name\":\"orders\",\"format\":\"CSV\","
                    + "\"fields\":[{\"name\":\"ID\",\"selector\":\"0\",\"type\":\"VARCHAR\"},"
                    + "{\"name\":\"AMT\",\"selector\":\"1\",\"type\":\"VARCHAR\"}]}}";
            assertEquals(200, send(c.port, "PUT", "/components/schema/orders", twoFields).statusCode());

            // Rolling back to v1 DROPS AMT — the same edit the gate refuses on an update path…
            assertEquals(422, send(c.port, "PUT", "/components/schema/orders", oneField).statusCode(),
                    "sanity: this content really is backward-breaking against current");

            // …yet the restore of that very version succeeds, with no override needed.
            HttpResponse<String> restored = send(c.port, "POST",
                    "/components/schema/orders/versions/1/restore", "");
            assertEquals(200, restored.statusCode(), restored.body());
            assertFalse(send(c.port, "GET", "/components/schema/orders", null).body().contains("AMT"),
                    "the rollback landed");
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

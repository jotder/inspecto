package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.catalog.SemanticModel;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests for the v3.2.0 {@code /catalog*} routes over real HTTP (P6). */
class ControlApiCatalogTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private static SemanticModel semantics() {
        var kpi = new SemanticModel.KpiMeta("daily", "Daily count", "day",
                List.of("mini_etl/mini"), List.of("ID"));
        return new SemanticModel("sem", Map.of(), Map.of("daily", kpi), Map.of(),
                new SemanticModel.DomainNotes("USD", "UTC", List.of("excludes tax")));
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(),
                List.of(semantics()), 3600, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        return client.send(b.method("GET", BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    }

    /** AUTHORING-REDESIGN-1 (e): a sql Step's fields[] metadata is read back over HTTP as a STEP node + columns. */
    @Test
    void aSqlStepsDescribedColumnsAreReadableOverHttp(@TempDir Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, """
                steps[1]:
                  - sql:
                      sql: "SELECT ID AS REF FROM input"
                      fields[1]:
                        - name: REF
                          from: ID
                          fn: keep
                          description: Booking reference
                          classification: PII
                """, false);
        CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), List.of(), 3600, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try (Ctx c = new Ctx(svc, api, api.port())) {
            HttpResponse<String> r = get(c.port, "/catalog/graph?from=step:mini_etl/step1&depth=1&direction=both");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode g = V1Body.of(r.body());
            JsonNode col = null, step = null;
            for (JsonNode n : g.get("nodes")) {
                if ("col:mini_etl/step1/REF".equals(n.get("id").asText())) col = n;
                if ("step:mini_etl/step1".equals(n.get("id").asText())) step = n;
            }
            assertNotNull(step, g.toString());
            assertEquals("STEP", step.get("kind").asText());
            assertNotNull(col, "the Step's column is a neighbour: " + g);
            assertEquals("PII", col.get("attrs").get("classification").asText());
            assertTrue(col.toString().contains("Booking reference"), col.toString());
        }
    }

    @Test
    void catalogListsEmittedTables(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode body = V1Body.of(get(c.port, "/catalog").body());
            assertTrue(body.isArray());
            boolean hasEvent = false;
            for (JsonNode n : body) if ("event:mini_etl/mini".equals(n.get("id").asText())) hasEvent = true;
            assertTrue(hasEvent, "the emitted event table is listed: " + body);
        }
    }

    @Test
    void graphTraversesFromKpiDownToSource(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = get(c.port,
                    "/catalog/graph?from=kpi:daily&depth=5&direction=both");
            assertEquals(200, r.statusCode());
            JsonNode g = V1Body.of(r.body());
            boolean reachesSource = false;
            for (JsonNode n : g.get("nodes")) if ("stream:mini_etl".equals(n.get("id").asText())) reachesSource = true;
            assertTrue(reachesSource, "KPI traversal reaches the source: " + g.get("nodes"));
        }
    }

    @Test
    void tableDetailReturnsNodeAndNeighbours(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = get(c.port, "/catalog/tables/event:mini_etl/mini");
            assertEquals(200, r.statusCode());
            JsonNode body = V1Body.of(r.body());
            assertEquals("event:mini_etl/mini", body.get("node").get("id").asText());
            assertNotNull(body.get("node").get("overlay"), "detail hydrates the overlay");
            assertTrue(body.get("neighbors").get("nodes").size() > 1, "neighbours include schema/columns");
        }
    }

    @Test
    void kpisEndpointReturnsCatalogAndDomain(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode body = V1Body.of(get(c.port, "/catalog/kpis").body());
            JsonNode kpis = body.get("kpis");
            assertEquals(1, kpis.size());
            assertEquals("daily", kpis.get(0).get("name").asText());
            // bare ref resolved to the event-table node id
            assertEquals("event:mini_etl/mini", kpis.get(0).get("inputs").get(0).asText());
            assertEquals("USD", body.get("domain").get("currency").asText());
        }
    }

    /** As {@link #open} but with a selector-declared schema, the only branch that records a {@code table}. */
    private Ctx openWithTable(Path dir, String table) throws Exception {
        Path schema = dir.resolve("t_schema.toon");
        java.nio.file.Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Path pipe = dir.resolve("t_pipeline.toon");
        String d = dir.toString().replace("\\", "/");
        java.nio.file.Files.writeString(pipe, """
                name: VOUCHER
                dirs:
                  poll: %s/inbox
                  database: %s/db
                output:
                  format: CSV
                processing:
                  threads: 1
                  schemas[1]{column_count,file_pattern,schema_file,table}:
                    3, "", "%s", %s
                """.formatted(d, d, schema.toString().replace("\\", "/"), table));
        CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(),
                List.of(semantics()), 3600, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /**
     * CATALOG-LINK-1 (2026-09-06): a batch with no {@code output_table} resolves by pipeline — the single
     * event node when there is one (this fixture), the Stream node otherwise — and never a fabricated store.
     * {@code table} still wins when both params are present; an unknown pipeline is a 404.
     */
    @Test
    void resolveByPipelineLinksTheSingleEventNodeAndNeverInventsAStore(@TempDir Path dir) throws Exception {
        try (Ctx c = openWithTable(dir, "voucher_main")) {
            HttpResponse<String> r = get(c.port, "/catalog/resolve?pipeline=VOUCHER");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = V1Body.of(r.body());
            assertEquals("TABLE", body.get("kind").asText());
            assertTrue(body.get("id").asText().toLowerCase().startsWith("event:voucher/"), "the graph keys the pipeline by its lower-cased id: " + body);
            assertEquals(200, get(c.port, "/catalog/tables/" + body.get("id").asText()).statusCode());

            JsonNode byTable = V1Body.of(get(c.port, "/catalog/resolve?pipeline=VOUCHER&table=voucher_main").body());
            assertEquals(body.get("id").asText(), byTable.get("id").asText(), "table wins, and here they agree");

            assertEquals(404, get(c.port, "/catalog/resolve?pipeline=nope").statusCode());
            assertEquals(404, get(c.port, "/catalog/resolve").statusCode(), "nothing named is still the old 404, never a guess");
        }
    }

    @Test
    void resolveMapsABatchOutputTableToItsCatalogNodeId(@TempDir Path dir) throws Exception {
        try (Ctx c = openWithTable(dir, "voucher_main")) {
            HttpResponse<String> r = get(c.port, "/catalog/resolve?table=voucher_main");
            assertEquals(200, r.statusCode());
            JsonNode body = V1Body.of(r.body());
            assertEquals("voucher_main", body.get("label").asText());
            // the id must be the one /catalog/tables/{id} serves — i.e. the link actually lands
            assertEquals(200, get(c.port, "/catalog/tables/" + body.get("id").asText()).statusCode());
        }
    }

    @Test
    void resolveIs404WhenTheStoreNameCannotBeProven(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // this pipeline's event node exists but carries no table attr (single-schema branch)
            assertEquals(404, get(c.port, "/catalog/resolve?table=mini").statusCode());
            assertEquals(404, get(c.port, "/catalog/resolve?table=nope").statusCode());
            assertEquals(404, get(c.port, "/catalog/resolve").statusCode());
        }
    }

    @Test
    void unknownNodeIs404AndBadFilterIs400(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, get(c.port, "/catalog/tables/kpi:nope").statusCode());
            assertEquals(400, get(c.port, "/catalog/graph?direction=sideways").statusCode());
            assertEquals(400, get(c.port, "/catalog/graph?kinds=BOGUS").statusCode());
        }
    }
}

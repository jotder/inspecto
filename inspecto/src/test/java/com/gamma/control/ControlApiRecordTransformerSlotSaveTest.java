package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.DisplayName;
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
 * The default template's Record Transformer (the projection SLOT, node id {@code map}, type
 * {@code transform.sql}) saves over real HTTP.
 *
 * <p>Found by driving the UI (2026-09-25): a new Pipeline's slot, configured in the Fields grid, sent
 * {@code {sql, fields}} — the pane's persisted shape for a CHAIN sql step — and {@code PUT …/graph}
 * answered {@code 422 UNSUPPORTED_MAP_KEY} "a map node has no home for 'sql'". In the slot {@code fields[]}
 * is what the engine compiles ({@code RecordTransform}); the {@code sql} beside it is the grid's own
 * rendering of those rows, so it is derived and is never written. A {@code sql} with NO fields is a
 * hand-written SELECT, which the slot has no home for — that one still refuses.
 */
class ControlApiRecordTransformerSlotSaveTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    /**
     * Registers the Onboard Stream draft the editor opens: an inactive {@code shop_orders} inside the write
     * root, naming the schema beside it — the state the user was in before pressing Save.
     */
    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path mini = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("seed")), "", false);
        String miniSchema = mini.resolveSibling("mini_schema.toon").toString().replace('\\', '/');
        Path pipe = writeRoot.resolve("shop_orders_pipeline.toon");
        Files.writeString(pipe, Files.readString(mini).replace("name: MINI_ETL", "name: shop_orders")
                .replace(miniSchema, "shop_orders_schema.toon"));
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    /** The schema the Onboard Stream draft's parser names — six columns, all declared VARCHAR. */
    private static void shopOrdersSchema(Path writeRoot) throws Exception {
        Files.createDirectories(writeRoot);
        Files.writeString(writeRoot.resolve("shop_orders_schema.toon"), """
                raw:
                  name: shop_orders
                  format: CSV
                  fields[6]{name,selector,type}:
                    order_id,"0",VARCHAR
                    order_date,"1",VARCHAR
                    customer,"2",VARCHAR
                    region,"3",VARCHAR
                    amount,"4",VARCHAR
                    currency,"5",VARCHAR
                """);
    }

    /** The slot's config exactly as the repro captured it: the grid's rendered {@code sql} AND its {@code fields}. */
    private static final String REPRO_MAP_CONFIG = """
            {"sql":"SELECT\\n  order_id AS order_id,\\n  TRY_CAST(order_date AS DATE) AS order_date,\\n  customer AS customer,\\n  region AS region,\\n  TRY_CAST(amount AS DECIMAL(18,2)) AS amount,\\n  currency AS currency\\nFROM input",
             "fields":[{"id":"seed-0-order_id","name":"order_id","from":"order_id","fn":"keep","args":{}},
                       {"id":"seed-1-order_date","name":"order_date","from":"order_date","fn":"convert.type","args":{"type":"DATE"}},
                       {"id":"seed-2-customer","name":"customer","from":"customer","fn":"keep","args":{}},
                       {"id":"seed-3-region","name":"region","from":"region","fn":"keep","args":{}},
                       {"id":"seed-4-amount","name":"amount","from":"amount","fn":"convert.type","args":{"type":"DECIMAL(18,2)"}},
                       {"id":"seed-5-currency","name":"currency","from":"currency","fn":"keep","args":{}}]}""";

    /** The repro's {@code nodes[]} with its data paths re-pointed under the test's temp dir. */
    private static String reproGraph(Path dir, String mapConfig) {
        String d = dir.toString().replace('\\', '/') + "/data";
        return """
            {"active":false,
             "nodes":[
              {"id":"acq","type":"acquisition","config":{"poll":"%1$s/inbox/shop_orders","duplicate_check":true,"marker_extension":".processed","markers_dir":"%1$s/shop_orders/markers"}},
              {"id":"parse","type":"parser.delimited","config":{"parsing":{"delimited":{"delimiter":",","has_header":true,"quote":"\\"","skip_header_lines":0,"skip_junk_lines":0,"skip_tail_lines":0,"skip_tail_columns":0,"ignore_errors":true,"null_padding":false,"store_rejects":true,"rejects_table":"reject_errors","rejects_scan":"reject_scans","filter_target_column":0},"encoding":"utf-8","compression":"auto","frontend":"delimited"},"schema_file":"shop_orders_schema.toon"}},
              {"id":"map","type":"transform.sql","config":%2$s},
              {"id":"sink","type":"sink.persistent","config":{"store":"shop_orders","filename_column":"file_name","database":"%1$s/shop_orders/database","backup":"%1$s/shop_orders/backup","temp":"%1$s/shop_orders/temp","threads":1}}],
             "edges":[{"from":"acq","rel":"data","to":"parse"},{"from":"parse","rel":"data","to":"map"},{"from":"map","rel":"data","to":"sink"}]}"""
                .formatted(d, mapConfig);
    }

    @Test
    @DisplayName("the repro payload saves: 200, fields[] lowered, no sql written, the Pipeline loads and round-trips")
    void theDefaultTemplatesRecordTransformerSaves(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        shopOrdersSchema(wr);
        try (Ctx c = open(dir, wr)) {
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", reproGraph(dir, REPRO_MAP_CONFIG));
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(V1Body.of(r.body()).get("written").asBoolean(), r.body());

            // The written TOON: the slot's authored half is processing.map.fields — and only that.
            Path file = wr.resolve("shop_orders_pipeline.toon");
            String toon = Files.readString(file);
            assertFalse(toon.contains("TRY_CAST(order_date"), "the rendered sql is derived and must not be written:\n" + toon);
            assertFalse(toon.contains("steps"), "the slot stays a slot, never a chain step:\n" + toon);

            // It LOADS — the same parse ConfigRegistry runs — and carries the six fields.
            PipelineConfig cfg = PipelineConfig.load(file.toString());
            assertNotNull(cfg.mapConfig(), toon);
            List<Map<String, Object>> fields = cfg.mapConfig().fields();
            assertEquals(6, fields.size(), toon);
            assertEquals("convert.type", fields.get(1).get("fn"), toon);
            assertEquals(Map.of("type", "DECIMAL(18,2)"), fields.get(4).get("args"), toon);

            // ConfigRegistry registered it: listed, with no loadError.
            JsonNode listed = V1Body.of(send(c.port, "GET", "/pipelines", null).body());
            JsonNode row = null;
            for (JsonNode p : listed) if ("shop_orders".equals(p.path("name").asText())) row = p;
            assertNotNull(row, "the saved Pipeline must register: " + listed);
            assertFalse(row.has("loadError"), row.toString());

            // Round-trip: the editor re-reads through GET /graph/raw (the editable graph; /graph is the
            // read-only projection) and gets the same fields and types back on the slot, and no sql.
            HttpResponse<String> g = send(c.port, "GET", "/pipelines/shop_orders/graph/raw", null);
            assertEquals(200, g.statusCode(), g.body());
            JsonNode map = null;
            for (JsonNode n : V1Body.of(g.body()).get("nodes")) if ("map".equals(n.path("id").asText())) map = n;
            assertNotNull(map, g.body());
            assertEquals("transform.sql", map.get("type").asText());
            assertFalse(map.get("config").has("sql"), map.toString());
            JsonNode back = map.get("config").get("fields");
            assertNotNull(back, "the slot lost its fields across the save: " + map + "\n" + toon);
            assertEquals(6, back.size(), map.toString());
            assertEquals("order_date", back.get(1).get("name").asText());
            assertEquals("DATE", back.get(1).get("args").get("type").asText());
            assertEquals("DECIMAL(18,2)", back.get(4).get("args").get("type").asText());
            assertEquals("keep", back.get(5).get("fn").asText());
        }
    }

    @Test
    @DisplayName("an untouched pass-through slot (no config at all) saves and loads")
    void anUntouchedSlotSaves(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        shopOrdersSchema(wr);
        try (Ctx c = open(dir, wr)) {
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", reproGraph(dir, "{}"));
            assertEquals(200, r.statusCode(), r.body());
            PipelineConfig cfg = PipelineConfig.load(wr.resolve("shop_orders_pipeline.toon").toString());
            assertNull(cfg.mapConfig(), "nothing authored ⇒ no processing.map; the schema projects as-is");
        }
    }

    @Test
    @DisplayName("a hand-written sql with NO fields in the slot still refuses — the slot compiles from fields[]")
    void handWrittenSqlInTheSlotStillRefuses(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        shopOrdersSchema(wr);
        try (Ctx c = open(dir, wr)) {
            String draft = Files.readString(wr.resolve("shop_orders_pipeline.toon"));
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph",
                    reproGraph(dir, "{\"sql\":\"SELECT order_id FROM input WHERE region = 'EU'\",\"fields\":[]}"));
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("UNSUPPORTED_MAP_KEY") && r.body().contains("compiles from fields[]"), r.body());
            assertEquals(draft, Files.readString(wr.resolve("shop_orders_pipeline.toon")), "a refused save writes nothing");
        }
    }
}

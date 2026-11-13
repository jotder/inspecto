package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.service.ColumnContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-3a — the Schema save warning over real HTTP (D-SE4 step 1, report-only): a save through the config write
 * funnel ({@code /config/write}, {@code /config/patch}) that renames or removes a mapped column consumers use is
 * NOT refused — it is written, with one {@code WARN_COLUMN_BREAKS} finding naming the consumers. An additive save
 * gets none; the kill switch silences it.
 */
class ControlApiColumnContractSaveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void clear() {
        System.clearProperty(ColumnContract.PROPERTY);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static void demoCopy(Path root) throws Exception {
        Path src = ReactorModules.root().resolve("spaces/demo/config");
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : s.toList()) {
                Path rel = src.relativize(p);
                if (rel.toString().replace('\\', '/').contains(".history")) continue;
                Path to = root.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
    }

    private HttpResponse<String> post(int port, String path, Object body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The stored demo orders schema with mapping.fields[GROSS] renamed (or, {@code to == null}, a column added). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> ordersSchema(Path root, String to) {
        Map<String, Object> doc = ColumnContract.stored(root.resolve("orders/orders_schema.toon"));
        assertNotNull(doc);
        Map<String, Object> mapping = new LinkedHashMap<>((Map<String, Object>) doc.get("mapping"));
        List<Object> fields = new ArrayList<>();
        for (Object o : (List<Object>) mapping.get("fields")) {
            Map<String, Object> f = new LinkedHashMap<>((Map<String, Object>) o);
            if (to != null && "GROSS".equals(f.get("name"))) f.put("name", to);
            fields.add(f);
        }
        if (to == null) fields.add(new LinkedHashMap<>(Map.of("name", "NOTE_X", "from", "STATUS", "fn", "keep")));
        mapping.put("fields", fields);
        Map<String, Object> out = new LinkedHashMap<>(doc);
        out.put("mapping", mapping);
        return out;
    }

    private static List<JsonNode> warnings(JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode f : body.path("findings"))
            if (ColumnContract.WARN_COLUMN_BREAKS.equals(f.path("code").asText())) out.add(f);
        return out;
    }

    private static Map<String, Object> write(Map<String, Object> config) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "schema");
        b.put("file", "orders_schema");
        b.put("subdir", "orders");
        b.put("config", config);
        b.put("overwrite", true);
        return b;
    }

    @Test
    void aRenameThroughConfigWriteIsWrittenWithAWarningNamingTheConsumers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        demoCopy(root);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", write(ordersSchema(root, "GROSS_AMOUNT")));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = V1Body.of(r.body());
            assertTrue(body.get("written").asBoolean(), "report-only: the save goes ahead");
            List<JsonNode> w = warnings(body);
            assertEquals(1, w.size(), body.toString());
            assertEquals("WARNING", w.get(0).get("severity").asText());
            String msg = w.get(0).get("message").asText();
            assertTrue(msg.contains("'GROSS' renamed to 'GROSS_AMOUNT'"), msg);
            assertTrue(msg.contains("breaks " + GROSS_CONSUMER_COUNT + " consumer(s)"), msg);
            for (String holder : List.of("expectation 'orders_gross_non_null'", "widget 'orders_daily_trend'",
                    "reconciliation 'orders_regional_recon'", "enrichment 'orders_daily_enrich'"))
                assertTrue(msg.contains(holder), holder + " missing from: " + msg);
            assertTrue(Files.readString(root.resolve("orders/orders_schema.toon")).contains("GROSS_AMOUNT"), "written");
        }
    }

    @Test
    void aRemovalThroughConfigPatchIsWrittenWithAWarning(@TempDir Path cfg, @TempDir Path root) throws Exception {
        demoCopy(root);
        Map<String, Object> schema = ordersSchema(root, "GROSS_AMOUNT");
        @SuppressWarnings("unchecked")
        List<Object> fields = new ArrayList<>((List<Object>) ((Map<String, Object>) schema.get("mapping")).get("fields"));
        fields.removeIf(o -> "GROSS_AMOUNT".equals(((Map<?, ?>) o).get("name")));
        Map<String, Object> patch = Map.of("mapping", Map.of("fields", fields));
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/patch",
                    Map.of("type", "schema", "name", "orders_schema", "subdir", "orders", "patch", patch));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = V1Body.of(r.body());
            List<JsonNode> w = warnings(body);
            assertEquals(1, w.size(), body.toString());
            assertTrue(w.get(0).get("message").asText().contains("'GROSS' removed"), w.toString());
            assertFalse(Files.readString(root.resolve("orders/orders_schema.toon")).contains("name: GROSS"), "written");
        }
    }

    @Test
    void anAdditiveSaveHasNoWarningAndTheKillSwitchSilencesIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        demoCopy(root);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> add = post(c.port, "/config/write", write(ordersSchema(root, null)));
            assertEquals(200, add.statusCode(), add.body());
            assertEquals(List.of(), warnings(V1Body.of(add.body())), "an added column is safe");

            System.setProperty(ColumnContract.PROPERTY, "off");
            HttpResponse<String> off = post(c.port, "/config/write", write(ordersSchema(root, "GROSS_AMOUNT")));
            assertEquals(200, off.statusCode(), off.body());
            assertTrue(V1Body.of(off.body()).get("written").asBoolean());
            assertEquals(List.of(), warnings(V1Body.of(off.body())), "-Dcolumn.contract=off: no warning");
        }
    }

    /** The demo's GROSS consumers (ColumnContractTest.GROSS_CONSUMERS, ControlApiColumnImpactTest). */
    private static final int GROSS_CONSUMER_COUNT = 7;
}

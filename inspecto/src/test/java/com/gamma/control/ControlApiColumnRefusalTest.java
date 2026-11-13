package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.ColumnContract;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-3b over real HTTP, two Spaces side by side: {@code alpha} sets {@code columns: refuse-breaking} in its
 * {@code safety-policy.toon}, {@code beta} sets nothing. Each Schema writer — {@code POST /config/write},
 * {@code POST /config/patch}, {@code PUT /components/schema/{id}} — answers 409 in alpha with the consumers a rename,
 * removal or narrowing breaks, saves with {@code ?force=true}, and saves with the SE-3a warning in beta. On Basic alpha
 * only warns; both kill switches win; a change no consumer reads, and a SQL reference the parser could not attribute,
 * never stop a save.
 */
class ControlApiColumnRefusalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer author} may author and administer. */
    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        return Optional.of(new Subject(a.substring(7), Set.of("canAuthorWorkbench", "canAdminister")));
    };

    @BeforeEach
    void isolate() {
        DiscoveredRoots.clear();
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        System.clearProperty(ColumnContract.PROPERTY);
        System.clearProperty(ColumnContract.REFUSAL_PROPERTY);
        DiscoveredRoots.clear();
        MetricRegistry.global().reset();
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, String user) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
        }
    }

    private static final String SCHEMA = """
            raw:
              name: ORDERS
              format: CSV
              fields[4]{name,selector,type}:
                ORDER_ID,"0",VARCHAR
                QUANTITY,"1",INTEGER
                UNIT_PRICE,"2",DOUBLE
                STATUS,"3",VARCHAR
            mapping:
              canonicalName: orders
              rawName: ORDERS
              fields[5]:
                - name: ORDER_ID
                  from: ORDER_ID
                  fn: keep
                - name: QUANTITY
                  from: QUANTITY
                  fn: keep
                - name: UNIT_PRICE
                  from: UNIT_PRICE
                  fn: keep
                - name: STATUS
                  from: STATUS
                  fn: keep
                - name: GROSS
                  from: ""
                  fn: custom
                  args:
                    expression: "QUANTITY * UNIT_PRICE"
            """;

    private static String pipeline(String id, String schemaFile) {
        StringBuilder dirs = new StringBuilder("dirs:\n  poll: data/inbox/" + id + "\n");
        for (String d : List.of("database", "backup", "temp", "errors", "quarantine", "markers", "status_dir", "log_dir"))
            dirs.append("  ").append(d).append(": data/").append(id).append('/').append(d).append('\n');
        return "name: " + id + "\nactive: false\n" + dirs + "output:\n  format: PARQUET\nprocessing:\n"
                + "  file_pattern: \"glob:**/*.csv\"\n  schema_file: " + schemaFile + "\n  csv_settings:\n    delimiter: \",\"\n";
    }

    /**
     * Both Spaces: Pipeline {@code orders} reads {@code orders/orders_schema.toon}, Expectation {@code e_gross} reads its
     * GROSS; Pipeline {@code regorders} reads the registry Schema component {@code regorders}, Expectation
     * {@code e_price} reads its UNIT_PRICE. Only alpha opts in.
     */
    private Ctx open(Path root, boolean signIn) throws Exception {
        for (String id : new String[] {"alpha", "beta"}) {
            Path config = Files.createDirectories(root.resolve(id + "/config"));
            Files.createDirectories(root.resolve(id + "/data"));
            Files.createDirectories(config.resolve("orders"));
            Files.createDirectories(config.resolve("registry/schemas"));
            Files.createDirectories(config.resolve("registry/expectations"));
            Files.writeString(config.resolve("orders/orders_pipeline.toon"), pipeline("orders", "orders_schema.toon"));
            Files.writeString(config.resolve("orders/orders_schema.toon"), SCHEMA);
            Files.writeString(config.resolve("regorders_pipeline.toon"), pipeline("regorders", "schema/regorders"));
            Files.writeString(config.resolve("registry/schemas/regorders.toon"), "name: regorders\n" + SCHEMA);
            Files.writeString(config.resolve("registry/expectations/e_gross.toon"),
                    "name: e_gross\ntargetType: pipeline\ntarget: orders\ncolumn: GROSS\nkind: non_null\n");
            Files.writeString(config.resolve("registry/expectations/e_price.toon"),
                    "name: e_price\ntargetType: pipeline\ntarget: regorders\ncolumn: UNIT_PRICE\nkind: non_null\n");
        }
        Files.writeString(root.resolve("alpha/config/safety-policy.toon"), "columns: refuse-breaking\n");
        if (signIn) Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port(), signIn ? "author" : null);
    }

    private HttpResponse<String> send(Ctx c, String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (c.user != null) b.header("Authorization", "Bearer " + c.user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> r) throws Exception {
        return r.statusCode() >= 400 ? V1Body.envelope(r.body()).get("error").get("details") : V1Body.of(r.body());
    }

    /** The orders Schema of {@code space} as stored, with {@code edit} applied to each mapping field. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(Path file, UnaryOperator<Map<String, Object>> edit) {
        Map<String, Object> doc = ColumnContract.stored(file);
        assertNotNull(doc, file.toString());
        Map<String, Object> mapping = new LinkedHashMap<>((Map<String, Object>) doc.get("mapping"));
        List<Object> fields = new ArrayList<>();
        for (Object o : (List<Object>) mapping.get("fields")) {
            Map<String, Object> f = edit.apply(new LinkedHashMap<>((Map<String, Object>) o));
            if (f != null) fields.add(f);
        }
        mapping.put("fields", fields);
        Map<String, Object> out = new LinkedHashMap<>(doc);
        out.put("mapping", mapping);
        return out;
    }

    private static UnaryOperator<Map<String, Object>> rename(String from, String to) {
        return f -> {
            if (from.equals(f.get("name"))) f.put("name", to);
            return f;
        };
    }

    private static Path ordersSchema(Path root, String space) {
        return root.resolve(space + "/config/orders/orders_schema.toon");
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

    private HttpResponse<String> renameGrossByWrite(Ctx c, Path root, String space, String query) throws Exception {
        return send(c, "POST", "/spaces/" + space + "/config/write" + query,
                write(schema(ordersSchema(root, space), rename("GROSS", "GROSS_AMOUNT"))));
    }

    private static List<JsonNode> codes(JsonNode list, String code) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode f : list) if (code.equals(f.path("code").asText())) out.add(f);
        return out;
    }

    private static void assertStoppedWith(HttpResponse<String> r, String column, String change, String kind, String name,
                                          String key) throws Exception {
        assertEquals(409, r.statusCode(), r.body());
        JsonNode b = body(r);
        assertFalse(b.get("written").asBoolean(), b.toString());
        JsonNode consumers = b.get("consumers");
        assertEquals(1, consumers.size(), consumers.toString());
        JsonNode c = consumers.get(0);
        assertEquals(kind, c.get("kind").asText());
        assertEquals(name, c.get("name").asText());
        assertEquals(key, c.get("key").asText());
        assertEquals(column, c.get("column").asText());
        assertEquals(change, c.get("change").asText());
        assertEquals(1, codes(b.get("findings"), ColumnRefusals.ERR_COLUMN_BREAKS).size(), b.toString());
        assertTrue(V1Body.envelope(r.body()).get("error").toString().contains("?force=true"), r.body());
    }

    // ── POST /config/write ─────────────────────────────────────────────────────────────────

    @Test
    void configWriteIsStoppedInTheOptedInSpaceForceSavesAndASpaceWithoutTheKeyWarns(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            String before = Files.readString(ordersSchema(root, "alpha"));
            assertStoppedWith(renameGrossByWrite(c, root, "alpha", ""), "GROSS", "renamed", "expectation", "e_gross", "column");
            assertEquals(before, Files.readString(ordersSchema(root, "alpha")), "stopped: nothing written");

            HttpResponse<String> forced = renameGrossByWrite(c, root, "alpha", "?force=true");
            assertEquals(200, forced.statusCode(), forced.body());
            assertEquals(1, codes(body(forced).get("findings"), ColumnContract.WARN_COLUMN_BREAKS).size(), forced.body());
            assertTrue(Files.readString(ordersSchema(root, "alpha")).contains("GROSS_AMOUNT"), "?force=true saves");

            HttpResponse<String> beta = renameGrossByWrite(c, root, "beta", "");
            assertEquals(200, beta.statusCode(), "beta has no columns: key, so SE-3a warns only: " + beta.body());
            List<JsonNode> w = codes(body(beta).get("findings"), ColumnContract.WARN_COLUMN_BREAKS);
            assertEquals(1, w.size(), beta.body());
            assertTrue(w.get(0).get("message").asText().contains("expectation 'e_gross' (column)"), w.toString());
            assertTrue(Files.readString(ordersSchema(root, "beta")).contains("GROSS_AMOUNT"));
        }
    }

    // ── POST /config/patch ─────────────────────────────────────────────────────────────────

    private HttpResponse<String> removeGrossByPatch(Ctx c, Path root, String space, String query) throws Exception {
        Map<String, Object> s = schema(ordersSchema(root, space), f -> "GROSS".equals(f.get("name")) ? null : f);
        return send(c, "POST", "/spaces/" + space + "/config/patch" + query, Map.of("type", "schema", "name", "orders_schema",
                "subdir", "orders", "patch", Map.of("mapping", s.get("mapping"))));
    }

    @Test
    void configPatchIsStoppedInTheOptedInSpaceForceSavesAndASpaceWithoutTheKeyWarns(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            String before = Files.readString(ordersSchema(root, "alpha"));
            assertStoppedWith(removeGrossByPatch(c, root, "alpha", ""), "GROSS", "removed", "expectation", "e_gross", "column");
            assertEquals(before, Files.readString(ordersSchema(root, "alpha")), "stopped: nothing written");

            HttpResponse<String> forced = removeGrossByPatch(c, root, "alpha", "?force=true");
            assertEquals(200, forced.statusCode(), forced.body());
            assertEquals(1, codes(body(forced).get("findings"), ColumnContract.WARN_COLUMN_BREAKS).size(), forced.body());
            assertFalse(Files.readString(ordersSchema(root, "alpha")).contains("GROSS"), "?force=true saves");

            HttpResponse<String> beta = removeGrossByPatch(c, root, "beta", "");
            assertEquals(200, beta.statusCode(), beta.body());
            assertEquals(1, codes(body(beta).get("findings"), ColumnContract.WARN_COLUMN_BREAKS).size(), beta.body());
        }
    }

    // ── PUT /components/schema/{id} ────────────────────────────────────────────────────────

    private static Path regSchema(Path root, String space) {
        return root.resolve(space + "/config/registry/schemas/regorders.toon");
    }

    /** UNIT_PRICE (DOUBLE, from raw) given {@code type: INTEGER} on its mapping field: a narrowing. */
    private HttpResponse<String> narrowPriceByComponent(Ctx c, Path root, String space, String query) throws Exception {
        Map<String, Object> s = schema(regSchema(root, space), f -> {
            if ("UNIT_PRICE".equals(f.get("name"))) f.put("type", "INTEGER");
            return f;
        });
        return send(c, "PUT", "/spaces/" + space + "/components/schema/regorders" + query, s);
    }

    @Test
    void aSchemaComponentUpdateIsStoppedInTheOptedInSpaceForceSavesAndASpaceWithoutTheKeyWarns(@TempDir Path root)
            throws Exception {
        try (Ctx c = open(root, true)) {
            String before = Files.readString(regSchema(root, "alpha"));
            assertStoppedWith(narrowPriceByComponent(c, root, "alpha", ""), "UNIT_PRICE", "type-narrowed", "expectation",
                    "e_price", "column");
            assertEquals(before, Files.readString(regSchema(root, "alpha")), "stopped: nothing written");

            HttpResponse<String> forced = narrowPriceByComponent(c, root, "alpha", "?force=true");
            assertEquals(200, forced.statusCode(), forced.body());
            assertEquals(1, codes(body(forced).get("columnWarnings"), ColumnContract.WARN_COLUMN_BREAKS).size(), forced.body());
            assertTrue(Files.readString(regSchema(root, "alpha")).contains("INTEGER"), "?force=true saves");

            HttpResponse<String> beta = narrowPriceByComponent(c, root, "beta", "");
            assertEquals(200, beta.statusCode(), beta.body());
            List<JsonNode> w = codes(body(beta).get("columnWarnings"), ColumnContract.WARN_COLUMN_BREAKS);
            assertEquals(1, w.size(), "the SE-3a warning now reaches this writer too: " + beta.body());
            assertTrue(w.get(0).get("message").asText().contains("DOUBLE → INTEGER"), w.toString());
        }
    }

    // ── edition, kill switches, the integrity report ───────────────────────────────────────

    private JsonNode columnsMode(Ctx c, String space) throws Exception {
        HttpResponse<String> r = send(c, "GET", "/spaces/" + space + "/integrity", null);
        assertEquals(200, r.statusCode(), r.body());
        return body(r).get("columnsMode");
    }

    @Test
    void onBasicTheKeyParsesButOnlyWarnsAndTheIntegrityReportSaysSo(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, false)) {
            HttpResponse<String> r = renameGrossByWrite(c, root, "alpha", "");
            assertEquals(200, r.statusCode(), "D-LC5: no refuse mode on Basic: " + r.body());
            assertEquals(1, codes(body(r).get("findings"), ColumnContract.WARN_COLUMN_BREAKS).size(), r.body());

            JsonNode m = columnsMode(c, "alpha");
            assertEquals("refuse-breaking", m.get("configured").asText(), m.toString());
            assertEquals("report", m.get("effective").asText());
            assertTrue(m.get("note").asText().contains("Standard or Advanced"), m.toString());
        }
    }

    @Test
    void theIntegrityReportShowsEachSpacesMode(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            JsonNode a = columnsMode(c, "alpha");
            assertEquals("refuse-breaking", a.get("configured").asText(), a.toString());
            assertEquals("refuse-breaking", a.get("effective").asText());
            JsonNode b = columnsMode(c, "beta");
            assertEquals("report", b.get("configured").asText(), b.toString());
            assertEquals("report", b.get("effective").asText());
        }
    }

    @Test
    void theRefusalKillSwitchWinsAndLeavesTheWarning(@TempDir Path root) throws Exception {
        System.setProperty(ColumnContract.REFUSAL_PROPERTY, "off");
        try (Ctx c = open(root, true)) {
            HttpResponse<String> r = renameGrossByWrite(c, root, "alpha", "");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(1, codes(body(r).get("findings"), ColumnContract.WARN_COLUMN_BREAKS).size(), r.body());
            assertEquals("report", columnsMode(c, "alpha").get("effective").asText());
        }
    }

    @Test
    void theColumnContractKillSwitchWinsAndSilencesBoth(@TempDir Path root) throws Exception {
        System.setProperty(ColumnContract.PROPERTY, "off");
        try (Ctx c = open(root, true)) {
            HttpResponse<String> r = renameGrossByWrite(c, root, "alpha", "");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(List.of(), codes(body(r).get("findings"), ColumnContract.WARN_COLUMN_BREAKS), r.body());
        }
    }

    // ── what never stops a save ────────────────────────────────────────────────────────────

    @Test
    void aChangeNoConsumerReadsAndANotAnalysedSqlReferenceNeverStopASave(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            // d.STATUS reads a path reference whose columns are unknown: NOT_ANALYSED, never a consumer
            Files.writeString(root.resolve("alpha/config/orders/na_enrich.toon"), """
                    input:
                      database: data/orders/database
                    references:
                      dim:
                        path: data/ref/dim.csv
                    transform: "SELECT i.ORDER_ID, d.STATUS FROM input i JOIN dim d ON i.ORDER_ID = d.ORDER_ID"
                    """);
            HttpResponse<String> r = send(c, "POST", "/spaces/alpha/config/write",
                    write(schema(ordersSchema(root, "alpha"), rename("STATUS", "STATUS_CODE"))));
            assertEquals(200, r.statusCode(), "no RESOLVED consumer reads STATUS, so alpha saves: " + r.body());
            List<JsonNode> w = codes(body(r).get("findings"), ColumnContract.WARN_COLUMN_BREAKS);
            assertEquals(1, w.size(), r.body());
            String msg = w.get(0).get("message").asText();
            assertTrue(msg.contains("no consumer in this Space reads it"), msg);
            assertTrue(msg.contains("1 SQL reference(s) to it were not analysed"), "the not-analysed read was seen: " + msg);
            assertTrue(Files.readString(ordersSchema(root, "alpha")).contains("STATUS_CODE"));
        }
    }
}

package com.gamma.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.control.ControlApi;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.module.Entitlements;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PA-9d over real HTTP (D-PA44): a windowed Alert Rule is saved only where the Real-time Detection Add-on is present
 * and entitled - {@code POST /alerts/rules} answers 422 {@code ERR_EDITION_FEATURE} naming the Add-on when the module
 * is absent, and when a signed entitlement leaves it out; and a windowed rule already on disk is listed by
 * {@code GET /alerts/rules} as {@code inert}, with the reason, while the Add-on is absent.
 */
class ControlApiRealtimeGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private KeyPair issuer;

    @TempDir Path tmp;

    @BeforeEach
    void trustATestIssuer() throws Exception {
        issuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Entitlements.trustForTests(Map.of("test-issuer", issuer.getPublic()));
        Entitlements.clockForTests(java.time.Clock.fixed(java.time.Instant.parse("2026-11-12T00:00:00Z"), java.time.ZoneOffset.UTC));
        System.setProperty(Entitlements.FILE_PROPERTY, tmp.resolve("conf/none.toon").toString());
        Entitlements.reset();
    }

    @AfterEach
    void restore() {
        EditionFeatures.overrideForTest(null);
        System.clearProperty(Entitlements.FILE_PROPERTY);
        Entitlements.trustForTests(null);
        Entitlements.clockForTests(null);
    }

    private record Ctx(SpaceManager spaces, ControlApi api) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    /** Space {@code s1}: a stream Pipeline writing {@code cdr_stream}, the Dataset {@code voice_cdr} over it, one Parquet file. */
    private Ctx open() throws Exception {
        Path base = tmp.resolve("spaces").resolve("s1");
        Path config = Files.createDirectories(base.resolve("config"));
        Path data = Files.createDirectories(base.resolve("data"));
        Files.createDirectories(base.resolve("duckdb"));
        Path store = Files.createDirectories(data.resolve("cdr_stream").resolve("event_day=2026-11-12"));
        try (Connection d = DriverManager.getConnection("jdbc:duckdb:"); Statement st = d.createStatement()) {
            st.execute("COPY (SELECT 'A1' AS a_number, 'B1' AS b_number, 1.0 AS duration_s, "
                    + "TIMESTAMP '2026-11-12 10:00:00' AS event_ts, 'cdr' AS topic, 0 AS \"partition\", 7 AS \"offset\") TO '"
                    + store.resolve("part-0.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        new ComponentStore(config.resolve("registry")).write("dataset", "voice_cdr",
                new HashMap<>(Map.of("physicalRef", "cdr_stream")));
        Path pipelines = Files.createDirectories(config.resolve("pipelines"));
        RealtimeSessionTest.writePipeline(pipelines, "CDR_STREAM", base.resolve("db"));
        Files.writeString(pipelines.resolve("schema.toon"), PipelineConfigBatchTest.miniSchema());
        SpaceManager spaces = SpaceManager.discover(tmp.resolve("spaces"));
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api);
    }

    private HttpResponse<String> call(Ctx c, String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.api().port() + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> windowed(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("dataset", "voice_cdr");
        m.put("measure", "countDistinct(b_number)");
        m.put("by", List.of("a_number"));
        m.put("comparator", "gte");
        m.put("threshold", 30);
        m.put("severity", "CRITICAL");
        m.put("window", Map.of("type", "sliding", "size", "10m", "slide", "1m", "time", "event_ts",
                "distinctOn", List.of("topic", "partition", "offset")));
        return m;
    }

    /** Write and sign an entitlement for this build (Basic) listing {@code modules}. */
    private void entitle(List<String> modules, List<String> addons) throws Exception {
        String toon = String.join("\n",
                "schema: inspecto.entitlement/1", "id: acme-2026-11-12", "customer: acme", "title: Acme Ltd", "product: inspecto",
                "edition: basic", "apps[0]:", "addons[" + addons.size() + "]:" + (addons.isEmpty() ? "" : " " + String.join(",", addons)),
                "modules[" + modules.size() + "]:" + (modules.isEmpty() ? "" : " " + String.join(",", modules)),
                "nodes: 1", "notBefore: 2026-11-11", "expires: 2027-11-11", "issued: 2026-11-11", "keyId: test-issuer", "");
        Path dir = Files.createDirectories(tmp.resolve("conf"));
        Path file = dir.resolve("entitlement.toon");
        Files.writeString(file, toon, StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(issuer.getPrivate());
        s.update(toon.getBytes(StandardCharsets.UTF_8));
        Files.writeString(dir.resolve("entitlement.sig"), Base64.getEncoder().encodeToString(s.sign()));
        System.setProperty(Entitlements.FILE_PROPERTY, file.toString());
        Entitlements.reset();
    }

    private static JsonNode rule(JsonNode rules, String name) {
        JsonNode list = rules.has("data") ? rules.get("data") : rules;
        for (JsonNode r : list) if (name.equals(r.path("name").asText())) return r;
        throw new AssertionError("no rule '" + name + "' in " + rules);
    }

    @Test
    void presentAndUnrestrictedTheWindowedRuleIsSavedAndArmed() throws Exception {
        try (Ctx c = open()) {
            HttpResponse<String> r = call(c, "POST", "/alerts/rules", windowed("wangiri-burst"));
            assertTrue(r.statusCode() / 100 == 2, r.statusCode() + " " + r.body());
            JsonNode listed = rule(JSON.readTree(call(c, "GET", "/alerts/rules", null).body()), "wangiri-burst");
            assertFalse(listed.has("inert"), "present: evaluated on commit, not inert: " + listed);
        }
    }

    @Test
    void absentTheSaveIs422NamingTheAddOnAndARuleOnDiskIsListedInert() throws Exception {
        try (Ctx c = open()) {
            assertTrue(call(c, "POST", "/alerts/rules", windowed("on-disk")).statusCode() / 100 == 2);
            EditionFeatures.overrideForTest(Set.of(EditionFeatures.ALERT_DISPATCH));   // a build without inspecto-realtime
            HttpResponse<String> r = call(c, "POST", "/alerts/rules", windowed("refused"));
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("ERR_EDITION_FEATURE") && r.body().contains(EditionFeatures.REALTIME_ORDER_CODE)
                    && r.body().contains("inspecto-realtime"), r.body());
            JsonNode rules = JSON.readTree(call(c, "GET", "/alerts/rules", null).body());
            assertTrue(rule(rules, "on-disk").path("inert").asText().contains("needs the Real-time Detection Add-on"),
                    rule(rules, "on-disk").toString());
            Map<String, Object> plain = windowed("plain");
            plain.remove("window");
            assertTrue(call(c, "POST", "/alerts/rules", plain).statusCode() / 100 == 2, "a rule with no window still saves");
        }
    }

    @Test
    void anEntitlementWithoutTheAddOnRefusesItAndOneWithItAllowsIt() throws Exception {
        entitle(List.of("ops"), List.of("incidents"));
        try (Ctx c = open()) {
            HttpResponse<String> r = call(c, "POST", "/alerts/rules", windowed("not-entitled"));
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains(Entitlements.NOT_ENTITLED) && r.body().contains(EditionFeatures.REALTIME_ORDER_CODE),
                    r.body());

            entitle(List.of("ops", "realtime"), List.of("incidents", EditionFeatures.REALTIME_ADDON));
            HttpResponse<String> ok = call(c, "POST", "/alerts/rules", windowed("entitled"));
            assertTrue(ok.statusCode() / 100 == 2, ok.statusCode() + " " + ok.body());
            assertFalse(rule(JSON.readTree(call(c, "GET", "/alerts/rules", null).body()), "entitled").has("inert"));
        }
    }
}

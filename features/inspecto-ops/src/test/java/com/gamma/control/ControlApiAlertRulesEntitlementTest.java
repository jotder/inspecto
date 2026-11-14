package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.module.Entitlements;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ENTITLEMENT-UNLISTED-FEATURES-1 over real HTTP: Alert Rules ({@code alert.dispatch}), which this module declares through
 * its {@code EditionFeatureProvider}, are withheld with the module - a fired rule opens an ALERT object in this module's
 * store, and the Incidents Add-on that sells them brings this module. A signed entitlement that leaves {@code ops} out
 * refuses {@code POST /alerts/rules} (422 {@code ERR_EDITION_FEATURE}, naming the entitlement) and {@code /bootstrap} gives
 * the feature a {@code featureReasons} entry; one that lists {@code ops} saves and arms the same rule. The edition features
 * this module only carries as the Standard marker ({@code pipeline.automation}, {@code laWidgets}) stay present without it.
 */
class ControlApiAlertRulesEntitlementTest {

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
        System.clearProperty(Entitlements.FILE_PROPERTY);
        Entitlements.trustForTests(null);
        Entitlements.clockForTests(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open() throws Exception {
        Path toon = TestConfigs.csv(Files.createDirectories(tmp.resolve("cfg")), PipelineConfigBatchTest.miniSchema()).write();
        System.setProperty("assist.write.root", Files.createDirectories(tmp.resolve("wr")).toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1" + path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    /** Write and sign an entitlement for this build (Basic: a test boots no Authenticator) listing {@code modules}. */
    private void entitle(List<String> modules) throws Exception {
        String toon = String.join("\n",
                "schema: inspecto.entitlement/1", "id: acme-2026-11-12", "customer: acme", "title: Acme Ltd", "product: inspecto",
                "edition: basic", "apps[0]:", "addons[0]:",
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

    private static final String RULE = """
            {"name":"%s","metric":"error_rate","comparator":"gt","threshold":0.1,"window":"1h","severity":"WARNING"}""";

    private JsonNode featureReasons(Ctx c) throws Exception {
        JsonNode boot = JSON.readTree(send(c, "GET", "/bootstrap", null).body());
        return (boot.has("data") ? boot.get("data") : boot).path("featureReasons");
    }

    @Test
    void anEntitlementWithoutTheModuleRefusesAlertRulesAndOneWithItArmsThem() throws Exception {
        entitle(List.of());
        try (Ctx c = open()) {
            assertEquals("Not in this installation's entitlement.",
                    featureReasons(c).path(EditionFeatures.ALERT_DISPATCH).asText(), featureReasons(c).toString());
            HttpResponse<String> r = send(c, "POST", "/alerts/rules", RULE.formatted("refused"));
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("ERR_EDITION_FEATURE") && r.body().contains(Entitlements.NOT_ENTITLED), r.body());
            assertTrue(EditionFeatures.present(EditionFeatures.PIPELINE_AUTOMATION)
                    && EditionFeatures.present(EditionFeatures.LA_WIDGETS), "the Standard edition features do not ride with ops");

            entitle(List.of("ops"));
            HttpResponse<String> ok = send(c, "POST", "/alerts/rules", RULE.formatted("armed"));
            assertTrue(ok.statusCode() / 100 == 2, ok.statusCode() + " " + ok.body());
            assertTrue(send(c, "GET", "/alerts/rules", null).body().contains("armed"), "armed");
            assertFalse(featureReasons(c).has(EditionFeatures.ALERT_DISPATCH), featureReasons(c).toString());
        }
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;
    }

    /**
     * The consequence of listing {@code alert.dispatch}: {@code /modules} shows it among ops' Features, but it is not a Space
     * switch - {@code GET /settings/modules} does not offer it ({@code installed}) and a PUT naming it is refused - so the
     * per-Space switch of ops stays the {@code ops} Feature alone, and switching that off still switches the module off.
     */
    @Test
    void theListedEditionFeatureIsNotASpaceSwitchAndTheOpsSwitchStillWorks() throws Exception {
        try (Ctx c = open()) {
            JsonNode ops = null;
            for (JsonNode m : data(send(c, "GET", "/modules", null)).get("modules")) if ("ops".equals(m.path("id").asText())) ops = m;
            assertNotNull(ops, "ops is installed");
            assertTrue(ops.at("/provides/features").toString().contains(EditionFeatures.ALERT_DISPATCH), ops.toString());

            JsonNode installed = data(send(c, "GET", "/settings/modules", null)).get("installed");
            assertTrue(installed.toString().contains("\"ops\"") && !installed.toString().contains(EditionFeatures.ALERT_DISPATCH),
                    installed.toString());
            assertEquals(422, send(c, "PUT", "/settings/modules", "{\"disabled\":[\"ops\",\"alert.dispatch\"]}").statusCode());

            assertEquals("ops", data(send(c, "PUT", "/settings/modules", "{\"disabled\":[\"ops\"]}")).get("disabled").get(0).asText());
            for (JsonNode m : data(send(c, "GET", "/modules", null)).get("modules"))
                if ("ops".equals(m.path("id").asText())) assertFalse(m.path("enabledInSpace").asBoolean(true), m.toString());
            data(send(c, "PUT", "/settings/modules", "{\"disabled\":[]}"));
        }
    }
}

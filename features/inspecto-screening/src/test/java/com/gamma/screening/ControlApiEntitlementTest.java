package com.gamma.screening;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.module.Entitlements;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRODUCT-ASSEMBLY-1 PA-7a over real HTTP: a signed entitlement narrows the Installed gate (D-PA22), no file is today's
 * behaviour (D-PA23), and a file that cannot be trusted fails the boot. Screening is the optional module under test: its
 * routes, its Job type and its {@code /modules} state follow the entitlement's {@code modules} list.
 */
class ControlApiEntitlementTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private KeyPair issuer;

    @TempDir Path tmp;

    @BeforeEach
    void trustATestIssuer() throws Exception {
        issuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Entitlements.trustForTests(Map.of("test-issuer", issuer.getPublic()));
    }

    @AfterEach
    void restore() {
        System.clearProperty(Entitlements.FILE_PROPERTY);
        Entitlements.trustForTests(null);
    }

    private record Ctx(CollectorService svc, ControlApi api) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open() throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(tmp.resolve("cfg")), "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api);
        } catch (Exception e) {
            svc.close();
            throw e;
        }
    }

    private HttpResponse<String> call(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.api().port() + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws IOException {
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;
    }

    /** Write and sign an entitlement for the Basic edition (this test's build) listing {@code modules}; returns the file. */
    private Path entitlement(String edition, List<String> modules, String keyId) throws Exception {
        String toon = String.join("\n",
                "schema: inspecto.entitlement/1", "id: acme-2026-11-11", "customer: acme", "title: Acme Ltd", "product: inspecto",
                "edition: " + edition, "apps[0]:", "addons[1]: screening",
                "modules[" + modules.size() + "]:" + (modules.isEmpty() ? "" : " " + String.join(",", modules)),
                "nodes: 2", "notBefore: 2026-11-11", "expires: 2027-11-11", "issued: 2026-11-11", "keyId: " + keyId, "");
        Path dir = Files.createDirectories(tmp.resolve("conf"));
        Path file = dir.resolve("entitlement.toon");
        Files.writeString(file, toon, StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(issuer.getPrivate());
        s.update(toon.getBytes(StandardCharsets.UTF_8));
        Files.writeString(dir.resolve("entitlement.sig"), Base64.getEncoder().encodeToString(s.sign()));
        System.setProperty(Entitlements.FILE_PROPERTY, file.toString());
        Entitlements.reset();
        return file;
    }

    /** The request got past the Installed gate: whatever the module answers, it is not the entitlement's refusal. */
    private static void assertReachesTheModule(HttpResponse<String> r) {
        assertFalse(r.body().contains(Entitlements.NOT_ENTITLED), r.statusCode() + " " + r.body());
        assertFalse(r.body().contains("not installed"), r.statusCode() + " " + r.body());
    }

    private static JsonNode module(JsonNode modules, String id) {
        for (JsonNode m : modules.path("modules")) if (id.equals(m.path("id").asText())) return m;
        throw new AssertionError("no module '" + id + "' in /modules: " + modules);
    }

    /** Whether a JobService built now registers {@code type} (the harness above runs no Jobs, so its /jobs/types is a 404). */
    private boolean registers(String type) throws Exception {
        try (com.gamma.util.Scheduler s = new com.gamma.util.Scheduler();
             com.gamma.job.JobService js = new com.gamma.job.JobService(List.of(), new com.gamma.etl.ConsignmentEventBus(), s, null,
                     Files.createDirectories(tmp.resolve("audit")).toString())) {
            return js.jobTypes().stream().anyMatch(d -> type.equals(d.id()));
        }
    }

    @Test
    void theScreeningJobTypeRegistersOnlyWhenEntitled() throws Exception {
        System.setProperty(Entitlements.FILE_PROPERTY, tmp.resolve("conf/none.toon").toString());
        Entitlements.reset();
        assertTrue(registers("screening.run"), "no file: registered as today");
        entitlement("basic", List.of("entity-list", "ops", "screening"), "test-issuer");
        assertTrue(registers("screening.run"), "listed: registered");
        entitlement("basic", List.of("entity-list", "ops"), "test-issuer");
        assertFalse(registers("screening.run"), "left out: not registered, so a Job of that type is an unknown type");
    }

    @Test
    void anEntitlementThatLeavesScreeningOutClosesItsRoutesJobTypeAndModule() throws Exception {
        entitlement("basic", List.of(), "test-issuer");
        try (Ctx c = open()) {
            HttpResponse<String> r = call(c, "POST", "/screening/check", "{\"name\":\"x\"}");
            assertEquals(503, r.statusCode(), r.body());
            assertTrue(r.body().contains(Entitlements.NOT_ENTITLED), r.body());
            JsonNode screening = module(data(call(c, "GET", "/modules", null)), "screening");
            assertEquals("INERT", screening.path("state").asText().toUpperCase(), screening.toString());
            assertTrue(screening.toString().contains(Entitlements.NOT_ENTITLED), screening.toString());
            JsonNode boot = data(call(c, "GET", "/bootstrap", null));
            assertEquals("Not in this installation's entitlement.", boot.path("featureReasons").path("screening").asText(),
                    boot.path("featureReasons").toString());
            JsonNode ent = boot.path("entitlement");
            assertTrue(ent.path("installed").asBoolean(), ent.toString());
            assertEquals("acme", ent.path("customer").asText());
            assertEquals(2, ent.path("nodes").asInt());
            assertEquals("2027-11-11", ent.path("expires").asText());
        }
    }

    @Test
    void anEntitlementThatListsScreeningLeavesItOpen() throws Exception {
        // what entitle.mjs issues for the screening Add-on: screening plus the optional modules it requires (entity-list, ops)
        entitlement("basic", List.of("entity-list", "ops", "screening"), "test-issuer");
        try (Ctx c = open()) {
            assertReachesTheModule(call(c, "POST", "/screening/check", "{\"name\":\"x\"}"));
            assertEquals("ACTIVE", module(data(call(c, "GET", "/modules", null)), "screening").path("state").asText().toUpperCase());
        }
    }

    @Test
    void noFileIsTodaysBehaviour() throws Exception {
        System.setProperty(Entitlements.FILE_PROPERTY, tmp.resolve("conf/none.toon").toString());
        Entitlements.reset();
        try (Ctx c = open()) {
            assertReachesTheModule(call(c, "POST", "/screening/check", "{\"name\":\"x\"}"));
            assertFalse(data(call(c, "GET", "/bootstrap", null)).path("entitlement").path("installed").asBoolean(true));
        }
    }

    @Test
    void aFileThatCannotBeTrustedFailsTheBootNamingIt() throws Exception {
        Path file = entitlement("basic", List.of("screening"), "test-issuer");
        Files.writeString(file, Files.readString(file).replace("nodes: 2", "nodes: 20"));   // one changed byte after signing
        Entitlements.reset();
        Exception tampered = assertThrows(Exception.class, this::open);
        assertTrue(tampered.getMessage().contains("signature does not match") && tampered.getMessage().contains(file.toString()),
                tampered.getMessage());

        entitlement("basic", List.of("screening"), "someone-else");
        assertTrue(assertThrows(Exception.class, this::open).getMessage().contains("key id 'someone-else' is not an issuer key"));

        Path unsigned = entitlement("basic", List.of("screening"), "test-issuer");
        Files.delete(unsigned.resolveSibling("entitlement.sig"));
        Entitlements.reset();
        assertTrue(assertThrows(Exception.class, this::open).getMessage().contains("needs its signature"));
    }

    @Test
    void anEntitlementForAnotherEditionFailsTheBoot() throws Exception {
        entitlement("standard", List.of("screening"), "test-issuer");
        IOException e = assertThrows(IOException.class, this::open);
        assertTrue(e.getMessage().contains("is for the standard Edition, but this build is basic"), e.getMessage());
    }
}

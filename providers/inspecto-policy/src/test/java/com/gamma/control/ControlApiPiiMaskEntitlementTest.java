package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
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
 * ENTITLEMENT-UNLISTED-FEATURES-1 over real HTTP: PII masking ({@code quality.pii.mask}, the {@code mask} Step), which this
 * module declares through its {@code EditionFeatureProvider}, is withheld with the module. A signed entitlement that leaves
 * {@code policy} out refuses a Pipeline with a {@code mask} Step at save (422 {@code ERR_EDITION_FEATURE}, naming the
 * entitlement) and {@code /bootstrap} gives the feature a {@code featureReasons} entry; one that lists {@code policy} saves
 * the same Pipeline.
 */
class ControlApiPiiMaskEntitlementTest {

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
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(tmp.resolve("cfg")), "");
        System.setProperty("assist.write.root", Files.createDirectories(tmp.resolve("wr")).toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
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

    private static final String MASKING = """
            {"type":"pipeline","config":{"name":"%s","active":false,
              "dirs":{"poll":"in","database":"out"},"processing":{"threads":1,"schema_file":"cdr.toon"},
              "steps":[{"mask":{"columns":["msisdn"],"mode":"full"}}]}}""";

    private JsonNode featureReasons(Ctx c) throws Exception {
        JsonNode boot = JSON.readTree(send(c, "GET", "/bootstrap", null).body());
        return (boot.has("data") ? boot.get("data") : boot).path("featureReasons");
    }

    @Test
    void anEntitlementWithoutTheModuleRefusesTheMaskStepAndOneWithItSavesIt() throws Exception {
        entitle(List.of());
        try (Ctx c = open()) {
            assertEquals("Not in this installation's entitlement.",
                    featureReasons(c).path(EditionFeatures.PII_MASK).asText(), featureReasons(c).toString());
            HttpResponse<String> r = send(c, "POST", "/config/write", MASKING.formatted("refused"));
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("ERR_EDITION_FEATURE") && r.body().contains(EditionFeatures.PII_MASK)
                    && r.body().contains(Entitlements.NOT_ENTITLED), r.body());

            entitle(List.of("policy"));
            HttpResponse<String> ok = send(c, "POST", "/config/write", MASKING.formatted("saved"));
            assertTrue(ok.statusCode() / 100 == 2, ok.statusCode() + " " + ok.body());
            assertFalse(featureReasons(c).has(EditionFeatures.PII_MASK), featureReasons(c).toString());
        }
    }
}

package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MODULE-REORG-P4-2} - the Alert Rule unmodelled-key policy, over real HTTP with a real Subject.
 *
 * <p>An author-owned {@code x-} key is an annotation and is KEPT by every door that stores a rule (create, update,
 * a held save once approved, a bundle import) and returned by {@code GET /alerts/rules}; PUT replaces, so a key the
 * client no longer sends is gone. Any other unmodelled key is REFUSED 422 {@code ERR_UNKNOWN_CONFIG_KEY} naming it,
 * with nothing written - the 200-that-dropped-it of before is the defect this ends. (Before the change the
 * {@code zz_cfg} probe below returned 200 and the file lacked the key: pinned then by
 * {@code ModuleRemovalRulesTest}, so each refusal here is a probe that would otherwise have SUCCEEDED.)
 */
class AlertRuleUnmodelledKeysTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        String who = auth.substring(7);
        Roles.Def def = Roles.SEED.get(who.replaceAll("-\\d+$", ""));
        if (def == null) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of(who.replaceAll("-\\d+$", "")));
        return Optional.of(new Subject(who, def.capabilities()));
    };

    @BeforeEach void arm() {
        Authenticators.forTest(FAKE);
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
    }
    @AfterEach void disarm() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("super-1", "super-2"))));
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", "Bearer " + who).header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static String rule(String extra) {
        return "{\"name\":\"plain-alert\",\"metric\":\"error_rate\",\"comparator\":\"gt\",\"threshold\":0.1,"
                + "\"window\":\"1h\",\"severity\":\"WARNING\"" + extra + "}";
    }

    private static Map<String, Object> stored(Ctx c) {
        return new ComponentStore(c.root.resolve("registry")).get("alert-rule", "plain-alert").orElseThrow().content();
    }

    private static JsonNode listed(HttpResponse<String> r) throws Exception {
        JsonNode body = JSON.readTree(r.body());
        JsonNode rules = body.has("data") ? body.get("data") : body;
        for (JsonNode n : rules) if ("plain-alert".equals(n.get("name").asText())) return n;
        throw new AssertionError("plain-alert not listed: " + r.body());
    }

    @Test
    void anUnmodelledKeyIsRefusedByNameAndNothingIsWritten(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            HttpResponse<String> r = send(c, "POST", "/alerts/rules", rule(",\"zz_cfg\":\"keep\""), "super-1");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("ERR_UNKNOWN_CONFIG_KEY") && r.body().contains("zz_cfg"), r.body());
            assertFalse(new ComponentStore(c.root.resolve("registry")).exists("alert-rule", "plain-alert"), "nothing written");

            assertEquals(200, send(c, "POST", "/alerts/rules", rule(""), "super-1").statusCode());
            HttpResponse<String> put = send(c, "PUT", "/alerts/rules/plain-alert", rule(",\"zz_cfg\":\"keep\""), "super-1");
            assertEquals(422, put.statusCode(), put.body());
            assertFalse(stored(c).containsKey("zz_cfg"), "the stored rule is untouched by a refused update");
        }
    }

    @Test
    void anAuthorOwnedKeyIsKeptByCreateAndUpdateAndReturnedByGet_andPutReplaces(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            assertEquals(200, send(c, "POST", "/alerts/rules", rule(",\"x-team\":\"noc\""), "super-1").statusCode());
            assertEquals("noc", stored(c).get("x-team"), "create keeps the annotation");
            JsonNode got = listed(send(c, "GET", "/alerts/rules", null, "super-1"));
            assertEquals("noc", got.get("x-team").asText(), "GET returns it, so a client round-trips it");

            // the client posts back exactly what GET returned, with one change: the annotation survives
            assertEquals(200, send(c, "PUT", "/alerts/rules/plain-alert",
                    got.toString().replace("\"WARNING\"", "\"CRITICAL\""), "super-1").statusCode());
            assertEquals("noc", stored(c).get("x-team"));
            assertEquals("CRITICAL", stored(c).get("severity"));

            // PUT is replace-with-posted: a body without the key removes it
            assertEquals(200, send(c, "PUT", "/alerts/rules/plain-alert", rule(""), "super-1").statusCode());
            assertFalse(stored(c).containsKey("x-team"));
        }
    }

    @Test
    void aHeldSaveCarriesTheAnnotationThroughApproval(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            assertEquals(200, send(c, "PUT", "/settings/approval",
                    "{\"approval\":{\"alert-rule\":{\"required\":true}}}", "super-1").statusCode());
            HttpResponse<String> held = send(c, "POST", "/alerts/rules", rule(",\"x-team\":\"noc\""), "super-1");
            assertEquals(202, held.statusCode(), held.body());
            assertFalse(new ComponentStore(c.root.resolve("registry")).exists("alert-rule", "plain-alert"), "held, not written");
            String id = JSON.readTree(held.body()).at("/data/pendingChange/id").asText();
            HttpResponse<String> ok = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", "super-2");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals("noc", stored(c).get("x-team"), "the approved write kept the annotation");
            assertEquals("noc", listed(send(c, "GET", "/alerts/rules", null, "super-1")).get("x-team").asText());
        }
    }

    @Test
    void aBundleImportKeepsTheAnnotationAndRefusesAnUnknownKey(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            String ok = bundle("{\"name\":\"plain-alert\",\"metric\":\"error_rate\",\"comparator\":\"gt\",\"threshold\":0.1,"
                    + "\"window\":\"1h\",\"severity\":\"WARNING\",\"x-team\":\"noc\"}");
            assertEquals(200, send(c, "POST", "/bundle/import", ok, "super-1").statusCode());
            assertEquals("noc", stored(c).get("x-team"));

            String bad = bundle("{\"name\":\"plain-alert\",\"metric\":\"error_rate\",\"comparator\":\"gt\",\"threshold\":0.1,"
                    + "\"window\":\"1h\",\"severity\":\"WARNING\",\"zz_cfg\":\"v\"}");
            HttpResponse<String> r = send(c, "POST", "/bundle/import", bad.replace("\"items\":", "\"actions\":{\"alert-rule/plain-alert\":\"overwrite\"},\"items\":"), "super-1");
            assertTrue(r.body().contains("zz_cfg"), "the item fails naming the key: " + r.body());
            assertFalse(stored(c).containsKey("zz_cfg"));
        }
    }

    private static String bundle(String contentJson) {
        return "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"exportedAt\":\"2026-07-18T00:00:00Z\","
                + "\"sourceSpace\":null,\"items\":[{\"kind\":\"alert-rule\",\"id\":\"plain-alert\","
                + "\"content\":" + contentJson + "}]}";
    }
}

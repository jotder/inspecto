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
 * {@code MODULE-REORG-P4-2} (the writers after Alert Rule): Expectation, Notification Rule and Notification Channel
 * persist what they REBUILD from a typed record's {@code toMap()}, so a key the record does not model used to vanish
 * behind a 200. Now an author-owned {@code x-} key is kept by create / update / a held save once approved / a bundle
 * import, and any other key is refused naming it (Expectation already refused it by its census; Rule and Channel did
 * not). Real HTTP with a real Subject. Every {@code x-} assertion is a probe that SUCCEEDED (200, key gone) before.
 */
class RebuiltWritersUnmodelledKeysTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        String who = auth.substring(7);
        Roles.Def def = Roles.SEED.get(who.replaceAll("-\\d+$", ""));
        if (def == null) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of(who.replaceAll("-\\d+$", "")));
        return Optional.of(new Subject(who, def.capabilities()));
    };

    @BeforeEach void arm() { Authenticators.forTest(FAKE); }
    @AfterEach void disarm() { Authenticators.forTest(null); }

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

    private static final String EXP = "{\"name\":\"e1\",\"targetType\":\"pipeline\",\"target\":\"orders\",\"column\":\"ID\",\"kind\":\"non_null\"";
    private static final String RULE = "{\"id\":\"r1\",\"eventType\":\"job.custom\",\"category\":\"job\"";
    private static final String CHAN = "{\"id\":\"c1\",\"kind\":\"webhook\",\"target\":\"https://example.test/hook\"";

    private static HttpResponse<String> send(Ctx c, String method, String path, String body, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", "Bearer " + who).header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(b.build(), BodyHandlers.ofString());
    }

    private static Map<String, Object> stored(Ctx c, String type, String id) {
        return new ComponentStore(c.root.resolve("registry")).get(type, id).orElseThrow().content();
    }

    private static boolean exists(Ctx c, String type, String id) {
        return new ComponentStore(c.root.resolve("registry")).exists(type, id);
    }

    private static JsonNode listed(Ctx c, String path, String key, String id) throws Exception {
        JsonNode body = JSON.readTree(send(c, "GET", path, null, "super-1").body());
        for (JsonNode n : body.has("data") ? body.get("data") : body) if (id.equals(n.get(key).asText())) return n;
        throw new AssertionError(id + " not listed at " + path);
    }

    private static String bundle(String kind, String id, String contentJson) {
        return "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"exportedAt\":\"2026-07-18T00:00:00Z\","
                + "\"sourceSpace\":null,\"items\":[{\"kind\":\"" + kind + "\",\"id\":\"" + id + "\",\"content\":" + contentJson + "}]}";
    }

    /** Hold {@code kind} for approval, post {@code body} to {@code path}, approve it as the second approver. */
    private static void heldThenApproved(Ctx c, String kind, String path, String body) throws Exception {
        assertEquals(200, send(c, "PUT", "/settings/approval",
                "{\"approval\":{\"" + kind + "\":{\"required\":true}}}", "super-1").statusCode());
        HttpResponse<String> held = send(c, "POST", path, body, "super-1");
        assertEquals(202, held.statusCode(), held.body());
        String id = JSON.readTree(held.body()).at("/data/pendingChange/id").asText();
        HttpResponse<String> ok = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", "super-2");
        assertEquals(200, ok.statusCode(), ok.body());
    }

    // ── Expectation ──────────────────────────────────────────────────────────────

    @Test
    void anExpectationKeepsItsAnnotationThroughCreateGetAndPut_andPutReplaces(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            assertEquals(200, send(c, "POST", "/expectations", EXP + ",\"x-team\":\"dq\"}", "super-1").statusCode());
            assertEquals("dq", stored(c, "expectation", "e1").get("x-team"), "create keeps the annotation");
            JsonNode got = listed(c, "/expectations", "name", "e1");
            assertEquals("dq", got.get("x-team").asText(), "GET returns it");
            assertEquals(200, send(c, "PUT", "/expectations/e1", got.toString(), "super-1").statusCode());
            assertEquals("dq", stored(c, "expectation", "e1").get("x-team"), "a GET-then-PUT round trip keeps it");
            assertEquals(200, send(c, "PUT", "/expectations/e1", EXP + "}", "super-1").statusCode());
            assertFalse(stored(c, "expectation", "e1").containsKey("x-team"), "PUT replaces with what was posted");
            HttpResponse<String> bad = send(c, "PUT", "/expectations/e1", EXP + ",\"zz_cfg\":\"v\"}", "super-1");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("zz_cfg"), bad.body());
            assertFalse(stored(c, "expectation", "e1").containsKey("zz_cfg"));
        }
    }

    @Test
    void anExpectationHeldSaveAndBundleImportCarryTheAnnotation(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            heldThenApproved(c, "expectation", "/expectations", EXP + ",\"x-team\":\"dq\"}");
            assertEquals("dq", stored(c, "expectation", "e1").get("x-team"), "the approved write kept the annotation");
        }
        try (Ctx c = open(cfg, tmp.resolve("two"))) {
            assertEquals(200, send(c, "POST", "/bundle/import", bundle("expectation", "e1", EXP + ",\"x-team\":\"dq\"}"), "super-1").statusCode());
            assertEquals("dq", stored(c, "expectation", "e1").get("x-team"));
            HttpResponse<String> r = send(c, "POST", "/bundle/import",
                    bundle("expectation", "e2", EXP.replace("e1", "e2") + ",\"zz_cfg\":\"v\"}"), "super-1");
            assertTrue(r.body().contains("zz_cfg"), r.body());
            assertFalse(exists(c, "expectation", "e2"));
        }
    }

    // ── Notification Rule ────────────────────────────────────────────────────────

    @Test
    void aNotificationRuleKeepsItsAnnotationAndRefusesAnUnknownKey(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            HttpResponse<String> bad = send(c, "POST", "/notifications/rules", RULE + ",\"zz_cfg\":\"v\"}", "super-1");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("ERR_UNKNOWN_CONFIG_KEY") && bad.body().contains("zz_cfg"), bad.body());
            assertFalse(exists(c, "notification-rule", "r1"), "nothing written");

            assertEquals(200, send(c, "POST", "/notifications/rules", RULE + ",\"x-team\":\"noc\"}", "super-1").statusCode());
            assertEquals("noc", stored(c, "notification-rule", "r1").get("x-team"));
            JsonNode got = listed(c, "/notifications/rules", "id", "r1");
            assertEquals("noc", got.get("x-team").asText(), "GET returns it; the store's own `name` envelope is accepted back");
            assertEquals(200, send(c, "PUT", "/notifications/rules/r1", got.toString(), "super-1").statusCode());
            assertEquals("noc", stored(c, "notification-rule", "r1").get("x-team"));
            assertEquals(422, send(c, "PUT", "/notifications/rules/r1", RULE + ",\"zz_cfg\":\"v\"}", "super-1").statusCode());
            assertEquals(200, send(c, "PUT", "/notifications/rules/r1", RULE + "}", "super-1").statusCode());
            assertFalse(stored(c, "notification-rule", "r1").containsKey("x-team"), "PUT replaces");
        }
    }

    @Test
    void aNotificationRuleHeldSaveAndBundleImportCarryTheAnnotation(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            heldThenApproved(c, "notification-rule", "/notifications/rules", RULE + ",\"x-team\":\"noc\"}");
            assertEquals("noc", stored(c, "notification-rule", "r1").get("x-team"));
        }
        try (Ctx c = open(cfg, tmp.resolve("two"))) {
            assertEquals(200, send(c, "POST", "/bundle/import", bundle("notification-rule", "r1", RULE + ",\"x-team\":\"noc\"}"), "super-1").statusCode());
            assertEquals("noc", stored(c, "notification-rule", "r1").get("x-team"));
            HttpResponse<String> r = send(c, "POST", "/bundle/import",
                    bundle("notification-rule", "r2", RULE.replace("r1", "r2") + ",\"zz_cfg\":\"v\"}"), "super-1");
            assertTrue(r.body().contains("zz_cfg"), r.body());
            assertFalse(exists(c, "notification-rule", "r2"));
        }
    }

    // ── Notification Channel ─────────────────────────────────────────────────────

    @Test
    void aNotificationChannelKeepsItsAnnotationAndRefusesAnUnknownKey(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            HttpResponse<String> bad = send(c, "POST", "/notifications/channels", CHAN + ",\"zz_cfg\":\"v\"}", "super-1");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("ERR_UNKNOWN_CONFIG_KEY") && bad.body().contains("zz_cfg"), bad.body());
            assertFalse(exists(c, "channel", "c1"));

            assertEquals(200, send(c, "POST", "/notifications/channels", CHAN + ",\"x-team\":\"noc\"}", "super-1").statusCode());
            assertEquals("noc", stored(c, "channel", "c1").get("x-team"));
            JsonNode got = listed(c, "/notifications/channels", "id", "c1");
            assertEquals("noc", got.get("x-team").asText());
            assertEquals(200, send(c, "PUT", "/notifications/channels/c1", got.toString(), "super-1").statusCode());
            assertEquals("noc", stored(c, "channel", "c1").get("x-team"), "a round trip keeps it");
            assertEquals(422, send(c, "PUT", "/notifications/channels/c1", "{\"zz_cfg\":\"v\"}", "super-1").statusCode());
        }
    }

    @Test
    void aNotificationChannelHeldSaveAndBundleImportCarryTheAnnotation(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            heldThenApproved(c, "channel", "/notifications/channels", CHAN + ",\"x-team\":\"noc\"}");
            assertEquals("noc", stored(c, "channel", "c1").get("x-team"));
        }
        try (Ctx c = open(cfg, tmp.resolve("two"))) {
            assertEquals(200, send(c, "POST", "/bundle/import", bundle("channel", "c1", CHAN + ",\"x-team\":\"noc\"}"), "super-1").statusCode());
            assertEquals("noc", stored(c, "channel", "c1").get("x-team"));
            HttpResponse<String> r = send(c, "POST", "/bundle/import",
                    bundle("channel", "c2", CHAN.replace("c1", "c2") + ",\"zz_cfg\":\"v\"}"), "super-1");
            assertTrue(r.body().contains("zz_cfg"), r.body());
            assertFalse(exists(c, "channel", "c2"));
        }
    }
}

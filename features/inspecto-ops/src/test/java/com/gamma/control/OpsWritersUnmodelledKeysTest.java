package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.AfterEach;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MODULE-REORG-P4-2} (the ops writers): Tag, Tag Rule and Case Rule persist what they REBUILD from a typed
 * record's {@code toMap()}, so a key the record does not model used to vanish behind a 200. Now an author-owned
 * {@code x-} key is kept (create, GET, the persisted file, a re-load, a Tag rename) and any other key is refused 422
 * {@code ERR_UNKNOWN_CONFIG_KEY} naming it with nothing written. A re-save REPLACES: what the client sent is stored.
 * Every {@code x-} assertion is a probe that SUCCEEDED (200, key gone) before the change.
 */
class OpsWritersUnmodelledKeysTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("cfg"));
        System.setProperty("assist.write.root", root.toString());
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), root);
    }

    @AfterEach
    void clearWriteRoot() {
        System.clearProperty("assist.write.root");
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }

    private JsonNode listed(Ctx c, String path, String name) throws Exception {
        for (JsonNode n : json(send(c, "GET", path, null))) if (name.equals(n.get("name").asText())) return n;
        throw new AssertionError(name + " not listed at " + path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> block(Path file, String key) throws Exception {
        return (Map<String, Object>) ToonHelper.load(file.toString()).get(key);
    }

    private static final String RULE = "\"name\":\"r1\",\"tag\":\"hot\",\"filter\":{\"type\":\"INCIDENT\",\"priority\":\"CRITICAL\"}";
    private static final String CASE = "\"name\":\"c1\",\"title\":\"Burst\",\"filter\":{\"type\":\"INCIDENT\",\"priority\":\"CRITICAL\"}";

    // ── Tag ──────────────────────────────────────────────────────────────────────

    @Test
    void aTagKeepsItsAnnotationAndRefusesAnyOtherUnmodelledKey(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> bad = send(c, "POST", "/tags", "{\"name\":\"t1\",\"zz_cfg\":1}");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("ERR_UNKNOWN_CONFIG_KEY") && bad.body().contains("zz_cfg"), bad.body());
            assertFalse(Files.exists(c.root.resolve("t1_tag.toon")), "a refused save writes nothing");
            assertTrue(json(send(c, "GET", "/tags", null)).isEmpty());

            HttpResponse<String> ok = send(c, "POST", "/tags", "{\"name\":\"t1\",\"x-team\":\"ops\"}");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals("ops", json(ok).path("x-team").asText(), "the create response carries the annotation");
            assertEquals("ops", listed(c, "/tags", "t1").path("x-team").asText(), "GET lists it");
            assertEquals("ops", block(c.root.resolve("t1_tag.toon"), "tag").get("x-team"), "the file keeps it");

            // a rename writes the destination file afresh: the annotation moves with the tag
            assertEquals(200, send(c, "POST", "/tags/t1/rename", "{\"to\":\"t2\"}").statusCode());
            assertEquals("ops", block(c.root.resolve("t2_tag.toon"), "tag").get("x-team"), "rename carries it");
            assertEquals("ops", listed(c, "/tags", "t2").path("x-team").asText());
        }
    }

    // ── Tag Rule ─────────────────────────────────────────────────────────────────

    @Test
    void aTagRuleKeepsItsAnnotation_aResaveReplaces_andAnyOtherKeyIsRefused(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> bad = send(c, "POST", "/tags/rules", "{" + RULE + ",\"zz_cfg\":1}");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("ERR_UNKNOWN_CONFIG_KEY") && bad.body().contains("zz_cfg"), bad.body());
            assertFalse(Files.exists(c.root.resolve("r1_tagrule.toon")), "a refused save writes nothing");

            HttpResponse<String> ok = send(c, "POST", "/tags/rules", "{" + RULE + ",\"x-team\":\"ops\"}");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals("ops", listed(c, "/tags/rules", "r1").path("x-team").asText(), "GET lists it");
            Path file = c.root.resolve("r1_tagrule.toon");
            assertEquals("ops", block(file, "tag_rule").get("x-team"), "the file keeps it");
            assertEquals("ops", com.gamma.ops.tag.TagRule.load(file).toMap().get("x-team"), "a reload keeps it");

            // the flattened-filter authoring sugar is modelled, not an unknown key
            assertEquals(200, send(c, "POST", "/tags/rules",
                    "{\"name\":\"r2\",\"tag\":\"hot\",\"type\":\"INCIDENT\",\"priority\":\"CRITICAL\"}").statusCode());

            // a rename of the rule's tag rewrites the rule file: the annotation survives
            send(c, "POST", "/tags", "{\"name\":\"fresh\"}");
            assertEquals(200, send(c, "POST", "/tags/hot/rename", "{\"to\":\"warm\"}").statusCode());
            assertEquals("warm", block(file, "tag_rule").get("tag"));
            assertEquals("ops", block(file, "tag_rule").get("x-team"), "rename rewrites the rule file and keeps it");

            // POST replaces with what was posted: the annotation it no longer sends is gone
            assertEquals(200, send(c, "POST", "/tags/rules",
                    "{\"name\":\"r1\",\"tag\":\"warm\",\"filter\":{\"type\":\"INCIDENT\",\"priority\":\"CRITICAL\"}}").statusCode());
            assertNull(block(file, "tag_rule").get("x-team"));
            assertNull(listed(c, "/tags/rules", "r1").get("x-team"));
        }
    }

    // ── Case Rule ────────────────────────────────────────────────────────────────

    @Test
    void aCaseRuleKeepsItsAnnotation_aResaveReplaces_andAnyOtherKeyIsRefused(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> bad = send(c, "POST", "/cases/rules", "{" + CASE + ",\"zz_cfg\":1}");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("ERR_UNKNOWN_CONFIG_KEY") && bad.body().contains("zz_cfg"), bad.body());
            assertFalse(Files.exists(c.root.resolve("c1_caserule.toon")), "a refused save writes nothing");

            HttpResponse<String> ok = send(c, "POST", "/cases/rules", "{" + CASE + ",\"x-team\":\"ops\"}");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals("ops", listed(c, "/cases/rules", "c1").path("x-team").asText(), "GET lists it");
            Path file = c.root.resolve("c1_caserule.toon");
            assertEquals("ops", block(file, "case_rule").get("x-team"), "the file keeps it");
            assertEquals("ops", com.gamma.ops.tag.CaseRule.load(file).toMap().get("x-team"), "a reload keeps it");

            assertEquals(200, send(c, "POST", "/cases/rules", "{" + CASE + "}").statusCode());
            assertNull(block(file, "case_rule").get("x-team"), "a re-save replaces with the posted body");
            assertNull(listed(c, "/cases/rules", "c1").get("x-team"));
        }
    }
}

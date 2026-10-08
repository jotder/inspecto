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
 * {@code MODULE-REORG-P4-2} for the Case Rule writer (moved from {@code OpsWritersUnmodelledKeysTest}, MODULE-REORG-P7): an
 * author-owned {@code x-} key is kept (create, GET, the persisted file, a re-load) and any other key is refused 422
 * {@code ERR_UNKNOWN_CONFIG_KEY} naming it with nothing written. A re-save REPLACES: what the client sent is stored.
 */
class CaseRuleUnmodelledKeysTest {

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

    private static final String CASE = "\"name\":\"c1\",\"title\":\"Burst\",\"filter\":{\"type\":\"INCIDENT\",\"priority\":\"CRITICAL\"}";

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
            assertEquals("ops", com.gamma.ops.cases.CaseRule.load(file).toMap().get("x-team"), "a reload keeps it");

            assertEquals(200, send(c, "POST", "/cases/rules", "{" + CASE + "}").statusCode());
            assertNull(block(file, "case_rule").get("x-team"), "a re-save replaces with the posted body");
            assertNull(listed(c, "/cases/rules", "c1").get("x-team"));
        }
    }
}

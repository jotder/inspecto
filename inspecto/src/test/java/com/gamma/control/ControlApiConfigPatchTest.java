package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.io.ConfigLoader;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@code POST /config/patch} (collector-config unification, 2026-08-04) over
 * real HTTP: the block-level save that deep-merges a partial draft over the file's CURRENT on-disk
 * content server-side, so a stage pane can never clobber blocks it didn't edit with a stale
 * client-held copy. Covers the fail-closed gate ordering (writes disabled → unknown type → bad
 * body → unsafe name → path jail → missing target → identity change → merged-draft findings) plus
 * the merge semantics: sibling blocks are preserved byte-identically, an explicit JSON {@code null}
 * deletes a key, and unmodeled keys survive. Modeled on {@link ControlApiConfigWriteTest}.
 */
class ControlApiConfigPatchTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a server. {@code writeRoot==null} ⇒ writes disabled. */
    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** Seed a pipeline file via the real {@code /config/write} route (same encoding the patch reads). */
    private void seed(int port, String name) throws Exception {
        String draft = """
                {"type":"pipeline","config":{
                   "name":"%s",
                   "dirs":{"poll":"in","database":"out"},
                   "parsing":{"delimiter":";"},
                   "collector":{"connector":"local","discovery":"poll",
                                "connection":"old_conn",
                                "duplicate":{"mode":"checksum","algorithm":"xxh64"}},
                   "processing":{"threads":1}}}""".formatted(name);
        HttpResponse<String> r = post(port, "/config/write", draft);
        assertEquals(200, r.statusCode(), "seed write failed: " + r.body());
    }

    private static String patch(String name, String patchJson) {
        return """
                {"type":"pipeline","name":"%s","patch":%s}""".formatted(name, patchJson);
    }

    @Test
    void disabledWhenNoWriteRootConfigured(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = post(c.port, "/config/patch",
                    patch("orders", "{\"collector\":{\"discovery\":\"watch\"}}"));
            assertEquals(503, r.statusCode(), "no -Dassist.write.root ⇒ writes disabled");
        }
    }

    @Test
    void unknownTypeIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/patch",
                    "{\"type\":\"nonsense\",\"name\":\"x\",\"patch\":{}}");
            assertEquals(404, r.statusCode(), r.body());
        }
    }

    @Test
    void missingBodyFieldsAre400(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(400, post(c.port, "/config/patch",
                    "{\"type\":\"pipeline\",\"name\":\"x\"}").statusCode(), "no patch map");
            assertEquals(400, post(c.port, "/config/patch",
                    "{\"type\":\"pipeline\",\"patch\":{}}").statusCode(), "no name");
            assertEquals(400, post(c.port, "/config/patch",
                    "{\"type\":\"pipeline\",\"name\":\"x\",\"patch\":\"scalar\"}").statusCode(),
                    "patch must be a map");
        }
    }

    @Test
    void unsafeNameIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/patch",
                    patch("../../etc/passwd", "{}"));
            assertEquals(422, r.statusCode(), "a name with path separators / .. is rejected");
        }
    }

    @Test
    void pathJailRejectsEscapingSubdir(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String escaping = """
                    {"type":"pipeline","name":"x","subdir":"../escape","patch":{}}""";
            assertEquals(403, post(c.port, "/config/patch", escaping).statusCode(),
                    "subdir escaping the root is blocked");
        }
    }

    @Test
    void missingTargetIs404NotACreate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/patch",
                    patch("never_written", "{\"collector\":{\"discovery\":\"watch\"}}"));
            assertEquals(404, r.statusCode(), "patch never creates a file — that is /config/write's job");
            assertFalse(Files.exists(root.resolve("never_written_pipeline.toon")));
        }
    }

    @Test
    void identityChangeIs409(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c.port, "stable");
            HttpResponse<String> r = post(c.port, "/config/patch",
                    patch("stable", "{\"name\":\"renamed\"}"));
            assertEquals(409, r.statusCode(),
                    "renaming the identity under the old filename splits config from index");
        }
    }

    /** The anti-clobber regression this route exists for. */
    @Test
    void patchingTheCollectorBlockLeavesSiblingBlocksByteIdentical(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c.port, "orders");
            Path file = root.resolve("orders_pipeline.toon");
            Map<String, Object> before = ConfigLoader.filesystem().decode(file.toString());

            HttpResponse<String> r = post(c.port, "/config/patch",
                    patch("orders", "{\"collector\":{\"discovery\":\"watch\"}}"));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean());
            assertEquals("orders", out.get("name").asText());

            Map<String, Object> after = ConfigLoader.filesystem().decode(file.toString());
            assertEquals(before.get("parsing"), after.get("parsing"), "untouched block preserved");
            assertEquals(before.get("dirs"), after.get("dirs"), "untouched block preserved");
            assertEquals(before.get("processing"), after.get("processing"), "untouched block preserved");
            @SuppressWarnings("unchecked")
            Map<String, Object> collector = (Map<String, Object>) after.get("collector");
            assertEquals("watch", collector.get("discovery"), "patched key applied");
            assertEquals("old_conn", collector.get("connection"),
                    "keys inside the patched block that the patch didn't name survive the merge");
        }
    }

    /** Cleared fields travel as explicit JSON nulls and delete their key. */
    @Test
    void explicitNullDeletesTheKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c.port, "cleared");
            HttpResponse<String> r = post(c.port, "/config/patch",
                    patch("cleared", "{\"collector\":{\"connection\":null}}"));
            assertEquals(200, r.statusCode(), r.body());

            Map<String, Object> after = ConfigLoader.filesystem()
                    .decode(root.resolve("cleared_pipeline.toon").toString());
            @SuppressWarnings("unchecked")
            Map<String, Object> collector = (Map<String, Object>) after.get("collector");
            assertFalse(collector.containsKey("connection"), "null patch value deletes the key");
            assertEquals("local", collector.get("connector"), "siblings survive the delete");
        }
    }

    /** Hand-authored keys no spec models must travel verbatim through a block patch. */
    @Test
    void unmodeledKeysSurviveThePatch(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c.port, "handmade");
            HttpResponse<String> r = post(c.port, "/config/patch",
                    patch("handmade", "{\"collector\":{\"discovery\":\"watch\"}}"));
            assertEquals(200, r.statusCode(), r.body());

            Map<String, Object> after = ConfigLoader.filesystem()
                    .decode(root.resolve("handmade_pipeline.toon").toString());
            @SuppressWarnings("unchecked")
            Map<String, Object> collector = (Map<String, Object>) after.get("collector");
            @SuppressWarnings("unchecked")
            Map<String, Object> duplicate = (Map<String, Object>) collector.get("duplicate");
            assertEquals("xxh64", duplicate.get("algorithm"),
                    "a key no spec models survives a patch of its sibling");
            assertEquals("checksum", duplicate.get("mode"));
        }
    }

    /** The merged WHOLE draft is what gets validated — a patch cannot sneak an armed no-schema config in. */
    @Test
    void mergedDraftWithErrorFindingsIs422AndWritesNothing(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c.port, "armed");
            Path file = root.resolve("armed_pipeline.toon");
            byte[] before = Files.readAllBytes(file);

            HttpResponse<String> r = post(c.port, "/config/patch",
                    patch("armed", "{\"active\":true}"));
            assertEquals(422, r.statusCode(), r.body());
            JsonNode out = V1Body.envelope(r.body()).get("error").get("details");
            assertFalse(out.get("written").asBoolean());
            assertTrue(out.get("findings").toString().contains("no schema is configured"),
                    "the finding names the missing schema: " + out.get("findings"));
            assertArrayEquals(before, Files.readAllBytes(file), "nothing written on a rejected patch");
        }
    }
}

package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MODULE-REORG-P4-2} (Saved View): {@code POST /events/views} read only the named fields of its body, so any other
 * key (an annotation, a typo, a nested {@code filters} object) vanished behind a 200. Now an {@code x-} key is kept (create,
 * list, a bundle import) and any other key is refused 422 {@code ERR_UNKNOWN_CONFIG_KEY} naming it with nothing stored.
 */
class SavedViewUnmodelledKeysTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        System.setProperty("assist.write.root", Files.createDirectories(dir.resolve("cfg")).toString());   // a bundle import is write-root gated
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
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

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;
    }

    private JsonNode listed(Ctx c, String name) throws Exception {
        for (JsonNode n : data(send(c, "GET", "/events/views", null))) if (name.equals(n.get("name").asText())) return n;
        throw new AssertionError(name + " not listed");
    }

    private static String bundle(String id, String contentJson) {
        return "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"exportedAt\":\"2026-07-18T00:00:00Z\","
                + "\"sourceSpace\":null,\"items\":[{\"kind\":\"saved-view\",\"id\":\"" + id + "\",\"content\":" + contentJson + "}]}";
    }

    @Test
    void aSavedViewKeepsItsAnnotation_aResaveReplaces_andAnyOtherKeyIsRefused(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> bad = send(c, "POST", "/events/views", "{\"name\":\"v1\",\"level\":\"ERROR\",\"zz_cfg\":1}");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("ERR_UNKNOWN_CONFIG_KEY") && bad.body().contains("zz_cfg"), bad.body());
            HttpResponse<String> nested = send(c, "POST", "/events/views", "{\"name\":\"v1\",\"filters\":{\"level\":\"ERROR\"}}");
            assertEquals(422, nested.statusCode(), "a nested filters object used to be dropped: the view saved with no filter at all");
            assertTrue(data(send(c, "GET", "/events/views", null)).isEmpty(), "a refused save stores nothing");

            HttpResponse<String> ok = send(c, "POST", "/events/views", "{\"name\":\"v1\",\"level\":\"ERROR\",\"x-team\":\"ops\"}");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals("ops", data(ok).path("x-team").asText(), "the response carries the annotation");
            assertEquals("ops", listed(c, "v1").path("x-team").asText(), "GET lists it");
            assertEquals("ERROR", listed(c, "v1").path("filters").path("level").asText());

            assertEquals(200, send(c, "POST", "/events/views", "{\"name\":\"v1\",\"level\":\"ERROR\"}").statusCode());
            assertNull(listed(c, "v1").get("x-team"), "a re-save replaces with the posted body");
        }
    }

    @Test
    void aBundleImportKeepsTheAnnotationAndFailsTheItemForAnyOtherKey(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> ok = send(c, "POST", "/bundle/import",
                    bundle("v2", "{\"name\":\"v2\",\"filters\":{\"level\":\"ERROR\"},\"createdAt\":5,\"x-team\":\"ops\"}"));
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals("ops", listed(c, "v2").path("x-team").asText());

            HttpResponse<String> bad = send(c, "POST", "/bundle/import",
                    bundle("v3", "{\"name\":\"v3\",\"filters\":{\"level\":\"ERROR\"},\"zz_cfg\":\"v\"}"));
            assertTrue(bad.body().contains("zz_cfg"), "the item fails naming the key: " + bad.statusCode() + " " + bad.body());
            assertEquals(1, data(send(c, "GET", "/events/views", null)).size(), "the refused item stored nothing");
        }
    }
}

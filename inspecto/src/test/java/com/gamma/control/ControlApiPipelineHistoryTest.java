package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PIPELINE-CONFIG-HISTORY-1} over real HTTP: every successful Pipeline-config save leaves one
 * version under {@code <write-root>/.history/pipelines/<id>/}, a refused save leaves none, the newest
 * {@value PipelineHistory#KEEP} are kept, versions diff against each other and the current config, a
 * rename carries the history to the new id, a delete purges it, and the read routes are gated exactly
 * like {@code GET /pipelines/{name}/graph/raw} (authenticated, no capability).
 *
 * <p>The save paths reach a config the way {@link ControlApiSaveGateParityTest} drives them — that class
 * pins that they all run one gate; this one pins that they all leave one version.
 */
class ControlApiPipelineHistoryTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    enum SavePath { WRITE, PATCH, GRAPH, IMPORT }

    @AfterEach
    void tearDown() { Authenticators.forTest(null); }

    // ── a save leaves a version; a refused save leaves none ─────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(SavePath.class)
    void aSuccessfulSaveOnEveryPathAddsOneVersion(SavePath path, @TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Files.createDirectories(wr);
        String name = "hist_" + path.name().toLowerCase();
        try (Ctx c = open(dir, wr)) {
            HttpResponse<String> r = save(c, path, dir, wr, name, base(dir, name));
            assertEquals(200, r.statusCode(), r.body());

            JsonNode list = V1Body.of(get(c.port, "/pipelines/" + name + "/history").body());
            assertEquals(name, list.get("pipeline").asText());
            assertEquals(PipelineHistory.KEEP, list.get("keep").asInt());
            assertEquals(1, list.get("versions").size(), list.toString());
            assertEquals(1, list.get("versions").get(0).get("version").asInt());

            // The snapshot is the bytes on disk after the write — exactly what a read serves.
            Path onDisk = path == SavePath.IMPORT ? wr.resolve(name).resolve(name + "_pipeline.toon")
                    : wr.resolve(name + "_pipeline.toon");
            JsonNode v1 = V1Body.of(get(c.port, "/pipelines/" + name + "/history/1").body());
            assertEquals(Files.readString(onDisk), v1.get("text").asText());
            assertTrue(Files.isRegularFile(wr.resolve(".history/pipelines/" + name + "/v1.toon")));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SavePath.class)
    void aRefusedSaveAddsNoVersion(SavePath path, @TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Files.createDirectories(wr);
        String name = "refused_" + path.name().toLowerCase();
        Map<String, Object> faulty = base(dir, name);
        faulty.put("bogus_block", map("x", "1"));   // ERR_UNKNOWN_CONFIG_KEY on every path
        try (Ctx c = open(dir, wr)) {
            HttpResponse<String> r = save(c, path, dir, wr, name, faulty);
            assertEquals(422, r.statusCode(), r.body());
            assertFalse(Files.exists(wr.resolve(".history/pipelines/" + name)), "a refused save leaves no version");
        }
    }

    // ── retention ────────────────────────────────────────────────────────────────

    @Test
    void fiftyOneSavesKeepTheNewestFifty(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Files.createDirectories(wr);
        try (Ctx c = open(dir, wr)) {
            for (int i = 1; i <= PipelineHistory.KEEP + 1; i++) {
                Map<String, Object> d = base(dir, "kept");
                d.put("description", "save " + i);
                assertEquals(200, write(c, d).statusCode());
            }
            JsonNode list = V1Body.of(get(c.port, "/pipelines/kept/history").body());
            assertEquals(PipelineHistory.KEEP, list.get("total").asInt());
            JsonNode versions = list.get("versions");
            assertEquals(PipelineHistory.KEEP, versions.size());
            assertEquals(PipelineHistory.KEEP + 1, versions.get(0).get("version").asInt(), "newest first");
            assertEquals(2, versions.get(versions.size() - 1).get("version").asInt(), "the oldest was pruned");
            assertEquals(404, get(c.port, "/pipelines/kept/history/1").statusCode(), "v1 is gone");
            try (var files = Files.list(wr.resolve(".history/pipelines/kept"))) {
                assertEquals(PipelineHistory.KEEP, files.count());
            }
        }
    }

    // ── diff ─────────────────────────────────────────────────────────────────────

    @Test
    void versionsDiffAgainstEachOtherAndTheCurrentConfig(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Files.createDirectories(wr);
        Path pipe = PipelineConfigBatchTest.writePipeline(wr, "", false);   // registered, so "current" resolves
        try (Ctx c = open(dir, wr, pipe)) {
            assertEquals(200, patch(c, "first").statusCode());
            assertEquals(200, patch(c, "second").statusCode());

            JsonNode diff = V1Body.of(get(c.port, "/pipelines/mini_etl/history/diff?from=1&to=2").body());
            assertEquals(1, diff.get("from").asInt());
            assertEquals(2, diff.get("to").asInt());
            assertEquals(1, diff.get("added").asInt(), diff.toString());
            assertEquals(1, diff.get("removed").asInt(), diff.toString());
            assertFalse(diff.get("coarse").asBoolean());
            String removed = null, added = null;
            for (JsonNode l : diff.get("lines")) {
                if ("remove".equals(l.get("op").asText())) removed = l.get("text").asText();
                if ("add".equals(l.get("op").asText())) added = l.get("text").asText();
            }
            assertTrue(removed != null && removed.contains("first"), diff.toString());
            assertTrue(added != null && added.contains("second"), diff.toString());

            // `to` defaults to the current config; the newest version IS the current config.
            JsonNode vsCurrent = V1Body.of(get(c.port, "/pipelines/mini_etl/history/diff?from=2").body());
            assertEquals("current", vsCurrent.get("to").asText());
            assertEquals(0, vsCurrent.get("added").asInt());
            assertEquals(0, vsCurrent.get("removed").asInt());
            assertEquals(1, V1Body.of(get(c.port, "/pipelines/mini_etl/history/diff?from=1&to=current").body())
                    .get("added").asInt());

            assertEquals(400, get(c.port, "/pipelines/mini_etl/history/diff").statusCode(), "from is required");
            assertEquals(400, get(c.port, "/pipelines/mini_etl/history/diff?from=x").statusCode());
            assertEquals(404, get(c.port, "/pipelines/mini_etl/history/diff?from=9").statusCode());
            assertEquals(400, get(c.port, "/pipelines/mini_etl/history/abc").statusCode());
            assertEquals(404, get(c.port, "/pipelines/mini_etl/history/9").statusCode());
            assertEquals(404, get(c.port, "/pipelines/nope/history").statusCode());
        }
    }

    @Test
    void theDiffIsALineDiff() {
        Map<String, Object> d = PipelineHistory.diff(List.of("a", "b", "c", "d"), List.of("a", "x", "c", "d", "e"));
        assertEquals(2L, d.get("added"));
        assertEquals(1L, d.get("removed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) d.get("lines");
        assertEquals(List.of("context:a", "remove:b", "add:x", "context:c", "context:d", "add:e"),
                lines.stream().map(l -> l.get("op") + ":" + l.get("text")).toList());
    }

    // ── rename / delete ──────────────────────────────────────────────────────────

    @Test
    void aRenameCarriesTheHistoryToTheNewId(@TempDir Path root) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(root, "", false);
        try (Ctx c = open(root, root, pipe)) {
            assertEquals(200, post(c.port, "/pipelines/mini_etl/label", "{\"name\":\"Mini Relabelled\"}").statusCode());
            assertEquals(1, V1Body.of(get(c.port, "/pipelines/mini_etl/history").body()).get("versions").size());

            HttpResponse<String> r = post(c.port, "/pipelines/mini_etl/rename", "{\"newId\":\"mini_v2\"}");
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(V1Body.of(r.body()).get("journal").toString().contains("config history moved"), r.body());

            JsonNode list = V1Body.of(get(c.port, "/pipelines/mini_v2/history").body());
            assertEquals("mini_v2", list.get("pipeline").asText());
            assertEquals(2, list.get("versions").size(), "the label save plus the rename itself: " + list);
            assertTrue(V1Body.of(get(c.port, "/pipelines/mini_v2/history/2").body()).get("text").asText()
                    .contains("mini_v2"));
            assertTrue(V1Body.of(get(c.port, "/pipelines/mini_v2/history/1").body()).get("text").asText()
                    .contains("Mini Relabelled"));
            assertFalse(Files.exists(root.resolve(".history/pipelines/mini_etl")));
            assertEquals(404, get(c.port, "/pipelines/mini_etl/history").statusCode());
        }
    }

    @Test
    void aDeletePurgesTheHistory(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Files.createDirectories(wr);
        try (Ctx c = open(dir, wr)) {
            assertEquals(200, write(c, base(dir, "doomed")).statusCode());
            assertTrue(Files.isDirectory(wr.resolve(".history/pipelines/doomed")));
            HttpResponse<String> del = send(c.port, "DELETE", "/config/pipeline/doomed", null);
            assertEquals(200, del.statusCode(), del.body());
            assertFalse(Files.exists(wr.resolve(".history/pipelines/doomed")), "delete means gone, history included");
            assertEquals(404, get(c.port, "/pipelines/doomed/history").statusCode());
        }
    }

    // ── read gating: like every other pipeline read ──────────────────────────────

    /** {@code Bearer author} → canAuthorWorkbench; {@code Bearer plain} → no capability at all. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer author".equals(auth)) return Optional.of(new Subject("jdoe", Set.of("canAuthorWorkbench")));
        if ("Bearer plain".equals(auth)) return Optional.of(new Subject("nobody", Set.of()));
        return Optional.empty();
    };

    @Test
    void theReadsAreRefusedUnauthenticatedAndOpenToAnyAuthenticatedCallerLikeGraphRaw(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Files.createDirectories(wr);
        Path pipe = PipelineConfigBatchTest.writePipeline(wr, "", false);
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir, wr, pipe)) {
            // The save itself is capability-gated, so a Subject is really attached (withCapability is live).
            assertEquals(403, send(c.port, "POST", "/config/patch", patchBody("gated"), "Authorization", "Bearer plain").statusCode());
            assertFalse(Files.exists(wr.resolve(".history/pipelines/mini_etl")), "a 403 save leaves no version");
            HttpResponse<String> saved = send(c.port, "POST", "/config/patch", patchBody("gated"), "Authorization", "Bearer author");
            assertEquals(200, saved.statusCode(), saved.body());

            for (String path : List.of("/pipelines/mini_etl/graph/raw", "/pipelines/mini_etl/history",
                    "/pipelines/mini_etl/history/1", "/pipelines/mini_etl/history/diff?from=1")) {
                assertEquals(401, send(c.port, "GET", path, null).statusCode(), "no credential → 401: " + path);
                HttpResponse<String> ok = send(c.port, "GET", path, null, "Authorization", "Bearer plain");
                assertEquals(200, ok.statusCode(), "authenticated, no capability → 200 (a read): " + path + " " + ok.body());
            }
        }
    }

    // ── the paths ────────────────────────────────────────────────────────────────

    private HttpResponse<String> save(Ctx c, SavePath path, Path dir, Path wr, String name,
                                      Map<String, Object> config) throws Exception {
        return switch (path) {
            case WRITE -> write(c, config);
            case PATCH -> {
                Files.writeString(wr.resolve(name + "_pipeline.toon"), ConfigCodec.toToon(base(dir, name)));
                yield send(c.port, "POST", "/config/patch",
                        JSON.writeValueAsString(map("type", "pipeline", "name", name, "patch", config)));
            }
            case GRAPH -> {
                Files.writeString(wr.resolve(name + "_pipeline.toon"), ConfigCodec.toToon(config));
                String b = dir.toString().replace('\\', '/');
                String graph = """
                    {"active":false,
                     "nodes":[{"id":"p","type":"parser","config":{"schema_file":"%s"}},
                              {"id":"out","type":"sink.persistent","config":{"database":"%s/db"}}],
                     "edges":[{"from":"p","rel":"data","to":"out"}]}"""
                        .formatted(schema(dir).toString().replace('\\', '/'), b);
                yield send(c.port, "PUT", "/pipelines/" + name + "/graph", graph);
            }
            case IMPORT -> {
                Map<String, byte[]> entries = new LinkedHashMap<>();
                entries.put(PipelineBundleRoutes.MANIFEST, ConfigCodec.toToon(map(
                        "format", PipelineBundleRoutes.FORMAT, "version", PipelineBundleRoutes.VERSION,
                        "pipeline", name, "pipeline_file", name + "_pipeline.toon"))
                        .getBytes(StandardCharsets.UTF_8));
                entries.put(name + "_pipeline.toon", ConfigCodec.toToon(config).getBytes(StandardCharsets.UTF_8));
                yield client.send(HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + c.port + "/api/v1/pipelines/import?name=" + name))
                        .header("Content-Type", "application/zip")
                        .method("POST", BodyPublishers.ofByteArray(zipOf(entries))).build(), BodyHandlers.ofString());
            }
        };
    }

    private HttpResponse<String> write(Ctx c, Map<String, Object> config) throws Exception {
        return send(c.port, "POST", "/config/write", writeBody(config));
    }

    /** A merge patch of the registered fixture {@code mini_etl}, changing only its description. */
    private HttpResponse<String> patch(Ctx c, String description) throws Exception {
        return send(c.port, "POST", "/config/patch", patchBody(description));
    }

    private static String patchBody(String description) throws Exception {
        return JSON.writeValueAsString(map("type", "pipeline", "name", "mini_etl",
                "patch", map("description", description)));
    }

    private static String writeBody(Map<String, Object> config) throws Exception {
        return JSON.writeValueAsString(map("type", "pipeline", "config", config, "overwrite", true));
    }

    // ── fixture ──────────────────────────────────────────────────────────────────

    private static Path schema(Path dir) throws Exception {
        Path schema = dir.resolve("hist_schema.toon");
        if (!Files.exists(schema)) Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        return schema;
    }

    /** A clean, INACTIVE pipeline — every save path accepts it. */
    private static Map<String, Object> base(Path dir, String name) throws Exception {
        String b = dir.toString().replace('\\', '/');
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("active", false);
        d.put("dirs", map("poll", b + "/in", "database", b + "/db"));
        d.put("processing", map("schema_file", schema(dir).toString().replace('\\', '/')));
        return d;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        return open(dir, writeRoot, PipelineConfigBatchTest.writePipeline(dir, ""));
    }

    private Ctx open(Path dir, Path writeRoot, Path pipe) throws Exception {
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return send(port, "GET", path, null);
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return send(port, "POST", path, body);
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (headers.length > 0) b.headers(headers);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static byte[] zipOf(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}

package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.config.io.ConfigCodec;
import com.gamma.config.spec.FindingCodes;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every Pipeline-config save path runs ONE gate ({@link SaveGate}) — pinned over real HTTP by driving the
 * SAME faulty config through each path and demanding the same verdict (`PROCESSOR-RELEASE-READINESS-1` G3).
 *
 * <p>🔴 Before the shared gate the paths were hand-kept copies of one list, and they had drifted:
 * {@code PUT /pipelines/{name}/graph} ran neither the unknown-Connection check nor the unknown-key census
 * that {@code /config/write} ran, bundle import ran neither of them plus neither TypeFlow check, and
 * {@code /validate} ran four of the eleven. So a config the write route refused was saved by the graph
 * editor — one config, two answers.
 *
 * <p>Each fault is expressed as a mutation of one clean, inactive base config, and reaches each path the
 * way that path receives a config: a draft body (write, validate), a merge patch (patch), the file the
 * graph is lowered over (graph — lenient lowering preserves sections the graph does not model, which is
 * exactly how a stray block survives an editor save), or the pipeline entry of a bundle zip (import).
 *
 * <p>⚠ The ONE deliberate difference: a <b>missing</b> referent is a WARNING on bundle import, never a
 * refusal. A pipeline bundle never carries its Connections (secrets never travel), so refusing would make
 * promotion into a fresh Space impossible; the import lands inactive. A referent that EXISTS but is the
 * wrong kind is refused on import too — shipping it would not help.
 */
class ControlApiSaveGateParityTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    enum SavePath { WRITE, PATCH, GRAPH, IMPORT, VALIDATE }

    /** A fault: how it mutates the base config, and the finding every path must answer it with. */
    enum Fault {
        UNKNOWN_COLLECTOR_CONNECTION(d -> d.put("collector", map("connector", "sftp", "connection", "ghost")),
                "collector.connection", null, true),
        UNKNOWN_BLOCK(d -> d.put("bogus_block", map("x", "1")),
                "bogus_block", FindingCodes.ERR_UNKNOWN_CONFIG_KEY, false),
        WEBHOOK_URL(d -> d.put("webhook", map("connection", "hook_https", "url", "https://example.invalid/in")),
                "webhook", FindingCodes.ERR_WEBHOOK_INVALID, false),
        WEBHOOK_CONNECTION_UNKNOWN(d -> d.put("webhook", map("connection", "ghost_hook")),
                "webhook.connection", FindingCodes.ERR_WEBHOOK_CONNECTION_UNKNOWN, true),
        WEBHOOK_CONNECTION_NOT_HTTPS(d -> d.put("webhook", map("connection", "files_sftp")),
                "webhook.connection", FindingCodes.ERR_WEBHOOK_CONNECTION_NOT_HTTPS, false);

        final Consumer<Map<String, Object>> apply;
        final String fieldPath;
        final String code;          // null ⇒ matched on fieldPath alone
        /** A missing referent — deferred (WARNING) on bundle import, refused everywhere else. */
        final boolean missingReferent;

        Fault(Consumer<Map<String, Object>> apply, String fieldPath, String code, boolean missingReferent) {
            this.apply = apply;
            this.fieldPath = fieldPath;
            this.code = code;
            this.missingReferent = missingReferent;
        }
    }

    static Stream<Arguments> everyFaultOnEveryPath() {
        List<Arguments> out = new ArrayList<>();
        for (Fault f : Fault.values())
            for (SavePath p : SavePath.values()) out.add(Arguments.of(f, p));
        return out.stream();
    }

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("everyFaultOnEveryPath")
    void everySavePathAnswersTheSameFaultTheSameWay(Fault fault, SavePath path, @TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Files.createDirectories(wr);
        String name = "parity_" + fault.name().toLowerCase();
        Map<String, Object> faulty = base(dir, name);
        fault.apply.accept(faulty);

        try (Ctx c = open(dir, wr)) {
            boolean deferred = path == SavePath.IMPORT && fault.missingReferent;
            HttpResponse<String> r = save(c, path, dir, wr, name, faulty);

            if (path == SavePath.VALIDATE) {
                // /validate refuses nothing — it must REPORT exactly what a save would refuse.
                assertEquals(200, r.statusCode(), r.body());
                assertFinding(V1Body.of(r.body()).get("findings"), fault, "ERROR", r.body());
                return;
            }
            if (deferred) {
                assertEquals(200, r.statusCode(), r.body());
                assertTrue(V1Body.of(r.body()).get("written").asBoolean(), r.body());
                assertFinding(V1Body.of(r.body()).get("findings"), fault.fieldPath,
                        FindingCodes.WARN_UNRESOLVED_CONNECTION, "WARNING", r.body());
                return;
            }
            assertEquals(422, r.statusCode(), path + " must refuse " + fault + ": " + r.body());
            JsonNode details = V1Body.envelope(r.body()).get("error").get("details");
            assertFalse(details.get("written").asBoolean(), r.body());
            assertFinding(details.get("findings"), fault, "ERROR", r.body());
            assertNothingLanded(path, wr, name);
        }
    }

    /** The clean control: the base config itself passes every path — so a refusal above is the fault's. */
    @Test
    void theCleanBaseSavesOnEveryPath(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Files.createDirectories(wr);
        try (Ctx c = open(dir, wr)) {
            for (SavePath p : SavePath.values()) {
                String name = "clean_" + p.name().toLowerCase();
                HttpResponse<String> r = save(c, p, dir, wr, name, base(dir, name));
                assertEquals(200, r.statusCode(), p + ": " + r.body());
                assertFalse(V1Body.of(r.body()).get("findings").toString().contains("\"severity\":\"ERROR\""),
                        p + ": " + r.body());
            }
        }
    }

    /** A webhook naming a REGISTERED https Connection is not refused — the check is not a blanket ban. */
    @Test
    void aWebhookOnARegisteredHttpsConnectionSaves(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Files.createDirectories(wr);
        try (Ctx c = open(dir, wr)) {
            Map<String, Object> draft = base(dir, "hook_ok");
            draft.put("webhook", map("connection", "hook_https"));
            HttpResponse<String> r = save(c, SavePath.WRITE, dir, wr, "hook_ok", draft);
            assertEquals(200, r.statusCode(), r.body());
        }
    }

    /** The graph editor's own shape: a {@code sink.webhook} NODE naming a Connection that does not exist. */
    @Test
    void aWebhookNodeOnAnUnknownConnectionIsRefusedAtTheGraphSave(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Files.createDirectories(wr);
        String b = dir.toString().replace('\\', '/');
        String graph = """
            {"active":false,
             "nodes":[{"id":"acq","type":"acquisition","config":{"poll":"%s/in"}},
                      {"id":"p","type":"parser","config":{"schema_file":"%s"}},
                      {"id":"out","type":"sink.persistent","config":{"database":"%s/db"}},
                      {"id":"hook","type":"sink.webhook","config":{"connection":"ghost_hook"}}],
             "edges":[{"from":"acq","rel":"data","to":"p"},{"from":"p","rel":"data","to":"out"}]}"""
                .formatted(b, schema(dir).toString().replace('\\', '/'), b);
        try (Ctx c = open(dir, wr)) {
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/hook_node/graph", graph);
            assertEquals(422, r.statusCode(), r.body());
            assertFinding(V1Body.envelope(r.body()).get("error").get("details").get("findings"),
                    Fault.WEBHOOK_CONNECTION_UNKNOWN, "ERROR", r.body());
            assertFalse(Files.exists(wr.resolve("hook_node_pipeline.toon")), r.body());
        }
    }

    // ── the paths ────────────────────────────────────────────────────────────────

    private HttpResponse<String> save(Ctx c, SavePath path, Path dir, Path wr, String name,
                                      Map<String, Object> config) throws Exception {
        return switch (path) {
            case WRITE -> send(c.port, "POST", "/config/write",
                    JSON.writeValueAsString(map("type", "pipeline", "config", config)));
            case VALIDATE -> send(c.port, "POST", "/validate",
                    JSON.writeValueAsString(map("type", "pipeline", "config", config)));
            case PATCH -> {
                Files.writeString(wr.resolve(name + "_pipeline.toon"), ConfigCodec.toToon(base(dir, name)));
                // A merge patch of the whole faulty map over the clean base IS the faulty map.
                yield send(c.port, "POST", "/config/patch",
                        JSON.writeValueAsString(map("type", "pipeline", "name", name, "patch", config)));
            }
            case GRAPH -> {
                // The file the graph lowers over carries the fault; the INACTIVE graph models neither the
                // collector nor the webhook, so lenient lowering keeps them verbatim — the editor's save
                // of a config that already carries the fault.
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
                yield sendZip(c.port, "/pipelines/import?name=" + name, zipOf(entries));
            }
        };
    }

    private static void assertNothingLanded(SavePath path, Path wr, String name) throws Exception {
        switch (path) {
            case WRITE -> assertFalse(Files.exists(wr.resolve(name + "_pipeline.toon")), "nothing written");
            case IMPORT -> assertFalse(Files.exists(wr.resolve(name).resolve(name + "_pipeline.toon")),
                    "a refused import leaves nothing written");
            case PATCH, GRAPH -> {
                // The seeded file is untouched: its fault is still exactly what was seeded (PATCH: the clean
                // base, never the merged fault; GRAPH: the faulty file, never the lowered rewrite).
                Map<String, Object> onDisk = ConfigCodec.toMap(Files.readString(wr.resolve(name + "_pipeline.toon")));
                if (path == SavePath.PATCH)
                    assertFalse(onDisk.containsKey("bogus_block") || onDisk.containsKey("webhook")
                            || onDisk.containsKey("collector"), "a refused patch leaves the file as it was");
            }
            case VALIDATE -> { }
        }
    }

    private static void assertFinding(JsonNode findings, Fault fault, String severity, String body) {
        assertFinding(findings, fault.fieldPath, fault.code, severity, body);
    }

    private static void assertFinding(JsonNode findings, String fieldPath, String code, String severity, String body) {
        assertNotNull(findings, body);
        for (JsonNode f : findings) {
            if (!fieldPath.equals(f.path("fieldPath").asText())) continue;
            if (code != null && !code.equals(f.path("code").asText())) continue;
            if (!severity.equals(f.path("severity").asText())) continue;
            return;
        }
        fail("no " + severity + " finding at '" + fieldPath + "'" + (code == null ? "" : " / " + code) + " — " + body);
    }

    // ── fixture ──────────────────────────────────────────────────────────────────

    private static Path schema(Path dir) throws Exception {
        Path schema = dir.resolve("parity_schema.toon");
        if (!Files.exists(schema)) Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        return schema;
    }

    /** A clean, INACTIVE pipeline — inactive so no arming check fires and every refusal is the fault's. */
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
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            svc.registerConnection(new ConnectionProfile("hook_https", "https", "hooks.example.invalid", 443,
                    null, "/in", null, null, Map.of(), null));
            svc.registerConnection(new ConnectionProfile("files_sftp", "sftp", "sftp.example.invalid", 22,
                    null, "/out", "svc", null, Map.of(), null));
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> sendZip(int port, String path, byte[] zip) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/zip")
                .method("POST", BodyPublishers.ofByteArray(zip)).build(), BodyHandlers.ofString());
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

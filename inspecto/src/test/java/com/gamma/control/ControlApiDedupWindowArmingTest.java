package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.DisplayName;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-9's authoring refusal — a windowed dedup ({@code scope: window(...)}) with no {@code order_by}
 * tie-break — at EVERY gate on the save path, over real HTTP: {@code /validate}, {@code /config/write},
 * {@code /config/patch} and {@code PUT /pipelines/{name}/graph}. The rule (a save-path rule is checked
 * against every gate on that path): against a <b>durable</b> ledger a non-deterministic winner is
 * unrepeatable data loss, so the refusal must land at save time, in both spellings
 * ({@code processing.dedup} and a {@code steps[]} chain entry), with
 * {@code ControlApiRouteArmingTest}'s severity split — ACTIVE ⇒ 422 and nothing written, an INACTIVE
 * draft saves with a warning that says when it will refuse.
 */
class ControlApiDedupWindowArmingTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
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
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                        .method("POST", BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> put(int port, String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                        .method("PUT", BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    /** The legacy singular spelling: {@code processing.dedup} declaring a window with no tie-break. */
    private static String windowedDedupNoOrderBy(boolean active) {
        return """
                {"type":"pipeline","config":{
                   "name":"win_dedup",
                   "active":%s,
                   "output_store":"deduped",
                   "dirs":{"poll":"in","database":"out"},
                   "processing":{"threads":1,"schema_file":"cdr.toon",
                                 "dedup":{"keys":["MSISDN"],"scope":"window(P4D)"}}}}"""
                .formatted(active);
    }

    /** The {@code steps[]} chain spelling of the same mistake. */
    private static String windowedStepsDedupNoOrderBy(boolean active) {
        return """
                {"type":"pipeline","config":{
                   "name":"win_dedup_steps",
                   "active":%s,
                   "output_store":"deduped",
                   "dirs":{"poll":"in","database":"out"},
                   "processing":{"threads":1,"schema_file":"cdr.toon"},
                   "steps":[{"dedup":{"keys":["MSISDN"],"scope":"window(P4D)"}}]}}"""
                .formatted(active);
    }

    @Test
    @DisplayName("an ACTIVE windowed dedup without order_by is refused at /config/write — 422, nothing written")
    void activeWindowedDedupWithoutOrderByIsRefusedAtWrite(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", windowedDedupNoOrderBy(true));
            assertEquals(422, r.statusCode(), r.body());
            JsonNode out = V1Body.envelope(r.body()).get("error").get("details");
            assertFalse(out.get("written").asBoolean(), "an unarmable windowed dedup must not be written");
            String findings = out.get("findings").toString();
            assertTrue(findings.contains("order_by"), findings);
            assertTrue(findings.contains("DURABLE"), findings);
            assertTrue(findings.contains("\"code\":\"ERR_DEDUP_WINDOW_UNARMABLE\""), findings);
            assertTrue(findings.contains("\"guidance\":"), findings);
            assertFalse(Files.exists(root.resolve("win_dedup_pipeline.toon")),
                    "an unarmable active windowed dedup must not reach disk");
        }
    }

    @Test
    @DisplayName("the steps[] spelling is caught too, naming the entry")
    void stepsChainSpellingIsCaughtToo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", windowedStepsDedupNoOrderBy(true));
            assertEquals(422, r.statusCode(), r.body());
            String findings = V1Body.envelope(r.body()).get("error").get("details").get("findings").toString();
            assertTrue(findings.contains("steps[0].dedup"), findings);
            assertTrue(findings.contains("\"code\":\"ERR_DEDUP_WINDOW_UNARMABLE\""), findings);
        }
    }

    @Test
    @DisplayName("an INACTIVE draft saves, warning when it will refuse")
    void inactiveDraftWarnsButSaves(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", windowedDedupNoOrderBy(false));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean(), "an inactive draft is a legitimate WIP");
            String findings = out.get("findings").toString();
            assertTrue(findings.contains("\"code\":\"WARN_DEDUP_WINDOW_UNARMABLE\""), findings);
            assertTrue(findings.contains("only once it is activated"), findings);
        }
    }

    @Test
    @DisplayName("/validate reports the same refusal without needing a write root, malformed scope included")
    void validateReportsTheRefusalAndMalformedScope(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = post(c.port, "/validate", windowedDedupNoOrderBy(true));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertFalse(out.get("clean").asBoolean(), out.toString());
            assertTrue(out.get("findings").toString().contains("ERR_DEDUP_WINDOW_UNARMABLE"),
                    out.get("findings").toString());

            // A malformed scope spelling is a finding too, not a save that fails later at run.
            String malformed = windowedDedupNoOrderBy(true).replace("window(P4D)", "window(4 days)");
            HttpResponse<String> m = post(c.port, "/validate", malformed);
            assertEquals(200, m.statusCode(), m.body());
            assertTrue(V1Body.of(m.body()).get("findings").toString().contains("ISO-8601"),
                    m.body());
        }
    }

    @Test
    @DisplayName("a well-formed windowed dedup passes clean — the gate refuses shapes, not the feature")
    void wellFormedWindowedDedupIsClean(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            String armable = windowedDedupNoOrderBy(true)
                    .replace("\"scope\":\"window(P4D)\"", "\"order_by\":\"event_ts DESC\",\"scope\":\"window(P4D)\"");
            HttpResponse<String> r = post(c.port, "/validate", armable);
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(V1Body.of(r.body()).get("findings").toString().contains("DEDUP_WINDOW"),
                    r.body());
        }
    }

    @Test
    @DisplayName("/config/patch re-checks the MERGED config")
    void patchIsGatedOnTheMergedConfig(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> seeded = post(c.port, "/config/write", windowedDedupNoOrderBy(false));
            assertEquals(200, seeded.statusCode(), seeded.body());

            HttpResponse<String> r = post(c.port, "/config/patch",
                    "{\"type\":\"pipeline\",\"name\":\"win_dedup\",\"patch\":{\"active\":true}}");
            assertEquals(422, r.statusCode(), "activating the draft must trip the windowed-dedup gate: " + r.body());
            assertTrue(V1Body.envelope(r.body()).get("error").get("details").get("findings").toString()
                    .contains("ERR_DEDUP_WINDOW_UNARMABLE"), r.body());
        }
    }

    @Test
    @DisplayName("the GRAPH editor's save is gated too — the fourth gate on the path")
    void graphSaveIsGatedToo(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Path schema = dir.resolve("win_schema.toon");
        Files.writeString(schema, "raw:\n  fields[1]{name,selector,type}:\n    MSISDN, \"0\", VARCHAR\n");
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", wr.toString());
        try (Ctx c = open(dir, wr)) {
            String b = dir.toString().replace('\\', '/');
            String graph = """
                {"active":true,
                 "nodes":[{"id":"acq","type":"acquisition","config":{"poll":"%s/in"}},
                          {"id":"p","type":"parser","config":{"schema_file":"%s"}},
                          {"id":"d","type":"transform.dedup","config":{"keys":["MSISDN"],"scope":"window(P4D)"}},
                          {"id":"s","type":"sink.persistent","config":{"database":"%s/db"}}],
                 "edges":[{"from":"acq","rel":"data","to":"p"},{"from":"p","rel":"data","to":"d"},
                          {"from":"d","rel":"data","to":"s"}]}"""
                    .formatted(b, schema.toString().replace('\\', '/'), b);
            HttpResponse<String> r = put(c.port, "/pipelines/win_graph/graph", graph);
            assertEquals(422, r.statusCode(), r.body());
            JsonNode out = V1Body.envelope(r.body()).get("error").get("details");
            assertFalse(out.get("written").asBoolean(), r.body());
            assertTrue(out.get("findings").toString().contains("ERR_DEDUP_WINDOW_UNARMABLE"),
                    out.get("findings").toString());
            assertFalse(Files.exists(wr.resolve("win_graph_pipeline.toon")),
                    "an unarmable active graph must not reach disk");
        } finally {
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
            System.clearProperty("assist.write.root");
        }
    }
}

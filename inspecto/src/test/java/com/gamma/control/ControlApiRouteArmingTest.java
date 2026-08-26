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
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The route: arming pre-check at SAVE time, over real HTTP.
 *
 * <p>Before this, the six arming rules fired only at {@code PipelineConfig.prepare()} — i.e. after
 * {@code /config/write} had already returned {@code written:true}. The operator learned their branch
 * tree was unarmable at the next run, long after they had moved on. These tests pin the two halves
 * that matter: an ACTIVE unarmable route is refused at the save (422, nothing written), and an
 * INACTIVE one is a warning that does not block a legitimate work in progress.
 */
class ControlApiRouteArmingTest {

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

    /**
     * A pipeline whose route names a database no sink declares — its rows would land nowhere — and
     * which has no {@code default:}, so a row matching no branch would be silently dropped.
     */
    private static String unarmableRoute(boolean active) {
        return """
                {"type":"pipeline","config":{
                   "name":"regional_cdr",
                   "active":%s,
                   "dirs":{"poll":"in","database":"out"},
                   "processing":{"threads":1,"schema_file":"cdr.toon"},
                   "sinks":[{"database":"emea_db"}],
                   "route":{"mode":"case","branches":[{"key":"apac","database":"apac_db"}]}}}"""
                .formatted(active);
    }

    @Test
    @DisplayName("an ACTIVE unarmable route is refused at the save — 422, nothing written")
    void activeUnarmableRouteIsRefusedAtSave(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", unarmableRoute(true));
            assertEquals(422, r.statusCode(), r.body());
            // v1 errors carry the rejected write's payload under error.details.
            JsonNode out = V1Body.envelope(r.body()).get("error").get("details");
            assertFalse(out.get("written").asBoolean(), "an unarmable active route must not be written");

            String findings = out.get("findings").toString();
            // BOTH problems are named, not just the first — prepare() would have thrown on one.
            assertTrue(findings.contains("matches no sinks[] destination"), findings);
            assertTrue(findings.contains("needs default:"), findings);
            assertTrue(findings.contains("ERROR"), findings);
        }
    }

    @Test
    @DisplayName("an INACTIVE unarmable route saves, with a warning that says WHEN it will refuse")
    void inactiveUnarmableRouteWarnsButSaves(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", unarmableRoute(false));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean(), "an inactive draft is a legitimate WIP");

            String findings = out.get("findings").toString();
            assertTrue(findings.contains("WARNING"), findings);
            assertFalse(findings.contains("\"severity\":\"ERROR\""), findings);
            // The warning has to say when it bites, or it reads as a problem with the draft as saved.
            assertTrue(findings.contains("only once it is activated"), findings);
        }
    }

    @Test
    @DisplayName("/validate reports the same refusals without needing a write root")
    void validateReportsArmingRefusals(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = post(c.port, "/validate", unarmableRoute(true));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertFalse(out.get("clean").asBoolean(), out.toString());
            assertTrue(out.get("findings").toString().contains("matches no sinks[] destination"),
                    out.get("findings").toString());
        }
    }

    @Test
    @DisplayName("a well-formed route is clean — the gate refuses shapes, not the feature")
    void wellFormedRouteSavesClean(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String armable = """
                {"type":"pipeline","config":{
                   "name":"regional_cdr_ok",
                   "active":true,
                   "dirs":{"poll":"in","database":"out"},
                   "processing":{"threads":1,"schema_file":"cdr.toon"},
                   "sinks":[{"database":"emea_db"},{"database":"apac_db"}],
                   "route":{"mode":"case","default":"apac","branches":[
                       {"key":"emea","database":"emea_db"},
                       {"key":"apac","database":"apac_db"}]}}}""";
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", armable);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean(), r.body());
            assertFalse(out.get("findings").toString().contains("route:"), out.get("findings").toString());
        }
    }
}

package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The arming pre-checks on the GRAPH editor's save route, over real HTTP.
 *
 * <p>{@code /config/write}, {@code /config/patch} and {@code /validate} have run
 * {@code armedWithoutSchemaFindings} + {@code routeArmingFindings} + {@code stepDisableFindings}
 * since 2026-08-26 ({@link ControlApiRouteArmingTest}) — but {@code PUT /pipelines/{name}/graph}
 * did not, so the editor's own Save answered {@code 200 written:true} for a config that then failed
 * to arm at the next {@code ConfigRegistry.rebuild}: one WARN log, the pipeline silently skipped
 * every cycle. These tests pin the same severity split on the graph route: ACTIVE unarmable ⇒ 422
 * and nothing written; an INACTIVE draft saves with a warning that says when it will refuse.
 */
class ControlApiGraphSaveArmingTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper M = new ObjectMapper();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots)
            implements AutoCloseable {
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
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> put(int port, String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                        .method("PUT", BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    /**
     * The graph the editor actually produces mid-authoring: {@code addRouteBranch} writes the branch
     * entry as {@code {key}} and wires its sink; the predicate is typed afterwards — so a branch with
     * no {@code where:} is the normal intermediate state, not a typo. The {@code emea} branch is
     * complete; {@code apac} lacks its predicate.
     */
    private static String blankPredicateGraph(boolean active, Path dir, Path schema) {
        String b = dir.toString().replace('\\', '/');
        return """
            {"active":%s,
             "nodes":[{"id":"acq","type":"acquisition","config":{"poll":"%s/in"}},
                      {"id":"p","type":"parser","config":{"schema_file":"%s"}},
                      {"id":"rt","type":"transform.route","config":{"mode":"case","default":"apac",
                          "branches":[{"key":"emea","where":"ID LIKE 'E%%'"},{"key":"apac"}]}},
                      {"id":"s1","type":"sink.persistent","config":{"database":"%s/db_emea"}},
                      {"id":"s2","type":"sink.persistent","config":{"database":"%s/db_apac"}}],
             "edges":[{"from":"acq","rel":"data","to":"p"},{"from":"p","rel":"data","to":"rt"},
                      {"from":"rt","rel":"route:emea","to":"s1"},{"from":"rt","rel":"route:apac","to":"s2"}]}"""
                .formatted(active, b, schema.toString().replace('\\', '/'), b, b);
    }

    private static Path miniSchema(Path dir) throws Exception {
        Path schema = dir.resolve("arming_schema.toon");
        Files.writeString(schema, "raw:\n  fields[1]{name,selector,type}:\n    ID, \"0\", VARCHAR\n");
        return schema;
    }

    @Test
    @DisplayName("an ACTIVE route branch with no where: is refused at the GRAPH save — 422, nothing written")
    void activeBlankPredicateBranchIsRefusedAtGraphSave(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            HttpResponse<String> r = put(c.port, "/pipelines/arming_a/graph",
                    blankPredicateGraph(true, dir, miniSchema(dir)));
            assertEquals(422, r.statusCode(), r.body());
            JsonNode out = V1Body.envelope(r.body()).get("error").get("details");
            assertFalse(out.get("written").asBoolean(), r.body());
            String findings = out.get("findings").toString();
            assertTrue(findings.contains("branch 'apac' has no where:"), findings);
            assertFalse(findings.contains("branch 'emea' has no where:"), findings);
            // R1: the graph route's findings carry the same stable code + split-out guidance.
            assertTrue(findings.contains("\"code\":\"ERR_ROUTE_UNARMABLE\""), findings);
            assertTrue(findings.contains("\"guidance\":"), findings);
            assertFalse(Files.exists(wr.resolve("arming_a_pipeline.toon")),
                    "an unarmable active graph must not reach disk");
        }
    }

    @Test
    @DisplayName("an INACTIVE draft with the same branch saves, warning when it will refuse")
    void inactiveBlankPredicateWarnsButSaves(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            HttpResponse<String> r = put(c.port, "/pipelines/arming_b/graph",
                    blankPredicateGraph(false, dir, miniSchema(dir)));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean(), r.body());
            String findings = out.get("findings").toString();
            assertTrue(findings.contains("WARNING"), findings);
            assertFalse(findings.contains("\"severity\":\"ERROR\""), findings);
            assertTrue(findings.contains("only once it is activated"), findings);
            assertTrue(findings.contains("\"code\":\"WARN_ROUTE_UNARMABLE\""), findings);
            assertTrue(Files.exists(wr.resolve("arming_b_pipeline.toon")), "a WIP draft must save");
        }
    }

    @Test
    @DisplayName("an ACTIVE graph with a disabled Step in an unparkable position is refused at the GRAPH save")
    void activeDisabledStepIsRefusedAtGraphSave(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        String b = dir.toString().replace('\\', '/');
        // a disabled PARSER on a linear graph — nothing downstream can park it.
        // (`enabled` rides INSIDE config — PipelineNode.enabled() reads config.enabled; a top-level
        // node key would be silently dropped by the codec.)
        String disabledParser = """
            {"active":true,
             "nodes":[{"id":"acq","type":"acquisition","config":{"poll":"%s/in"}},
                      {"id":"p","type":"parser","config":{"schema_file":"%s","enabled":false}},
                      {"id":"out","type":"sink.persistent","config":{"database":"%s/db"}}],
             "edges":[{"from":"acq","rel":"data","to":"p"},{"from":"p","rel":"data","to":"out"}]}"""
                .formatted(b, miniSchema(dir).toString().replace('\\', '/'), b);
        try (Ctx c = open(dir, wr)) {
            HttpResponse<String> r = put(c.port, "/pipelines/arming_c/graph", disabledParser);
            assertEquals(422, r.statusCode(), r.body());
            JsonNode out = V1Body.envelope(r.body()).get("error").get("details");
            String findings = out.get("findings").toString();
            assertTrue(findings.contains("park"), findings);
            assertTrue(findings.contains("ERROR"), findings);
            assertTrue(findings.contains("\"code\":\"ERR_STEP_DISABLE_UNPARKABLE\""), findings);
            assertTrue(findings.contains("\"guidance\":"), findings);
            assertFalse(Files.exists(wr.resolve("arming_c_pipeline.toon")), r.body());
        }
    }
}

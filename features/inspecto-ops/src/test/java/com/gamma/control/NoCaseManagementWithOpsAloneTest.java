package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import com.gamma.workflow.ObjectType;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-P7: a Professional install WITHOUT inspecto-case-management - this module's own test class path has
 * {@code inspecto-ops} and not the add-on - over real HTTP. Incidents keep working and a Case object is still an
 * ordinary, inert row the generic handlers list and move through its lifecycle, while every Case-management route
 * answers 503 naming the missing module and {@code /bootstrap} reports {@code features.cases == false} next to
 * {@code features.ops == true}.
 *
 * <p>The 503 surface is read from the manifest (the host's known-modules), not repeated here, so the test cannot drift
 * from the module it stands in for.
 */
class NoCaseManagementWithOpsAloneTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "{}" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void everyCaseManagementRouteAnswers503NamingTheModule(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String[] r : AbsentModuleRoutes.surface("case-management")) {
                HttpResponse<String> res = send(c.port, r[0], r[1].replace("([^/]+)", "probe"), "{}");
                assertEquals(503, res.statusCode(), r[0] + " " + r[1] + " -> " + res.body());
                assertTrue(V1Body.of(res.body()).get("error").get("message").asText().contains("inspecto-case-management"),
                        "the refusal names the module that would fix it: " + res.body());
            }
        }
    }

    @Test
    void bootstrapReportsOpsPresentAndCasesAbsent(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode features = V1Body.of(send(c.port, "GET", "/bootstrap", null).body()).get("features");
            assertTrue(features.get("ops").asBoolean(), features.toString());
            assertTrue(features.has("cases"), "present and false, never merely absent: " + features);
            assertFalse(features.get("cases").asBoolean(), features.toString());
        }
    }

    @Test
    void incidentsAndInertCaseRowsKeepWorkingWithoutTheModule(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            var objects = TestOpsEngine.of(c.svc);
            String incident = objects.open(ObjectType.INCIDENT, "disk full", "d", "HIGH", null, Map.of()).id();
            String kase = objects.open(ObjectType.CASE, "ring", "d", "HIGH", null, Map.of()).id();

            assertEquals(200, send(c.port, "GET", "/objects/" + incident, null).statusCode());
            assertEquals(200, send(c.port, "GET", "/objects/" + kase, null).statusCode(), "a Case row is listed and read generically");
            JsonNode list = V1Body.of(send(c.port, "GET", "/objects?type=CASE", null).body());
            assertTrue(list.findValuesAsText("id").contains(kase), "the Case row lists: " + list);

            // the Case LIFECYCLE is substrate: the generic transition route still moves it
            HttpResponse<String> moved = send(c.port, "POST", "/objects/" + kase + "/transition", "{\"action\":\"investigate\"}");
            assertEquals(200, moved.statusCode(), moved.body());
            assertEquals("INVESTIGATING", V1Body.of(moved.body()).get("status").asText());

            // while the add-on's own operations are the module's alone
            assertEquals(503, send(c.port, "POST", "/objects/" + kase + "/merge", "{\"sources\":[\"x\"]}").statusCode());
            assertEquals(503, send(c.port, "GET", "/cases/rules", null).statusCode());
        }
    }
}

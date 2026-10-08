package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
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
 * MODULE-REORG-P7: a Professional install WITHOUT inspecto-action-requests - this module's own test class path has
 * {@code inspecto-ops} (the Incident / Case providers of the linked-subject seam) and not the add-on - over real
 * HTTP. Every Action Request route answers 503 naming the missing module, {@code /bootstrap} reports
 * {@code features.actionRequests == false} next to {@code features.ops == true}, the {@code invoke-api} consequence
 * is {@code unavailable} naming the module even though an Incident COULD be opened, and the base approver roster
 * still answers.
 */
class NoActionRequestsWithOpsAloneTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "{}" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void everyActionRequestRouteAnswers503NamingTheModule(@TempDir Path dir, @TempDir Path wr) throws Exception {
        try (Ctx c = open(dir, wr)) {
            for (String[] r : AbsentModuleRoutes.surface("action-requests")) {
                HttpResponse<String> res = send(c.port, r[0], r[1].replace("([^/]+)", "probe"), "{}");
                assertEquals(503, res.statusCode(), r[0] + " " + r[1] + " -> " + res.body());
                assertTrue(V1Body.of(res.body()).get("error").get("message").asText().contains("inspecto-action-requests"),
                        "the refusal names the module that would fix it: " + res.body());
            }
        }
    }

    @Test
    void bootstrapReportsOpsPresentAndActionRequestsAbsent(@TempDir Path dir, @TempDir Path wr) throws Exception {
        try (Ctx c = open(dir, wr)) {
            JsonNode features = V1Body.of(send(c.port, "GET", "/bootstrap", null).body()).get("features");
            assertTrue(features.get("ops").asBoolean(), features.toString());
            assertTrue(features.has("actionRequests"), "present and false, never merely absent: " + features);
            assertFalse(features.get("actionRequests").asBoolean(), features.toString());
        }
    }

    @Test
    void invokeApiIsUnavailableNamingTheModuleWhileIncidentsKeepWorking(@TempDir Path dir, @TempDir Path wr) throws Exception {
        try (Ctx c = open(dir, wr)) {
            // probe: ops is installed, so the Incident provider IS available - the refusal below is the missing add-on, not missing objects
            assertTrue(LinkedSubjects.of(c.api, "incident").isPresent());
            ConnectionRegistry.register(new ConnectionProfile("hook", "https", "tickets.test", 443, null, "api", null, null,
                    Map.of(), null, null));
            JsonNode applied;
            try {
                assertEquals(200, send(c.port, "POST", "/decision-rules", "{\"name\":\"leak\",\"consequences\":["
                        + "{\"action\":\"invoke-api\",\"params\":{\"connection\":\"hook\"}}]}").statusCode());
                applied = V1Body.of(send(c.port, "POST", "/decision-rules/leak/apply", "{}").body());
            } finally {
                ConnectionRegistry.clear();
            }
            JsonNode one = applied.at("/executed/0");
            assertEquals("unavailable", one.get("status").asText(), applied.toString());
            assertTrue(one.get("detail").asText().contains("inspecto-action-requests"), one.toString());
        }
    }

    @Test
    void theBaseApproverRosterStillAnswersWithoutTheAddOn(@TempDir Path dir, @TempDir Path wr) throws Exception {
        try (Ctx c = open(dir, wr)) {
            assertEquals(200, send(c.port, "GET", "/settings/approvers", null).statusCode());
        }
    }
}

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-P7 - the assertion that makes the Action Requests gating REAL rather than claimed, on the DEFAULT
 * (Personal) build, over real HTTP. The sibling of {@link NoCaseManagementShipsInThePersonalBuildTest}.
 *
 * <p>Three facts, each of which a plausible-looking regression would break on its own:
 * <ol>
 *   <li>every Action Request path answers <b>503 with the edition message</b> naming the module - not 404 (the stub
 *       lost a path), not 200 (the module is back in Personal, or the routes crept into the core again);</li>
 *   <li>{@code /bootstrap} reports {@code features.actionRequests == false}, present and false;</li>
 *   <li>the {@code invoke-api} Decision Rule consequence is listed and applied as {@code unavailable}, naming the
 *       module through its manifest - never executed, never an unexplained "unknown action".</li>
 * </ol>
 * The BASE halves that the add-on once hid inside - the approver-eligibility check, the approver roster and the
 * Pending Change hold - keep working with it absent: {@code ControlApiPendingChangesTest},
 * {@code ControlApiApproverRosterTest} and {@code ApproverCheckParityTest} run on this same module-less class path.
 */
class NoActionRequestsShipsInThePersonalBuildTest {

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
    void everyActionRequestPathAnswers503NotInstalledOnThePersonalBuild(@TempDir Path dir, @TempDir Path wr) throws Exception {
        try (Ctx c = open(dir, wr)) {
            List<String[]> surface = AbsentModuleRoutes.surface("action-requests");
            assertEquals(7, surface.size(), "the manifest names the seven Action Request routes");
            for (String[] r : surface) {
                HttpResponse<String> res = send(c.port, r[0], r[1].replace("([^/]+)", "probe"), "{}");
                assertEquals(503, res.statusCode(), r[0] + " " + r[1] + " -> " + res.body());
                JsonNode err = V1Body.of(res.body()).get("error");
                assertNotNull(err, r[1] + " must carry the v1 error object: " + res.body());
                assertTrue(err.get("message").asText().contains("inspecto-action-requests"),
                        "the refusal names the module that would fix it: " + err);
            }
        }
    }

    @Test
    void bootstrapReportsActionRequestsAbsent(@TempDir Path dir, @TempDir Path wr) throws Exception {
        try (Ctx c = open(dir, wr)) {
            JsonNode features = V1Body.of(send(c.port, "GET", "/bootstrap", null).body()).get("features");
            assertNotNull(features);
            assertTrue(features.has("actionRequests"), "present and false, never merely absent: " + features);
            assertFalse(features.get("actionRequests").asBoolean(), features.toString());
        }
    }

    @Test
    void invokeApiIsUnavailableNamingTheModuleNotUnknown(@TempDir Path dir, @TempDir Path wr) throws Exception {
        try (Ctx c = open(dir, wr)) {
            JsonNode rows = V1Body.of(send(c.port, "GET", "/decision-rules/consequences", null).body());
            JsonNode row = null;
            for (JsonNode n : rows) if ("invoke-api".equals(n.get("id").asText())) row = n;
            assertNotNull(row, "invoke-api is still LISTED: " + rows);
            assertFalse(row.get("available").asBoolean(), row.toString());
            assertEquals("action-requests", row.get("module").asText(), row.toString());
            assertTrue(row.get("reason").asText().contains("inspecto-action-requests"), row.toString());

            // saving still validates the Connection (the core guard keeps the invoke-api rule check)
            ConnectionRegistry.register(new ConnectionProfile("hook", "https", "tickets.test", 443, null, "api", null, null,
                    java.util.Map.of(), null, null));
            JsonNode applied;
            try {
                HttpResponse<String> saved = send(c.port, "POST", "/decision-rules", "{\"name\":\"leak\",\"consequences\":["
                        + "{\"action\":\"invoke-api\",\"params\":{\"connection\":\"hook\"}}]}");
                assertEquals(200, saved.statusCode(), saved.body());
                applied = V1Body.of(send(c.port, "POST", "/decision-rules/leak/apply", "{}").body());
            } finally {
                ConnectionRegistry.clear();
            }
            JsonNode one = applied.at("/executed/0");
            assertEquals("unavailable", one.get("status").asText(), applied.toString());
            assertTrue(one.get("detail").asText().contains("inspecto-action-requests"), one.toString());
        }
    }
}

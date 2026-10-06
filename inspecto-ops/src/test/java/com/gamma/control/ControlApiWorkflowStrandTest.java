package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.workflow.ObjectType;
import com.gamma.ops.ObjectService;
import com.gamma.ops.OperationalObject;
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
 * ASSURE-WORKFLOW-SLA-1: a Workflow PUT / restore / DELETE that drops a state live objects still occupy is refused
 * 409, naming each such state and its count — those objects would otherwise have no legal move while their SLA
 * keeps breaching. Real HTTP over the ops engine, which reads the authored workflow back (hot reload).
 */
class ControlApiWorkflowStrandTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private static String wf(String extraStates) {
        return "{\"id\":\"incident\",\"objectType\":\"INCIDENT\",\"initial\":\"NEW\",\"terminal\":[\"ARCHIVED\"],"
                + "\"transitions\":[{\"from\":\"NEW\",\"to\":\"RESOLVED\",\"action\":\"resolve\"},"
                + "{\"from\":\"RESOLVED\",\"to\":\"ARCHIVED\",\"action\":\"archive\"},"
                + "{\"from\":\"NEW\",\"to\":\"ARCHIVED\",\"action\":\"archive\"}" + extraStates + "]}";
    }

    private static final String TRIAGE = ",{\"from\":\"NEW\",\"to\":\"TRIAGE\",\"action\":\"triage\"},"
            + "{\"from\":\"TRIAGE\",\"to\":\"ARCHIVED\",\"action\":\"archive\"}";
    private static final String HOLD = ",{\"from\":\"NEW\",\"to\":\"HOLD\",\"action\":\"hold\"},"
            + "{\"from\":\"HOLD\",\"to\":\"ARCHIVED\",\"action\":\"archive\"}";

    @Test
    void droppingAnOccupiedStateIsRefused409OnPutRestoreAndDelete(@TempDir Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        System.setProperty("assist.write.root", dir.resolve("wr").toString());
        ControlApi api;
        try {
            api = new ControlApi(svc, 0);
        } finally {
            System.clearProperty("assist.write.root");
        }
        api.start();
        try {
            int port = api.port();
            ObjectService objects = TestOpsEngine.of(svc);
            assertEquals(200, send(port, "POST", "/components/workflow", wf(TRIAGE)).statusCode());
            OperationalObject a = objects.open(ObjectType.INCIDENT, "a", "d", "HIGH", null, Map.of());
            assertEquals("NEW", a.status(), "the authored workflow is live");
            objects.transition(a.id(), "triage", "alice");

            HttpResponse<String> put = send(port, "PUT", "/components/workflow/incident", wf(""));
            assertEquals(409, put.statusCode(), put.body());
            assertTrue(put.body().contains("TRIAGE (1)"), put.body());
            HttpResponse<String> del = send(port, "DELETE", "/components/workflow/incident", null);
            assertEquals(409, del.statusCode(), del.body());
            assertTrue(del.body().contains("NEW") || del.body().contains("TRIAGE (1)"), del.body());

            objects.transition(a.id(), "archive", "alice");          // nothing in TRIAGE any more
            assertEquals(200, send(port, "PUT", "/components/workflow/incident", wf(HOLD)).statusCode(), "v2: TRIAGE → HOLD");
            OperationalObject b = objects.open(ObjectType.INCIDENT, "b", "d", "HIGH", null, Map.of());
            objects.transition(b.id(), "hold", "alice");
            HttpResponse<String> restore = send(port, "POST", "/components/workflow/incident/versions/1/restore", null);
            assertEquals(409, restore.statusCode(), restore.body());
            assertTrue(restore.body().contains("HOLD (1)"), restore.body());

            objects.transition(b.id(), "archive", "alice");
            assertEquals(200, send(port, "POST", "/components/workflow/incident/versions/1/restore", null).statusCode());
        } finally {
            api.close();
            svc.close();
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }
}

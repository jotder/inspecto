package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.objects.ObjectType;
import com.gamma.ops.OperationalObject;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ROUTE-UNGATED-DEFAULT-1 step 2b (operator decision 2026-09-15): Incident/Case triage is split in two.
 * Changing an object's DISPOSITION — ack / resolve / transition / assign / merge / split / PATCH, and the
 * Case-Rule evaluate that groups Incidents into a Case — is administrative and needs {@code canAdminister};
 * adding to the record — comment / attachment / link / RCA seed — is collaboration and stays open.
 *
 * <p>Real HTTP, an armed {@code Authenticator}: the collaboration half is proven OPEN with a Subject that holds
 * no relevant capability (not merely with no Subject, where nothing is checked), and the disposition half is
 * proven CLOSED to that same Subject and open to one carrying the capability.
 */
class ControlApiTriageGateTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer admin} → canAdminister; {@code Bearer analyst} → an authenticated operator WITHOUT it. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer admin".equals(auth)) return Optional.of(new Subject("root", Set.of("canAdminister")));
        if ("Bearer analyst".equals(auth)) return Optional.of(new Subject("ana", Set.of("canOperateRuns")));
        return Optional.empty();
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void changingTheDispositionNeedsCanAdministerButCollaborationStaysOpen(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject seed = TestOpsEngine.of(c.svc).open(ObjectType.ALERT, "disk full", "msg",
                    "CRITICAL", "pipeA", Map.of("rule", "r1"));
            String id = seed.id();

            // collaboration: OPEN to an authenticated caller with no triage capability at all
            assertEquals(200, send(c.port, "POST", "/objects/" + id + "/comments",
                    "{\"body\":\"looking\",\"author\":\"ana\"}", "analyst").statusCode(),
                    "a comment adds to the record and must stay open");

            // disposition: CLOSED to the same caller — 403, and the capability is named
            HttpResponse<String> denied = send(c.port, "POST", "/objects/" + id + "/ack", null, "analyst");
            assertEquals(403, denied.statusCode(), denied.body());
            assertEquals(403, send(c.port, "PATCH", "/objects/" + id, "{\"priority\":\"LOW\"}", "analyst").statusCode(),
                    "PATCH edits priority/severity/assignee — disposition, so it is gated too");
            assertEquals(403, send(c.port, "POST", "/objects/" + id + "/assign",
                    "{\"assignee\":\"dana\"}", "analyst").statusCode());

            // ...and open to the one who carries it, with the ordinary outcome
            assertEquals(200, send(c.port, "POST", "/objects/" + id + "/ack", null, "admin").statusCode());
            assertEquals(200, send(c.port, "POST", "/objects/" + id + "/resolve", null, "admin").statusCode());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + bearer);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}

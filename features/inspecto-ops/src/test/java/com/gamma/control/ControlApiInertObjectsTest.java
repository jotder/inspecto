package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectStore;
import com.gamma.ops.OperationalObject;
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
 * An object of a type this build does not know (a legacy {@code ALERT} row, or a module that is not installed) is
 * LISTED with a diagnostic and readable by id, and every mutating route on it answers 409 naming the type - the row
 * is never rewritten.
 */
class ControlApiInertObjectsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void anInertObjectIsListedReadableAndEveryMutatingRouteRefusesIt(@TempDir Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
             ControlApi api = new ControlApi(svc, 0)) {
            api.start();
            int port = api.port();
            ObjectStore store = TestOpsEngine.of(svc).substrate().store();
            OperationalObject inert = store.create(OperationalObject.inert("ZZ_UNKNOWN", "ZZ-1", "legacy row", "d",
                    "OPEN", "INFO", null, null, null, "corr", Map.of("k", "v"), 5, 5, 0, 0));
            OperationalObject incident = TestOpsEngine.of(svc).open(ObjectType.INCIDENT, "real", "d", "INFO", "corr", Map.of());

            JsonNode list = json(send(port, "GET", "/objects", null));
            assertEquals(2, list.size(), "the unknown row does not break the list and is not dropped");
            JsonNode row = null;
            for (JsonNode n : list) if ("ZZ-1".equals(n.get("id").asText())) row = n;
            assertNotNull(row);
            assertEquals("ZZ_UNKNOWN", row.get("objectType").asText());
            assertTrue(row.get("inert").asBoolean());
            assertEquals("type ZZ_UNKNOWN is not installed/known: left untouched", row.get("diagnostic").asText());

            assertEquals(200, send(port, "GET", "/objects/ZZ-1", null).statusCode());
            assertTrue(json(send(port, "GET", "/objects/ZZ-1", null)).get("inert").asBoolean());

            for (String[] call : new String[][]{
                    {"POST", "/objects/ZZ-1/ack", "{}"},
                    {"POST", "/objects/ZZ-1/resolve", "{}"},
                    {"POST", "/objects/ZZ-1/transition", "{\"status\":\"CLOSED\"}"},
                    {"POST", "/objects/ZZ-1/assign", "{\"assignee\":\"bob\"}"},
                    {"POST", "/objects/ZZ-1/comments", "{\"body\":\"hi\"}"},
                    {"POST", "/objects/ZZ-1/links", "{\"to\":\"" + incident.id() + "\"}"},
                    {"POST", "/objects/" + incident.id() + "/links", "{\"to\":\"ZZ-1\"}"},
                    {"PATCH", "/objects/ZZ-1", "{\"priority\":\"P1\"}"},
                    {"PUT", "/objects/ZZ-1/findings", "{}"}}) {
                HttpResponse<String> r = send(port, call[0], call[1], call[2]);
                assertEquals(409, r.statusCode(), call[0] + " " + call[1] + " -> " + r.body());
                assertTrue(r.body().contains("ZZ_UNKNOWN"), "the refusal names the unknown type: " + r.body());
            }
            assertEquals(inert, store.get("ZZ-1").orElseThrow(), "the row was never rewritten");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }
}

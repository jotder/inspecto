package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.alert.AlertRule;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.audit.AuditAttrs;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
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
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-P7-INCIDENTS slice 3 — {@code POST /alerts/{id}/ack|resolve} over real HTTP. Rule-fired Alerts live in
 * the Alert-owned store (not the object substrate), so these two routes are how an operator works one on EVERY
 * edition. Every gate: 401 no credential, 403 a Subject without {@code canWorkIncidents}, 200 with it, 404 unknown id,
 * 422 a move illegal from the Alert's state, the state visible on {@code GET /alerts}, an AUDIT row. This module's
 * test classpath carries no operational-object module, so every test here is the Personal shape.
 */
class ControlApiAlertAckTest {

    private static final AlertRule RULE = new AlertRule("low-revenue", null, "lt", 1000, null, "WARNING", null,
            "sales_ds", "sum(amount)");

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private static void armSubjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer worker" -> Optional.of(new Subject("worker-1", Set.of("canWorkIncidents")));
            case "Bearer author" -> Optional.of(new Subject("author-1", Set.of("canAuthorAlertRules")));
            default -> Optional.empty();
        });
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        svc.alertService().orElseThrow().measureProbe((d, m) -> OptionalDouble.of(750));
        svc.alertService().orElseThrow().upsert(RULE);
        assertEquals(1, svc.alertService().orElseThrow().evaluateAll().size(), "the breach fires one Alert");
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        if (body != null) b.header("Content-Type", "application/json");
        return client.send(b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private JsonNode firstAlert(Ctx c, String auth) throws Exception {
        HttpResponse<String> res = send(c, "GET", "/alerts", null, auth);
        assertEquals(200, res.statusCode(), res.body());
        JsonNode list = V1Body.of(res.body());
        assertEquals(1, list.size(), list.toString());
        return list.get(0);
    }

    @Test
    void theFiredAlertCarriesItsIdAndOpenState(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode a = firstAlert(c, null);
            assertTrue(a.get("id").asText().startsWith("ALERT-"), a.toString());
            assertEquals("OPEN", a.get("state").asText());
            assertEquals("low-revenue", a.get("rule").asText(), "the documented members are unchanged");
        }
    }

    @Test
    void noCredentialIs401AndASubjectWithoutTheCapabilityIs403(@TempDir Path dir) throws Exception {
        armSubjects();
        try (Ctx c = open(dir)) {
            String id = firstAlert(c, "Bearer worker").get("id").asText();
            for (String verb : List.of("ack", "resolve")) {
                assertEquals(401, send(c, "POST", "/alerts/" + id + "/" + verb, "{}", null).statusCode(), verb);
                HttpResponse<String> denied = send(c, "POST", "/alerts/" + id + "/" + verb, "{}", "Bearer author");
                assertEquals(403, denied.statusCode(), denied.body());
                assertTrue(denied.body().contains("canWorkIncidents"), denied.body());
            }
            assertEquals("OPEN", firstAlert(c, "Bearer worker").get("state").asText(), "a refused call moved nothing");
        }
    }

    @Test
    void anOperatorAcksThenResolvesAndGetAlertsShowsEachState(@TempDir Path dir) throws Exception {
        armSubjects();
        try (Ctx c = open(dir)) {
            String id = firstAlert(c, "Bearer worker").get("id").asText();

            HttpResponse<String> ack = send(c, "POST", "/alerts/" + id + "/ack", "{}", "Bearer worker");
            assertEquals(200, ack.statusCode(), ack.body());
            assertEquals("ACKNOWLEDGED", V1Body.of(ack.body()).get("state").asText());
            assertEquals("ACKNOWLEDGED", firstAlert(c, "Bearer worker").get("state").asText());

            HttpResponse<String> again = send(c, "POST", "/alerts/" + id + "/ack", "{}", "Bearer worker");
            assertEquals(422, again.statusCode(), "ack is not legal from ACKNOWLEDGED: " + again.body());

            HttpResponse<String> resolve = send(c, "POST", "/alerts/" + id + "/resolve", "{}", "Bearer worker");
            assertEquals(200, resolve.statusCode(), resolve.body());
            JsonNode resolved = V1Body.of(resolve.body());
            assertEquals("RESOLVED", resolved.get("state").asText());
            assertEquals("worker-1", resolved.get("closedBy").asText(), "the actor is the Subject, not a body field");
            assertEquals("RESOLVED", firstAlert(c, "Bearer worker").get("state").asText());

            // RESOLVED is terminal: a second resolve (and an ack) is refused, nothing changes.
            assertEquals(422, send(c, "POST", "/alerts/" + id + "/resolve", "{}", "Bearer worker").statusCode());
            assertEquals(422, send(c, "POST", "/alerts/" + id + "/ack", "{}", "Bearer worker").statusCode());
        }
    }

    @Test
    void aSubjectCannotBeReattributedByTheBody(@TempDir Path dir) throws Exception {
        armSubjects();
        try (Ctx c = open(dir)) {
            String id = firstAlert(c, "Bearer worker").get("id").asText();
            HttpResponse<String> res = send(c, "POST", "/alerts/" + id + "/resolve", "{\"actor\":\"mallory\"}", "Bearer worker");
            assertEquals(200, res.statusCode(), res.body());
            assertEquals("worker-1", V1Body.of(res.body()).get("closedBy").asText());
        }
    }

    @Test
    void anUnknownIdIs404ForBothVerbs(@TempDir Path dir) throws Exception {
        armSubjects();
        try (Ctx c = open(dir)) {
            // whole path literals on purpose: tools/check-authgate-coverage.mjs matches literals, not concatenations
            for (String path : List.of("/alerts/ALERT-NOPE/ack", "/alerts/ALERT-NOPE/resolve")) {
                HttpResponse<String> res = send(c, "POST", path, "{}", "Bearer worker");
                assertEquals(404, res.statusCode(), res.body());
                assertTrue(res.body().contains("NOT_FOUND"), res.body());
            }
        }
    }

    @Test
    void resolveWithoutAckingFirstIsLegal(@TempDir Path dir) throws Exception {
        armSubjects();
        try (Ctx c = open(dir)) {
            String id = firstAlert(c, "Bearer worker").get("id").asText();
            assertEquals(200, send(c, "POST", "/alerts/" + id + "/resolve", "{}", "Bearer worker").statusCode());
            assertEquals("RESOLVED", firstAlert(c, "Bearer worker").get("state").asText());
        }
    }

    @Test
    void theMoveIsAuditedWithItsCapabilityAndTarget(@TempDir Path dir) throws Exception {
        armSubjects();
        try (Ctx c = open(dir)) {
            String id = firstAlert(c, "Bearer worker").get("id").asText();
            assertEquals(200, send(c, "POST", "/alerts/" + id + "/ack", "{}", "Bearer worker").statusCode());
            List<Event> rows = c.svc.events().page(500, null, null).stream()
                    .filter(e -> EventType.AUDIT.equals(e.type()) && String.valueOf(e.message()).contains(id))
                    .toList();
            assertEquals(1, rows.size(), "one AUDIT row per move: " + rows);
            Event row = rows.get(0);
            assertEquals("acknowledged", String.valueOf(row.attributes().get(AuditAttrs.ACTION)).replaceAll(".*\\.", ""));
            assertEquals("canWorkIncidents", String.valueOf(row.attributes().get(AuditAttrs.CAPABILITY)));
            assertTrue(row.message().contains("worker-1"), row.message());
        }
    }

    @Test
    void withNoSecurityModuleThePersonalOperatorWorksAnAlertWithAnHonourSystemActor(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {   // no Authenticator: Personal
            assertTrue(c.svc.objects().isEmpty(), "the Personal shape: no operational objects");
            String id = firstAlert(c, null).get("id").asText();
            HttpResponse<String> ack = send(c, "POST", "/alerts/" + id + "/ack", null, null);
            assertEquals(200, ack.statusCode(), ack.body());
            HttpResponse<String> resolve = send(c, "POST", "/alerts/" + id + "/resolve", "{\"actor\":\"pat\"}", null);
            assertEquals(200, resolve.statusCode(), resolve.body());
            assertEquals("pat", V1Body.of(resolve.body()).get("closedBy").asText());
            assertEquals("RESOLVED", firstAlert(c, null).get("state").asText());
        }
    }
}

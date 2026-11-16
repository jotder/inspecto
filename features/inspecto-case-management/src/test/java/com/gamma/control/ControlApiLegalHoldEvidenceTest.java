package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.audit.AuditAttrs;
import com.gamma.audit.Event;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.EventLog;
import com.gamma.objects.LegalHoldException;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.LinkRelationship;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LEGAL-HOLD-SCOPE-1 over real HTTP: while an Incident is under legal hold, no route may remove its evidence links —
 * {@code DELETE /objects/{id}/links}, {@code POST /objects/{id}/merge} (the absorbed Case loses its members) and
 * {@code POST /objects/{id}/split} (the held Case loses members) each answer 409 naming the hold and leave an AUDIT
 * record; the hold reaches the Case the Incident is in (a Case shared with an unheld Incident stays held — the safe
 * default). Releasing the hold ({@code legalHold: false}) lets every one of them through.
 */
class ControlApiLegalHoldEvidenceTest {

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

    @Test
    void aHeldIncidentFreezesItsCaseMembershipUntilTheHoldIsReleased(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            var ops = TestOpsEngine.of(c.svc);
            OperationalObject held = ops.open(ObjectType.CASE, "ring A", "d", "HIGH", null, null, null, "corr", Map.of());
            OperationalObject survivor = ops.open(ObjectType.CASE, "ring B", "d", "HIGH", null, null, null, "corr", Map.of());
            OperationalObject i1 = ops.open(ObjectType.INCIDENT, "held one", "d", "HIGH", null, null, null, "corr", Map.of());
            OperationalObject i2 = ops.open(ObjectType.INCIDENT, "unheld one", "d", "HIGH", null, null, null, "corr", Map.of());
            ops.link(held.id(), i1.id(), LinkRelationship.CONTAINS, null);
            ops.link(held.id(), i2.id(), LinkRelationship.CONTAINS, null);

            assertEquals(200, send(c.port, "PATCH", "/objects/" + i1.id(), "{\"attributes\":{\"legalHold\":\"LIT-2026-07\"}}").statusCode());

            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                HttpResponse<String> r = send(c.port, "DELETE", "/objects/" + held.id() + "/links?to=" + i1.id()
                        + "&relationship=CONTAINS&actor=mallory", null);
                assertEquals(409, r.statusCode(), r.body());
                assertTrue(r.body().contains("Incident " + i1.id() + " is under legal hold"), r.body());

                r = send(c.port, "DELETE", "/objects/" + held.id() + "/links?to=" + i2.id() + "&relationship=CONTAINS", null);
                assertEquals(409, r.statusCode(), "the Case a held Incident is in is held, even for its unheld member: " + r.body());
                assertTrue(r.body().contains("Case " + held.id() + " contains Incident " + i1.id() + ", which is under legal hold"), r.body());

                r = send(c.port, "POST", "/objects/" + survivor.id() + "/merge", "{\"sources\":[\"" + held.id() + "\"]}");
                assertEquals(409, r.statusCode(), "absorbing the held Case would empty it: " + r.body());
                r = send(c.port, "POST", "/objects/" + held.id() + "/split",
                        "{\"title\":\"part\",\"members\":[\"" + i2.id() + "\"]}");
                assertEquals(409, r.statusCode(), "a held Case keeps its members: " + r.body());
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            List<Event> refusals = seen.stream()
                    .filter(e -> LegalHoldException.AUDIT_ACTION.equals(e.attributes().get(AuditAttrs.ACTION))).toList();
            assertEquals(4, refusals.size(), "every refusal is audited: " + refusals);
            assertEquals("mallory", refusals.get(0).attributes().get(AuditAttrs.ACTOR));
            assertTrue(refusals.get(0).attributes().get("hold").contains(i1.id()), refusals.get(0).attributes().toString());

            JsonNode links = json(send(c.port, "GET", "/objects/" + held.id() + "/links", null));
            assertTrue(hasEdge(links, held.id(), i1.id()) && hasEdge(links, held.id(), i2.id()), "nothing moved: " + links);
            assertNotEquals("CLOSED", json(send(c.port, "GET", "/objects/" + held.id(), null)).get("status").asText(),
                    "the refused merge did not close the source");

            // ── release: normal handling is back at once ──
            assertEquals(200, send(c.port, "PATCH", "/objects/" + i1.id(), "{\"attributes\":{\"legalHold\":\"false\"}}").statusCode());
            assertEquals(200, send(c.port, "POST", "/objects/" + held.id() + "/split",
                    "{\"title\":\"part\",\"members\":[\"" + i2.id() + "\"]}").statusCode());
            assertEquals(200, send(c.port, "DELETE", "/objects/" + held.id() + "/links?to=" + i1.id()
                    + "&relationship=CONTAINS", null).statusCode());
            assertEquals(200, send(c.port, "POST", "/objects/" + survivor.id() + "/merge",
                    "{\"sources\":[\"" + held.id() + "\"]}").statusCode());
        }
    }

    @Test
    void aHoldSetOnTheCaseItselfFreezesItsMembersLinks(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            var ops = TestOpsEngine.of(c.svc);
            OperationalObject kase = ops.open(ObjectType.CASE, "ring", "d", "HIGH", null, null, null, "corr", Map.of());
            OperationalObject i = ops.open(ObjectType.INCIDENT, "i", "d", "HIGH", null, null, null, "corr", Map.of());
            ops.link(kase.id(), i.id(), LinkRelationship.CONTAINS, null);
            assertEquals(200, send(c.port, "PATCH", "/objects/" + kase.id(), "{\"attributes\":{\"legalHold\":\"yes\"}}").statusCode());

            HttpResponse<String> r = send(c.port, "DELETE", "/objects/" + kase.id() + "/links?to=" + i.id() + "&relationship=CONTAINS", null);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("Incident " + i.id() + " is in Case " + kase.id() + ", which is under legal hold"),
                    "a Case's hold reaches the Incidents it contains: " + r.body());

            assertEquals(200, send(c.port, "PATCH", "/objects/" + kase.id(), "{\"attributes\":{\"legalHold\":\"off\"}}").statusCode());
            assertEquals(200, send(c.port, "DELETE", "/objects/" + kase.id() + "/links?to=" + i.id() + "&relationship=CONTAINS", null).statusCode());
        }
    }

    private static boolean hasEdge(JsonNode links, String from, String to) {
        for (JsonNode l : links)
            if (from.equals(l.get("from").asText()) && to.equals(l.get("to").asText())
                    && "CONTAINS".equals(l.get("relationship").asText())) return true;
        return false;
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}

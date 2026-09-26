package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.Event;
import com.gamma.event.EventType;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Incident/Case triage gates over real HTTP, with an armed {@code Authenticator} whose Subjects carry the
 * SEEDED grants ({@link Roles#SEED}) — so this pins the role table and the route gates together.
 *
 * <p>ROUTE-UNGATED-DEFAULT-1 step 2b (operator decision 2026-09-15) split triage in two: adding to the record —
 * comment / attachment / link / RCA seed — is collaboration and stays open. <b>(operator, 2026-09-26)</b>
 * working an object through its lifecycle — ack / resolve / transition / assign — takes the narrower
 * {@code canWorkIncidents}, which {@code operations}, {@code support}, {@code power} and {@code admin} hold,
 * while merge / split / the PATCH / the Case-Rule evaluate stay {@code canAdminister}.
 *
 * <p>Every refusal is proven against an AUTHENTICATED Subject lacking the capability ({@code business}), never
 * merely against no Subject, where {@code withCapability} checks nothing.
 */
class ControlApiTriageGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer <seeded role>} → Subject {@code u-<role>} carrying exactly that role's seeded grants. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        String role = auth.substring("Bearer ".length());
        Roles.Def def = Roles.SEED.get(role);
        return def == null ? Optional.empty() : Optional.of(new Subject("u-" + role, def.capabilities()));
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
    void theSeedGrantsCanWorkIncidentsToTheTriageRolesAndNotToBusiness() {
        for (String role : List.of("operations", "support", "power", "admin", "super"))
            assertTrue(Roles.SEED.get(role).capabilities().contains(Roles.CAN_WORK_INCIDENTS), role);
        assertFalse(Roles.SEED.get("business").capabilities().contains(Roles.CAN_WORK_INCIDENTS));
        assertFalse(Roles.SEED.get("operations").capabilities().contains(Roles.CAN_ADMINISTER),
                "working an Incident must not come with installation administration");
    }

    @Test
    void workingAnObjectNeedsCanWorkIncidentsButCollaborationStaysOpen(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = TestOpsEngine.of(c.svc).open(ObjectType.ALERT, "disk full", "msg",
                    "CRITICAL", "pipeA", Map.of("rule", "r1")).id();

            // collaboration: OPEN to an authenticated caller with no triage capability at all
            assertEquals(200, send(c.port, "POST", "/objects/" + id + "/comments",
                    "{\"body\":\"looking\",\"author\":\"b\"}", "business").statusCode(),
                    "a comment adds to the record and must stay open");

            // no credential at all → 401 (the AuthN gate), before any capability is asked
            assertEquals(401, send(c.port, "POST", "/objects/" + id + "/transition",
                    "{\"action\":\"ack\"}", null).statusCode());

            // working it: CLOSED to an authenticated Subject without the capability — 403, capability named
            HttpResponse<String> denied = send(c.port, "POST", "/objects/" + id + "/ack", null, "business");
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains("canWorkIncidents"), denied.body());
            assertEquals(403, send(c.port, "POST", "/objects/" + id + "/resolve", null, "business").statusCode());
            assertEquals(403, send(c.port, "POST", "/objects/" + id + "/transition",
                    "{\"action\":\"ack\"}", "business").statusCode());
            assertEquals(403, send(c.port, "POST", "/objects/" + id + "/assign",
                    "{\"assignee\":\"dana\"}", "business").statusCode());
            assertEquals("OPEN", TestOpsEngine.of(c.svc).get(id).orElseThrow().status(), "nothing moved");
            // The gate wraps the scope guard, so the refusal comes before existence-hiding: an id that does not
            // exist is refused the same way, and the caller learns nothing about which ids do.
            for (String path : List.of("/objects/nope/ack", "/objects/nope/resolve", "/objects/nope/transition",
                    "/objects/nope/assign"))
                assertEquals(403, send(c.port, "POST", path, "{\"action\":\"ack\",\"assignee\":\"dana\"}",
                        "business").statusCode(), path);

            // ...and open to an analyst who holds it, with the ordinary outcome
            assertEquals(200, send(c.port, "POST", "/objects/" + id + "/ack", null, "operations").statusCode());
            assertEquals(200, send(c.port, "POST", "/objects/" + id + "/assign",
                    "{\"assignee\":\"dana\"}", "operations").statusCode());
            assertEquals(200, send(c.port, "POST", "/objects/" + id + "/resolve", null, "operations").statusCode());
            assertEquals("RESOLVED", TestOpsEngine.of(c.svc).get(id).orElseThrow().status());
        }
    }

    @Test
    void everySeededTriageRoleMayAssignOverHttp(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = TestOpsEngine.of(c.svc).open(ObjectType.INCIDENT, "late feed", "d", "MAJOR",
                    null, Map.of()).id();
            for (String role : List.of("operations", "support", "power", "admin", "super")) {
                HttpResponse<String> r = send(c.port, "POST", "/objects/" + id + "/assign",
                        "{\"assignee\":\"" + role + "\"}", role);
                assertEquals(200, r.statusCode(), role + ": " + r.body());
            }
            assertEquals("super", TestOpsEngine.of(c.svc).get(id).orElseThrow().assignee());
        }
    }

    @Test
    void mergeSplitPatchAndCaseRuleEvaluateStayCanAdminister(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String kase = TestOpsEngine.of(c.svc).open(ObjectType.CASE, "ring", "d", "HIGH", null, Map.of()).id();
            HttpResponse<String> patch = send(c.port, "PATCH", "/objects/" + kase, "{\"priority\":\"LOW\"}", "operations");
            assertEquals(403, patch.statusCode(), patch.body());
            assertTrue(patch.body().contains("canAdminister"), patch.body());
            assertEquals(403, send(c.port, "POST", "/objects/" + kase + "/merge",
                    "{\"sources\":[\"x\"]}", "operations").statusCode());
            assertEquals(403, send(c.port, "POST", "/objects/" + kase + "/split",
                    "{\"members\":[\"x\"]}", "operations").statusCode());
            assertEquals(403, send(c.port, "POST", "/cases/rules/any/evaluate", "{}", "operations").statusCode());
            // ...refused before existence-hiding, as above
            assertEquals(403, send(c.port, "PATCH", "/objects/nope", "{\"priority\":\"LOW\"}", "operations").statusCode());
            assertEquals(403, send(c.port, "POST", "/objects/nope/merge", "{\"sources\":[\"x\"]}", "operations").statusCode());
            assertEquals(403, send(c.port, "POST", "/objects/nope/split", "{\"members\":[\"x\"]}", "operations").statusCode());

            assertEquals(200, send(c.port, "PATCH", "/objects/" + kase, "{\"priority\":\"LOW\"}", "admin").statusCode(),
                    "and the administrator still edits the fields");
        }
    }

    /**
     * The operator's goal, end to end: an {@code operations} analyst takes a Case OPEN → investigate → resolve,
     * records Disposition CONFIRMED and a resolution comment, and every lifecycle move is recorded under THAT
     * Subject — a body {@code actor} naming someone else is not believed.
     */
    @Test
    void anOperationsAnalystClosesTheirOwnCaseWithADisposition(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = TestOpsEngine.of(c.svc).open(ObjectType.CASE, "card-testing ring", "d", "HIGH",
                    null, Map.of()).id();
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            c.svc.eventLog().addSubscriber(sub);
            try {
                HttpResponse<String> findings = send(c.port, "PUT", "/objects/" + id + "/findings",
                        "{\"findings\":{\"disposition\":\"CONFIRMED\",\"summary\":\"ring confirmed\"}}", "operations");
                assertEquals(200, findings.statusCode(), findings.body());

                HttpResponse<String> investigate = send(c.port, "POST", "/objects/" + id + "/transition",
                        "{\"action\":\"investigate\",\"actor\":\"someone-else\"}", "operations");
                assertEquals(200, investigate.statusCode(), investigate.body());
                assertEquals("INVESTIGATING", V1Body.of(investigate.body()).get("status").asText());

                assertEquals(200, send(c.port, "POST", "/objects/" + id + "/comments",
                        "{\"body\":\"Confirmed with the issuer.\",\"author\":\"u-operations\"}", "operations").statusCode());

                HttpResponse<String> resolve = send(c.port, "POST", "/objects/" + id + "/transition",
                        "{\"action\":\"resolve\",\"actor\":\"someone-else\"}", "operations");
                assertEquals(200, resolve.statusCode(), resolve.body());
            } finally {
                c.svc.eventLog().removeSubscriber(sub);
            }

            OperationalObject after = TestOpsEngine.of(c.svc).get(id).orElseThrow();
            assertEquals("RESOLVED", after.status());
            assertEquals("CONFIRMED",
                    JSON.readValue(after.attributes().get("findings"), Map.class).get("disposition"));

            List<Event> moves = seen.stream()
                    .filter(e -> EventType.OBJECT_ACTIVITY.equals(e.type()))
                    .filter(e -> List.of("investigate", "resolve").contains(e.attributes().get("action")))
                    .toList();
            assertEquals(2, moves.size(), () -> "one activity event per move: " + seen);
            for (Event e : moves)
                assertEquals("u-operations", e.attributes().get("actor"),
                        "the authenticated Subject, not the body's actor: " + e.attributes());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}

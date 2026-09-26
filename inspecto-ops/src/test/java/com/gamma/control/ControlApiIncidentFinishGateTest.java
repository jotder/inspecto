package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.Event;
import com.gamma.event.EventType;
import com.gamma.objects.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.note.NoteKind;
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
 * INCIDENT-FINISH-GATE-1 (operator, 2026-09-26: "narrow route"): an analyst holding {@code canWorkIncidents} can
 * FINISH an Incident, not only move it. The postmortem ({@code → RESOLVED} needs it) and Accept's category are
 * written through {@code PUT /objects/{id}/postmortem} and {@code PUT /objects/{id}/category}, each taking its
 * one key and refusing any other (422) — so neither is a way round the {@code canAdminister} PATCH.
 *
 * <p>Armed with seeded-role Subjects ({@link Roles#SEED}), as {@code ControlApiTriageGateTest} is: every refusal
 * is proven against an AUTHENTICATED Subject lacking the capability ({@code business}), never merely against no
 * Subject, where {@code withCapability} checks nothing.
 */
class ControlApiIncidentFinishGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String POSTMORTEM = "{\"postmortem\":{\"commander\":\"dana\","
            + "\"timeline\":[{\"time\":\"09:00\",\"text\":\"feed stalled\"}],"
            + "\"causeAnalysis\":[\"upstream SFTP rotated its key\"],"
            + "\"actions\":[{\"done\":false,\"text\":\"pin the host key\",\"owner\":\"ops\"}]}}";
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

    /** An Incident with an SLA (the fourth section the I1 resolution gate asks for), due a day out. */
    private static String incident(Ctx c) {
        return TestOpsEngine.of(c.svc).open(ObjectType.INCIDENT, "late feed", "d", "MAJOR", null,
                Map.of("dueAt", Long.toString(System.currentTimeMillis() + 86_400_000L))).id();
    }

    @Test
    void thePostmortemAndCategoryRoutesNeedCanWorkIncidents(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = incident(c);
            for (String[] r : List.of(new String[]{"/postmortem", POSTMORTEM},
                    new String[]{"/category", "{\"category\":\"Data / Feed / Late\"}"})) {
                assertEquals(401, send(c.port, "PUT", "/objects/" + id + r[0], r[1], null).statusCode(), r[0]);
                HttpResponse<String> denied = send(c.port, "PUT", "/objects/" + id + r[0], r[1], "business");
                assertEquals(403, denied.statusCode(), r[0] + ": " + denied.body());
                assertTrue(denied.body().contains("canWorkIncidents"), denied.body());
            }
            // refused before existence-hiding, like the other triage gates (literal paths: the auth-gate
            // coverage guard reads them)
            assertEquals(403, send(c.port, "PUT", "/objects/nope/postmortem", POSTMORTEM, "business").statusCode());
            assertEquals(403, send(c.port, "PUT", "/objects/nope/category", "{\"category\":\"x\"}", "business").statusCode());
            OperationalObject untouched = TestOpsEngine.of(c.svc).get(id).orElseThrow();
            assertNull(untouched.attributes().get("postmortem"));
            assertNull(untouched.attributes().get("category"));

            assertEquals(200, send(c.port, "PUT", "/objects/" + id + "/postmortem", POSTMORTEM, "operations").statusCode());
            assertEquals(200, send(c.port, "PUT", "/objects/" + id + "/category",
                    "{\"category\":\"Data / Feed / Late\"}", "operations").statusCode());
            OperationalObject after = TestOpsEngine.of(c.svc).get(id).orElseThrow();
            assertEquals("Data / Feed / Late", after.attributes().get("category"));
            assertEquals("dana", JSON.readValue(after.attributes().get("postmortem"), Map.class).get("commander"));
            assertEquals(404, send(c.port, "PUT", "/objects/nope/category",
                    "{\"category\":\"x\"}", "operations").statusCode(), "an unknown id is still a 404");
        }
    }

    @Test
    void eachNarrowRouteRefusesEveryOtherKey(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = incident(c);
            HttpResponse<String> smuggled = send(c.port, "PUT", "/objects/" + id + "/postmortem",
                    POSTMORTEM.substring(0, POSTMORTEM.length() - 1) + ",\"priority\":\"LOW\"}", "operations");
            assertEquals(422, smuggled.statusCode(), smuggled.body());
            assertTrue(smuggled.body().contains("priority"), smuggled.body());
            assertEquals(422, send(c.port, "PUT", "/objects/" + id + "/category",
                    "{\"category\":\"x\",\"attributes\":{\"escalated\":\"true\"}}", "operations").statusCode());
            assertEquals(422, send(c.port, "PUT", "/objects/" + id + "/category",
                    "{\"category\":\"x\",\"postmortem\":{}}", "operations").statusCode(),
                    "not even the sibling narrow key");
            // malformed bodies
            assertEquals(400, send(c.port, "PUT", "/objects/" + id + "/postmortem",
                    "{\"postmortem\":\"a string\"}", "operations").statusCode());
            assertEquals(400, send(c.port, "PUT", "/objects/" + id + "/category",
                    "{\"category\":\"  \"}", "operations").statusCode());

            OperationalObject after = TestOpsEngine.of(c.svc).get(id).orElseThrow();
            assertNotEquals("LOW", after.priority(), "nothing was written");
            assertNull(after.attributes().get("postmortem"));
            assertNull(after.attributes().get("category"));
            assertNull(after.attributes().get("escalated"));
        }
    }

    /**
     * The operator's goal, end to end: an {@code operations} analyst takes an Incident IDENTIFIED → DIAGNOSING →
     * RESOLVED — categorise, self-assign, accept, write the postmortem, resolve — with no {@code canAdminister}
     * anywhere, and each write is audited under THAT Subject.
     */
    @Test
    void anOperationsAnalystFinishesAnIncidentWithAPostmortem(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = incident(c);
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            c.svc.eventLog().addSubscriber(sub);
            try {
                assertEquals(200, send(c.port, "PUT", "/objects/" + id + "/category",
                        "{\"category\":\"Data / Feed / Late\"}", "operations").statusCode());
                assertEquals(200, send(c.port, "POST", "/objects/" + id + "/assign",
                        "{\"assignee\":\"u-operations\"}", "operations").statusCode());
                HttpResponse<String> accept = send(c.port, "POST", "/objects/" + id + "/transition",
                        "{\"action\":\"accept\"}", "operations");
                assertEquals(200, accept.statusCode(), accept.body());
                assertEquals("DIAGNOSING", V1Body.of(accept.body()).get("status").asText());

                // the I1 gate holds: no postmortem yet → the resolve is refused
                assertEquals(422, send(c.port, "POST", "/objects/" + id + "/transition",
                        "{\"action\":\"resolve\"}", "operations").statusCode());

                HttpResponse<String> pm = send(c.port, "PUT", "/objects/" + id + "/postmortem", POSTMORTEM, "operations");
                assertEquals(200, pm.statusCode(), pm.body());
                HttpResponse<String> resolve = send(c.port, "POST", "/objects/" + id + "/transition",
                        "{\"action\":\"resolve\"}", "operations");
                assertEquals(200, resolve.statusCode(), resolve.body());
            } finally {
                c.svc.eventLog().removeSubscriber(sub);
            }
            assertEquals("RESOLVED", TestOpsEngine.of(c.svc).get(id).orElseThrow().status());

            List<Event> writes = seen.stream()
                    .filter(e -> EventType.OBJECT_ACTIVITY.equals(e.type()))
                    .filter(e -> List.of("category", "postmortem").contains(e.attributes().get("action")))
                    .toList();
            assertEquals(2, writes.size(), () -> "one audit event per narrow write: " + seen);
            for (Event e : writes) assertEquals("u-operations", e.attributes().get("actor"), e.attributes().toString());
        }
    }

    /** CASE-UI-GATE-LEFTOVERS-1: a signed-in comment is authored by the Subject; a body {@code author} is not believed. */
    @Test
    void aSignedInCommentIsAuthoredByTheSubjectNotTheBody(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = incident(c);
            assertEquals(200, send(c.port, "POST", "/objects/" + id + "/comments",
                    "{\"body\":\"looking\",\"author\":\"someone-else\"}", "business").statusCode());
            assertEquals(200, send(c.port, "POST", "/notes/object/" + id + "/comments",
                    "{\"body\":\"still looking\",\"author\":\"someone-else\"}", "business").statusCode());
            var comments = TestOpsEngine.of(c.svc).notesOf(id, NoteKind.COMMENT);
            assertEquals(2, comments.size(), comments.toString());
            for (var n : comments) assertEquals("u-business", n.author(), n.toString());
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

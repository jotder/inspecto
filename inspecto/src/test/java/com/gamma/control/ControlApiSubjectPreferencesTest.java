package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.notify.Notification;
import com.gamma.notify.NotificationPreferenceOverrides;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-Subject notification preferences over real HTTP (ses-sns-adapter-design §7, fixes SEC review F2):
 * {@code PUT /notifications/preferences} writes the CALLER's override only and is self-service;
 * {@code PUT /notifications/preferences/default} writes the deployment default and needs
 * {@code canAdminister}. Every gate test attaches a Subject — without one {@code withCapability} is a
 * no-op, and the test would pass against an ungated route.
 */
class ControlApiSubjectPreferencesTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
        NotificationPreferenceOverrides overrides() { return svc.notificationService().preferenceOverrides(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /** Stands in for the security module. {@code viewer} has no verified email claim. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer alice".equals(auth))
            return Optional.of(new Subject("alice", Set.of(), null, Map.of(), "alice@example.com"));
        if ("Bearer bob".equals(auth))
            return Optional.of(new Subject("bob", Set.of(), null, Map.of(), "bob@example.com"));
        if ("Bearer viewer".equals(auth)) return Optional.of(new Subject("viewer", Set.of()));
        if ("Bearer admin".equals(auth))
            return Optional.of(new Subject("admin", Set.of(Roles.CAN_ADMINISTER), null, Map.of(), "admin@example.com"));
        return Optional.empty();
    };

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        System.clearProperty("notify.preferences.file");
    }

    private static String prefs(String category, String channelsJson) {
        return "{\"preferences\":[{\"category\":\"" + category + "\",\"channels\":" + channelsJson + "}]}";
    }

    @Test
    void oneSubjectsPutNeverChangesAnotherSubjectsEffectiveGrid(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            HttpResponse<String> put = send(c.port, "PUT", "/notifications/preferences",
                    prefs("pipeline", "{\"inApp\":false}"), "Bearer alice");
            assertEquals(200, put.statusCode(), "self-service: no capability needed — " + put.body());

            JsonNode alice = row(get(c, "Bearer alice"), "pipeline");
            assertFalse(alice.at("/channels/inApp").asBoolean());
            assertEquals("overridden", alice.at("/source/inApp").asText());
            assertEquals("inherited", alice.at("/source/email").asText());

            JsonNode bob = row(get(c, "Bearer bob"), "pipeline");
            assertTrue(bob.at("/channels/inApp").asBoolean(), "bob's effective grid is untouched");
            assertEquals("inherited", bob.at("/source/inApp").asText());
            assertTrue(c.svc.notificationPreferences().enabled("pipeline", "inApp"),
                    "a personal PUT never writes the deployment default");
        }
    }

    @Test
    void theDefaultNeedsCanAdministerWithASubjectAttached(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            String body = prefs("job", "{\"inApp\":false}");
            for (String who : new String[] {"Bearer viewer", "Bearer alice"}) {
                HttpResponse<String> denied = send(c.port, "PUT", "/notifications/preferences/default", body, who);
                assertEquals(403, denied.statusCode(), who + " -> " + denied.body());
                assertEquals("PERMISSION_DENIED", V1Body.of(denied.body()).at("/error/errorCode").asText());
            }
            assertTrue(c.svc.notificationPreferences().enabled("job", "inApp"), "a refused write changed nothing");

            // alice holds her own override on job before the admin changes the default
            send(c.port, "PUT", "/notifications/preferences", prefs("job", "{\"inApp\":true}"), "Bearer alice");
            assertEquals(200, send(c.port, "PUT", "/notifications/preferences/default", body, "Bearer admin").statusCode());
            assertFalse(c.svc.notificationPreferences().enabled("job", "inApp"));

            JsonNode bob = row(get(c, "Bearer bob"), "job");
            assertFalse(bob.at("/channels/inApp").asBoolean(), "bob inherits the new default");
            assertTrue(row(get(c, "Bearer alice"), "job").at("/channels/inApp").asBoolean(),
                    "alice's override outranks the default");
            JsonNode dflt = row(V1Body.of(send(c.port, "GET", "/notifications/preferences/default", null,
                    "Bearer viewer").body()), "job");
            assertFalse(dflt.at("/channels/inApp").asBoolean(), "the default grid is readable by anyone");
        }
    }

    @Test
    void aCriticalCategoryCannotBeOverriddenOff(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            assertEquals(200, send(c.port, "PUT", "/notifications/preferences",
                    prefs("security", "{\"inApp\":false,\"email\":false}"), "Bearer alice").statusCode());
            JsonNode security = row(get(c, "Bearer alice"), "security");
            assertTrue(security.at("/channels/inApp").asBoolean());
            assertTrue(security.at("/channels/email").asBoolean());
            assertEquals("inherited", security.at("/source/inApp").asText());
            assertFalse(security.at("/editable/inApp").asBoolean());
            assertEquals(200, send(c.port, "PUT", "/notifications/preferences/default",
                    prefs("security", "{\"inApp\":false}"), "Bearer admin").statusCode());
            assertTrue(c.svc.notificationPreferences().enabled("security", "inApp"), "locked at both layers");
        }
    }

    @Test
    void anEmailAddressInTheBodyIsIgnored(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            String hostile = "{\"email\":\"evil@attacker.test\",\"address\":\"evil@attacker.test\",\"preferences\":"
                    + "[{\"category\":\"pipeline\",\"email\":\"evil@attacker.test\",\"target\":\"evil@attacker.test\","
                    + "\"channels\":{\"email\":true}}]}";
            // viewer has no verified email claim — email cannot be turned on at all
            assertEquals(200, send(c.port, "PUT", "/notifications/preferences", hostile, "Bearer viewer").statusCode());
            JsonNode viewer = row(get(c, "Bearer viewer"), "pipeline");
            assertFalse(viewer.at("/channels/email").asBoolean());
            assertFalse(viewer.at("/editable/email").asBoolean());
            assertTrue(viewer.at("/editable/inApp").asBoolean());

            // alice has one — her email is delivered to the CLAIM, never to the body's address
            assertEquals(200, send(c.port, "PUT", "/notifications/preferences", hostile, "Bearer alice").statusCode());
            assertTrue(row(get(c, "Bearer alice"), "pipeline").at("/channels/email").asBoolean());
            assertEquals(List.of(new NotificationPreferenceOverrides.Enrolled("alice", "alice@example.com")),
                    c.overrides().enrolled());
        }
    }

    @Test
    void aNullCellResetsTheCallersOverrideToTheDefault(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            send(c.port, "PUT", "/notifications/preferences", prefs("pipeline", "{\"inApp\":false}"), "Bearer alice");
            HttpResponse<String> reset = send(c.port, "PUT", "/notifications/preferences",
                    prefs("pipeline", "{\"inApp\":null}"), "Bearer alice");
            JsonNode row = row(V1Body.of(reset.body()), "pipeline");
            assertTrue(row.at("/channels/inApp").asBoolean());
            assertEquals("inherited", row.at("/source/inApp").asText());
        }
    }

    @Test
    void theFeedHonoursTheReadersInAppPreference(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            c.svc.notifications().add(Notification.create("pipeline", "BATCH_FAILED", "b1", "Pipeline a failed", "d", "k-a"));
            send(c.port, "PUT", "/notifications/preferences", prefs("pipeline", "{\"inApp\":false}"), "Bearer alice");

            assertEquals(0, V1Body.of(send(c.port, "GET", "/notifications", null, "Bearer alice").body()).size());
            assertEquals(0, V1Body.of(send(c.port, "GET", "/notifications/unread-count", null, "Bearer alice").body())
                    .get("count").asInt());
            assertEquals(1, V1Body.of(send(c.port, "GET", "/notifications", null, "Bearer bob").body()).size(),
                    "bob inherits in-app on, so he still sees it");
        }
    }

    @Test
    void personalEditionStillWritesTheSingleGrid(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {   // no Authenticator ⇒ no Subject
            HttpResponse<String> put = send(c.port, "PUT", "/notifications/preferences",
                    prefs("pipeline", "{\"inApp\":false,\"email\":true}"), null);
            assertEquals(200, put.statusCode());
            assertFalse(c.svc.notificationPreferences().enabled("pipeline", "inApp"), "the one grid changed");
            assertTrue(c.svc.notificationPreferences().enabled("pipeline", "email"));
            assertTrue(c.overrides().enrolled().isEmpty(), "no override layer without a Subject");
            assertFalse(c.overrides().anyEnables("pipeline", "inApp"));
            JsonNode row = row(get(c, null), "pipeline");
            assertFalse(row.at("/channels/inApp").asBoolean());
            assertEquals("inherited", row.at("/source/inApp").asText());
        }
    }

    @Test
    void overridesLiveInOneDurableDeploymentFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deployment").resolve(NotificationPreferenceOverrides.FILE);
        System.setProperty("notify.preferences.file", file.toString());
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            send(c.port, "PUT", "/notifications/preferences", prefs("pipeline", "{\"inApp\":false}"), "Bearer alice");
        }
        assertTrue(Files.exists(file), "written through the atomic config-write path");
        try (Ctx c = open(dir)) {
            assertFalse(row(get(c, "Bearer alice"), "pipeline").at("/channels/inApp").asBoolean(),
                    "the override survives a restart");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode get(Ctx c, String auth) throws Exception {
        HttpResponse<String> r = send(c.port, "GET", "/notifications/preferences", null, auth);
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static JsonNode row(JsonNode grid, String category) {
        for (JsonNode r : grid) if (category.equals(r.get("category").asText())) return r;
        throw new AssertionError("no row " + category + " in " + grid);
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}

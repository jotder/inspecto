package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.notify.Notification;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the in-app notification feed routes ({@code /notifications*}) over real HTTP.
 * The feed is seeded directly through the store (the event→feed engine is covered by
 * {@code NotificationServiceTest}), then driven through the read/badge/read-all/delete routes.
 */
class ControlApiNotificationsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
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

    private static Notification seed(CollectorService svc, String title, String dedupe) {
        return svc.notifications().add(
                Notification.create("pipeline", "BATCH_FAILED", "b1", title, "detail", dedupe));
    }

    /** Stands in for the security module: a no-capability viewer and a {@code canAdminister} admin. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer viewer".equals(auth)) return Optional.of(new Subject("viewer", Set.of()));
        if ("Bearer admin".equals(auth)) return Optional.of(new Subject("admin", Set.of(Roles.CAN_ADMINISTER)));
        return Optional.empty();
    };

    @AfterEach
    void clearAuthenticator() { Authenticators.forTest(null); }

    /**
     * SEC review F2: the feed and the preference grid are ONE shared state per Space, not per caller —
     * {@code NotificationStore} and {@code NotificationPreferences} carry no recipient. So the four writes
     * once exempted as "the caller's own" change every user's view, and must be admin-gated, fail-closed.
     * Reads stay open. (With no Subject attached {@code withCapability} is a no-op, hence the FAKE.)
     */
    @Test
    void globalFeedAndPreferenceWritesNeedCanAdminister(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            Notification a = seed(c.svc, "Pipeline a failed", "k-a");
            Notification b = seed(c.svc, "Pipeline b failed", "k-b");
            String prefs = "{\"preferences\":[{\"category\":\"pipeline\",\"channels\":{\"inApp\":false}}]}";

            for (String[] call : new String[][] {
                    {"PUT", "/notifications/preferences", prefs},
                    {"POST", "/notifications/read-all", null},
                    {"POST", "/notifications/" + a.id() + "/read", null},
                    {"DELETE", "/notifications/" + b.id(), null}}) {
                HttpResponse<String> denied = send(c.port, call[0], call[1], call[2], "Bearer viewer");
                assertEquals(403, denied.statusCode(), call[0] + " " + call[1] + " -> " + denied.body());
                assertEquals("PERMISSION_DENIED", V1Body.of(denied.body()).at("/error/errorCode").asText());
            }
            assertTrue(c.svc.notificationPreferences().enabled("pipeline", "inApp"),
                    "a refused preference write changed nothing");
            assertEquals(2, c.svc.notifications().unreadCount(), "a refused read/delete changed nothing");
            assertEquals(200, send(c.port, "GET", "/notifications", null, "Bearer viewer").statusCode(),
                    "reads stay open to every authenticated caller");

            assertEquals(200, send(c.port, "PUT", "/notifications/preferences", prefs, "Bearer admin").statusCode());
            assertFalse(c.svc.notificationPreferences().enabled("pipeline", "inApp"));
            assertEquals(200, send(c.port, "POST", "/notifications/" + a.id() + "/read", null, "Bearer admin").statusCode());
            assertEquals(200, send(c.port, "POST", "/notifications/read-all", null, "Bearer admin").statusCode());
            assertEquals(200, send(c.port, "DELETE", "/notifications/" + b.id(), null, "Bearer admin").statusCode());
        }
    }

    @Test
    void feedUnreadCountReadAndDelete(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Notification a = seed(c.svc, "Pipeline a failed", "k-a");
            Notification b = seed(c.svc, "Pipeline b failed", "k-b");

            // feed: both present, newest-first
            JsonNode feed = json(send(c.port, "GET", "/notifications", null));
            assertEquals(2, feed.size());

            // unread badge count
            assertEquals(2, json(send(c.port, "GET", "/notifications/unread-count", null)).get("count").asInt());

            // mark one read → count drops, state flips
            JsonNode read = json(send(c.port, "POST", "/notifications/" + a.id() + "/read", null));
            assertEquals("READ", read.get("state").asText());
            assertEquals(1, json(send(c.port, "GET", "/notifications/unread-count", null)).get("count").asInt());

            // mark all read → 0 unread
            assertEquals(1, json(send(c.port, "POST", "/notifications/read-all", null)).get("updated").asInt());
            assertEquals(0, json(send(c.port, "GET", "/notifications/unread-count", null)).get("count").asInt());

            // delete (archive) → removed from active feed
            assertTrue(json(send(c.port, "DELETE", "/notifications/" + b.id(), null)).get("deleted").asBoolean());
            assertEquals(1, json(send(c.port, "GET", "/notifications", null)).size());

            // missing id → 404
            assertEquals(404, send(c.port, "POST", "/notifications/no-such/read", null).statusCode());
            assertEquals(404, send(c.port, "DELETE", "/notifications/no-such", null).statusCode());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return send(port, method, path, body, null);
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String bearer)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (bearer != null) b.header("Authorization", bearer);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }
}

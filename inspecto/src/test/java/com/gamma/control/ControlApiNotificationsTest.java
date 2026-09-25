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
        if ("Bearer alice".equals(auth)) return Optional.of(new Subject("alice", Set.of()));
        if ("Bearer admin".equals(auth)) return Optional.of(new Subject("admin", Set.of(Roles.CAN_ADMINISTER)));
        return Optional.empty();
    };

    @AfterEach
    void clearAuthenticator() { Authenticators.forTest(null); }

    /**
     * SEC review F2: the deployment-default preference grid and the archive ("delete") are ONE shared state,
     * not per caller, so those writes stay admin-gated, fail-closed. Reads stay open. The READ state moved to
     * a per-Subject store (operator, 2026-09-25) — see {@link #readStateIsPerSubject} — and so did the
     * caller's own preferences ({@code PUT /notifications/preferences}, {@code ControlApiSubjectPreferencesTest}).
     * (With no Subject attached {@code withCapability} is a no-op, hence the FAKE.)
     */
    @Test
    void sharedFeedAndPreferenceWritesNeedCanAdminister(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            seed(c.svc, "Pipeline a failed", "k-a");
            Notification b = seed(c.svc, "Pipeline b failed", "k-b");
            String prefs = "{\"preferences\":[{\"category\":\"pipeline\",\"channels\":{\"inApp\":false}}]}";

            for (String[] call : new String[][] {
                    {"PUT", "/notifications/preferences/default", prefs},
                    {"DELETE", "/notifications/" + b.id(), null}}) {
                HttpResponse<String> denied = send(c.port, call[0], call[1], call[2], "Bearer viewer");
                assertEquals(403, denied.statusCode(), call[0] + " " + call[1] + " -> " + denied.body());
                assertEquals("PERMISSION_DENIED", V1Body.of(denied.body()).at("/error/errorCode").asText());
            }
            assertTrue(c.svc.notificationPreferences().enabled("pipeline", "inApp"),
                    "a refused preference write changed nothing");
            assertEquals(2, json(send(c.port, "GET", "/notifications", null, "Bearer viewer")).size(),
                    "a refused delete changed nothing; reads stay open to every authenticated caller");

            assertEquals(200, send(c.port, "PUT", "/notifications/preferences/default", prefs, "Bearer admin").statusCode());
            assertFalse(c.svc.notificationPreferences().enabled("pipeline", "inApp"));
            assertEquals(200, send(c.port, "DELETE", "/notifications/" + b.id(), null, "Bearer admin").statusCode());
        }
    }

    /**
     * Operator decision 2026-09-25: each authenticated Subject marks ITS OWN notifications read / unread and
     * read-all for itself — no capability needed — and the feed and the badge count report {@code read} as the
     * CALLING Subject sees it. One Subject's read never shows up in another Subject's feed.
     */
    @Test
    void readStateIsPerSubject(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            Notification a = seed(c.svc, "Pipeline a failed", "k-a");
            seed(c.svc, "Pipeline b failed", "k-b");

            HttpResponse<String> read = send(c.port, "POST", "/notifications/" + a.id() + "/read", null, "Bearer viewer");
            assertEquals(200, read.statusCode(), read.body());
            assertTrue(json(read).get("read").asBoolean());
            assertEquals("READ", json(read).get("state").asText());

            assertEquals(1, unread(c, "Bearer viewer"));
            assertEquals(2, unread(c, "Bearer alice"), "viewer's read is viewer's only");
            assertEquals(2, unread(c, "Bearer admin"));
            assertTrue(entry(c, "Bearer viewer", a.id()).get("read").asBoolean());
            JsonNode alicesView = entry(c, "Bearer alice", a.id());
            assertFalse(alicesView.get("read").asBoolean(), "the feed reports read per the calling Subject");
            assertEquals("UNREAD", alicesView.get("state").asText());
            assertTrue(alicesView.get("readAt").isNull());

            HttpResponse<String> all = send(c.port, "POST", "/notifications/read-all", null, "Bearer alice");
            assertEquals(200, all.statusCode(), all.body());
            assertEquals(2, json(all).get("updated").asInt());
            assertEquals(0, unread(c, "Bearer alice"));
            assertEquals(1, unread(c, "Bearer viewer"), "alice's read-all is alice's only");
            assertEquals(2, unread(c, "Bearer admin"));

            HttpResponse<String> back = send(c.port, "POST", "/notifications/" + a.id() + "/unread", null, "Bearer alice");
            assertEquals(200, back.statusCode(), back.body());
            assertFalse(json(back).get("read").asBoolean());
            assertEquals(1, unread(c, "Bearer alice"));
            assertTrue(entry(c, "Bearer viewer", a.id()).get("read").asBoolean(), "alice's unread is alice's only");

            assertEquals(404, send(c.port, "POST", "/notifications/no-such/read", null, "Bearer viewer").statusCode());
            assertEquals(404, send(c.port, "POST", "/notifications/no-such/unread", null, "Bearer viewer").statusCode());
            assertEquals(401, send(c.port, "POST", "/notifications/" + a.id() + "/read", null, null).statusCode(),
                    "ungated is not unauthenticated");
        }
    }

    private long unread(Ctx c, String bearer) throws Exception {
        return json(send(c.port, "GET", "/notifications/unread-count", null, bearer)).get("count").asLong();
    }

    private JsonNode entry(Ctx c, String bearer, String id) throws Exception {
        for (JsonNode n : json(send(c.port, "GET", "/notifications", null, bearer)))
            if (id.equals(n.get("id").asText())) return n;
        throw new AssertionError("no " + id + " in the feed");
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

            // mark one unread again → the badge comes back
            JsonNode unread = json(send(c.port, "POST", "/notifications/" + a.id() + "/unread", null));
            assertEquals("UNREAD", unread.get("state").asText());
            assertFalse(unread.get("read").asBoolean());
            assertEquals(1, json(send(c.port, "GET", "/notifications/unread-count", null)).get("count").asInt());

            // delete (archive) → removed from active feed
            assertTrue(json(send(c.port, "DELETE", "/notifications/" + b.id(), null)).get("deleted").asBoolean());
            assertEquals(1, json(send(c.port, "GET", "/notifications", null)).size());

            // missing id → 404
            assertEquals(404, send(c.port, "POST", "/notifications/no-such/read", null).statusCode());
            assertEquals(404, send(c.port, "POST", "/notifications/no-such/unread", null).statusCode());
            assertEquals(404, send(c.port, "DELETE", "/notifications/no-such", null).statusCode());
        }
    }

    /** Personal edition: no Subject, so the reader is the historic fallback identity — {@code appUser}, or the
     *  honour-system {@code X-Actor} — the same convention the audit trail's actor uses. */
    @Test
    void withoutASubjectTheReaderIsTheFallbackActor(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Notification a = seed(c.svc, "Pipeline a failed", "k-a");
            assertEquals(200, send(c.port, "POST", "/notifications/" + a.id() + "/read", null).statusCode());

            HttpRequest bob = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + c.port + "/api/v1/notifications/unread-count"))
                    .header("X-Actor", "bob").GET().build();
            assertEquals(1, V1Body.of(client.send(bob, BodyHandlers.ofString()).body()).get("count").asInt(),
                    "appUser's read is not bob's");
            assertEquals(0, json(send(c.port, "GET", "/notifications/unread-count", null)).get("count").asInt());
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

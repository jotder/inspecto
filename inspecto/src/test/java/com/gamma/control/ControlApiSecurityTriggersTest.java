package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The §8 security triggers over real HTTP (ses-sns-adapter-design): refusals the audit trail records become ONE
 * {@code security} notification per key per window, visible to administrators only.
 */
class ControlApiSecurityTriggersTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer viewer".equals(auth)) return Optional.of(new Subject("viewer", Set.of()));
        if ("Bearer admin".equals(auth))
            return Optional.of(new Subject("admin", Set.of(Roles.CAN_ADMINISTER, "canConfigureAccess"),
                    null, Map.of(), "admin@example.com"));
        return Optional.empty();
    };

    private Ctx open(Path dir) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("root"));
        System.setProperty("assist.write.root", writeRoot.toString());
        System.setProperty("notify.security.t1.threshold", "5");
        System.setProperty("notify.security.t2.threshold", "5");
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        System.clearProperty("assist.write.root");
        System.clearProperty("notify.security.t1.threshold");
        System.clearProperty("notify.security.t2.threshold");
    }

    @Test
    void manyForbiddenRequestsProduceOneSecurityNotificationForAdministratorsOnly(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            for (int i = 0; i < 12; i++)
                assertEquals(403, send(c.port, "PUT", "/notifications/preferences/default", "{}", "Bearer viewer", null)
                        .statusCode());
            List<Notification> security = awaitSecurity(c, 1);
            assertEquals(1, security.size(), "12 refusals over a threshold of 5 fire once in the window");
            assertTrue(security.get(0).body().contains("viewer"), security.get(0).body());

            JsonNode adminFeed = V1Body.of(send(c.port, "GET", "/notifications", null, "Bearer admin", null).body());
            assertTrue(adminFeed.toString().contains("\"security\""), "an administrator sees it: " + adminFeed);
            JsonNode viewerFeed = V1Body.of(send(c.port, "GET", "/notifications", null, "Bearer viewer", null).body());
            assertFalse(viewerFeed.toString().contains("\"security\""), "a non-administrator does not");

            String id = security.get(0).id();
            for (String op : List.of("read", "unread"))
                assertEquals(404, send(c.port, "POST", "/notifications/" + id + "/" + op, "{}", "Bearer viewer", null)
                        .statusCode(), "a non-administrator cannot " + op + " it by id either");
            assertEquals(200, send(c.port, "POST", "/notifications/" + id + "/read", "{}", "Bearer admin", null)
                    .statusCode(), "an administrator can");
        }
    }

    @Test
    void t2KeysASpoofedForwardedForFromAnUnlistedPeerOnTheSocketPeer(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            for (int i = 0; i < 5; i++)
                assertEquals(401, send(c.port, "GET", "/notifications", null, null, "203.0.113." + i).statusCode());
            List<Notification> security = awaitSecurity(c, 1);
            assertEquals(1, security.size(), "five rotating headers are ONE key, the socket peer");
            String body = security.get(0).body();
            assertTrue(body.contains("127.0.0.1"), body);
            assertFalse(body.contains("203.0.113."), body);
        }
    }

    @Test
    void t4FiresOnARolesWrite(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(dir)) {
            HttpResponse<String> put = send(c.port, "PUT", "/access/roles",
                    "{\"roles\":[{\"name\":\"auditor\",\"capabilities\":[\"canOperateRuns\"]}]}", "Bearer admin", null);
            assertEquals(200, put.statusCode(), put.body());
            List<Notification> security = awaitSecurity(c, 1);
            assertEquals(1, security.size());
            assertTrue(security.get(0).title().contains("Role"), security.get(0).title());
        }
    }

    private static List<Notification> awaitSecurity(Ctx c, int atLeast) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        List<Notification> found;
        do {
            found = c.svc.notifications().recent(100).stream().filter(n -> "security".equals(n.category())).toList();
            if (found.size() >= atLeast) {
                Thread.sleep(200);   // let any (wrong) extra firing land before asserting the count
                return c.svc.notifications().recent(100).stream().filter(n -> "security".equals(n.category())).toList();
            }
            Thread.sleep(25);
        } while (System.currentTimeMillis() < deadline);
        return found;
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth, String xff)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        if (xff != null) b.header("X-Forwarded-For", xff);
        return client.send(b.build(), BodyHandlers.ofString());
    }
}

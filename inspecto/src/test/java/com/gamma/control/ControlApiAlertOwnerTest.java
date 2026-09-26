package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.alert.AlertRule;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.notify.Notification;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DUCKLE-C1 residual 2 — <b>owner-routed alerting</b>, over real HTTP: the capture point and the feed.
 *
 * <ul>
 *   <li>An Alert Rule's owner is the component's R3 sharing-envelope {@code owner} ({@link ComponentAccess}):
 *       stamped from the authenticated {@link Subject} on create, carried forward by an edit, and changed only
 *       by the owner or an access admin — through {@code /alerts/rules} exactly as through
 *       {@code /components}. With no Subject (Personal) the rule is {@code appUser}, i.e. unowned.</li>
 *   <li>A notification addressed to one Subject is in that Subject's feed and badge count only, and a
 *       broadcast is in everyone's; without a Subject (Personal) everything is shown, as before.</li>
 * </ul>
 */
class ControlApiAlertOwnerTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** Two authors (and an administrator) standing in for the security module. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        Set<String> author = Set.of(Roles.CAN_AUTHOR_ALERT_RULES, Roles.CAN_AUTHOR_WORKBENCH);
        if ("Bearer alice".equals(auth)) return Optional.of(new Subject("alice", author));
        if ("Bearer bob".equals(auth)) return Optional.of(new Subject("bob", author));
        if ("Bearer admin".equals(auth)) return Optional.of(new Subject("admin", Set.of(Roles.CAN_ADMINISTER)));
        return Optional.empty();
    };

    @BeforeEach
    void professionalBuild() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
    }

    @AfterEach
    void restore() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String bearer)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (bearer != null) b.header("Authorization", bearer);
        if (body != null) b.header("Content-Type", "application/json");
        return client.send(b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
                .build(), BodyHandlers.ofString());
    }

    private static String rule(String name, String extra) {
        return """
                {"name":"%s","metric":"error_rate","threshold":0.05,"window":"1h"%s}""".formatted(name, extra);
    }

    private static String storedOwner(Path root, String name) {
        return AlertRule.fromMap(new ComponentStore(root.resolve("registry")).get("alert-rule", name)
                .orElseThrow().content()).owner();
    }

    private static String armedOwner(Ctx c, String name) {
        return c.svc.alertService().orElseThrow().rules().stream()
                .filter(r -> name.equals(r.get("name"))).findFirst().orElseThrow().get("owner").toString();
    }

    @Test
    void theAuthoringSubjectIsTheOwnerAndOnlyTheOwnerMovesIt(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> created = send(c.port, "POST", "/alerts/rules", rule("alices-rate", ""), "Bearer alice");
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("alice", storedOwner(root, "alices-rate"), "the authenticated author is the owner");
            assertEquals("alice", armedOwner(c, "alices-rate"), "the running engine routes by the same owner");

            HttpResponse<String> edited = send(c.port, "PUT", "/alerts/rules/alices-rate", rule("alices-rate", ""),
                    "Bearer bob");
            assertEquals(200, edited.statusCode(), edited.body());
            assertEquals("alice", storedOwner(root, "alices-rate"), "an edit that omits the owner carries it forward");
            assertEquals("alice", armedOwner(c, "alices-rate"));

            HttpResponse<String> hijack = send(c.port, "PUT", "/alerts/rules/alices-rate",
                    rule("alices-rate", ",\"owner\":\"bob\""), "Bearer bob");
            assertEquals(403, hijack.statusCode(), "only the owner re-addresses a rule's alerts: " + hijack.body());
            assertEquals("alice", storedOwner(root, "alices-rate"));

            HttpResponse<String> handover = send(c.port, "PUT", "/alerts/rules/alices-rate",
                    rule("alices-rate", ",\"owner\":\"bob\""), "Bearer alice");
            assertEquals(200, handover.statusCode(), handover.body());
            assertEquals("bob", storedOwner(root, "alices-rate"), "the owner may hand the rule over");
            assertEquals("bob", armedOwner(c, "alices-rate"));
        }
    }

    @Test
    void aSaveThroughTheAlertRoutesKeepsTheSharesItDoesNotModel(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            String shares = ",\"shares\":[{\"subjectType\":\"user\",\"subjectId\":\"bob\",\"access\":\"edit\"}]";
            HttpResponse<String> created = send(c.port, "POST", "/alerts/rules", rule("shared-rate", shares),
                    "Bearer alice");
            assertEquals(200, created.statusCode(), created.body());
            HttpResponse<String> edited = send(c.port, "PUT", "/alerts/rules/shared-rate", rule("shared-rate", ""),
                    "Bearer bob");
            assertEquals(200, edited.statusCode(), "bob holds an edit share: " + edited.body());
            Object kept = new ComponentStore(root.resolve("registry")).get("alert-rule", "shared-rate")
                    .orElseThrow().content().get("shares");
            assertTrue(kept instanceof List<?> l && l.size() == 1, "a plain save never strips protection: " + kept);
            assertEquals("alice", storedOwner(root, "shared-rate"));
        }
    }

    @Test
    void withoutASubjectTheRuleIsUnowned(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> created = send(c.port, "POST", "/alerts/rules", rule("personal-rate", ""), null);
            assertEquals(200, created.statusCode(), created.body());
            assertEquals(AlertRule.UNOWNED, storedOwner(root, "personal-rate"), "Personal: no Subject to capture");
            assertFalse(new ComponentStore(root.resolve("registry")).get("alert-rule", "personal-rate")
                    .orElseThrow().content().containsKey("owner"), "and the appUser placeholder is not stored");
        }
    }

    @Test
    void anAddressedNotificationIsInItsRecipientsFeedOnly(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Notification alices = c.svc.notifications().add(Notification.create("ops", "ALERT_FIRED", "a",
                    "Alert: alices-rate", "breached", "k-alice", "alice"));
            Notification everyone = c.svc.notifications().add(Notification.create("ops", "ALERT_FIRED", "b",
                    "Alert: shared-rate", "breached", "k-all"));

            assertEquals(List.of(alices.id(), everyone.id()), sorted(feed(c, null), alices, everyone),
                    "Personal: no Subject, so everything stored is shown, as before");

            Authenticators.forTest(FAKE);
            assertEquals(List.of(alices.id(), everyone.id()), sorted(feed(c, "Bearer alice"), alices, everyone));
            assertEquals(List.of(everyone.id()), sorted(feed(c, "Bearer bob"), alices, everyone));
            assertEquals(List.of(everyone.id()), sorted(feed(c, "Bearer admin"), alices, everyone),
                    "the feed is a personal inbox; oversight is GET /alerts");
            assertEquals(2, unread(c, "Bearer alice"));
            assertEquals(1, unread(c, "Bearer bob"));
        }
    }

    private List<String> feed(Ctx c, String bearer) throws Exception {
        HttpResponse<String> r = send(c.port, "GET", "/notifications", null, bearer);
        assertEquals(200, r.statusCode(), r.body());
        List<String> ids = new ArrayList<>();
        for (JsonNode n : V1Body.of(r.body())) ids.add(n.get("id").asText());
        return ids;
    }

    /** {@code ids} in the fixed order (addressed, broadcast), so the assertion does not depend on timestamps. */
    private static List<String> sorted(List<String> ids, Notification first, Notification second) {
        List<String> out = new ArrayList<>();
        if (ids.contains(first.id())) out.add(first.id());
        if (ids.contains(second.id())) out.add(second.id());
        assertEquals(out.size(), ids.size(), "nothing else is in the feed: " + ids);
        return out;
    }

    private long unread(Ctx c, String bearer) throws Exception {
        return V1Body.of(send(c.port, "GET", "/notifications/unread-count", null, bearer).body())
                .get("count").asLong();
    }
}

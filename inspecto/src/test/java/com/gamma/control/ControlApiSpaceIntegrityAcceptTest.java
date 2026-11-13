package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceLock;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LC-6 — {@code POST /spaces/{id}/integrity/accept} over real HTTP under an ARMED authenticator: 401, 403 for an
 * author without {@code canAdminister}, 503 under the kill switch and for the single-tenant default Space, 400 for a
 * bad body, 404 for a path the lock does not know, and 200 accepting one file, then the rest; the accepted files leave
 * {@code changedOutside}, the next computation still reads the re-signed lock as its baseline, and an AUDIT event names
 * the operator.
 */
class ControlApiSpaceIntegrityAcceptTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code admin} holds canAuthorWorkbench and canAdminister; {@code author} only canAuthorWorkbench. */
    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        String user = a.substring(7);
        return Optional.of(new Subject(user, "admin".equals(user) ? Set.of("canAuthorWorkbench", "canAdminister")
                : "author".equals(user) ? Set.of("canAuthorWorkbench") : Set.of()));
    };

    @BeforeEach
    void isolate() {
        DiscoveredRoots.clear();
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        System.clearProperty(SpaceLock.PROPERTY);
        DiscoveredRoots.clear();
        MetricRegistry.global().reset();
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
        }
    }

    private Ctx open(Path root) throws Exception {
        Path config = Files.createDirectories(root.resolve("alpha/config"));
        Files.createDirectories(root.resolve("alpha/data"));
        Files.writeString(config.resolve("base_job.toon"), "job:\n  name: base\n  type: noop\n");
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port(), config);
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String user, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> accept(Ctx c, String user, String body) throws Exception {
        return send(c, "POST", "/spaces/alpha/integrity/accept", user, body);
    }

    private JsonNode awaitReady(Ctx c, Predicate<JsonNode> ok) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> r = send(c, "GET", "/spaces/alpha/integrity", "admin", null);
            assertEquals(200, r.statusCode(), r.body());
            last = V1Body.of(r.body());
            if ("ready".equals(last.get("status").asText()) && !last.has("recomputing") && ok.test(last)) return last;
            Thread.sleep(100);
        }
        throw new AssertionError("no ready report in time: " + last);
    }

    /** Two files added by hand (times well before any server write), then a server write so a computation sees them. */
    private void handEdits(Ctx c, Path root) throws Exception {
        awaitReady(c, n -> true);
        for (String n : List.of("one", "two")) {
            Path f = c.config.resolve(n + "_job.toon");
            Files.writeString(f, "job:\n  name: " + n + "\n  type: noop\n");
            Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        }
        String backups = root.resolve("alpha/data/backups").toAbsolutePath().normalize().toString().replace("\\", "\\\\");
        assertEquals(200, send(c, "POST", "/spaces/alpha/jobs", "admin",
                "{\"name\":\"served\",\"type\":\"maintenance\",\"task\":\"cleanup\",\"retention_days\":\"30\",\"backup_dir\":\""
                        + backups + "\"}").statusCode());
        awaitReady(c, n -> n.get("changedOutside").get("total").asInt() == 2);
    }

    private static List<String> outside(JsonNode report) {
        List<String> out = new ArrayList<>();
        for (JsonNode o : report.get("changedOutside").get("items")) out.add(o.get("path").asText());
        return out;
    }

    // ── gates ────────────────────────────────────────────────────────────────────

    @Test
    void noCredentialIs401(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(401, accept(c, null, null).statusCode());
        }
    }

    @Test
    void anAuthorWithoutCanAdministerIs403(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = accept(c, "author", null);
            assertEquals(403, r.statusCode(), r.body());
        }
    }

    @Test
    void theKillSwitchIs503(@TempDir Path root) throws Exception {
        System.setProperty(SpaceLock.PROPERTY, "off");
        try (Ctx c = open(root)) {
            assertEquals(503, accept(c, "admin", null).statusCode());
        }
    }

    @Test
    void theSingleTenantDefaultSpaceIs503(@TempDir Path cfg) throws Exception {
        Authenticators.forTest(FAKE);
        System.setProperty("assist.write.root", cfg.toString());
        CollectorService svc = new CollectorService(List.of(), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        try {
            api.start();
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + api.port() + "/api/v1/spaces/default/integrity/accept"))
                    .header("Authorization", "Bearer admin").POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, r.statusCode(), r.body());
        } finally {
            System.clearProperty("assist.write.root");
            api.close();
            svc.close();
        }
    }

    @Test
    void aBadBodyIs400(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            for (String bad : List.of("{\"paths\":\"one_job.toon\"}", "{\"paths\":[]}", "{\"paths\":[1]}", "{\"paths\":[\" \"]}")) {
                HttpResponse<String> r = accept(c, "admin", bad);
                assertEquals(400, r.statusCode(), bad + " -> " + r.body());
            }
        }
    }

    @Test
    void aPathTheLockDoesNotKnowIs404AndAcceptsNothing(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            handEdits(c, root);
            HttpResponse<String> r = accept(c, "admin", "{\"paths\":[\"one_job.toon\",\"nope_job.toon\"]}");
            assertEquals(404, r.statusCode(), r.body());
            assertTrue(r.body().contains("nope_job.toon"), r.body());
            assertEquals(2, awaitReady(c, n -> true).get("changedOutside").get("total").asInt(), "nothing accepted");
        }
    }

    // ── accept ───────────────────────────────────────────────────────────────────

    @Test
    void acceptOneThenEveryOtherAndTheLockStaysTheBaseline(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            List<Event> audit = new CopyOnWriteArrayList<>();
            handEdits(c, root);
            c.spaces.space(com.gamma.service.SpaceId.of("alpha")).orElseThrow().service().eventLog().addSubscriber(e -> {
                if (EventType.AUDIT.equals(e.type()) && "config.changed-outside.accepted".equals(e.attributes().get("action")))
                    audit.add(e);
            });

            HttpResponse<String> one = accept(c, "admin", "{\"paths\":[\"one_job.toon\",\"base_job.toon\"]}");
            assertEquals(200, one.statusCode(), one.body());
            JsonNode b = V1Body.of(one.body());
            assertEquals(1, b.get("accepted").size(), b.toString());
            assertEquals("one_job.toon", b.get("accepted").get(0).get("path").asText());
            assertEquals("added", b.get("accepted").get(0).get("change").asText());
            assertEquals("base_job.toon", b.get("notChangedOutside").get(0).asText(), "not changed outside: listed, not an error");
            assertEquals(1, b.get("changedOutside").asInt());
            assertEquals("admin", b.get("acceptedBy").asText());
            assertEquals(List.of("two_job.toon"), outside(awaitReady(c, n -> true)));
            assertEquals(1, audit.size(), "one AUDIT event per accept");
            assertEquals("admin", audit.get(0).attributes().get("actor"));
            assertTrue(audit.get(0).attributes().get("paths").contains("one_job.toon"));

            HttpResponse<String> all = accept(c, "admin", null);
            assertEquals(200, all.statusCode(), all.body());
            assertEquals("two_job.toon", V1Body.of(all.body()).get("accepted").get(0).get("path").asText());

            // the accept is itself a server write: the computation after it reads the re-signed lock as its baseline
            Thread.sleep(AFTER_WRITE_MS);
            JsonNode after = awaitReady(c, n -> true);
            assertEquals("lock", after.get("baseline").asText(), "the accepted lock verifies: " + after);
            assertEquals(0, after.get("changedOutside").get("total").asInt(), after.toString());

            HttpResponse<String> again = accept(c, "admin", null);
            assertEquals(200, again.statusCode(), again.body());
            assertEquals(0, V1Body.of(again.body()).get("accepted").size(), "nothing left: an idempotent 200");
            assertEquals(2, audit.size(), "an accept that accepts nothing is not audited as one");
        }
    }

    /** The Space lock's debounce (1.5 s) plus a margin, so the post-write computation has run. */
    private static final long AFTER_WRITE_MS = 2_500;
}

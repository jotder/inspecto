package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-1a — {@code GET /spaces/{id}/integrity} over real HTTP under an ARMED authenticator: 401, 403 without
 * {@code canAuthorWorkbench}, {@code computing} then {@code ready} with a planted broken link, the one write hook in
 * the request pipeline (a server write is not an outside edit; a hand edit is), {@code disabled} under the kill
 * switch, and {@code unavailable} for the single-tenant default Space.
 */
class ControlApiSpaceIntegrityTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer author} holds canAuthorWorkbench; {@code Bearer viewer} holds nothing. */
    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        String user = a.substring(7);
        return Optional.of(new Subject(user, "author".equals(user) ? Set.of("canAuthorWorkbench") : Set.of()));
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
        Files.writeString(config.resolve("orphan_job.toon"),
                "job:\n  name: orphan\n  type: noop\n  on_pipeline: gone\n");
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

    private JsonNode integrity(Ctx c) throws Exception {
        HttpResponse<String> r = send(c, "GET", "/spaces/alpha/integrity", "author", null);
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private JsonNode awaitReady(Ctx c, java.util.function.Predicate<JsonNode> ok) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = integrity(c);
            if ("ready".equals(last.get("status").asText()) && !last.has("recomputing") && ok.test(last)) return last;
            Thread.sleep(100);
        }
        throw new AssertionError("no ready report in time: " + last);
    }

    // ── gates ────────────────────────────────────────────────────────────────────

    @Test
    void noCredentialIs401(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(401, send(c, "GET", "/spaces/alpha/integrity", null, null).statusCode());
        }
    }

    @Test
    void aSubjectWithoutCanAuthorWorkbenchIs403(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(403, send(c, "GET", "/spaces/alpha/integrity", "viewer", null).statusCode());
        }
    }

    // ── answers ──────────────────────────────────────────────────────────────────

    @Test
    void computingThenReadyWithTheBrokenLink(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals("computing", integrity(c).get("status").asText(),
                    "the Space was not started: the first read schedules the computation");
            JsonNode r = awaitReady(c, n -> true);
            assertEquals(1, r.get("broken").get("total").asInt(), r.toString());
            JsonNode b = r.get("broken").get("items").get(0);
            assertEquals("job", b.get("fromKind").asText());
            assertEquals("gone", b.get("value").asText());
            assertEquals("none", r.get("baseline").asText());
            assertTrue(Files.isRegularFile(root.resolve("alpha/.inspecto/space.lock")));
        }
    }

    /** CENSUS-EXPECTATION-TARGET-PATH-1: a store-path target resolves; one naming no store is still broken. */
    @Test
    void anExpectationStorePathTargetIsNotBroken(@TempDir Path root) throws Exception {
        Path config = Files.createDirectories(root.resolve("alpha/config/registry/expectations"));
        Files.writeString(root.resolve("alpha/config/orders_pipeline.toon"), "name: orders\n");
        Files.writeString(config.resolve("band.toon"), "name: band\ntarget: orders/database\n");
        Files.writeString(config.resolve("stray.toon"), "name: stray\ntarget: nostore/database\n");
        try (Ctx c = open(root)) {
            integrity(c);
            JsonNode r = awaitReady(c, n -> true);
            List<String> broken = new ArrayList<>();
            r.get("broken").get("items").forEach(b -> broken.add(b.get("fromKind").asText() + ":" + b.get("value").asText()));
            assertEquals(List.of("expectation:nostore/database", "job:gone"), broken.stream().sorted().toList(), r.toString());
        }
    }

    @Test
    void aServerWriteIsNotAnOutsideEditAndAHandEditIs(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            integrity(c);
            awaitReady(c, n -> true);

            // a hand edit, its time well before any server write
            Path hand = c.config.resolve("hand_job.toon");
            Files.writeString(hand, "job:\n  name: hand\n  type: noop\n");
            Files.setLastModifiedTime(hand, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));

            String backups = root.resolve("alpha/data/backups").toAbsolutePath().normalize().toString().replace("\\", "\\\\");
            HttpResponse<String> w = send(c, "POST", "/spaces/alpha/jobs", "author",
                    "{\"name\":\"served\",\"type\":\"maintenance\",\"task\":\"cleanup\",\"retention_days\":\"30\",\"backup_dir\":\"" + backups + "\"}");
            assertEquals(200, w.statusCode(), w.body());

            JsonNode r = awaitReady(c, n -> n.get("files").asInt() >= 3);
            List<String> outside = new ArrayList<>();
            for (JsonNode o : r.get("changedOutside").get("items")) outside.add(o.get("change").asText() + " " + o.get("path").asText());
            assertEquals(List.of("added hand_job.toon"), outside, r.toString());
        }
    }

    @Test
    void theKillSwitchAnswersDisabledAndWritesNoLock(@TempDir Path root) throws Exception {
        System.setProperty(SpaceLock.PROPERTY, "off");
        try (Ctx c = open(root)) {
            assertEquals("disabled", integrity(c).get("status").asText());
            Thread.sleep(2000);
            assertFalse(Files.exists(root.resolve("alpha/.inspecto/space.lock")));
        }
    }

    @Test
    void theSingleTenantDefaultSpaceIsUnavailable(@TempDir Path cfg) throws Exception {
        Authenticators.forTest(FAKE);
        System.setProperty("assist.write.root", cfg.toString());
        CollectorService svc = new CollectorService(List.of(), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        try {
            api.start();
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + api.port() + "/api/v1/spaces/default/integrity"))
                    .header("Authorization", "Bearer author").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("unavailable", V1Body.of(r.body()).get("status").asText());
        } finally {
            System.clearProperty("assist.write.root");
            api.close();
            svc.close();
        }
    }

    /** The lock is signed with the Pending Change key: the file name it reads must be that key's. */
    @Test
    void theLockIsSignedWithThePendingChangeKey() {
        assertEquals(PendingChanges.KEY_FILE, SpaceLock.KEY_FILE);
    }
}

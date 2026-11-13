package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import com.gamma.service.SpaceOrigin;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-5a — {@code GET /spaces/{id}/origin} and {@code POST /spaces/{id}/origin/adopt} over real HTTP under an ARMED
 * authenticator: 401, 403 per route, every adopt gate (400, 422, 403, 404, 409 version, 409 already recorded), adopt
 * recording the origin, and the read before and after.
 */
class ControlApiSpaceOriginTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code admin} holds both capabilities, {@code author} only canAuthorWorkbench, {@code viewer} nothing. */
    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        String user = a.substring(7);
        Set<String> caps = switch (user) {
            case "admin" -> Set.of("canAdminister", "canAuthorWorkbench");
            case "author" -> Set.of("canAuthorWorkbench");
            default -> Set.of();
        };
        return Optional.of(new Subject(user, caps));
    };

    @BeforeEach
    void isolate() {
        DiscoveredRoots.clear();
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        DiscoveredRoots.clear();
        MetricRegistry.global().reset();
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
        }
    }

    /** A hand-made Space {@code alpha} whose one Dataset equals the {@code starter} template's (version 3). */
    private Ctx open(Path root) throws Exception {
        Path tpl = root.resolve("_templates").resolve("starter");
        Files.createDirectories(tpl.resolve("config"));
        Files.writeString(tpl.resolve("template.toon"), "name: Starter\nversion: \"3\"\n");
        Files.writeString(tpl.resolve("config").resolve("d_dataset.toon"), "physicalRef: d/database\n");
        Path config = Files.createDirectories(root.resolve("alpha/config"));
        Files.createDirectories(root.resolve("alpha/data"));
        Files.writeString(config.resolve("d_dataset.toon"), "physicalRef: d/database\n");
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port(), root);
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String user, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> adopt(Ctx c, String user, String body) throws Exception {
        return send(c, "POST", "/spaces/alpha/origin/adopt", user, body);
    }

    // ── auth gates ──

    @Test
    void noCredentialIs401(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(401, send(c, "GET", "/spaces/alpha/origin", null, null).statusCode());
            assertEquals(401, adopt(c, null, "{\"template\":\"starter\"}").statusCode());
        }
    }

    @Test
    void readNeedsCanAuthorWorkbenchAndAdoptNeedsCanAdminister(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(403, send(c, "GET", "/spaces/alpha/origin", "viewer", null).statusCode());
            assertEquals(403, adopt(c, "author", "{\"template\":\"starter\"}").statusCode(), "an author cannot adopt");
            assertFalse(Files.exists(root.resolve("alpha").resolve(SpaceOrigin.RECORD)));
        }
    }

    // ── adopt gates ──

    @Test
    void adoptRefusesABadBodyAVersionAPathAndAnUnknownTemplate(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(400, adopt(c, "admin", "{}").statusCode());
            assertEquals(400, adopt(c, "admin", "{\"template\":\"starter\",\"version\":3}").statusCode());
            assertEquals(422, adopt(c, "admin", "{\"template\":\"starter\",\"version\":\"3 4\"}").statusCode());
            assertEquals(403, adopt(c, "admin", "{\"template\":\"../alpha\"}").statusCode());
            assertEquals(404, adopt(c, "admin", "{\"template\":\"nope\"}").statusCode());
            assertEquals(409, adopt(c, "admin", "{\"template\":\"starter\",\"version\":\"2\"}").statusCode(),
                    "only the template version installed now can be matched");
            assertFalse(Files.exists(root.resolve("alpha").resolve(SpaceOrigin.RECORD)), "no refusal wrote a record");
        }
    }

    // ── answers ──

    @Test
    void adoptRecordsTheOriginThenTheReadReportsItAndASecondAdoptIs409(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> before = send(c, "GET", "/spaces/alpha/origin", "author", null);
            assertEquals(200, before.statusCode(), before.body());
            assertEquals("none", V1Body.of(before.body()).get("status").asText());

            HttpResponse<String> r = adopt(c, "admin", "{\"template\":\"starter\",\"version\":\"3\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode a = V1Body.of(r.body());
            assertEquals("full", a.get("match").asText(), a.toString());
            assertTrue(a.get("recorded").asBoolean());
            assertEquals("d_dataset.toon", a.get("matching").get(0).asText());

            JsonNode o = V1Body.of(send(c, "GET", "/spaces/alpha/origin", "author", null).body());
            assertEquals("recorded", o.get("status").asText(), o.toString());
            assertEquals("starter", o.get("template").asText());
            assertEquals("3", o.get("templateVersion").asText());
            assertEquals("adopted", o.get("origin").asText());
            assertEquals("d_dataset.toon", o.get("space").get("unchanged").get(0).asText());
            assertEquals("same-version", o.get("templateSince").get("status").asText());

            HttpResponse<String> again = adopt(c, "admin", "{\"template\":\"starter\"}");
            assertEquals(409, again.statusCode(), "a Space that records its origin is not re-adopted");
        }
    }
}

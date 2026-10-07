package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import com.gamma.access.Roles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code DUCKLE-C6-POLICY-NARROWING-1} S7: {@code GET /settings/safety-policy} over real HTTP, with a Subject
 * (a no-Subject request makes {@code withCapability} a no-op, so it would pass against an ungated route).
 * Read-only: no write-root gate, no caller path to jail; the gates are the capability and the fail-closed
 * posture on an unreadable file.
 */
class SafetyPolicyExplainRoutesTest {

    @TempDir Path dir;
    private final HttpClient client = HttpClient.newHttpClient();
    private String inheritedRoots;

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer admin".equals(auth)) return Optional.of(new Subject("admin", Set.of(Roles.CAN_ADMINISTER)));
        if ("Bearer author".equals(auth)) return Optional.of(new Subject("author", Set.of(Roles.CAN_AUTHOR_WORKBENCH)));
        return Optional.empty();
    };

    @BeforeEach
    void isolate() {
        inheritedRoots = System.getProperty("assist.safety.roots");
        System.clearProperty("assist.safety.roots");
        DiscoveredRoots.clear();
        Authenticators.forTest(FAKE);
    }

    @AfterEach
    void restore() {
        Authenticators.forTest(null);
        if (inheritedRoots == null) System.clearProperty("assist.safety.roots");
        else System.setProperty("assist.safety.roots", inheritedRoots);
        System.clearProperty("system.config.dir");
        DiscoveredRoots.clear();
    }

    private HttpResponse<String> get(int port, String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (bearer != null) b.header("Authorization", bearer);
        return client.send(b.GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer admin").header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static JsonNode field(JsonNode data, String name) {
        for (JsonNode f : data.get("fields")) if (name.equals(f.get("field").asText())) return f;
        throw new AssertionError("no field " + name + " in " + data);
    }

    @Test
    void theExplainNamesWhichTierConstrainedEachFieldAndIsAdminOnlyAndAnswersADiagnosticWhenUnreadable() throws Exception {
        Path serverDir = Files.createDirectories(dir.resolve("server"));
        Files.writeString(serverDir.resolve("safety-policy.toon"), """
                permit:
                  network: false
                caps:
                  max_threads: 4
                """);
        System.setProperty("system.config.dir", serverDir.toString());
        SpaceManager spaces = SpaceManager.discover(dir.resolve("root"));
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            assertEquals(200, post(port, "/spaces", "{\"id\":\"acme\"}").statusCode());
            Path spaceCfg = Files.createDirectories(dir.resolve("root").resolve("acme").resolve("config"));
            Files.writeString(spaceCfg.resolve("safety-policy.toon"), """
                    caps:
                      max_threads: 2
                      max_batch_files: 50
                    deny:
                      hosts[1]: evil.example.com
                    """);

            // capability gate: no Subject -> refused, a Subject without canAdminister -> 403, never the body
            assertNotEquals(200, get(port, "/spaces/acme/settings/safety-policy", null).statusCode());
            assertEquals(403, get(port, "/spaces/acme/settings/safety-policy", "Bearer author").statusCode());

            HttpResponse<String> ok = get(port, "/spaces/acme/settings/safety-policy", "Bearer admin");
            assertEquals(200, ok.statusCode(), ok.body());
            JsonNode data = V1Body.of(ok.body());
            assertEquals("acme", data.get("space").asText());
            assertTrue(data.get("readable").asBoolean());
            assertTrue(data.get("files").get("server").get("present").asBoolean());
            assertTrue(data.get("files").get("space").get("present").asBoolean());

            assertEquals(3, data.get("exempt").size(), "D15: server-configured egress is listed as outside the tier");
            JsonNode network = field(data, "permit.network");
            assertFalse(network.get("effective").asBoolean());
            assertEquals("[\"server\"]", network.get("constrainedBy").toString());
            JsonNode threads = field(data, "caps.max_threads");
            assertEquals(2, threads.get("effective").asInt(), "MIN of server 4 and space 2");
            assertEquals("[\"space\"]", threads.get("constrainedBy").toString());
            assertEquals("[\"space\"]", field(data, "caps.max_batch_files").get("constrainedBy").toString());
            assertEquals("[\"space\"]", field(data, "deny.hosts").get("constrainedBy").toString());
            assertEquals("evil.example.com", field(data, "deny.hosts").get("effective").get(0).asText());
            JsonNode untouched = field(data, "permit.advance_state");
            assertEquals("[\"default\"]", untouched.get("constrainedBy").toString());
            assertTrue(untouched.get("effective").asBoolean());
            assertEquals("[\"default\"]", field(data, "allow.roots").get("constrainedBy").toString());

            // a Space with no policy file of its own is explained by the server tier + defaults
            assertEquals(200, post(port, "/spaces", "{\"id\":\"plain\"}").statusCode());
            JsonNode plain = V1Body.of(get(port, "/spaces/plain/settings/safety-policy", "Bearer admin").body());
            assertFalse(plain.get("files").get("space").get("present").asBoolean());
            assertEquals(4, field(plain, "caps.max_threads").get("effective").asInt());
            assertEquals("[\"server\"]", field(plain, "caps.max_threads").get("constrainedBy").toString());

            // an unreadable file: 200 with the reason and NO effective policy (the gates still refuse)
            Files.writeString(spaceCfg.resolve("safety-policy.toon"), "mode: audit\n");   // illegal in a Space file
            HttpResponse<String> broken = get(port, "/spaces/acme/settings/safety-policy", "Bearer admin");
            assertEquals(200, broken.statusCode(), broken.body());
            JsonNode b = V1Body.of(broken.body());
            assertFalse(b.get("readable").asBoolean());
            assertTrue(b.get("problem").asText().contains("safety-policy.toon"), b.toString());
            assertNull(b.get("fields"), "no guessed policy");
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }
}

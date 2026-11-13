package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.metrics.MetricRegistry;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `SPACE-LINK-CENSUS-1` LC-4 over real HTTP, two Spaces side by side: {@code alpha} sets
 * {@code links: refuse-activation} in its {@code safety-policy.toon}, {@code beta} sets nothing. With the JVM modes at
 * their {@code shadow} default, alpha refuses a delete or a save that breaks a link (force still deletes) while beta
 * only reports; on Basic (nobody signs in) alpha only reports too and its integrity report says why; the JVM kill switch
 * {@code off} still wins. Also the Spaces-screen count on {@code GET /spaces}.
 */
class ControlApiSpaceLinksModeTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer author} may author and administer; {@code Bearer viewer} holds nothing. */
    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        String user = a.substring(7);
        return Optional.of(new Subject(user, "author".equals(user)
                ? Set.of("canAuthorWorkbench", "canAdminister") : Set.of()));
    };

    @BeforeEach
    void isolate() {
        DiscoveredRoots.clear();
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        System.clearProperty(LinkRefusals.DELETE_PROPERTY);
        DiscoveredRoots.clear();
        MetricRegistry.global().reset();
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
        }
    }

    /** Both Spaces: Dataset ds1 feeds widget w1. Only alpha opts into refuse-activation. */
    private Ctx open(Path root, boolean signIn) throws Exception {
        for (String id : new String[] {"alpha", "beta"}) {
            Path config = Files.createDirectories(root.resolve(id + "/config"));
            Files.createDirectories(root.resolve(id + "/data"));
            Files.createDirectories(config.resolve("registry/datasets"));
            Files.createDirectories(config.resolve("registry/widgets"));
            Files.writeString(config.resolve("registry/datasets/ds1.toon"), "name: ds1\nphysicalRef: feed/database\n");
            Files.writeString(config.resolve("registry/widgets/w1.toon"), "name: w1\nvizType: bar\ndatasetId: ds1\n");
        }
        Files.writeString(root.resolve("alpha/config/safety-policy.toon"), "links: refuse-activation\n");
        if (signIn) Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String user, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> r) throws Exception {
        return r.statusCode() >= 400 ? V1Body.envelope(r.body()).get("error").get("details") : V1Body.of(r.body());
    }

    private JsonNode linksMode(Ctx c, String space, String user) throws Exception {
        HttpResponse<String> r = send(c, "GET", "/spaces/" + space + "/integrity", user, null);
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body()).get("linksMode");
    }

    @Test
    void refuseActivationRefusesADeleteThatBreaksALinkWhileASpaceWithoutItOnlyReports(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            HttpResponse<String> alpha = send(c, "DELETE", "/spaces/alpha/components/dataset/ds1", "author", null);
            assertEquals(409, alpha.statusCode(), alpha.body());
            JsonNode deps = body(alpha).get("dependents");
            assertEquals(1, deps.size(), deps.toString());
            assertEquals("w1", deps.get(0).get("name").asText());
            assertTrue(Files.exists(root.resolve("alpha/config/registry/datasets/ds1.toon")), "refused: ds1 stays");

            HttpResponse<String> beta = send(c, "DELETE", "/spaces/beta/components/dataset/ds1", "author", null);
            assertEquals(200, beta.statusCode(), "beta has no links: mode, so the JVM shadow default applies: " + beta.body());
            assertEquals("w1", body(beta).get("brokenLinks").get(0).get("name").asText());
            assertFalse(Files.exists(root.resolve("beta/config/registry/datasets/ds1.toon")));

            HttpResponse<String> forced = send(c, "DELETE", "/spaces/alpha/components/dataset/ds1?force=true", "author", null);
            assertEquals(200, forced.statusCode(), "?force=true still deletes: " + forced.body());
            assertFalse(Files.exists(root.resolve("alpha/config/registry/datasets/ds1.toon")));

            JsonNode a = linksMode(c, "alpha", "author");
            assertEquals("refuse-activation", a.get("configured").asText());
            assertEquals("refuse-activation", a.get("effective").asText());
            JsonNode b = linksMode(c, "beta", "author");
            assertEquals("report", b.get("configured").asText());
            assertEquals("report", b.get("effective").asText());
        }
    }

    @Test
    void refuseActivationRefusesASaveThatAddsABrokenLinkWhileASpaceWithoutItWarns(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            String widget = "{\"id\":\"w9\",\"vizType\":\"bar\",\"datasetId\":\"no_such_ds\"}";
            HttpResponse<String> alpha = send(c, "POST", "/spaces/alpha/components/widget", "author", widget);
            assertEquals(422, alpha.statusCode(), alpha.body());
            assertEquals("ERR_LINK_DANGLING", body(alpha).get("findings").get(0).get("code").asText());
            assertFalse(Files.exists(root.resolve("alpha/config/registry/widgets/w9.toon")));

            HttpResponse<String> beta = send(c, "POST", "/spaces/beta/components/widget", "author", widget);
            assertEquals(200, beta.statusCode(), beta.body());
            assertEquals("WARN_LINK_DANGLING", body(beta).get("linkWarnings").get(0).get("code").asText());
        }
    }

    @Test
    void theJvmKillSwitchStillWins(@TempDir Path root) throws Exception {
        System.setProperty(LinkRefusals.DELETE_PROPERTY, "off");
        try (Ctx c = open(root, true)) {
            HttpResponse<String> r = send(c, "DELETE", "/spaces/alpha/components/dataset/ds1", "author", null);
            assertEquals(200, r.statusCode(), r.body());
            assertNull(body(r).get("brokenLinks"), "off checks nothing: " + r.body());
        }
    }

    @Test
    void onBasicTheKeyParsesButOnlyReportsAndTheIntegrityReportSaysSo(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, false)) {
            HttpResponse<String> r = send(c, "DELETE", "/spaces/alpha/components/dataset/ds1", null, null);
            assertEquals(200, r.statusCode(), "D-LC5: no refuse mode on Basic: " + r.body());
            assertEquals("w1", body(r).get("brokenLinks").get(0).get("name").asText());

            JsonNode m = linksMode(c, "alpha", null);
            assertEquals("refuse-activation", m.get("configured").asText());
            assertEquals("report", m.get("effective").asText());
            assertTrue(m.get("note").asText().contains("Standard or Advanced"), m.toString());
        }
    }

    // ── the Spaces-screen count ─────────────────────────────────────────────────────────────

    @Test
    void theSpacesListCarriesTheIntegrityCountOnlyForAnAuthorOfThatSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            Files.writeString(root.resolve("alpha/config/registry/widgets/w2.toon"), "name: w2\nvizType: bar\ndatasetId: gone\n");
            send(c, "GET", "/spaces/alpha/integrity", "author", null);   // the first read schedules the computation
            long deadline = System.currentTimeMillis() + 15_000;
            JsonNode alpha = null;
            while (System.currentTimeMillis() < deadline) {
                HttpResponse<String> r = send(c, "GET", "/spaces", "author", null);
                assertEquals(200, r.statusCode(), r.body());
                for (JsonNode s : V1Body.of(r.body())) if ("alpha".equals(s.get("id").asText())) alpha = s;
                if (alpha != null && alpha.has("integrity") && "ready".equals(alpha.get("integrity").get("status").asText())) break;
                Thread.sleep(100);
            }
            assertNotNull(alpha);
            JsonNode i = alpha.get("integrity");
            assertEquals("ready", i.get("status").asText(), alpha.toString());
            assertEquals(1, i.get("broken").asInt(), i.toString());
            assertEquals(0, i.get("duplicates").asInt());
            assertEquals(0, i.get("changedOutside").asInt());

            HttpResponse<String> viewer = send(c, "GET", "/spaces", "viewer", null);
            assertEquals(200, viewer.statusCode(), viewer.body());
            for (JsonNode s : V1Body.of(viewer.body()))
                assertFalse(s.has("integrity"), "no count for a caller who may not read /integrity: " + s);
        }
    }
}

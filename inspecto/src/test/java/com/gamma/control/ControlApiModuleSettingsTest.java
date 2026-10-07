package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.gamma.access.Roles;
import com.gamma.access.ComponentAccess;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-1 P2b (D-MR10): the per-Space Enabled gate over real HTTP. The test class path carries one
 * discovered route module ({@link TestDiscoveredRoutes}, feature {@code testDiscovered}), which is what is switched
 * off. An Authenticator is ARMED, because with no Subject {@code withCapability} is a no-op and the 403 would never
 * be exercised.
 */
class ControlApiModuleSettingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "Bearer admin", OPS = "Bearer ops";
    private static final String FEATURE = TestDiscoveredRoutes.FEATURE;
    private static final String PING = TestDiscoveredRoutes.PATH;
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String who = ex.getRequestHeaders().getFirst("Authorization");
            String role = ADMIN.equals(who) ? "admin" : OPS.equals(who) ? "operations" : null;
            if (role == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(role);
            ComponentAccess.heldRoles(ex, java.util.Set.of(role));
            return Optional.of(new Subject(role + "-1", def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        Ctx c = new Ctx(spaces, api, api.port(), root);
        assertEquals(200, send(c, "POST", "/spaces", "{\"id\":\"acme\"}", ADMIN).statusCode());
        assertEquals(200, send(c, "POST", "/spaces", "{\"id\":\"beta\"}", ADMIN).statusCode());
        return c;
    }

    private Path file(Ctx c, String space) {
        return c.root.resolve(space).resolve("config").resolve("modules.toon");
    }

    @Test
    void getReportsTheInstalledFeaturesAndNothingDisabledByDefault(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            JsonNode v = data(send(c, "GET", "/spaces/acme/settings/modules", null, OPS), 200);
            assertEquals(0, v.get("disabled").size(), v.toString());
            assertEquals(0, v.get("inert").size());
            assertTrue(contains(v.get("installed"), FEATURE), v.toString());
            assertFalse(v.get("unreadable").asBoolean());
            assertFalse(Files.exists(file(c, "acme")), "an absent file means everything is enabled");
        }
    }

    @Test
    void putIsAdministratorOnlyAndValidatedFailClosed(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String off = "{\"disabled\":[\"" + FEATURE + "\"]}";
            assertEquals(403, send(c, "PUT", "/spaces/acme/settings/modules", off, OPS).statusCode());
            assertEquals(401, send(c, "PUT", "/spaces/acme/settings/modules", off, null).statusCode());
            assertFalse(Files.exists(file(c, "acme")), "a refused write leaves nothing behind");
            // an id no installed module declares, and a core feature, are not disableable
            assertEquals(422, send(c, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":[\"nope\"]}", ADMIN).statusCode());
            assertEquals(422, send(c, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":[\"authoring\"]}", ADMIN).statusCode());
            assertEquals(422, send(c, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":\"" + FEATURE + "\"}", ADMIN).statusCode());
            assertEquals(422, send(c, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":[1]}", ADMIN).statusCode());
            assertEquals(422, send(c, "PUT", "/spaces/acme/settings/modules", "{}", ADMIN).statusCode());
            assertEquals(422, send(c, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":[],\"enabled\":[]}", ADMIN).statusCode());
            assertFalse(Files.exists(file(c, "acme")));
            // happy path
            JsonNode put = data(send(c, "PUT", "/spaces/acme/settings/modules", off, ADMIN), 200);
            assertEquals(FEATURE, put.get("disabled").get(0).asText());
            assertTrue(Files.exists(file(c, "acme")));
            assertEquals(FEATURE, data(send(c, "GET", "/spaces/acme/settings/modules", null, OPS), 200).get("disabled").get(0).asText());
            // and back on
            assertEquals(0, data(send(c, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":[]}", ADMIN), 200).get("disabled").size());
        }
    }

    @Test
    void aDisabledModulesRouteAnswersModuleDisabledOnlyInThatSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c, "GET", "/spaces/acme" + PING, null, OPS).statusCode());
            data(send(c, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":[\"" + FEATURE + "\"]}", ADMIN), 200);

            HttpResponse<String> off = send(c, "GET", "/spaces/acme" + PING, null, OPS);
            assertEquals(404, off.statusCode(), off.body());
            assertEquals(ErrorCodes.MODULE_DISABLED, JSON.readTree(off.body()).at("/error/errorCode").asText(), off.body());
            // a Space that did not switch it off still has it, and so does the un-prefixed (default Space) path
            assertEquals(200, send(c, "GET", "/spaces/beta" + PING, null, OPS).statusCode());
            // a refused caller learns nothing about the Space: authentication comes first
            assertEquals(401, send(c, "GET", "/spaces/acme" + PING, null, null).statusCode());
            // the settings and topology routes are core: never disabled by their own gate
            assertEquals(200, send(c, "GET", "/spaces/acme/settings/modules", null, OPS).statusCode());
            assertEquals(200, send(c, "GET", "/spaces/acme/modules", null, OPS).statusCode());

            data(send(c, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":[]}", ADMIN), 200);
            assertEquals(200, send(c, "GET", "/spaces/acme" + PING, null, OPS).statusCode(), "re-enabled: reachable again");
        }
    }

    @Test
    void bootstrapFeaturesAndTheModulesReportFlipWithTheSpacesChoice(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertTrue(data(send(c, "GET", "/bootstrap", null, ADMIN), 200).at("/features/" + FEATURE).asBoolean());
            data(send(c, "PUT", "/settings/modules", "{\"disabled\":[\"" + FEATURE + "\"]}", ADMIN), 200);
            JsonNode b = data(send(c, "GET", "/bootstrap", null, ADMIN), 200);
            assertTrue(b.at("/features").has(FEATURE), "an installed-but-disabled feature stays listed, as false: " + b);
            assertFalse(b.at("/features/" + FEATURE).asBoolean(), b.toString());
            // the five legacy keys are derived from the same enabled set
            assertFalse(b.at("/features/ops").asBoolean());

            JsonNode mods = data(send(c, "GET", "/modules", null, ADMIN), 200).get("modules");
            for (JsonNode m : mods) assertTrue(m.has("enabledInSpace"), m.toString());
            data(send(c, "PUT", "/settings/modules", "{\"disabled\":[]}", ADMIN), 200);
            assertTrue(data(send(c, "GET", "/bootstrap", null, ADMIN), 200).at("/features/" + FEATURE).asBoolean());
        }
    }

    @Test
    void aStoredIdNoInstalledModuleDeclaresIsInertAndSurvivesASaveWithOtherKeys(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // a module that was removed after the Space chose to disable it, plus a key this code does not model
            Files.writeString(file(c, "acme"), "disabled[2]: removedModule,otherGone\nnote: keep me\n");
            JsonNode v = data(send(c, "GET", "/spaces/acme/settings/modules", null, OPS), 200);
            assertEquals(2, v.get("disabled").size(), v.toString());
            assertEquals(2, v.get("inert").size(), "stored ids with no installed module are reported inert");
            assertEquals(200, send(c, "GET", "/spaces/acme" + PING, null, OPS).statusCode(), "an inert id disables nothing");

            JsonNode put = data(send(c, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":[\"" + FEATURE + "\"]}", ADMIN), 200);
            assertTrue(contains(put.get("disabled"), FEATURE) && contains(put.get("disabled"), "removedModule")
                    && contains(put.get("disabled"), "otherGone"), "inert ids survive the save: " + put);
            // clearing the list clears the live choice but must still carry the inert ids and the unmodelled key
            JsonNode cleared = data(send(c, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":[]}", ADMIN), 200);
            assertEquals(2, cleared.get("disabled").size(), cleared.toString());
            String onDisk = Files.readString(file(c, "acme"));
            assertTrue(onDisk.contains("removedModule") && onDisk.contains("otherGone"), onDisk);
            assertTrue(onDisk.contains("note") && onDisk.contains("keep me"), "an unmodelled key must survive a save: " + onDisk);
            assertEquals(200, send(c, "GET", "/spaces/acme" + PING, null, OPS).statusCode());
        }
    }

    @Test
    void anUnreadableFileDisablesNothingIsReportedAndRefusesASave(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Files.writeString(file(c, "acme"), "disabled[3]: only,two\n");   // length mismatch: strict TOON refuses it
            JsonNode v = data(send(c, "GET", "/spaces/acme/settings/modules", null, OPS), 200);
            assertTrue(v.get("unreadable").asBoolean(), v.toString());
            assertEquals(200, send(c, "GET", "/spaces/acme" + PING, null, OPS).statusCode());
            assertEquals(422, send(c, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":[]}", ADMIN).statusCode(),
                    "a save over a file we cannot read would drop what it holds");
        }
    }

    private static boolean contains(JsonNode arr, String s) {
        for (JsonNode n : arr) if (s.equals(n.asText())) return true;
        return false;
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }
}

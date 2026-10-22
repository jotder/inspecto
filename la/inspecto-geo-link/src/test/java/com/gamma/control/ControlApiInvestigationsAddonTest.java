package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.etl.EditionFeatures;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LA-ONBOARD-1} OB-5 / OB-10: Investigations is a billable add-on enforced at RUNTIME, held by default wherever the
 * edition includes it. A <b>Standard</b> install (the classpath's {@link InvestigationsEditionForTests} stand-in for
 * {@code inspecto-ops}, a signed-in caller) WITHOUT the add-on ({@code -Daddons.laInvestigations=false}) keeps the explorer,
 * answers every Investigation route {@code 503 CAPABILITY_UNAVAILABLE} naming the add-on, and {@code /bootstrap} reports
 * {@code laLevel: explorer} with a {@code featureReasons.investigations} line; WITH it (the default) the same routes answer and
 * the level is {@code investigations}. Basic never has it, whatever the setting says. Over real HTTP.
 */
class ControlApiInvestigationsAddonTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "Bearer admin";
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void restore() {
        System.clearProperty(EditionFeatures.LA_INVESTIGATIONS_SETTING);
        System.clearProperty("auth.mode");
        EditionFeatures.overrideForTest(null);
        Authenticators.forTest(null);
    }

    private interface Body {
        void run(int port) throws Exception;
    }

    /** A Standard host: {@code auth.mode=oidc}, an Authenticator granting {@code admin} to {@link #ADMIN}, one Space {@code s}. */
    private void standard(Path root, Body body) throws Exception {
        System.setProperty("auth.mode", "oidc");
        Authenticators.forTest(ex -> {
            if (!ADMIN.equals(ex.getRequestHeaders().getFirst("Authorization"))) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get("admin");
            ComponentAccess.heldRoles(ex, Set.of("admin"));
            return Optional.of(new Subject("admin-1", def.capabilities(), def.dataScopes()));
        });
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            assertEquals(200, send(api.port(), "POST", "/spaces", "{\"id\":\"s\"}").statusCode());
            body.run(api.port());
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @Test
    void standardWithoutTheAddonRefusesInvestigationsNamingTheAddonAndKeepsTheExplorer(@TempDir Path root) throws Exception {
        System.setProperty(EditionFeatures.LA_INVESTIGATIONS_SETTING, "false");
        standard(root, port -> {
            for (String path : new String[]{"/inv/investigations", "/inv/investigation-templates", "/inv/graph/algorithms"}) {
                HttpResponse<String> res = send(port, "GET", "/spaces/s" + path, null);
                JsonNode err = JSON.readTree(res.body()).at("/error");
                assertEquals(503, res.statusCode(), path + " -> " + res.body());
                assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, err.at("/errorCode").asText(), res.body());
                String msg = err.at("/message").asText();
                assertTrue(msg.contains("Investigations add-on") && msg.contains(EditionFeatures.LA_INVESTIGATIONS_ORDER_CODE),
                        "the refusal names the add-on and its Order Code: " + msg);
                assertFalse(msg.contains("this build (Basic)"), "a Standard install is not told it is Basic: " + msg);
            }
            HttpResponse<String> explorer = send(port, "GET", "/spaces/s/inv/entity-types", null);
            assertEquals(200, explorer.statusCode(), "the explorer stays up without the add-on: " + explorer.body());

            JsonNode boot = JSON.readTree(send(port, "GET", "/bootstrap", null).body()).at("/data");
            assertEquals("standard", boot.at("/edition").asText(), "the edition is unchanged - DT-11: " + boot);
            assertEquals("explorer", boot.at("/laLevel").asText(), boot.toString());
            assertFalse(boot.at("/features/investigations").asBoolean(true), boot.toString());
            assertEquals("Needs the Investigations add-on, which this installation does not hold.",
                    boot.at("/featureReasons/investigations").asText(), boot.toString());
            assertFalse(boot.at("/featureReasons").has("geoMap"), "the Geo map is not the add-on's: " + boot);
        });
    }

    @Test
    void standardHoldsTheAddonByDefault(@TempDir Path root) throws Exception {
        standard(root, port -> {
            HttpResponse<String> list = send(port, "GET", "/spaces/s/inv/investigations", null);
            assertEquals(200, list.statusCode(), "Standard holds the add-on unless the install removes it: " + list.body());

            JsonNode boot = JSON.readTree(send(port, "GET", "/bootstrap", null).body()).at("/data");
            assertEquals("investigations", boot.at("/laLevel").asText(), boot.toString());
            assertTrue(boot.at("/features/investigations").asBoolean(), boot.toString());
            assertTrue(boot.at("/featureReasons").isObject(), "the map is always there: " + boot);
            assertEquals(0, boot.at("/featureReasons").size(), "nothing is withheld: " + boot);
        });
    }

    @Test
    void basicNeverHasTheAddonWhateverTheSettingSays(@TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());   // Basic: no module declares any edition feature
        System.setProperty(EditionFeatures.LA_INVESTIGATIONS_SETTING, "true");
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"s\"}").statusCode());
            HttpResponse<String> res = send(port, "GET", "/spaces/s/inv/investigations", null);
            assertEquals(503, res.statusCode(), res.body());
            String msg = JSON.readTree(res.body()).at("/error/message").asText();
            assertTrue(msg.contains("available in Standard and above, with the Investigations add-on"), msg);

            JsonNode boot = JSON.readTree(send(port, "GET", "/bootstrap", null).body()).at("/data");
            assertEquals("basic", boot.at("/edition").asText(), boot.toString());
            assertEquals("explorer", boot.at("/laLevel").asText(), boot.toString());
            assertEquals("Investigations are in Standard and above, with the Investigations add-on.",
                    boot.at("/featureReasons/investigations").asText(), boot.toString());
            assertEquals("The Geo map is in Standard and above.", boot.at("/featureReasons/geoMap").asText(), boot.toString());
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", ADMIN);
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }
}

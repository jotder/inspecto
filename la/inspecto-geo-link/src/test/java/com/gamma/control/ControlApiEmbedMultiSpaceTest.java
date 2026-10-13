package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticators;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The public embed view of a deployment with MORE THAN ONE Space: the token names no Space, so the route finds the grant's Space itself.
 * The probe is minting in the SECOND Space - the case that answered 404 while the route read only the default Space's store.
 */
class ControlApiEmbedMultiSpaceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String L = "Bearer lead";
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void reset() {
        CaseTeamObjectEngine.CASES = null;
        Authenticators.forTest(null);
        System.clearProperty("la.embed.enabled");
    }

    private HttpResponse<String> send(int port, String method, String path, String body, boolean auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).header("Content-Type", "application/json");
        if (auth) b.header("Authorization", L);
        return client.send("GET".equals(method) ? b.GET().build() : b.method(method, BodyPublishers.ofString(body == null ? "" : body)).build(),
                BodyHandlers.ofString());
    }

    private JsonNode ok(int port, String method, String path, String body) throws Exception {
        HttpResponse<String> r = send(port, method, path, body, true);
        assertEquals(200, r.statusCode(), method + " " + path + " -> " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static String refusal(HttpResponse<String> r) throws Exception {
        JsonNode e = JSON.readTree(r.body()).path("error");
        return r.statusCode() + " " + e.path("errorCode").asText() + " " + e.path("message").asText();
    }

    private static void seed(SpaceManager spaces, String space) throws Exception {
        Path cfg = spaces.space(com.gamma.service.SpaceId.of(space)).orElseThrow().root().config();
        new ViewStore(cfg.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                "SELECT caller, callee, channel FROM (VALUES ('a','b','voice'),('a','c','sms')) AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
        new ComponentStore(cfg.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
    }

    @Test
    void aGrantMintedInTheSecondSpaceIsViewableAndEveryRefusalIsTheSame404(@TempDir Path root) throws Exception {
        System.setProperty("la.embed.enabled", "true");
        Authenticators.forTest(ex -> "Bearer lead".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("lead-1", Set.of("canManageIncidents", "canShareInvestigation", "canAdminister"))) : Optional.empty());
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            ok(port, "POST", "/spaces", "{\"id\":\"one\"}");
            ok(port, "POST", "/spaces", "{\"id\":\"two\"}");
            seed(spaces, "two");
            String inv = "/spaces/two/inv/investigations";
            ok(port, "POST", inv, "{\"id\":\"inv-a\",\"purpose\":\"p\",\"title\":\"t\",\"dataset\":\"calls_ds\","
                    + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}");
            ok(port, "POST", inv + "/inv-a/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}");
            String token = ok(port, "POST", inv + "/inv-a/embeds", "{}").get("token").asText();

            HttpResponse<String> good = send(port, "GET", "/public/investigations/" + token, null, false);
            assertEquals(200, good.statusCode(), "the second Space's grant must resolve: " + good.body());
            assertEquals("inv-a", JSON.readTree(good.body()).at("/data/investigation/id").asText());

            String unknown = refusal(send(port, "GET", "/public/investigations/unknown", null, false));
            assertEquals(404, send(port, "GET", "/public/investigations/unknown", null, false).statusCode());
            assertEquals(unknown, refusal(send(port, "GET", "/public/investigations/" + token + "x", null, false)), "mangled");
            assertEquals(unknown, refusal(send(port, "GET", "/public/investigations/" + token.substring(0, token.indexOf('.')) + ".zzzz", null, false)), "right id, wrong secret");

            // the grant's Space switches the module off: the same uniform 404, even though another Space is untouched
            ok(port, "PUT", "/spaces/two/settings/modules", "{\"disabled\":[\"geoLink\"]}");
            assertEquals(unknown, refusal(send(port, "GET", "/public/investigations/" + token, null, false)), "module off in the grant's Space");
            ok(port, "PUT", "/spaces/two/settings/modules", "{\"disabled\":[]}");
            assertEquals(200, send(port, "GET", "/public/investigations/" + token, null, false).statusCode());

            ok(port, "POST", inv + "/inv-a/embeds/" + token.substring(0, token.indexOf('.')) + "/revoke", "{}");
            assertEquals(unknown, refusal(send(port, "GET", "/public/investigations/" + token, null, false)), "revoked");
        } finally {
            api.close();
            spaces.close();
        }
    }
}

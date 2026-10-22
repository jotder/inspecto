package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The masking default follows SIGN-IN, not the Link Analysis level ({@code LA-ONBOARD-1} OB-6, operator answer A-16): with
 * <b>no sign-in</b> ({@code auth.mode} none, no Authenticator) a Space that states no {@code masking_mode} masks nothing - the
 * projection answers raw ids and {@code GET /settings/link-analysis} says {@code maskingModeInForce: none} - even where the
 * Investigations level is present; where people <b>sign in</b> the shipped {@code typed} holds, WITH or WITHOUT the
 * Investigations add-on. A mode the Space STATES wins either way. Over real HTTP; the bound column is classified MSISDN, an
 * Entity Type {@code typed} masks.
 *
 * <p>Sign-in is {@code -Dauth.mode=oidc}; the add-on is removed with {@code -Daddons.laInvestigations=false}; Basic is the
 * edition features pinned empty; otherwise the classpath's {@link InvestigationsEditionForTests} declares Investigations.
 */
class ControlApiMaskingDefaultBySignInTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001", B = "27820000002";
    private static final String PROJECTION = "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void restore() {
        EditionFeatures.overrideForTest(null);
        System.clearProperty("auth.mode");
        System.clearProperty(EditionFeatures.LA_INVESTIGATIONS_SETTING);
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee FROM (VALUES ('" + A + "','" + B + "')) AS t(caller,callee)", "2026-10-21T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view",
                    "columns", List.of(Map.of("name", "caller", "classification", "MSISDN"))));
            return new Ctx(svc, api, writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private JsonNode data(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.api().port() + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = body == null ? b.GET() : b.method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = client.send(b.build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), path + " -> " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private void assertUnmasked(Ctx c) throws Exception {
        assertEquals("none", data(c, "GET", "/settings/link-analysis", null).get("maskingModeInForce").asText());
        JsonNode proj = data(c, "POST", "/inv/projection", PROJECTION);
        assertEquals("none", proj.at("/masking/mode").asText());
        assertFalse(proj.at("/masking/masked").asBoolean());
        assertEquals(A, proj.get("rows").get(0).get("source").asText(), "no sign-in answers the raw id");
    }

    private void assertTypedMasked(Ctx c) throws Exception {
        assertEquals("typed", data(c, "GET", "/settings/link-analysis", null).get("maskingModeInForce").asText());
        JsonNode proj = data(c, "POST", "/inv/projection", PROJECTION);
        assertEquals("typed", proj.at("/masking/mode").asText());
        assertTrue(proj.get("rows").get(0).get("source").asText().startsWith("masked:"), proj.toString());
        assertFalse(proj.toString().contains(A), "a sign-in install never answers the typed raw id: " + proj);
    }

    @Test
    void noSignInOnBasicMasksNothingByDefault(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root)) {
            assertUnmasked(c);
        }
    }

    @Test
    void noSignInMasksNothingByDefaultEvenAtTheInvestigationsLevel(@TempDir Path cfg, @TempDir Path root) throws Exception {
        assertTrue(EditionFeatures.present(EditionFeatures.INVESTIGATIONS), "the classpath declares Investigations");
        try (Ctx c = open(cfg, root)) {
            assertUnmasked(c);
        }
    }

    @Test
    void noSignInKeepsAModeTheSpaceStates(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root)) {
            Files.writeString(c.root().resolve("link-analysis.toon"), "masking_mode: typed\n");
            assertEquals("typed", data(c, "GET", "/settings/link-analysis", null).get("maskingModeInForce").asText());
            JsonNode proj = data(c, "POST", "/inv/projection", PROJECTION);
            assertTrue(proj.get("rows").get(0).get("source").asText().startsWith("masked:"), proj.toString());
        }
    }

    @Test
    void signInWithTheInvestigationsAddonMasksTypedByDefault(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("auth.mode", "oidc");
        try (Ctx c = open(cfg, root)) {
            assertTypedMasked(c);
        }
    }

    @Test
    void signInWithoutTheInvestigationsAddonStaysMasked(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("auth.mode", "oidc");
        System.setProperty(EditionFeatures.LA_INVESTIGATIONS_SETTING, "false");
        assertFalse(EditionFeatures.present(EditionFeatures.INVESTIGATIONS), "the add-on is removed: the Explorer level");
        try (Ctx c = open(cfg, root)) {
            assertTypedMasked(c);
        }
    }

    @Test
    void signInKeepsAModeTheSpaceStates(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("auth.mode", "oidc");
        try (Ctx c = open(cfg, root)) {
            Files.writeString(c.root().resolve("link-analysis.toon"), "masking_mode: none\n");
            assertUnmasked(c);
        }
    }
}

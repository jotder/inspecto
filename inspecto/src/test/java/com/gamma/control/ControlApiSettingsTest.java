package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SettingsRoutes} per-space branding, geo, Link Analysis caps and icon map over real HTTP: defaults before any save, a PUT round-trip that
 * persists {@code branding.toon} in the space's config tree, blank-folds-to-null, per-space isolation via the
 * {@code /spaces/{id}/settings/branding} seam, and the over-large-logo 422 guard. Drives a discover-mode
 * ControlApi so each space has a real (writable) config root.
 */
class ControlApiSettingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port(), root);
    }

    @Test
    void brandingRoundTripsAndIsolatesPerSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // defaults before any save — all fields null (client falls back to the shipped defaults)
            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/branding", null));
            assertTrue(def.get("logoDataUrl").isNull() && def.get("caption").isNull() && def.get("footerText").isNull());

            // PUT round-trip; blank footer folds to null
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/branding",
                    "{\"logoDataUrl\":\"data:image/png;base64,AAAA\",\"caption\":\"Chase the anomaly\",\"footerText\":\"  \"}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("Chase the anomaly", json(put).get("caption").asText());
            assertTrue(json(put).get("footerText").isNull(), "blank folds to null");

            // persisted on disk in the space's config tree (not a *_pipeline.toon suffix, so config discovery ignores it)
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("branding.toon")));
            JsonNode got = json(send(c.port, "GET", "/spaces/acme/settings/branding", null));
            assertEquals("data:image/png;base64,AAAA", got.get("logoDataUrl").asText());
            assertEquals("Chase the anomaly", got.get("caption").asText());

            // per-space isolation: 'beta' is untouched
            assertTrue(json(send(c.port, "GET", "/spaces/beta/settings/branding", null)).get("caption").isNull());

            // over-large logo → 422
            String bigLogo = "\"logoDataUrl\":\"" + "x".repeat(600 * 1024) + "\"";
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/branding", "{" + bigLogo + "}").statusCode());
        }
    }

    @Test
    void geoSettingsRoundTripAndIsolatePerSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // default before any save — null (no self-hosted tile server)
            assertTrue(json(send(c.port, "GET", "/spaces/acme/settings/geo", null)).get("tileServerUrl").isNull());

            // PUT round-trip, persisted as geo.toon in the space's config tree
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/geo",
                    "{\"tileServerUrl\":\"http://tiles.example/{z}/{x}/{y}.png\"}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("http://tiles.example/{z}/{x}/{y}.png", json(put).get("tileServerUrl").asText());
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("geo.toon")));
            assertEquals("http://tiles.example/{z}/{x}/{y}.png",
                    json(send(c.port, "GET", "/spaces/acme/settings/geo", null)).get("tileServerUrl").asText());

            // per-space isolation + blank-folds-to-null on save
            assertTrue(json(send(c.port, "GET", "/spaces/beta/settings/geo", null)).get("tileServerUrl").isNull());
            assertTrue(json(send(c.port, "PUT", "/spaces/acme/settings/geo", "{\"tileServerUrl\":\"  \"}"))
                    .get("tileServerUrl").isNull(), "blank folds to null");
        }
    }

    @Test
    void iconMapRoundTripsAndIsolatesPerSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // default before any save — empty map
            assertEquals(0, json(send(c.port, "GET", "/spaces/acme/config/icon-map", null)).size(), "no rules yet");

            // PUT round-trip: a type entry + a category entry, persisted as icon-map.toon
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/config/icon-map",
                    "{\"parser.dsv\":{\"glyph\":\"table\",\"color\":\"#00aaff\"},\"PARSE\":{\"glyph\":\"filter\",\"color\":\"#ff8800\"}}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("table", json(put).get("parser.dsv").get("glyph").asText());
            assertEquals("#ff8800", json(put).get("PARSE").get("color").asText());
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("icon-map.toon")));

            JsonNode got = json(send(c.port, "GET", "/spaces/acme/config/icon-map", null));
            assertEquals("filter", got.get("PARSE").get("glyph").asText());
            assertEquals("#00aaff", got.get("parser.dsv").get("color").asText());

            // per-space isolation: 'beta' is untouched
            assertEquals(0, json(send(c.port, "GET", "/spaces/beta/config/icon-map", null)).size());

            // a malformed entry (missing color) → 422
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/config/icon-map",
                    "{\"parser.json\":{\"glyph\":\"braces\"}}").statusCode());
        }
    }

    @Test
    void linkAnalysisCapsRoundTripAndRefuseBadValues(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // absent document reads as both-null (inherit the shipped defaults), not a failure
            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null));
            assertTrue(def.get("projectionNodeCap").isNull() && def.get("analysisNodeCap").isNull()
                            && def.get("suspicionNodeCap").isNull(),
                    "no document yet ⇒ inherit all three");

            // PUT round-trip of both values, persisted as link-analysis.toon in the space's config tree
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"projectionNodeCap\":1200,\"analysisNodeCap\":4000,\"suspicionNodeCap\":800}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals(1200, json(put).get("projectionNodeCap").asInt());
            assertEquals(4000, json(put).get("analysisNodeCap").asInt());
            // Suspicion score's ceiling is INDEPENDENT of the shared one: its cost is quadratic while the
            // other 26 algorithms are trivial at the shared cap, so it must be settable lower (D-S3).
            assertEquals(800, json(put).get("suspicionNodeCap").asInt());
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("link-analysis.toon")));
            JsonNode got = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null));
            assertEquals(1200, got.get("projectionNodeCap").asInt());
            assertEquals(4000, got.get("analysisNodeCap").asInt());
            assertEquals(800, got.get("suspicionNodeCap").asInt(), "the third cap survives the round trip");

            // per-space isolation: 'beta' still inherits
            assertTrue(json(send(c.port, "GET", "/spaces/beta/settings/link-analysis", null))
                    .get("projectionNodeCap").isNull());

            // null round-trips as null — an omitted/null cap means "inherit", never "unbounded"
            HttpResponse<String> cleared = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"projectionNodeCap\":null,\"analysisNodeCap\":900}");
            assertEquals(200, cleared.statusCode(), cleared.body());
            assertTrue(json(cleared).get("projectionNodeCap").isNull(), "null ⇒ inherit the shipped default");
            assertEquals(900, json(cleared).get("analysisNodeCap").asInt());
            assertTrue(json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null))
                    .get("projectionNodeCap").isNull(), "cleared on disk too");

            // fail closed, never a silent clamp: 0, a non-integer, and above the sanity ceiling are all 422
            HttpResponse<String> zero = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"projectionNodeCap\":0}");
            assertEquals(422, zero.statusCode(), zero.body());
            assertTrue(zero.body().contains("projectionNodeCap") && zero.body().contains("1..100000"),
                    "the 422 names the field and the allowed range: " + zero.body());

            HttpResponse<String> nan = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"analysisNodeCap\":\"lots\"}");
            assertEquals(422, nan.statusCode(), nan.body());
            assertTrue(nan.body().contains("analysisNodeCap"), nan.body());

            HttpResponse<String> huge = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"analysisNodeCap\":100001}");
            assertEquals(422, huge.statusCode(), huge.body());
            assertTrue(huge.body().contains("analysisNodeCap") && huge.body().contains("1..100000"), huge.body());

            HttpResponse<String> badSuspicion = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"suspicionNodeCap\":0}");
            assertEquals(422, badSuspicion.statusCode(), badSuspicion.body());
            assertTrue(badSuspicion.body().contains("suspicionNodeCap")
                            && badSuspicion.body().contains("1..100000"),
                    "the third cap is refused by name and range like the other two: " + badSuspicion.body());

            // a refused write left the last good values standing
            assertEquals(900, json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null))
                    .get("analysisNodeCap").asInt());
        }
    }

    /**
     * The capability gate on the settings writes, exercised with an ARMED Authenticator.
     *
     * 🔴 <b>Why this exists.</b> `withCapability` is a NO-OP unless a {@link Subject} is attached, and no test
     * attaches one unless it installs a fake authenticator. Every other test in this class runs
     * Personal-shaped, so they pass identically whether or not these routes are gated at all — adding or
     * removing the gate would change no assertion here. `tools/check-authgate-coverage.mjs` flagged
     * `PUT /settings/link-analysis` for exactly that on 2026-09-22, taking the ratchet from 79 to 80.
     *
     * ⚠ Three statuses on purpose: 401 is authentication, <b>403 is the GATE</b> — a present Subject LACKING
     * the capability — and only the 403 distinguishes a gated route from one that merely needs a login.
     */
    @Test
    void settingsWritesRequireCanAuthorWorkbench(@TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> "Bearer valid".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("jdoe", Set.of("canAuthorWorkbench")))
                : "Bearer plain".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("nobody", Set.of()))
                : Optional.empty());
        try (Ctx c = open(root)) {
            // ⚠ The setup call needs a credential too. Installing a fake Authenticator makes EVERY route
            // outside ControlApi.PUBLIC_PATHS demand one, and /spaces is not in that set — so a credential-less
            // setup call 401s before the test reaches what it means to assert. (Being exempt from the
            // canAdminister CAPABILITY while no space exists is a different gate, and does not waive AuthN.)
            assertEquals(200, sendAs(c.port, "POST", "/spaces", "{\"id\":\"acme\"}", "Bearer valid").statusCode());
            String body = "{\"projectionNodeCap\":600}";

            assertEquals(401, send(c.port, "PUT", "/spaces/acme/settings/link-analysis", body).statusCode(),
                    "no credential is a clean 401, never a 500");

            HttpResponse<String> denied = sendAs(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    body, "Bearer plain");
            assertEquals(403, denied.statusCode(), "a Subject WITHOUT the capability is refused: " + denied.body());
            assertTrue(denied.body().contains("canAuthorWorkbench"), "the refusal names the capability");

            assertEquals(200, sendAs(c.port, "GET", "/spaces/acme/settings/link-analysis", null, "Bearer plain")
                    .statusCode(), "reads stay open by policy: the same capability-less Subject may READ");

            HttpResponse<String> allowed = sendAs(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    body, "Bearer valid");
            assertEquals(200, allowed.statusCode(), "a Subject WITH the capability is allowed: " + allowed.body());
        } finally {
            Authenticators.forTest(null);
        }
    }

    private HttpResponse<String> sendAs(int port, String method, String path, String body, String auth)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return V1Body.of(r.body()); }
}

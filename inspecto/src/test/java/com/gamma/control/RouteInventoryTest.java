package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The route inventory the control publishes about itself (route-gating plan steps 4c/4d).
 *
 * <p>⚠ The inventory is the RUNTIME table, from the router — not a source scan. That distinction is the
 * point: the test classpath does not carry the optional modules, so a scan and this table answer different
 * questions, and the evidence report needs both.
 */
class RouteInventoryTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private JsonNode get(int port, String path) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        // the v1 envelope wraps every body in `data`
        return M.readTree(r.body()).path("data");
    }

    @Test
    void everyRouteReportsThePostureItDeclared(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode body = get(c.port, "/audit/route-inventory");
            JsonNode counts = body.path("counts");

            assertTrue(counts.path("total").asInt() > 100, "the real surface, not a stub: " + counts);
            assertTrue(counts.path("gated").asInt() > 0);
            assertTrue(counts.path("exempt").asInt() > 0);
            assertEquals(counts.path("total").asInt(),
                    counts.path("gated").asInt() + counts.path("exempt").asInt() + counts.path("openRead").asInt(),
                    "every route falls in exactly one posture - a fourth state is the hole this control closed");

            // a known gated route and a known exemption both report correctly
            assertEquals("canManageIncidents", posture(body, "POST", "/recon/promote").path("capability").asText());
            assertEquals("read-shaped",
                    posture(body, "POST", "/queries/([^/]+)/run").path("exemptionCategory").asText());
        }
    }

    /**
     * ⛔ Every MUTATING route must be gated or exempt. An {@code open-read} posture on a POST/PUT/PATCH/DELETE
     * would mean the boot check let one through — the one thing this control exists to prevent.
     */
    @Test
    void noMutatingRouteIsUndeclared(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (JsonNode r : get(c.port, "/audit/route-inventory").path("routes")) {
                String method = r.path("method").asText();
                if (List.of("POST", "PUT", "PATCH", "DELETE").contains(method))
                    assertNotEquals("open-read", r.path("posture").asText(),
                            "undeclared mutating route reached the inventory: " + method + " " + r.path("pattern"));
            }
        }
    }

    /** The digest is over the SORTED table, so it is a property of the surface, not of module load order. */
    @Test
    void theDigestIsStableAcrossBoots(@TempDir Path dir, @TempDir Path other) throws Exception {
        String first, second;
        try (Ctx c = open(dir)) { first = get(c.port, "/audit/route-inventory").path("digest").asText(); }
        try (Ctx c = open(other)) { second = get(c.port, "/audit/route-inventory").path("digest").asText(); }

        assertFalse(first.isBlank());
        assertEquals(first, second, "two boots of the same build must agree, or the digest signals noise");
    }

    private static JsonNode posture(JsonNode body, String method, String pattern) {
        for (JsonNode r : body.path("routes"))
            if (method.equals(r.path("method").asText()) && pattern.equals(r.path("pattern").asText())) return r;
        return fail("no inventory row for " + method + " " + pattern);
    }
}

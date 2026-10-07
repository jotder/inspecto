package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /access/policies/preview} over real HTTP (policy-authoring-ux-design.md S4, D6/D7): the
 * draft's impact as a matrix of seeded/authored roles × {read, write, operate} (and × every known
 * resource kind a current or draft policy targets), each cell {@code before → after}. It is the only
 * check that catches a policy that is valid and well-typed but aimed at the wrong people. Gated
 * {@code canConfigureAccess}; every gate exercised WITH a Subject (a Subject-less test makes
 * {@code withCapability} a no-op).
 */
class ControlApiPolicyPreviewTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer admin} holds canConfigureAccess; anyone else holds nothing. */
    private static final Authenticator AUTH = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        String id = auth.substring(7);
        return Optional.of(new Subject(id, "admin".equals(id) ? Set.of("canConfigureAccess") : Set.of(), null, Map.of()));
    };

    private record Ctx(CollectorService svc, ControlApi api, int port, Path writeRoot) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("cfg"));
        System.setProperty("assist.write.root", writeRoot.toString());
        Authenticators.forTest(AUTH);
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), writeRoot);
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty("assist.write.root");
    }

    @Test
    void aSubjectWithoutCanConfigureAccessIsRefused(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = send(c.port, "{\"policies\":[]}", "ana");
            assertEquals(403, r.statusCode(), r.body());
            assertTrue(r.body().contains("canConfigureAccess"), r.body());
            assertEquals(401, send(c.port, "{\"policies\":[]}", null).statusCode());
        }
    }

    @Test
    void theMatrixShowsWhichRoleAndActionCellsTheDraftFlips(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode m = data(send(c.port, """
                    {"policies":[{"name":"freeze-ops-writes","effect":"deny","target":{"actions":["write"]},
                      "when":"subject.roles contains 'operations'"}]}""", "admin"));
            assertTrue(m.get("enabled").asBoolean());
            JsonNode ops = cell(m, "operations", "write", null);
            assertEquals("ABSTAIN", ops.get("before").asText());
            assertEquals("DENY", ops.get("after").asText());
            assertTrue(ops.get("changed").asBoolean());
            assertEquals("freeze-ops-writes", ops.get("afterPolicy").asText());
            assertFalse(cell(m, "operations", "read", null).get("changed").asBoolean(), "the target keeps reads");
            assertFalse(cell(m, "admin", "write", null).get("changed").asBoolean(), "admin does not hold 'operations'");
            assertNull(cell(m, "operations", "write", "incident"), "no resourceKinds target → no row-level columns");
            assertEquals("[\"read\",\"write\",\"operate\"]", m.get("actions").toString());
            assertTrue(m.get("roles").toString().contains("\"operations\""), "the seeded roles are the rows: " + m.get("roles"));

            // nothing was saved — a preview is not a write
            assertFalse(Files.exists(c.writeRoot().resolve("access-policies.toon")));
        }
    }

    @Test
    void aRowLevelDraftAddsTheTargetedKindAndBeforeReflectsTheSavedDoc(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, sendPut(c.port, """
                    {"policies":[{"name":"ops-no-incidents","effect":"deny","target":{"resourceKinds":["incident"]},
                      "when":"subject.roles contains 'operations'"}]}""").statusCode());
            // the draft drops the saved policy: before=DENY, after=ABSTAIN for operations on incident rows
            JsonNode m = data(send(c.port, "{\"policies\":[]}", "admin"));
            JsonNode row = cell(m, "operations", "read", "incident");
            assertNotNull(row, "the kind a current policy targets gets its own column: " + m.get("kinds"));
            assertEquals("DENY", row.get("before").asText());
            assertEquals("ABSTAIN", row.get("after").asText());
            assertTrue(row.get("changed").asBoolean());
            assertFalse(cell(m, "business", "read", "incident").get("changed").asBoolean());
        }
    }

    @Test
    void anInvalidDraftIs422AndTheDraftsWarningsRideTheResult(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> bad = send(c.port,
                    "{\"policies\":[{\"name\":\"p\",\"effect\":\"deny\",\"wen\":\"subject.id == 'x'\"}]}", "admin");
            assertEquals(422, bad.statusCode(), bad.body());
            JsonNode m = data(send(c.port, """
                    {"policies":[{"name":"typo","effect":"deny","target":{"actions":["write"]},
                      "when":"subject.roles contains 'operatons'"}]}""", "admin"));
            assertEquals("unknown-role", m.get("warnings").get(0).get("code").asText());
        }
    }

    private static JsonNode cell(JsonNode m, String role, String action, String kind) {
        for (JsonNode c : m.get("cells")) {
            JsonNode k = c.get("resourceKind");
            boolean kindMatches = kind == null ? (k == null || k.isNull()) : (k != null && kind.equals(k.asText()));
            if (role.equals(c.get("role").asText()) && action.equals(c.get("action").asText()) && kindMatches) return c;
        }
        return null;
    }

    private JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;
    }

    private HttpResponse<String> sendPut(int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/access/policies"))
                .header("Authorization", "Bearer admin").header("Content-Type", "application/json")
                .PUT(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> send(int port, String body, String subject) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/access/policies/preview"))
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body));
        if (subject != null) b.header("Authorization", "Bearer " + subject);
        return client.send(b.build(), BodyHandlers.ofString());
    }
}

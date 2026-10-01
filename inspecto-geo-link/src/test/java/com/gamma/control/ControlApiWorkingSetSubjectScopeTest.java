package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-4 measurement (Decision 5, cache scope): the Working Set a Subject reads is a pure function of the sealed log
 * ({@code WorkingSetRoutes.relation} keys its cache on dir + sha256(log) and evaluates nothing from the Dataset), so two
 * armed Subjects reading one Investigation at one log position get the same rows and the second is a cache hit. Access
 * is decided per request BEFORE the cache (the Dataset view gate), so a warm cache never answers for a Subject who has
 * lost the Dataset. Armed Authenticator throughout: with no Subject the gates are no-ops and nothing is proven.
 */
class ControlApiWorkingSetSubjectScopeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String OWNER = "Bearer owner", MEMBER = "Bearer member";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        CaseTeamObjectEngine.CASES = null;
        Authenticators.forTest(null);
        AccessDeciders.forTest(null);
    }

    private static void subjects() {
        Set<String> all = Set.of("canManageIncidents", "canAuthorAlertRules");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", all));
            case MEMBER -> Optional.of(new Subject("analyst-2", all));   // the Case's assignee
            default -> Optional.empty();
        });
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('a','b','voice'),('a','c','sms'),('b','d','voice'))"
                            + " AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, auth);
        assertEquals(200, r.statusCode(), method + " " + path + " → " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private int status(Ctx c, String path, String auth) throws Exception {
        return send(c, "GET", path, null, auth).statusCode();
    }

    /** An Investigation owned by analyst-1, one sealed expand, linked to a Case whose assignee is analyst-2. */
    private void sharedInvestigation(Ctx c, String maskingMode, Path root) throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: " + maskingMode + "\n");
        ok(c, "POST", "/inv/investigations", CREATE, OWNER);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}", OWNER);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", OWNER);
        ok(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", OWNER);
    }

    @Test
    void twoSubjectsAtOneLogPositionGetIdenticalRowsFromOneCacheEntry(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        CaseTeamObjectEngine.arm().put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", false));
        try (Ctx c = open(cfg, root)) {
            sharedInvestigation(c, "none", root);
            for (String of : List.of("entities", "links")) {
                JsonNode owner = ok(c, "GET", INV + "/working-set?of=" + of, null, OWNER);
                JsonNode member = ok(c, "GET", INV + "/working-set?of=" + of, null, MEMBER);
                assertTrue(owner.get("total").asInt() > 0, of);
                assertEquals(owner.get("rows").toString(), member.get("rows").toString(), "rows differ for " + of);
                assertEquals(owner.get("key").asText(), member.get("key").asText(), "key differs for " + of);
                assertTrue(member.get("cached").asBoolean(), "the second Subject is served from the first one's entry (" + of + ")");
            }
        }
    }

    @Test
    void maskedTokensBelongToTheInvestigationNotTheSubjectAndAWarmCacheNeverOutlivesTheDatasetGate(
            @TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        CaseTeamObjectEngine.arm().put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", false));
        try (Ctx c = open(cfg, root)) {
            sharedInvestigation(c, "all", root);
            JsonNode owner = ok(c, "GET", INV + "/working-set?of=entities", null, OWNER);
            JsonNode member = ok(c, "GET", INV + "/working-set?of=entities", null, MEMBER);
            assertFalse(owner.toString().contains("\"entityId\":\"a\""), "masked: " + owner);
            assertEquals(owner.get("rows").toString(), member.get("rows").toString(), "same masked tokens for both Subjects");

            // The Dataset is now restricted to its owner: the cache is warm, yet the member is refused (the gate runs first).
            new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds",
                    Map.of("view", "calls_view", "owner", "analyst-1", "shares", List.of()));
            assertEquals(404, status(c, INV + "/working-set?of=entities", MEMBER));
            assertEquals(200, status(c, INV + "/working-set?of=entities", OWNER));
        }
    }
}

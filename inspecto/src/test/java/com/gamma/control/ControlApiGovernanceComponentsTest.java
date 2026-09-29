package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-WORKFLOW-SLA-1 — the {@code workflow}, {@code sla-policy} and {@code escalation-rule} component kinds: written
 * only through their literal {@code canAdminister} routes, validated fail-closed, versioned, and held by the
 * maker-checker policy like every governable kind.
 *
 * <p>⚠ A REAL Subject is attached on every request ({@link Authenticators#forTest}); with none, {@code withCapability}
 * is a no-op and every 403 below would be a 200 against an ungated route. Roles come from {@link Roles#SEED}:
 * {@code admin} holds canAdminister (and not canAuthorWorkbench); {@code developer} holds canAuthorWorkbench;
 * {@code operations} holds canManageIncidents.
 */
class ControlApiGovernanceComponentsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    static final String WORKFLOW = """
            {"id":"incident","objectType":"INCIDENT","initial":"NEW","terminal":["CLOSED","ARCHIVED"],"transitions":[
              {"from":"NEW","to":"RESOLVED","action":"resolve"},{"from":"RESOLVED","to":"CLOSED","action":"close"},
              {"from":"NEW","to":"ARCHIVED","action":"archive"},{"from":"RESOLVED","to":"ARCHIVED","action":"archive"}]}
            """;
    static final String SLA = """
            {"id":"incident","objectType":"INCIDENT","calendar":{"zone":"Europe/London","workingDays":["MON","TUE","WED","THU","FRI"],
              "start":"09:00","end":"17:00","holidays":["2026-12-25"]},
             "targets":[{"priority":"CRITICAL","responseMinutes":30,"resolutionMinutes":240},{"priority":"*","resolutionMinutes":960}]}
            """;
    static final String RULE = """
            {"id":"page-duty","objectType":"INCIDENT","on":"breach","reassign":"duty-manager","notify":true}
            """;

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        String who = auth.substring(7);
        Roles.Def def = Roles.SEED.get(who.replaceAll("-\\d+$", ""));
        if (def == null) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of(who.replaceAll("-\\d+$", "")));
        return Optional.of(new Subject(who, def.capabilities()));
    };

    @BeforeEach
    void arm() {
        Authenticators.forTest(FAKE);
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** kind, create path, body, item path, restore path — literal, so the auth-gate coverage guard can read them. */
    private static final List<String[]> KINDS = List.of(
            new String[]{"workflow", "/components/workflow", WORKFLOW, "/components/workflow/incident",
                    "/components/workflow/incident/versions/1/restore"},
            new String[]{"sla-policy", "/components/sla-policy", SLA, "/components/sla-policy/incident",
                    "/components/sla-policy/incident/versions/1/restore"},
            new String[]{"escalation-rule", "/components/escalation-rule", RULE, "/components/escalation-rule/page-duty",
                    "/components/escalation-rule/page-duty/versions/1/restore"});

    @Test
    void theSeedsPremiseHolds() {
        assertTrue(Roles.SEED.get("admin").capabilities().contains(Roles.CAN_ADMINISTER));
        assertFalse(Roles.SEED.get("developer").capabilities().contains(Roles.CAN_ADMINISTER));
        assertTrue(Roles.SEED.get("developer").capabilities().contains(Roles.CAN_AUTHOR_WORKBENCH));
        assertFalse(Roles.SEED.get("operations").capabilities().contains(Roles.CAN_ADMINISTER));
        assertTrue(Roles.SEED.get("operations").capabilities().contains(Roles.CAN_MANAGE_INCIDENTS));
    }

    /** The four write routes of each kind: 401 with no credential, 403 without canAdminister, 200 with it. */
    @Test
    void everyWriteRouteOfEachKindIs401Then403Then200(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            for (String[] k : KINDS) {
                String base = k[1];
                String one = k[3];
                String restore = k[4];
                assertEquals(401, send(c, "POST", base, k[2], null).statusCode(), k[0]);
                for (String who : List.of("developer", "operations"))
                    assertEquals(403, send(c, "POST", base, k[2], who).statusCode(), k[0] + " " + who);
                assertEquals(200, send(c, "POST", base, k[2], "admin").statusCode(), k[0]);
                for (String who : List.of("developer", "operations")) {
                    assertEquals(403, send(c, "PUT", one, k[2], who).statusCode(), k[0] + " " + who);
                    assertEquals(403, send(c, "POST", restore, null, who).statusCode(), k[0] + " " + who);
                    assertEquals(403, send(c, "DELETE", one, null, who).statusCode(), k[0] + " " + who);
                }
                assertEquals(401, send(c, "PUT", one, k[2], null).statusCode(), k[0]);
                assertEquals(200, send(c, "PUT", one, k[2], "admin").statusCode(), k[0]);
                assertEquals(200, send(c, "POST", restore, null, "admin").statusCode(), k[0]);
                assertEquals(200, send(c, "GET", one, null, "operations").statusCode(), "reads stay open: " + k[0]);
                assertEquals(401, send(c, "DELETE", one, null, null).statusCode(), k[0]);
                assertEquals(200, send(c, "DELETE", one, null, "admin").statusCode(), k[0]);
            }
        }
    }

    /** The generic door — and an encoding that dodges the literal route — refuses these kinds for everyone. */
    @Test
    void theGenericComponentsDoorRefusesAGovernanceKindEvenForSuper(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            assertEquals(403, send(c, "POST", "/components/workflo%2577", WORKFLOW, "super").statusCode());
            assertEquals(403, send(c, "POST", "/components/sla%252Dpolicy", SLA, "super").statusCode());
            assertEquals(200, send(c, "POST", "/components/workflow", WORKFLOW, "super").statusCode());
            assertEquals(403, send(c, "PUT", "/components/workflo%2577/incident", WORKFLOW, "super").statusCode());
            assertEquals(403, send(c, "DELETE", "/components/workflo%2577/incident", null, "super").statusCode());
        }
    }

    @Test
    void anInvalidWorkflowPolicyOrRuleIsRefused422AndWritesNothing(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            String unreachable = WORKFLOW.replace("{\"from\":\"NEW\",\"to\":\"ARCHIVED\",\"action\":\"archive\"}",
                    "{\"from\":\"LIMBO\",\"to\":\"ARCHIVED\",\"action\":\"archive\"}");
            HttpResponse<String> r = send(c, "POST", "/components/workflow", unreachable, "admin");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("LIMBO is unreachable"), r.body());
            String bypass = WORKFLOW.replace("{\"from\":\"NEW\",\"to\":\"ARCHIVED\",\"action\":\"archive\"}",
                    "{\"from\":\"NEW\",\"to\":\"CLOSED\",\"action\":\"close\"}");
            r = send(c, "POST", "/components/workflow", bypass, "admin");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("skipping the Disposition and postmortem gate"), r.body());
            assertEquals(422, send(c, "POST", "/components/sla-policy",
                    SLA.replace("Europe/London", "+01:00"), "admin").statusCode(), "an offset is not an IANA zone");
            assertEquals(422, send(c, "POST", "/components/escalation-rule",
                    "{\"id\":\"noop\",\"objectType\":\"INCIDENT\",\"on\":\"breach\"}", "admin").statusCode());
            ComponentStore store = new ComponentStore(c.root.resolve("registry"));
            assertTrue(store.list("workflow").isEmpty() && store.list("sla-policy").isEmpty()
                    && store.list("escalation-rule").isEmpty(), "a refusal writes nothing");
        }
    }

    @Test
    void historyAndRestoreWorkThroughTheComponentStore(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            assertEquals(200, send(c, "POST", "/components/workflow", WORKFLOW, "admin").statusCode());
            String edited = WORKFLOW.replace("\"initial\":\"NEW\"", "\"initial\":\"NEW\",\"label\":\"v2\"");
            assertEquals(200, send(c, "PUT", "/components/workflow/incident", edited, "admin").statusCode());
            JsonNode versions = JSON.readTree(send(c, "GET", "/components/workflow/incident/versions", null, "admin").body());
            JsonNode list = versions.has("data") ? versions.get("data") : versions;
            assertEquals(1, list.size(), versions.toString());
            assertEquals(200, send(c, "POST", "/components/workflow/incident/versions/1/restore", null, "admin").statusCode());
            assertNull(new ComponentStore(c.root.resolve("registry")).get("workflow", "incident").orElseThrow()
                    .content().get("label"), "restored to v1");
        }
    }

    /** Maker-checker: with an approval policy on the kind, the write is HELD (202) and lands only on approval. */
    @Test
    void aGovernedWorkflowWriteIsHeldUntilAnotherAdministratorApprovesIt(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp)) {
            assertEquals(200, send(c, "PUT", "/settings/approval",
                    "{\"approval\":{\"workflow\":{\"required\":true}}}", "admin-1").statusCode());
            HttpResponse<String> held = send(c, "POST", "/components/workflow", WORKFLOW, "admin-1");
            assertEquals(202, held.statusCode(), held.body());
            ComponentStore store = new ComponentStore(c.root.resolve("registry"));
            assertTrue(store.list("workflow").isEmpty(), "held, not written");
            String id = JSON.readTree(held.body()).at("/data/pendingChange/id").asText();
            assertEquals(403, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", "admin-1").statusCode(),
                    "four-eyes: the author cannot approve");
            HttpResponse<String> ok = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", "admin-2");
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(store.get("workflow", "incident").isPresent(), "written on approval");
        }
    }
}

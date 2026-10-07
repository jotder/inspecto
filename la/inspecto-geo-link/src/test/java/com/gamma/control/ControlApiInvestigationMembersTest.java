package com.gamma.control;

import com.gamma.spi.auth.AccessDecider;
import com.gamma.spi.auth.AccessDeciders;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7-1 — Investigation MEMBERS (lead / analyst / reviewer), over real HTTP with an ARMED Authenticator and six
 * Subjects (with no Subject {@code withCapability} is a no-op and every gate is open, so nothing would be proven).
 * Every Subject holds the same capabilities, so what separates them here is ONLY the membership role.
 */
class ControlApiInvestigationMembersTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String REVOKE = "/inv/investigations/inv-a/members/revoke";
    private static final String L = "Bearer lead", A = "Bearer analyst", R = "Bearer reviewer", S = "Bearer stranger",
            C = "Bearer casemember", NOCAP = "Bearer nocap";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
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
        Set<String> all = Set.of("canManageIncidents", "canRunLinkGraphAnalysis", "canApproveLinkExpansions",
                "canRevealLinkEntities", "canAuthorAlertRules");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case L -> Optional.of(new Subject("lead-1", all));
            case A -> Optional.of(new Subject("analyst-2", all));
            case R -> Optional.of(new Subject("reviewer-3", all));
            case S -> Optional.of(new Subject("stranger-4", all));
            case C -> Optional.of(new Subject("case-5", all));          // the linked Case's assignee, never a member
            case NOCAP -> Optional.of(new Subject("lead-1", Set.of())); // the lead, without any capability
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
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, auth);
        assertEquals(200, r.statusCode(), method + " " + path + " as " + auth + " → " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private int status(Ctx c, String method, String path, String body, String auth) throws Exception {
        return send(c, method, path, body, auth).statusCode();
    }

    private static String message(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    private static String grantBody(String subject, String role) {
        return "{\"subject\":\"" + subject + "\",\"role\":\"" + role + "\"}";
    }

    /** inv-a with one sealed expand, A an analyst and R a reviewer. No Case is linked unless the caller does so. */
    private void team(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}", L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", L);
        ok(c, "POST", INV + "/members", grantBody("analyst-2", "analyst"), L);
        ok(c, "POST", INV + "/members", grantBody("reviewer-3", "reviewer"), L);
    }

    private Path membersFile(Ctx c) {
        return c.root().resolve("audit/snapshots/investigations/inv-a/members.jsonl");
    }

    /** The reads every MEMBER holds (coverage needs a timeCol, so it answers 422 once past the gate: only 404 matters). */
    private static final List<String> READS = List.of(INV + "/log", INV + "/working-set", INV + "/dossier",
            INV + "/dossier/bundle", INV + "/measures", INV + "/references", INV + "/case");

    private void assertReads(Ctx c, String auth, int expected) throws Exception {
        for (String path : READS) assertEquals(expected, status(c, "GET", path, null, auth), "GET " + path + " as " + auth);
        int coverage = status(c, "GET", INV + "/coverage", null, auth);
        assertEquals(expected == 404, coverage == 404, "coverage as " + auth + " → " + coverage);
        int verify = status(c, "POST", INV + "/dossier/verify", "{}", auth);   // a bad body is 422 once past the gate
        assertEquals(expected == 404, verify == 404, "dossier verify as " + auth + " → " + verify);
    }

    private void assertReplay(Ctx c, String auth, int expected) throws Exception {
        assertEquals(expected, status(c, "POST", INV + "/replay", "{}", auth), "replay as " + auth);
    }

    /** Every write the lead holds, with the status it must answer to someone who may not (403 a member, 404 a non-member). */
    private void assertWritesRefused(Ctx c, String auth, int expected) throws Exception {
        assertEquals(expected, status(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"d\"]}", auth), "ops as " + auth);
        assertEquals(expected, status(c, "POST", INV + "/undo", "{}", auth), "undo as " + auth);
        assertEquals(expected, status(c, "POST", INV + "/reorder", "{\"id\":\"inv-f\",\"order\":[2,1]}", auth), "reorder as " + auth);
        assertEquals(expected, status(c, "POST", INV + "/template", "{}", auth), "template as " + auth);
        assertEquals(expected, status(c, "POST", INV + "/alert-rules", "{\"name\":\"r\",\"relation\":\"entities\","
                + "\"measure\":\"count(*)\",\"condition\":\"GT\",\"threshold\":1}", auth), "alert rules as " + auth);
        assertEquals(expected, status(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", auth), "relink as " + auth);
        assertEquals(expected, status(c, "DELETE", INV + "/case", null, auth), "unlink as " + auth);
        assertEquals(expected, status(c, "POST", INV + "/references",
                "{\"system\":\"crm\",\"type\":\"case\",\"id\":\"9\"}", auth), "references as " + auth);
        assertEquals(expected, status(c, "POST", INV + "/reveal", "{\"tokens\":[\"masked:x\"]}", auth), "reveal as " + auth);
    }

    @Test
    void eachRoleReachesExactlyItsRoutes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Map<String, Map<String, Object>> cases = CaseTeamObjectEngine.arm();
        cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "someone", "case-5", false));
        try (Ctx c = open(cfg, root)) {
            team(c);
            ok(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", L);

            assertReads(c, L, 200);   // the probe that WOULD succeed: the same requests by the lead
            assertReads(c, A, 200);
            assertReads(c, R, 200);
            assertReads(c, S, 404);
            assertReads(c, C, 200);   // LA-24 unchanged: a Case member reads
            for (String who : List.of(L, A, R)) assertReplay(c, who, 200);
            assertReplay(c, S, 404);
            assertReplay(c, C, 404);   // replay was never open to a Case member

            assertEquals(200, status(c, "POST", INV + "/references", "{\"system\":\"crm\",\"type\":\"case\",\"id\":\"9\"}", L));
            assertWritesRefused(c, A, 403);
            assertWritesRefused(c, R, 403);
            assertWritesRefused(c, S, 404);
            assertWritesRefused(c, C, 404);
            assertEquals(200, status(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"d\"]}", L), "the lead still writes");
            assertEquals(200, status(c, "POST", INV + "/undo", "{}", L));

            // members: any member reads the list; a Case member and a stranger do not
            for (String who : List.of(L, A, R)) assertEquals(200, status(c, "GET", INV + "/members", null, who), "members as " + who);
            assertEquals(404, status(c, "GET", INV + "/members", null, S));
            assertEquals(404, status(c, "GET", INV + "/members", null, C));
            JsonNode asA = ok(c, "GET", INV + "/members", null, A);
            assertEquals("analyst", asA.get("you").asText());
            assertEquals(3, asA.get("members").size(), asA.toString());
            assertEquals("lead-1", asA.get("members").get(0).get("subject").asText());
            assertEquals(2, asA.get("history").size());
        }
    }

    @Test
    void aGraphRunFollowsTheInvestigationsReadGate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String run = "{\"investigationId\":\"inv-a\",\"algorithm\":\"connectedComponents\"}";
            AtomicReference<String> aRun = new AtomicReference<>();
            for (String who : List.of(L, A, R)) {
                HttpResponse<String> r = send(c, "POST", "/inv/graph/runs", run, who);
                assertTrue(r.statusCode() == 200 || r.statusCode() == 202, who + " → " + r.statusCode() + " " + r.body());
                if (who.equals(A)) aRun.set(JSON.readTree(r.body()).get("data").get("runId").asText());
            }
            assertEquals(404, status(c, "POST", "/inv/graph/runs", run, S), "a stranger cannot start a run");
            assertNotNull(aRun.get());
            assertEquals(200, status(c, "GET", "/inv/graph/runs/" + aRun.get(), null, A));
            ok(c, "POST", REVOKE, "{\"subject\":\"analyst-2\"}", L);
            assertEquals(404, status(c, "GET", "/inv/graph/runs/" + aRun.get(), null, A), "the revoked analyst loses the run it started");
            assertEquals(404, status(c, "POST", "/inv/graph/runs", run, A));
        }
    }

    @Test
    void withoutAMembersFileTheOwnerIsTheSoleLeadAsBefore(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE, L);
            ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}", L);
            assertReads(c, L, 200);
            assertReads(c, A, 404);
            assertReads(c, R, 404);
            assertReads(c, S, 404);
            assertWritesRefused(c, A, 404);
            JsonNode members = ok(c, "GET", INV + "/members", null, L);
            assertEquals(1, members.get("members").size());
            assertEquals("lead", members.get("members").get(0).get("role").asText());
            assertEquals(0, members.get("history").size());
            assertEquals(404, status(c, "GET", INV + "/members", null, A));
            assertFalse(Files.exists(membersFile(c)), "nothing is migrated and a read writes nothing");
            JsonNode mine = ok(c, "GET", "/inv/investigations", null, L).get("items").get(0);
            assertEquals("owner", mine.get("access").asText());
            assertFalse(mine.get("readOnly").asBoolean());
            assertEquals(0, ok(c, "GET", "/inv/investigations", null, A).get("items").size());
        }
    }

    @Test
    void aStrangerGetsTheVeryAnswerAnUnknownIdGets(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            for (String path : List.of("/log", "/working-set", "/members", "/dossier")) {
                HttpResponse<String> stranger = send(c, "GET", INV + path, null, S);
                HttpResponse<String> unknown = send(c, "GET", "/inv/investigations/inv-nope" + path, null, S);
                assertEquals(404, stranger.statusCode(), path);
                assertEquals(unknown.statusCode(), stranger.statusCode(), path);
                assertEquals(message(unknown).replace("inv-nope", "X"), message(stranger).replace("inv-a", "X"), path);
            }
            HttpResponse<String> grant = send(c, "POST", INV + "/members", grantBody("stranger-4", "lead"), S);
            assertEquals(404, grant.statusCode(), "a stranger cannot even learn the Investigation exists by trying to join it");
            assertFalse(Files.readString(membersFile(c)).contains("stranger-4"));
            assertEquals(2, ok(c, "GET", INV + "/members", null, L).get("history").size());
        }
    }

    @Test
    void aRevokeTakesEffectOnTheNextRequestAndAGrantChangesTheRole(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            assertReads(c, A, 200);
            ok(c, "POST", REVOKE, "{\"subject\":\"analyst-2\"}", L);
            assertReads(c, A, 404);
            assertWritesRefused(c, A, 404);
            assertEquals(0, ok(c, "GET", "/inv/investigations", null, A).get("items").size());

            ok(c, "POST", INV + "/members", grantBody("analyst-2", "reviewer"), L);   // re-grant as a different role
            assertReads(c, A, 200);
            assertEquals("reviewer", ok(c, "GET", INV + "/members", null, A).get("you").asText());
            ok(c, "POST", INV + "/members", grantBody("analyst-2", "reviewer"), L);   // idempotent: records nothing
            List<String> lines = Files.readAllLines(membersFile(c));
            assertEquals(4, lines.size(), lines.toString());   // grant analyst, grant reviewer, revoke, re-grant
        }
    }

    @Test
    void theLastLeadIsProtectedAndLeadershipCanBeHandedOver(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            assertEquals(422, status(c, "POST", REVOKE, "{\"subject\":\"lead-1\"}", L), "revoking the last lead");
            assertEquals(422, status(c, "POST", INV + "/members", grantBody("lead-1", "analyst"), L), "self-demotion");
            assertEquals(200, status(c, "POST", INV + "/members", grantBody("lead-1", "lead"), L), "no change is fine");
            assertEquals(200, status(c, "GET", INV + "/log", null, L), "nothing was changed by the refusals");

            ok(c, "POST", INV + "/members", grantBody("analyst-2", "lead"), L);
            assertEquals(200, status(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"d\"]}", A), "the new lead writes");
            ok(c, "POST", REVOKE, "{\"subject\":\"lead-1\"}", A);   // two leads: the creator may go
            assertReads(c, L, 404);
            assertEquals(404, status(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"e\"]}", L));
            assertEquals(422, status(c, "POST", REVOKE, "{\"subject\":\"analyst-2\"}", A), "now A is the last lead");
        }
    }

    @Test
    void grantingAndRevokingAreLeadOnlyAndValidated(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            for (String who : List.of(A, R)) {
                assertEquals(403, status(c, "POST", INV + "/members", grantBody("stranger-4", "analyst"), who), "grant as " + who);
                assertEquals(403, status(c, "POST", REVOKE, "{\"subject\":\"reviewer-3\"}", who), "revoke as " + who);
            }
            assertEquals(404, status(c, "POST", INV + "/members", grantBody("stranger-4", "analyst"), C));
            assertEquals(403, status(c, "POST", INV + "/members", grantBody("stranger-4", "analyst"), NOCAP), "capability");
            assertEquals(403, status(c, "POST", REVOKE, "{\"subject\":\"analyst-2\"}", NOCAP), "capability");
            assertEquals(422, status(c, "POST", INV + "/members", "{\"subject\":\"x\"}", L), "role required");
            assertEquals(422, status(c, "POST", INV + "/members", grantBody("x", "owner"), L), "unknown role");
            assertEquals(422, status(c, "POST", INV + "/members", "{\"role\":\"analyst\"}", L), "subject required");
            assertEquals(422, status(c, "POST", INV + "/members", grantBody("a\\nb", "analyst"), L), "control characters");
            assertEquals(404, status(c, "POST", REVOKE, "{\"subject\":\"stranger-4\"}", L), "not a member");
            assertEquals(2, Files.readAllLines(membersFile(c)).size(), "every refusal wrote nothing");
            assertEquals(404, status(c, "GET", "/inv/investigations/inv-nope/members", null, L));
            ok(c, "POST", INV + "/members", grantBody("stranger-4", "analyst"), L);   // the probe that would otherwise succeed
            assertReads(c, S, 200);
        }
    }

    @Test
    void grantsOnOneInvestigationOpenNothingOnAnother(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            ok(c, "POST", "/inv/investigations", CREATE.replace("inv-a", "inv-b"), L);
            assertEquals(200, status(c, "GET", "/inv/investigations/inv-b/log", null, L));
            assertEquals(404, status(c, "GET", "/inv/investigations/inv-b/log", null, A));
            assertEquals(200, status(c, "GET", INV + "/log", null, A));
            JsonNode asA = ok(c, "GET", "/inv/investigations", null, A);
            assertEquals(1, asA.get("items").size());
            assertEquals("analyst", asA.get("items").get(0).get("access").asText());
            assertTrue(asA.get("items").get(0).get("readOnly").asBoolean());
        }
    }

    @Test
    void theFourEyesApproverIsALeadOrReviewerOnceTheInvestigationHasMembers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE, L);
            String approve = INV + "/pending/req-1/approve";
            HttpResponse<String> legacy = send(c, "POST", approve, "{}", S);
            assertEquals(404, legacy.statusCode());
            assertTrue(message(legacy).contains("no pending request"), "no members yet: any approver reaches the request — " + message(legacy));
            ok(c, "POST", INV + "/members", grantBody("analyst-2", "analyst"), L);
            ok(c, "POST", INV + "/members", grantBody("reviewer-3", "reviewer"), L);
            assertTrue(message(send(c, "POST", approve, "{}", R)).contains("no pending request"), "a reviewer reaches it");
            assertTrue(message(send(c, "POST", approve, "{}", L)).contains("no pending request"), "a lead reaches it");
            assertEquals(403, status(c, "POST", approve, "{}", A), "an analyst may not decide");
            HttpResponse<String> stranger = send(c, "POST", approve, "{}", S);
            assertEquals(404, stranger.statusCode());
            assertTrue(message(stranger).contains("no investigation"), "a non-member no longer reaches it — " + message(stranger));
        }
    }

    @Test
    void thePolicyOnlyNarrowsAndSeesTheRoleMap(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        AtomicReference<String> decision = new AtomicReference<>("ABSTAIN");
        List<Object> seen = new ArrayList<>();
        AccessDeciders.forTest((ex, subject, action, route, kind, resource) -> {
            if (!"investigation".equals(kind)) return AccessDecider.Decision.ABSTAIN;
            seen.add(resource);
            return AccessDecider.Decision.valueOf(decision.get());
        });
        try (Ctx c = open(cfg, root)) {
            team(c);
            assertReads(c, L, 200);
            assertReads(c, A, 200);
            assertTrue(seen.stream().anyMatch(r -> r instanceof Map<?, ?> m && m.get("members") instanceof Map<?, ?> roles
                            && "lead".equals(roles.get("lead-1")) && "analyst".equals(roles.get("analyst-2"))
                            && "reviewer".equals(roles.get("reviewer-3"))),
                    "the PDP resource carries the role map: " + seen.get(seen.size() - 1));

            decision.set("ALLOW");
            assertReads(c, S, 404);   // an ALLOW is not a grant
            assertEquals(404, status(c, "POST", INV + "/members", grantBody("stranger-4", "lead"), S));
            assertWritesRefused(c, A, 403);

            decision.set("DENY");
            assertReads(c, L, 404);   // a DENY hides it even from a lead...
            assertReads(c, A, 404);
            assertReads(c, R, 404);
            assertWritesRefused(c, L, 404);
            assertWritesRefused(c, A, 404);   // ...and a member's refusal must not leak its existence either
            assertEquals(404, status(c, "GET", INV + "/members", null, L));
            assertEquals(404, status(c, "POST", INV + "/members", grantBody("stranger-4", "analyst"), L));
            assertEquals(2, Files.readAllLines(membersFile(c)).size());

            decision.set("ABSTAIN");
            assertReads(c, L, 200);
        }
    }
}

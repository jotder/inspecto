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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-24 — sharing an Investigation with a Case team, over real HTTP and with an armed Authenticator (with no Subject
 * {@code withCapability} is a no-op and every Investigation is open to the caller, so nothing would be proven).
 *
 * <p>Case management is the optional {@code inspecto-ops} module, which this module never depends on;
 * {@link CaseTeamObjectEngine} stands in for it when armed and is ABSENT otherwise — so the ops-absent tests below
 * run against exactly what a bundle without ops has.
 */
class ControlApiInvestigationCaseShareTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String OWNER = "Bearer owner", MEMBER = "Bearer member", LEAD = "Bearer lead",
            STRANGER = "Bearer stranger", NOCAP = "Bearer nocap";
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
            case NOCAP -> Optional.of(new Subject("analyst-1", Set.of()));
            case MEMBER -> Optional.of(new Subject("analyst-2", all));   // the Case's assignee — full capabilities
            case LEAD -> Optional.of(new Subject("lead-1", Set.of()));   // the Case's owner — no capabilities
            case STRANGER -> Optional.of(new Subject("analyst-3", all));
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
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, auth);
        assertEquals(200, r.statusCode(), method + " " + path + " → " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private int status(Ctx c, String method, String path, String body, String auth) throws Exception {
        return send(c, method, path, body, auth).statusCode();
    }

    /** An Investigation with one sealed expand, so the log, Working Set, Dossier and measures all have content. */
    private void investigation(Ctx c, String create) throws Exception {
        ok(c, "POST", "/inv/investigations", create, OWNER);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}", OWNER);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", OWNER);
    }

    private static final List<String> READS = List.of(INV + "/log", INV + "/working-set", INV + "/dossier",
            INV + "/measures", INV + "/case");

    private void assertReads(Ctx c, String auth, int expected) throws Exception {
        for (String path : READS) assertEquals(expected, status(c, "GET", path, null, auth), "GET " + path + " as " + auth);
        int verify = status(c, "POST", INV + "/dossier/verify", "{}", auth);   // a bad body is 422 once past the gate
        assertEquals(expected == 404, verify == 404, "dossier verify as " + auth + " → " + verify);
    }

    /** Every write route an owner holds — each must refuse a Case member exactly as it refuses a stranger. */
    private void assertWritesRefused(Ctx c, String auth) throws Exception {
        assertEquals(404, status(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"d\"]}", auth), "ops");
        assertEquals(404, status(c, "POST", INV + "/undo", "{}", auth), "undo");
        assertEquals(404, status(c, "POST", INV + "/reorder", "{\"id\":\"inv-f\",\"order\":[2,1]}", auth), "reorder");
        assertEquals(404, status(c, "POST", INV + "/replay", "{}", auth), "replay");
        assertEquals(404, status(c, "POST", INV + "/template", "{}", auth), "template");
        assertEquals(404, status(c, "POST", INV + "/alert-rules", "{\"name\":\"r\",\"relation\":\"entities\","
                + "\"measure\":\"count(*)\",\"condition\":\"GT\",\"threshold\":1}", auth), "alert rules");
        assertEquals(404, status(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", auth), "relink");
        assertEquals(404, status(c, "DELETE", INV + "/case", null, auth), "unlink");
    }

    @Test
    void aCaseMemberReadsButCannotWriteAndANonMemberStillGets404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Map<String, Map<String, Object>> cases = CaseTeamObjectEngine.arm();
        cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", false));
        try (Ctx c = open(cfg, root)) {
            investigation(c, CREATE);
            assertReads(c, MEMBER, 404);   // not linked yet: owner-only, as before LA-24

            JsonNode linked = ok(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", OWNER);
            assertEquals("CASE-1", linked.get("caseRef").asText());
            assertTrue(linked.get("sharing").asBoolean(), linked.toString());
            assertEquals("owner", linked.get("access").asText());
            assertFalse(Files.readString(root.resolve("audit/snapshots/investigations/inv-a/header.json")).contains("CASE-1"),
                    "the link lives outside the sealed header");

            assertReads(c, MEMBER, 200);
            assertReads(c, LEAD, 200);     // the Case's owner is a member too — and reads need no capability
            JsonNode asMember = ok(c, "GET", INV + "/case", null, MEMBER);
            assertEquals("case-member", asMember.get("access").asText());
            assertTrue(asMember.get("readOnly").asBoolean());
            assertWritesRefused(c, MEMBER);

            assertReads(c, STRANGER, 404);
            assertWritesRefused(c, STRANGER);
            assertEquals(200, status(c, "GET", INV + "/log", null, OWNER), "the owner is untouched");
        }
    }

    @Test
    void linkingIsOwnerOnlyGatedAndNeedsACaseTheCallerCanSee(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Map<String, Map<String, Object>> cases = CaseTeamObjectEngine.arm();
        cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", false));
        cases.put("CASE-SHUT", CaseTeamObjectEngine.caseOf("CASE-SHUT", "lead-1", "analyst-2", true));
        cases.put("ALERT-1", Map.of("kind", "alert", "id", "ALERT-1", "owner", "x", "assignee", "y",
                "attributes", Map.of(), "closed", false));
        try (Ctx c = open(cfg, root)) {
            investigation(c, CREATE);
            assertEquals(403, status(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", NOCAP), "capability");
            assertEquals(403, status(c, "DELETE", INV + "/case", null, NOCAP), "capability");
            assertEquals(422, status(c, "PUT", INV + "/case", "{}", OWNER));
            assertEquals(422, status(c, "PUT", INV + "/case", "{\"caseRef\":\"../x\"}", OWNER));
            assertEquals(404, status(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-NOPE\"}", OWNER));
            assertEquals(404, status(c, "PUT", INV + "/case", "{\"caseRef\":\"ALERT-1\"}", OWNER), "not a Case");
            assertEquals(409, status(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-SHUT\"}", OWNER), "closed");
            assertEquals(404, status(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", STRANGER), "not the owner");
            assertFalse(Files.exists(root.resolve("audit/snapshots/investigations/inv-a/case-link.json")),
                    "a refusal writes nothing");

            AtomicBoolean hideCases = new AtomicBoolean(true);
            AccessDeciders.forTest((ex, subject, action, route, kind, resource) ->
                    hideCases.get() && "case".equals(kind) ? AccessDecider.Decision.DENY : AccessDecider.Decision.ABSTAIN);
            assertEquals(404, status(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", OWNER), "a Case the owner cannot see");
            hideCases.set(false);

            ok(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", OWNER);
            JsonNode gone = ok(c, "DELETE", INV + "/case", null, OWNER);
            assertTrue(gone.get("caseRef").isNull());
            assertReads(c, MEMBER, 404);
        }
    }

    @Test
    void caseRefAtCreateIsCheckedBeforeAnythingIsWritten(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        CaseTeamObjectEngine.arm().put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", false));
        try (Ctx c = open(cfg, root)) {
            String withCase = CREATE.replace("{", "{\"caseRef\":\"CASE-NOPE\",");
            assertEquals(404, status(c, "POST", "/inv/investigations", withCase, OWNER));
            assertFalse(Files.exists(root.resolve("audit/snapshots/investigations/inv-a")), "a refusal writes nothing");
            investigation(c, CREATE.replace("{", "{\"caseRef\":\"CASE-1\","));
            assertReads(c, MEMBER, 200);
        }
    }

    /** A9 (operator 2026-09-30): Case members may read {@code /coverage}; a stranger and a closed Case still 404. */
    @Test
    void aCaseMemberReadsCoverageAndAStrangerOrAClosedCaseStill404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Map<String, Map<String, Object>> cases = CaseTeamObjectEngine.arm();
        cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", false));
        try (Ctx c = open(cfg, root)) {
            new ViewStore(root.resolve("views")).write(new ViewDefinition("timed_view", "flow-x", List.of(),
                    "SELECT caller, callee, TIMESTAMP '2026-09-01 10:00:00' AS ts FROM (VALUES ('a','b'),('b','d'))"
                            + " AS t(caller,callee)", "2026-09-30T00:00:00Z"));
            new ComponentStore(root.resolve("registry")).write("dataset", "timed_ds", Map.of("view", "timed_view"));
            ok(c, "POST", "/inv/investigations", "{\"id\":\"inv-a\",\"purpose\":\"Fraud referral FR-9\","
                    + "\"dataset\":\"timed_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"timeCol\":\"ts\"}", OWNER);
            String cov = INV + "/coverage?from=2026-09-01T00:00:00Z&to=2026-09-03T00:00:00Z";
            assertEquals(200, status(c, "GET", cov, null, OWNER), "the owner reads coverage");
            assertEquals(404, status(c, "GET", cov, null, MEMBER), "not linked yet: owner-only");

            ok(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", OWNER);
            assertEquals(200, status(c, "GET", cov, null, MEMBER), "a Case member reads coverage");
            assertEquals(404, status(c, "GET", cov, null, STRANGER), "a stranger still sees nothing");

            cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", true));
            assertEquals(404, status(c, "GET", cov, null, MEMBER), "a closed Case grants nothing");
            assertEquals(200, status(c, "GET", cov, null, OWNER), "the owner keeps access");
        }
    }

    /** Fail closed (assumption for operator review): access ends when the Case closes, vanishes, or the member leaves. */
    @Test
    void accessEndsWhenTheCaseClosesIsDeletedOrTheMemberLeaves(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Map<String, Map<String, Object>> cases = CaseTeamObjectEngine.arm();
        cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", false));
        try (Ctx c = open(cfg, root)) {
            investigation(c, CREATE);
            ok(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", OWNER);
            assertReads(c, MEMBER, 200);
            assertTrue(ok(c, "GET", INV + "/case", null, OWNER).get("sharing").asBoolean(), "the twin: an open Case shares");

            cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-9", false));
            assertReads(c, MEMBER, 404);   // reassigned away
            assertReads(c, LEAD, 200);

            cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", true));
            assertReads(c, MEMBER, 404);   // closed
            assertReads(c, LEAD, 404);
            JsonNode shut = ok(c, "GET", INV + "/case", null, OWNER);   // plan §5.10: the answer agrees with the gate
            assertFalse(shut.get("sharing").asBoolean(), "a closed Case shares nothing: " + shut);
            assertTrue(shut.get("reason").asText().contains("closed"), shut.toString());

            cases.remove("CASE-1");
            assertReads(c, LEAD, 404);     // deleted
            assertFalse(ok(c, "GET", INV + "/case", null, OWNER).get("sharing").asBoolean(), "a vanished Case shares nothing");
            assertEquals(200, status(c, "GET", INV + "/log", null, OWNER), "the owner keeps access throughout");
        }
    }

    /** D-E7: Case membership is a separate grant, not a policy ALLOW — a PDP DENY hides it from members too. */
    @Test
    void aPolicyDenyStillHidesItFromCaseMembers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        CaseTeamObjectEngine.arm().put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", false));
        AtomicBoolean deny = new AtomicBoolean();
        AccessDeciders.forTest((ex, subject, action, route, kind, resource) ->
                deny.get() && "investigation".equals(kind) ? AccessDecider.Decision.DENY : AccessDecider.Decision.ABSTAIN);
        try (Ctx c = open(cfg, root)) {
            investigation(c, CREATE);
            ok(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", OWNER);
            assertReads(c, MEMBER, 200);
            deny.set(true);
            assertReads(c, MEMBER, 404);
            assertReads(c, OWNER, 404);
        }
    }

    /** Link Analysis works with Case management ABSENT: the link is stored, grants nothing, and says why. */
    @Test
    void withOpsAbsentTheLinkIsStoredButGrantsNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            assertTrue(c.svc().objects().isEmpty(), "precondition: no Case management in this bundle");
            investigation(c, CREATE.replace("{", "{\"caseRef\":\"CASE-1\","));
            JsonNode s = ok(c, "GET", INV + "/case", null, OWNER);
            assertEquals("CASE-1", s.get("caseRef").asText());
            assertFalse(s.get("sharing").asBoolean());
            assertTrue(s.get("reason").asText().contains("not installed"), s.toString());
            assertTrue(Files.readString(root.resolve("audit/snapshots/investigations/inv-a/case-link.json"))
                    .contains("\"verified\":false"));
            assertReads(c, MEMBER, 404);
            assertReads(c, OWNER, 200);
            ok(c, "DELETE", INV + "/case", null, OWNER);
            assertTrue(ok(c, "GET", INV + "/case", null, OWNER).get("caseRef").isNull());
        }
    }
}

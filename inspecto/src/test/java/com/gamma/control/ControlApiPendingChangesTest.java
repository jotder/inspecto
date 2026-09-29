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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maker-checker over real HTTP (`ASSURE-MAKER-CHECKER-1` S1–S3): the approval policy, the hold, the Pending
 * Change inbox and its decide gates. Every capability gate is exercised WITH an armed Authenticator — with no
 * Subject {@code withCapability} is a no-op and a test would pass against an ungated route.
 */
class ControlApiPendingChangesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTHOR = "Bearer author", CHECKER = "Bearer checker", SELF = "Bearer self",
            ADMIN = "Bearer admin", PLAIN = "Bearer plain";
    private static final String PACK_POLICY = "{\"approval\":{\"pattern-pack\":{\"required\":true}}}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /**
     * Shaped like the OIDC Authenticator (re-verification finding 3: apply now FAILS CLOSED without recorded
     * roles): each caller holds ONE seeded role, resolved against this Space's role table, and the recognised
     * role is stamped as held. AUTHOR is a pipeline-developer; every approver is an admin (canApproveChanges, no
     * canAuthorWorkbench — a checker need not be a builder); SELF is the author's own id holding admin.
     * {@code Bearer noroles} is an Authenticator that stamps NO roles.
     */
    @BeforeEach
    void armAuthenticator() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if ("Bearer noroles".equals(h)) return Optional.of(new Subject("nr-1", Set.of("canAuthorWorkbench")));
            String[] who = switch (h) {
                case AUTHOR -> new String[] {"author-1", "pipeline-developer"};
                case CHECKER -> new String[] {"checker-1", "admin"};
                case SELF -> new String[] {"author-1", "admin"};
                case ADMIN -> new String[] {"admin-1", "admin"};
                case PLAIN -> new String[] {"plain-1", "admin"};
                case "Bearer ops" -> new String[] {"ops-1", "operations"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            ComponentAccess.heldRoles(ex, Set.of(who[1]));
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
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

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private void policy(Ctx c, String body) throws Exception {
        data(send(c, "PUT", "/settings/approval", body, ADMIN), 200);
    }

    private String propose(Ctx c, String id) throws Exception {
        JsonNode held = data(send(c, "POST", "/components/pattern-pack",
                "{\"id\":\"" + id + "\",\"title\":\"Carousel\"}", AUTHOR), 202);
        assertEquals("pending", held.get("status").asText(), held.toString());
        return held.at("/pendingChange/id").asText();
    }

    private ComponentStore store(Ctx c) {
        return new ComponentStore(c.root.resolve("registry"));
    }

    // ── S1: the policy ──────────────────────────────────────────────────────────────────────────────

    @Test
    void policyOffLeavesEveryWriteExactlyAsBefore(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            JsonNode p = data(send(c, "GET", "/settings/approval", null, AUTHOR), 200);
            assertEquals(0, p.get("approval").size(), "default OFF");
            data(send(c, "POST", "/components/pattern-pack", "{\"id\":\"p1\",\"title\":\"x\"}", AUTHOR), 200);
            assertTrue(store(c).exists("pattern-pack", "p1"), "written at once");
            assertEquals(0, data(send(c, "GET", "/pending-changes", null, AUTHOR), 200).get("total").asInt());
            assertFalse(Files.exists(root.resolve("pending-changes")), "nothing held, nothing stored");
        }
    }

    @Test
    void thePolicyIsValidatedFailClosedAndAdministratorOnly(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            assertEquals(403, send(c, "PUT", "/settings/approval", PACK_POLICY, AUTHOR).statusCode(),
                    "an author cannot lift (or set) the policy");
            assertEquals(422, send(c, "PUT", "/settings/approval",
                    "{\"approval\":{\"channel\":{\"required\":true}}}", ADMIN).statusCode(), "channel is not governable");
            assertEquals(422, send(c, "PUT", "/settings/approval",
                    "{\"approval\":{\"no-such-kind\":{\"required\":true}}}", ADMIN).statusCode());
            assertEquals(422, send(c, "PUT", "/settings/approval",
                    "{\"approval\":{\"pipeline\":{\"required\":true,\"approverCapability\":\"canFly\"}}}", ADMIN).statusCode());
            assertEquals(422, send(c, "PUT", "/settings/approval",
                    "{\"approval\":{\"pipeline\":{\"required\":true,\"quorum\":2}}}", ADMIN).statusCode());
            assertEquals(422, send(c, "PUT", "/settings/approval", "{\"approvals\":{}}", ADMIN).statusCode());
            assertFalse(Files.exists(root.resolve(ApprovalPolicy.FILE)), "a refusal writes nothing");

            JsonNode saved = data(send(c, "PUT", "/settings/approval",
                    "{\"approval\":{\"pipeline\":{\"required\":true}},\"expiresAfterHours\":24}", ADMIN), 200);
            assertEquals("canApproveChanges", saved.at("/approval/pipeline/approverCapability").asText());
            assertTrue(saved.at("/approval/pipeline/fourEyes").asBoolean(), "four-eyes by default");
            assertEquals(24, saved.get("expiresAfterHours").asInt());
        }
    }

    @Test
    void thePolicyRouteIsRefusedOutrightOnABuildWithNoAuthenticator(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        // Operator, 2026-09-28: with no Authenticator the canAdminister wrap is a no-op, so the route itself
        // refuses — turning four-eyes on OR off. Personal edition cannot change the policy through the API.
        Path root = Files.createDirectories(tmp.resolve("config"));
        Authenticators.forTest(null);
        try (Ctx c = open(cfg, root)) {
            for (String body : List.of(PACK_POLICY, "{\"approval\":{}}", "")) {
                HttpResponse<String> r = send(c, "PUT", "/settings/approval", body, null);
                assertEquals(403, r.statusCode(), r.body());
                assertTrue(r.body().contains("no Authenticator"), r.body());
            }
            assertFalse(Files.exists(root.resolve(ApprovalPolicy.FILE)), "a refusal writes nothing");
        }
    }

    @Test
    void anEmptyPolicyBodyIsRefusedAndTurningFourEyesOffMustBeExplicit(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            for (String body : List.of("", "{}")) {
                HttpResponse<String> r = send(c, "PUT", "/settings/approval", body, ADMIN);
                assertEquals(422, r.statusCode(), "body '" + body + "': " + r.body());
            }
            assertTrue(data(send(c, "GET", "/settings/approval", null, ADMIN), 200)
                    .at("/approval/pattern-pack/required").asBoolean(), "the refused PUT left the policy on");
            policy(c, "{\"approval\":{}}");
            assertEquals(0, data(send(c, "GET", "/settings/approval", null, ADMIN), 200).get("approval").size(),
                    "an explicit empty approval map turns it off");
        }
    }

    @Test
    void anUnreadablePolicyFileHoldsEveryGovernableKind(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            Files.writeString(root.resolve(ApprovalPolicy.FILE), "approval:\n  pattern-pack: 7\n");
            assertTrue(data(send(c, "GET", "/settings/approval", null, AUTHOR), 200).get("failedClosed").asBoolean());
            propose(c, "p1");
            assertFalse(store(c).exists("pattern-pack", "p1"), "fail closed: held, not written");
        }
    }

    // ── S2 + S3: propose → approve ──────────────────────────────────────────────────────────────────

    @Test
    void anAuthorProposesAndADifferentPersonApproves(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            String id = propose(c, "p1");
            assertFalse(store(c).exists("pattern-pack", "p1"), "a held write writes nothing");

            JsonNode list = data(send(c, "GET", "/pending-changes?status=pending", null, AUTHOR), 200);
            assertEquals(1, list.get("total").asInt());
            assertEquals("author-1", list.at("/items/0/author").asText());
            assertEquals("create", list.at("/items/0/operation").asText());
            JsonNode diff = data(send(c, "GET", "/pending-changes/" + id + "/diff", null, CHECKER), 200);
            assertTrue(diff.get("added").asInt() > 0 && diff.get("removed").asInt() == 0, diff.toString());
            assertTrue(diff.toString().contains("Carousel"), diff.toString());

            assertEquals(403, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", AUTHOR).statusCode(),
                    "the author lacks canApproveChanges");
            HttpResponse<String> own = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", SELF);
            assertEquals(403, own.statusCode(), "four-eyes: the author may never approve their own change");
            assertTrue(own.body().contains("four-eyes"), own.body());

            JsonNode ok = data(send(c, "POST", "/pending-changes/" + id + "/approve",
                    "{\"reason\":\"looks right\"}", CHECKER), 200);
            assertTrue(ok.get("applied").asBoolean(), ok.toString());
            assertEquals("approved", ok.at("/pendingChange/status").asText());
            assertEquals("checker-1", ok.at("/pendingChange/decidedBy").asText());
            Map<String, Object> written = store(c).get("pattern-pack", "p1").orElseThrow().content();
            assertEquals("Carousel", written.get("title"));
            assertEquals("author-1", written.get("owner"), "the author owns what they proposed");

            assertEquals(409, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER).statusCode(),
                    "already decided");
            assertEquals(409, send(c, "POST", "/pending-changes/" + id + "/decline", "{}", CHECKER).statusCode());
        }
    }

    @Test
    void aChangeAgainstAStaleBaseIsRefusedAndClosedAsStale(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            store(c).write("pattern-pack", "p1", Map.of("title", "v1"));
            policy(c, PACK_POLICY);
            JsonNode held = data(send(c, "PUT", "/components/pattern-pack/p1", "{\"title\":\"v2\"}", AUTHOR), 202);
            String id = held.at("/pendingChange/id").asText();
            store(c).write("pattern-pack", "p1", Map.of("title", "moved underneath"));   // the base moves

            HttpResponse<String> r = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER);
            assertEquals(409, r.statusCode(), r.body());
            assertEquals("moved underneath", store(c).get("pattern-pack", "p1").orElseThrow().content().get("title"),
                    "a stale change is never applied");
            assertEquals("stale", data(send(c, "GET", "/pending-changes/" + id, null, CHECKER), 200)
                    .get("status").asText());
        }
    }

    /** A DELETE is held like any write, end to end: held and kept, four-eyes, applied once, stale when edited. */
    @Test
    void aHeldDeleteIsAppliedOnlyByAnotherPersonAndNeverOverAnEditedTarget(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            store(c).write("pattern-pack", "d1", Map.of("title", "v1", "owner", "author-1"));
            store(c).write("pattern-pack", "d2", Map.of("title", "v1", "owner", "author-1"));
            policy(c, PACK_POLICY);

            JsonNode held = data(send(c, "DELETE", "/components/pattern-pack/d1", null, AUTHOR), 202);
            String id = held.at("/pendingChange/id").asText();
            assertEquals("delete", data(send(c, "GET", "/pending-changes/" + id, null, CHECKER), 200)
                    .get("operation").asText());
            assertTrue(store(c).exists("pattern-pack", "d1"), "a held delete deletes nothing");
            assertEquals(403, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", SELF).statusCode(),
                    "four-eyes");
            assertTrue(store(c).exists("pattern-pack", "d1"));
            assertTrue(data(send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER), 200)
                    .get("applied").asBoolean());
            assertFalse(store(c).exists("pattern-pack", "d1"), "approved: deleted");
            assertEquals(409, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER).statusCode(),
                    "already decided");

            String stale = data(send(c, "DELETE", "/components/pattern-pack/d2", null, AUTHOR), 202)
                    .at("/pendingChange/id").asText();
            store(c).write("pattern-pack", "d2", Map.of("title", "edited since", "owner", "author-1"));
            HttpResponse<String> r = send(c, "POST", "/pending-changes/" + stale + "/approve", "{}", CHECKER);
            assertEquals(409, r.statusCode(), r.body());
            assertEquals("edited since", store(c).get("pattern-pack", "d2").orElseThrow().content().get("title"),
                    "a stale delete never deletes the edited target");
            assertEquals("stale", data(send(c, "GET", "/pending-changes/" + stale, null, CHECKER), 200)
                    .get("status").asText());
        }
    }

    @Test
    void declineClosesTheChangeUnappliedAndTheAuthorCannotDeclineTheirOwn(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            String id = propose(c, "p1");
            assertEquals(403, send(c, "POST", "/pending-changes/" + id + "/decline", "{}", SELF).statusCode());
            JsonNode d = data(send(c, "POST", "/pending-changes/" + id + "/decline",
                    "{\"reason\":\"wrong space\"}", CHECKER), 200);
            assertEquals("declined", d.at("/pendingChange/status").asText());
            assertEquals("wrong space", d.at("/pendingChange/decisionReason").asText());
            assertFalse(store(c).exists("pattern-pack", "p1"));
            // the target is free again: a new proposal is accepted
            propose(c, "p1");
        }
    }

    // ── ASSURE-MAKER-CHECKER-RESIDUALS-1 (2): the author withdraws their own change ─────────────────

    @Test
    void onlyTheAuthorWithdrawsTheirOwnPendingChange(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            String id = propose(c, "p1");
            HttpResponse<String> other = send(c, "POST", "/pending-changes/" + id + "/withdraw", "{}", CHECKER);
            assertEquals(403, other.statusCode(), "an approver declines; only the author withdraws: " + other.body());
            assertTrue(other.body().contains("only its author"), other.body());
            assertEquals("pending", data(send(c, "GET", "/pending-changes/" + id, null, CHECKER), 200)
                    .get("status").asText(), "a refused withdraw changes nothing");

            // the author needs no capability: AUTHOR (a pipeline-developer) holds no canApproveChanges
            JsonNode w = data(send(c, "POST", "/pending-changes/" + id + "/withdraw",
                    "{\"reason\":\"proposed too early\"}", AUTHOR), 200);
            assertFalse(w.get("applied").asBoolean(), w.toString());
            assertEquals("withdrawn", w.at("/pendingChange/status").asText());
            assertEquals("author-1", w.at("/pendingChange/decidedBy").asText());
            assertEquals("proposed too early", w.at("/pendingChange/decisionReason").asText());
            assertFalse(store(c).exists("pattern-pack", "p1"), "a withdrawn change writes nothing");
            Map<String, Object> rec = PendingChanges.read(root, id);
            assertFalse(PendingChanges.invalid(rec), "the withdrawn record is signed like a declined one");
            assertEquals("withdrawn", rec.get("status"));

            // terminal: nobody can decide or withdraw it again
            assertEquals(409, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER).statusCode(),
                    "approve after withdraw");
            assertEquals(409, send(c, "POST", "/pending-changes/" + id + "/decline", "{}", CHECKER).statusCode());
            assertEquals(409, send(c, "POST", "/pending-changes/" + id + "/withdraw", "{}", AUTHOR).statusCode());
            assertEquals(1, data(send(c, "GET", "/pending-changes?status=withdrawn", null, AUTHOR), 200)
                    .get("total").asInt());

            HttpResponse<String> audit = send(c, "GET", "/audit/search?type=AUDIT&limit=500", null, ADMIN);
            assertEquals(200, audit.statusCode(), audit.body());
            JsonNode row = null;
            for (JsonNode r : JSON.readTree(audit.body()).get("data")) {
                JsonNode a = r.get("attributes");
                if (a != null && "pending-change.withdrawn".equals(a.path("action").asText())) row = a;
            }
            assertTrue(row != null, "a pending-change.withdrawn audit row: " + audit.body());
            assertEquals("author-1", row.path("actor").asText(), row.toString());
            assertEquals(id, row.path("pendingChange").asText(), row.toString());
            assertEquals("proposed too early", row.path("reason").asText(), row.toString());

            propose(c, "p1");   // the target is free again
        }
    }

    @Test
    void theWithdrawGates(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            String id = propose(c, "p1");
            assertEquals(401, send(c, "POST", "/pending-changes/" + id + "/withdraw", "{}", null).statusCode());
            assertEquals(422, send(c, "POST", "/pending-changes/not-an-id/withdraw", "{}", AUTHOR).statusCode());
            assertEquals(404, send(c, "POST", "/pending-changes/pc-20260101000000-abcdef/withdraw", "{}", AUTHOR)
                    .statusCode());
            assertEquals(422, send(c, "POST", "/pending-changes/" + id + "/withdraw",
                    "{\"reason\":\"" + "x".repeat(PendingChanges.MAX_REASON + 1) + "\"}", AUTHOR).statusCode());

            // a decided change: 409, whoever asks
            data(send(c, "POST", "/pending-changes/" + id + "/decline", "{}", CHECKER), 200);
            HttpResponse<String> decided = send(c, "POST", "/pending-changes/" + id + "/withdraw", "{}", AUTHOR);
            assertEquals(409, decided.statusCode(), decided.body());
            assertTrue(decided.body().contains("already declined"), decided.body());

            // an expired change: 409
            String late = propose(c, "p2");
            Map<String, Object> rec = PendingChanges.read(root, late);
            rec.put("expiresAt", "2020-01-01T00:00:00Z");
            PendingChanges.save(root, rec);
            assertEquals(409, send(c, "POST", "/pending-changes/" + late + "/withdraw", "{}", AUTHOR).statusCode());

            // Personal-shaped (no Authenticator, no Subject): refused like approve / decline — no author to be
            String open = propose(c, "p3");
            Authenticators.forTest(null);
            HttpResponse<String> anon = send(c, "POST", "/pending-changes/" + open + "/withdraw", "{}", null);
            assertEquals(403, anon.statusCode(), anon.body());
            assertTrue(anon.body().contains("authenticated"), anon.body());
        }
    }

    @Test
    void oneChangePerTargetAtATime(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            propose(c, "p1");
            assertEquals(409, send(c, "POST", "/components/pattern-pack", "{\"id\":\"p1\",\"title\":\"again\"}",
                    AUTHOR).statusCode());
        }
    }

    @Test
    void theDecideGates(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            String id = propose(c, "p1");
            assertEquals(401, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", null).statusCode());
            assertEquals(422, send(c, "POST", "/pending-changes/not-an-id/approve", "{}", CHECKER).statusCode());
            assertEquals(404, send(c, "POST", "/pending-changes/pc-20260101000000-abcdef/approve", "{}", CHECKER)
                    .statusCode());
            assertEquals(404, send(c, "POST", "/pending-changes/pc-20260101000000-abcdef/decline", "{}", CHECKER)
                    .statusCode());
            assertEquals(403, send(c, "POST", "/pending-changes/pc-20260101000000-abcdef/decline", "{}", AUTHOR)
                    .statusCode(), "the route gate: no canApproveChanges");
            // D-P13: PLAIN holds canApproveChanges and NOT the route's canAuthorWorkbench — a checker need not be
            // a builder. The write is the AUTHOR's, so it applies.
            JsonNode ok = data(send(c, "POST", "/pending-changes/" + id + "/approve", "{}", PLAIN), 200);
            assertTrue(ok.get("applied").asBoolean(), ok.toString());
            assertTrue(store(c).exists("pattern-pack", "p1"));
        }
    }

    // ── D-P13 with the SEEDED role table: an admin (canApproveChanges, no canAuthorWorkbench) approves a builder ──

    /** An Authenticator shaped like the OIDC one: `Bearer <user>:<role>` → the role's grants in THIS Space's table. */
    private static void armWithSeededRoles() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if (!h.startsWith("Bearer ") || !h.contains(":")) return Optional.empty();
            String user = h.substring(7, h.indexOf(':')), role = h.substring(h.indexOf(':') + 1);
            Roles.Def def = Roles.effective(ex).get(role);
            if (def == null) return Optional.empty();
            ComponentAccess.heldRoles(ex, Set.of(role));
            return Optional.of(new Subject(user, def.capabilities(), def.dataScopes()));
        });
    }

    @Test
    void aSeededAdminApprovesABuildersChangeAndTheAuthorIsRecheckedAtApplyTime(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        armWithSeededRoles();
        String builder = "Bearer dana:pipeline-developer", admin = "Bearer ada:admin";
        try (Ctx c = open(cfg, root)) {
            assertFalse(Roles.SEED.get("admin").capabilities().contains("canAuthorWorkbench"), "premise: admin is no builder");
            assertTrue(Roles.SEED.get("admin").capabilities().contains("canApproveChanges"));
            data(send(c, "PUT", "/settings/approval", PACK_POLICY, admin.replace("ada", "ops-admin")), 200);

            String first = data(send(c, "POST", "/components/pattern-pack", "{\"id\":\"p1\",\"title\":\"A\"}", builder),
                    202).at("/pendingChange/id").asText();
            JsonNode ok = data(send(c, "POST", "/pending-changes/" + first + "/approve", "{}", admin), 200);
            assertTrue(ok.get("applied").asBoolean(), ok.toString());
            assertEquals("dana", store(c).get("pattern-pack", "p1").orElseThrow().content().get("owner"),
                    "applied as its author");

            // The author loses the grant in THIS Space's role table while a second change waits: re-checked now.
            String second = data(send(c, "POST", "/components/pattern-pack", "{\"id\":\"p2\",\"title\":\"B\"}", builder),
                    202).at("/pendingChange/id").asText();
            Roles.write(root, Map.of("pipeline-developer", new Roles.Def(Set.of("canRequestShares"), null)), List.of());
            HttpResponse<String> refused = send(c, "POST", "/pending-changes/" + second + "/approve", "{}", admin);
            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("no longer holds"), refused.body());
            assertFalse(store(c).exists("pattern-pack", "p2"));
            assertEquals("pending", data(send(c, "GET", "/pending-changes/" + second, null, admin), 200)
                    .get("status").asText());
        }
    }

    @Test
    void approvingWithNoSubjectIsRefused(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            String id = propose(c, "p1");
            Authenticators.forTest(null);   // no Authenticator: the route gate is a no-op, the handler is not
            HttpResponse<String> r = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", null);
            assertEquals(403, r.statusCode(), r.body());
            assertTrue(r.body().contains("authenticated"), r.body());
        }
    }

    @Test
    void anExpiredChangeCannotBeApproved(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            String id = propose(c, "p1");
            Map<String, Object> rec = PendingChanges.read(root, id);
            rec.put("expiresAt", "2020-01-01T00:00:00Z");
            PendingChanges.save(root, rec);
            assertEquals(409, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER).statusCode());
            assertEquals("expired", data(send(c, "GET", "/pending-changes/" + id, null, CHECKER), 200)
                    .get("status").asText());
        }
    }

    @Test
    void aPipelineEditThroughItsOwnRouteIsHeldAndAppliedThroughTheSameRoute(@TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(root, root)) {   // the Pipeline must live under the write root to be edited
            policy(c, "{\"approval\":{\"pipeline\":{\"required\":true}}}");
            Path file = c.svc.pathFor("mini_etl").orElseThrow();
            String before = Files.readString(file);
            JsonNode held = data(send(c, "POST", "/pipelines/mini_etl/label", "{\"name\":\"Mini ETL (renamed)\"}",
                    AUTHOR), 202);
            String id = held.at("/pendingChange/id").asText();
            assertEquals(before, Files.readString(file), "the config is untouched while the change waits");
            assertEquals("pipeline", held.at("/pendingChange/kind").asText());

            JsonNode ok = data(send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER), 200);
            assertTrue(ok.get("applied").asBoolean(), ok.toString());
            assertEquals(200, ok.at("/result/status").asInt(), ok.toString());
            assertTrue(Files.readString(file).contains("Mini ETL (renamed)"), "applied through the label route");
        }
    }

    /** The fixture Pipeline under the write root, switched off — a rename refuses an active one. */
    private Ctx openInactive(Path root) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(root, "");
        Map<String, Object> raw = com.gamma.config.io.ConfigLoader.filesystem().decode(pipe.toString());
        Map<String, Object> patched = new java.util.LinkedHashMap<>(raw);
        patched.put("active", false);
        Files.writeString(pipe, com.gamma.config.io.ConfigCodec.toToon(patched));
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

    /**
     * Verification finding 2: a Pipeline rename rewrites its dependents (Expectation / Decision Rule targets,
     * Dataset store refs, Alert Rule onPipeline, Enrichment triggers). With only the `pipeline` hold, a policy
     * on a DEPENDENT's kind was bypassed. The rename is refused, naming the governed dependent.
     */
    @Test
    void aRenameThatWouldRewriteAGovernedDependentIsRefused(@TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = openInactive(root)) {
            store(c).write("expectation", "rows_present", Map.of("target", "mini_etl", "kind", "row_count", "min", 1));
            policy(c, "{\"approval\":{\"expectation\":{\"required\":true}}}");
            HttpResponse<String> r = send(c, "POST", "/pipelines/mini_etl/rename", "{\"newId\":\"mini_two\"}", AUTHOR);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("expectation 'rows_present'"), r.body());
            assertEquals("mini_etl", store(c).get("expectation", "rows_present").orElseThrow().content().get("target"),
                    "the governed dependent is untouched");
            assertTrue(c.svc.pathFor("mini_etl").isPresent(), "and so is the Pipeline");
            assertEquals(0, data(send(c, "GET", "/pending-changes", null, AUTHOR), 200).get("total").asInt(),
                    "refused, not held: the rename never became a Pending Change");
        }
    }

    /** Verification finding 5: changing the policy is audited as its own row — who, and before / after. */
    @Test
    void changingThePolicyIsAuditedWithBeforeAndAfter(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            policy(c, "{\"approval\":{}}");   // and lifted again
            HttpResponse<String> audit = send(c, "GET", "/audit/search?type=AUDIT&limit=500", null, ADMIN);
            assertEquals(200, audit.statusCode(), audit.body());
            JsonNode rows = JSON.readTree(audit.body()).get("data");
            JsonNode lifted = null;
            for (JsonNode r : rows) {
                JsonNode a = r.get("attributes");
                if (a != null && "approval-policy.changed".equals(a.path("action").asText())
                        && JSON.readTree(a.path("after").asText()).path("approval").isEmpty()) lifted = a;
            }
            assertTrue(lifted != null, "an approval-policy.changed row for the lift: " + audit.body());
            assertEquals("admin-1", lifted.path("actor").asText(), "carries the actor: " + lifted);
            assertTrue(JSON.readTree(lifted.path("before").asText()).path("approval").has("pattern-pack"),
                    "carries the policy it replaced: " + lifted);
        }
    }

    // ── re-verification finding 2: a Pending Change record is tamper-evident and replays only maker-checker routes ──

    /** A record naming PUT /access/roles as seeded roles — what the verifier forged to rewrite roles.toon. */
    private static Map<String, Object> forged(String id) {
        Map<String, Object> rec = new java.util.LinkedHashMap<>();
        rec.put("id", id);
        rec.put("kind", "pattern-pack");
        rec.put("name", "p1");
        rec.put("operation", "create");
        rec.put("status", "pending");
        rec.put("author", "someone-else");
        rec.put("authorRoles", List.of("super"));
        rec.put("createdAt", "2026-09-26T00:00:00Z");
        rec.put("expiresAt", "2099-01-01T00:00:00Z");
        rec.put("approverCapability", "canApproveChanges");
        rec.put("fourEyes", true);
        rec.put("baseVersion", "absent");
        rec.put("proposedVersion", "absent");
        rec.put("request", Map.of("method", "PUT", "path", "/access/roles",
                "body", "{\"roles\":[{\"name\":\"developer\",\"capabilities\":[\"canAdminister\"]}]}", "headers", Map.of()));
        return rec;
    }

    @Test
    void aForgedRecordFailsItsIntegrityCheckAndCannotBeApproved(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            String id = "pc-20260926000000-00f00d";
            Files.createDirectories(root.resolve("pending-changes"));
            Files.writeString(root.resolve("pending-changes").resolve(id + ".json"),
                    JSON.writeValueAsString(forged(id)));   // written straight to disk: no server MAC
            JsonNode listed = data(send(c, "GET", "/pending-changes", null, CHECKER), 200);
            assertEquals("invalid", listed.at("/items/0/status").asText(), listed.toString());
            HttpResponse<String> r = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("integrity"), r.body());
            assertFalse(Files.exists(root.resolve("roles.toon")), "nothing was dispatched");
        }
    }

    @Test
    void aGenuineRecordNamingANonMakerCheckerRouteIsNeverReplayed(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            String id = "pc-20260926000000-0ddba1";
            PendingChanges.save(root, forged(id));   // a VALID MAC — the door is the route allowlist
            HttpResponse<String> r = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("not a maker-checker route"), r.body());
            assertFalse(Files.exists(root.resolve("roles.toon")), "nothing was dispatched");
        }
    }

    @Test
    void editingARealRecordOnDiskInvalidatesIt(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            String id = propose(c, "p1");
            Path f = root.resolve("pending-changes").resolve(id + ".json");
            Files.writeString(f, Files.readString(f).replace("Carousel", "Swapped"));   // a tampered body
            HttpResponse<String> r = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER);
            assertEquals(409, r.statusCode(), r.body());
            assertFalse(store(c).exists("pattern-pack", "p1"));
            assertEquals("invalid", data(send(c, "GET", "/pending-changes/" + id, null, CHECKER), 200)
                    .get("status").asText());
        }
    }

    /** Re-verification finding 3: an author whose roles were never recorded cannot be re-checked — 403, not applied. */
    @Test
    void anAuthorWhoseRolesWereNeverRecordedIsRefusedAtApply(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        // the write root is a SUBDIR: its key lives in the sibling <root>.secrets/, still inside the TempDir
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            String id = data(send(c, "POST", "/components/pattern-pack", "{\"id\":\"p9\",\"title\":\"x\"}",
                    "Bearer noroles"), 202).at("/pendingChange/id").asText();
            HttpResponse<String> r = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER);
            assertEquals(403, r.statusCode(), r.body());
            assertTrue(r.body().contains("roles are unknown"), r.body());
            assertFalse(store(c).exists("pattern-pack", "p9"));
        }
    }

    // ── the job kind (operator, 2026-09-28): /jobs writers hold; a Job's RUN-time writes do not ──────────

    private static final String JOB_POLICY = "{\"approval\":{\"job\":{\"required\":true},\"dataset\":{\"required\":true}}}";
    private static final String BEAT = "{\"name\":\"beat\",\"type\":\"maintenance\",\"task\":\"heartbeat\",\"cron\":\"0 3 * * *\"}";

    private static Path jobToon(Path root) {
        return root.resolve("jobs").resolve("beat_job.toon");
    }

    private static final String REPORT = "{\"name\":\"mailer\",\"type\":\"report\",\"cron\":\"0 3 * * *\","
            + "\"recipients\":\"ops@example.com\",\"attach\":\"false\"}";

    /**
     * ASSURE-XLSX-ATTACHMENTS-1 (operator 2026-09-29): turning {@code attach: true} on is APPROVAL-REQUIRED — held as
     * a Pending Change with NO approval policy configured, four-eyes, applied only by a second person.
     */
    @Test
    void turningAttachOnIsHeldForFourEyesEvenWithNoPolicy(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> plain = send(c, "POST", "/jobs", REPORT, AUTHOR);
            assertTrue(plain.statusCode() < 300 && plain.statusCode() != 202, "no policy, no attach: applied at once — " + plain.body());

            JsonNode held = data(send(c, "PUT", "/jobs/mailer", REPORT.replace("\"false\"", "\"true\""), AUTHOR), 202);
            String id = held.at("/pendingChange/id").asText();
            assertEquals("job", held.at("/pendingChange/kind").asText(), held.toString());
            assertEquals("false", data(send(c, "GET", "/jobs/mailer", null, AUTHOR), 200).at("/attach").asText(),
                    "a held flip writes nothing");

            assertEquals(403, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", SELF).statusCode(),
                    "four-eyes: the author may never approve their own attach");
            assertEquals(403, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", AUTHOR).statusCode());
            JsonNode ok = data(send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER), 200);
            assertTrue(ok.get("applied").asBoolean(), ok.toString());
            assertEquals("true", data(send(c, "GET", "/jobs/mailer", null, AUTHOR), 200).at("/attach").asText());
            // round 3: the approval recorded the fingerprint of the Job AS LOADED — the version the run-time lock sends
            com.gamma.job.JobConfig loaded = c.svc.jobServiceOrCreate().jobConfig("mailer").orElseThrow();
            assertTrue(com.gamma.job.AttachApprovals.approved(root, "mailer",
                    com.gamma.job.AttachApprovals.fingerprint(loaded, root)), "approval fingerprint recorded");

            // an already-approved attach Job: a schedule edit goes under the normal (here: no) policy...
            HttpResponse<String> edit = send(c, "PUT", "/jobs/mailer",
                    REPORT.replace("\"false\"", "\"true\"").replace("0 3", "0 4"), AUTHOR);
            assertTrue(edit.statusCode() < 300 && edit.statusCode() != 202, edit.body());
            // ...but changing WHO gets it or WHAT is attached re-opens the four-eyes hold (finding 4)
            String approved = REPORT.replace("\"false\"", "\"true\"").replace("0 3", "0 4");
            for (String change : List.of(
                    approved.replace("ops@example.com", "ops@example.com,leak@example.com"),
                    approved.replace("}", ",\"dataset\":\"other_ds\"}"),
                    approved.replace("}", ",\"format\":\"xlsx\"}"),
                    approved.replace("}", ",\"scope\":\"dataset\"}"),
                    approved.replace("}", ",\"measures\":\"count\"}"),
                    approved.replace("}", ",\"group_by\":\"msisdn\"}"))) {
                JsonNode reheld = data(send(c, "PUT", "/jobs/mailer", change, AUTHOR), 202);
                data(send(c, "POST", "/pending-changes/" + reheld.at("/pendingChange/id").asText() + "/withdraw", "{}", AUTHOR), 200);
            }

            // a NEW report Job created with attach: true is held too
            data(send(c, "POST", "/jobs", REPORT.replace("mailer", "mailer2").replace("\"false\"", "\"true\""), AUTHOR), 202);
            assertEquals(404, send(c, "GET", "/jobs/mailer2", null, AUTHOR).statusCode());
        } finally {
            System.clearProperty("jobs.audit.dir");
        }
    }

    // ── round 4: the attachment approval is pinned to the content fixed at HOLD time ─────────────────

    private static final String DS_REPORT = "{\"name\":\"mailer\",\"type\":\"report\",\"cron\":\"0 3 * * *\","
            + "\"scope\":\"dataset\",\"dataset\":\"sales_ds\",\"recipients\":\"ops@example.com\",\"attach\":\"true\"}";

    private static void seedDataset(Path root, String sql) throws Exception {
        new com.gamma.pipeline.ViewStore(root.resolve("views")).write(new com.gamma.pipeline.ViewDefinition(
                "sales_view", "flow-x", List.of(), sql, "2026-07-08T00:00:00Z"));
        new ComponentStore(root.resolve("registry")).write("dataset", "sales_ds", Map.of("view", "sales_view"));
    }

    private static Map<String, Object> approvalOf(Ctx c) {
        return com.gamma.job.AttachApprovals.approval(c.root, "mailer").orElse(null);
    }

    private static boolean liveApproved(Ctx c) {
        com.gamma.job.JobConfig loaded = c.svc.jobServiceOrCreate().jobConfig("mailer").orElseThrow();
        return com.gamma.job.AttachApprovals.approved(c.root, "mailer", com.gamma.job.AttachApprovals.fingerprint(loaded, c.root));
    }

    private String proposeAndApprove(Ctx c, String method, String path, String body) throws Exception {
        String id = data(send(c, method, path, body, AUTHOR), 202).at("/pendingChange/id").asText();
        assertTrue(data(send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER), 200).get("applied").asBoolean());
        return id;
    }

    // ── round 5: an approval counts only if its HMAC-signed Pending Change agrees ────────────────────

    @SuppressWarnings("unchecked")
    private static void editApproval(Ctx c, String job, java.util.function.Consumer<Map<String, Object>> edit) throws Exception {
        Path f = c.root.resolve(com.gamma.job.AttachApprovals.FILE);
        Map<String, Object> all = JSON.readValue(f.toFile(), Map.class);
        edit.accept(all);
        Files.write(f, JSON.writeValueAsBytes(all));
    }

    @Test
    void aCopiedForgedOrUnbackedApprovalRecordIsNeverHonoured(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        try (Ctx c = open(cfg, root)) {
            seedDataset(root, "SELECT 1 AS n");
            String id = proposeAndApprove(c, "POST", "/jobs", DS_REPORT);
            assertTrue(liveApproved(c), "the genuine approval verifies");
            Map<String, Object> genuine = new java.util.LinkedHashMap<>(approvalOf(c));

            // (1) a forged nonce
            editApproval(c, "mailer", all -> ((Map<String, Object>) all.get("mailer")).put("nonce", "00ff"));
            assertFalse(liveApproved(c), "forged nonce");
            editApproval(c, "mailer", all -> all.put("mailer", genuine));
            assertTrue(liveApproved(c));

            // (2) a record whose fingerprint was edited to match content nobody approved
            seedDataset(root, "SELECT secret FROM everything");
            com.gamma.job.JobConfig loaded = c.svc.jobServiceOrCreate().jobConfig("mailer").orElseThrow();
            String now = com.gamma.job.AttachApprovals.fingerprint(loaded, root);
            editApproval(c, "mailer", all -> ((Map<String, Object>) all.get("mailer")).put("fingerprint", now));
            assertFalse(liveApproved(c), "the signed Pending Change fixed a different fingerprint");
            seedDataset(root, "SELECT 1 AS n");
            editApproval(c, "mailer", all -> all.put("mailer", genuine));

            // (3) a record copied onto another Job, fingerprint recomputed for it
            Map<String, Object> other = new java.util.LinkedHashMap<>(com.gamma.job.AttachApprovals.asSection(loaded));
            other.put("name", "mailer2");
            String otherFp = com.gamma.job.AttachApprovals.fingerprint(other, root);
            editApproval(c, "mailer2", all -> {
                Map<String, Object> copy = new java.util.LinkedHashMap<>(genuine);
                copy.put("fingerprint", otherFp);
                all.put("mailer2", copy);
            });
            assertFalse(com.gamma.job.AttachApprovals.approved(root, "mailer2", otherFp), "a copied record");

            // (4) the Pending Change edited on disk (it cannot be re-signed): its MAC fails
            Path pcFile;
            try (var files = Files.walk(root)) {
                pcFile = files.filter(p -> p.getFileName().toString().startsWith(id)).findFirst().orElseThrow();
            }
            String signed = Files.readString(pcFile);
            Files.writeString(pcFile, signed.replace("checker-1", "checker-2"));
            assertFalse(liveApproved(c), "a re-signed (edited) Pending Change fails its MAC");
            Files.writeString(pcFile, signed);
            assertTrue(liveApproved(c));

            // (5) a withdrawn / non-approved Pending Change backs nothing
            Map<String, Object> pc = JSON.readValue(signed, Map.class);
            pc.put("status", "withdrawn");
            PendingChanges.save(root, pc);   // a genuinely signed record, but not approved
            assertFalse(liveApproved(c), "a withdrawn Pending Change");
        } finally {
            System.clearProperty("jobs.audit.dir");
        }
    }

    /** Finding 1: a write landing between proposal and approve (here, the view's SQL) is never blessed — 409. */
    @Test
    void aRaceWriteBetweenProposalAndApproveIsNotBlessed(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        try (Ctx c = open(cfg, root)) {
            seedDataset(root, "SELECT 1 AS n");
            String id = data(send(c, "POST", "/jobs", DS_REPORT, AUTHOR), 202).at("/pendingChange/id").asText();
            seedDataset(root, "SELECT secret FROM everything");   // the race
            HttpResponse<String> r = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER);
            assertEquals(409, r.statusCode(), r.body());
            assertEquals(null, approvalOf(c), "nothing was blessed");
            assertEquals(404, send(c, "GET", "/jobs/mailer", null, AUTHOR).statusCode(), "nothing was applied");
        } finally {
            System.clearProperty("jobs.audit.dir");
        }
    }

    /** Finding 2: approving a cron-only edit (held by the policy, not by the attachment rule) blesses nothing. */
    @Test
    void aCronOnlyApprovalDoesNotReBlessTheAttachment(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        try (Ctx c = open(cfg, root)) {
            seedDataset(root, "SELECT 1 AS n");
            policy(c, JOB_POLICY);
            String first = proposeAndApprove(c, "POST", "/jobs", DS_REPORT);
            assertEquals(first, approvalOf(c).get("pendingChange"));
            JsonNode held = data(send(c, "PUT", "/jobs/mailer", DS_REPORT.replace("0 3", "0 5"), AUTHOR), 202);
            assertTrue(held.at("/pendingChange/attachFingerprint").isMissingNode(), "a cron edit carries no attach approval");
            data(send(c, "POST", "/pending-changes/" + held.at("/pendingChange/id").asText() + "/approve", "{}", CHECKER), 200);
            assertEquals(first, approvalOf(c).get("pendingChange"), "the attachment approval is the ORIGINAL one");
            assertTrue(liveApproved(c));
        } finally {
            System.clearProperty("jobs.audit.dir");
        }
    }

    /** Findings 4 + 5: a view SQL change un-approves the live Job; re-saving it is the re-approve path. */
    @Test
    void aViewChangeUnapprovesAndReSavingReApproves(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        try (Ctx c = open(cfg, root)) {
            seedDataset(root, "SELECT 1 AS n");
            String first = proposeAndApprove(c, "POST", "/jobs", DS_REPORT);
            assertTrue(liveApproved(c));
            seedDataset(root, "SELECT 2 AS n");
            assertFalse(liveApproved(c), "the view's SQL is inside the fingerprint");
            String again = proposeAndApprove(c, "PUT", "/jobs/mailer", DS_REPORT);   // re-save = re-approve
            assertTrue(liveApproved(c));
            assertEquals(again, approvalOf(c).get("pendingChange"));
            assertFalse(first.equals(again));
        } finally {
            System.clearProperty("jobs.audit.dir");
        }
    }

    /** Deleting a Job revokes its approval: an identical re-create is held again. */
    @Test
    void deletingAJobRevokesItsAttachmentApproval(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        try (Ctx c = open(cfg, root)) {
            seedDataset(root, "SELECT 1 AS n");
            proposeAndApprove(c, "POST", "/jobs", DS_REPORT);
            assertTrue(send(c, "DELETE", "/jobs/mailer", null, AUTHOR).statusCode() < 300);
            assertEquals(null, approvalOf(c), "revoked");
            data(send(c, "POST", "/jobs", DS_REPORT, AUTHOR), 202);
        } finally {
            System.clearProperty("jobs.audit.dir");
        }
    }

    /** Re-verification finding 2: /config/write and /config/patch reach the SAME mandatory hold (via PendingChanges.hold). */
    @Test
    void configWriteAndPatchCannotTurnAttachOnWithoutFourEyes(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        try (Ctx c = open(cfg, root)) {
            String attaching = REPORT.replace("\"false\"", "\"true\"").replace("mailer", "held1");
            JsonNode held = data(send(c, "POST", "/config/write", "{\"type\":\"job\",\"config\":{\"job\":" + attaching + "}}",
                    AUTHOR), 202);
            assertEquals("job", held.at("/pendingChange/kind").asText(), held.toString());
            try (var files = Files.walk(root)) {
                assertFalse(files.anyMatch(x -> x.getFileName().toString().equals("held1_job.toon")), "held: nothing written");
            }

            HttpResponse<String> plain = send(c, "POST", "/config/write", "{\"type\":\"job\",\"config\":{\"job\":" + REPORT + "}}", AUTHOR);
            assertTrue(plain.statusCode() < 300 && plain.statusCode() != 202, plain.body());
            data(send(c, "POST", "/config/patch", "{\"type\":\"job\",\"name\":\"mailer\",\"patch\":{\"job\":{\"attach\":\"true\"}}}",
                    AUTHOR), 202);
            try (var files = Files.walk(root)) {
                for (Path f : files.filter(x -> x.getFileName().toString().equals("mailer_job.toon")).toList())
                    assertFalse(Files.readString(f).contains("true"), "the held patch wrote nothing: " + Files.readString(f));
            }
        } finally {
            System.clearProperty("jobs.audit.dir");
        }
    }

    @Test
    void aJobCreateAndUpdateAreHeldAndAppliedOnlyByASecondPerson(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        try (Ctx c = open(cfg, root)) {
            policy(c, JOB_POLICY);
            JsonNode held = data(send(c, "POST", "/jobs", BEAT, AUTHOR), 202);
            String create = held.at("/pendingChange/id").asText();
            assertEquals("job", held.at("/pendingChange/kind").asText(), held.toString());
            assertFalse(Files.exists(jobToon(root)), "a held Job create writes nothing");
            assertEquals(404, send(c, "GET", "/jobs/beat", null, AUTHOR).statusCode(), "and registers nothing");

            HttpResponse<String> own = send(c, "POST", "/pending-changes/" + create + "/approve", "{}", SELF);
            assertEquals(403, own.statusCode(), "four-eyes: the author may never approve their own Job");
            assertFalse(Files.exists(jobToon(root)));

            JsonNode ok = data(send(c, "POST", "/pending-changes/" + create + "/approve", "{}", CHECKER), 200);
            assertTrue(ok.get("applied").asBoolean(), ok.toString());
            JsonNode job = data(send(c, "GET", "/jobs/beat", null, AUTHOR), 200);
            assertEquals("0 3 * * *", job.get("cron").asText());
            assertTrue(Files.readString(jobToon(root)).contains("author-1"),
                    "MAINT-TASK-AUTHORITY-1: the applied Job carries its AUTHOR, server-stamped, not the approver");
            assertFalse(Files.readString(jobToon(root)).contains("checker-1"));

            String update = data(send(c, "PUT", "/jobs/beat", BEAT.replace("0 3 * * *", "0 4 * * *"), AUTHOR), 202)
                    .at("/pendingChange/id").asText();
            assertEquals("0 3 * * *", data(send(c, "GET", "/jobs/beat", null, AUTHOR), 200).get("cron").asText(),
                    "a held update leaves the live Job as it was");
            assertEquals(403, send(c, "POST", "/pending-changes/" + update + "/approve", "{}", SELF).statusCode());
            assertTrue(data(send(c, "POST", "/pending-changes/" + update + "/approve", "{}", CHECKER), 200)
                    .get("applied").asBoolean());
            assertEquals("0 4 * * *", data(send(c, "GET", "/jobs/beat", null, AUTHOR), 200).get("cron").asText());

            // the other /jobs writers reach the hold too — reschedule and delete
            data(send(c, "POST", "/jobs/beat/reschedule", "{\"cron\":\"0 5 * * *\"}", AUTHOR), 202);
            assertEquals("0 4 * * *", data(send(c, "GET", "/jobs/beat", null, AUTHOR), 200).get("cron").asText());
        } finally {
            System.clearProperty("jobs.audit.dir");
        }
    }

    @Test
    void aJobDeleteIsHeld(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        try (Ctx c = open(cfg, root)) {
            data(send(c, "POST", "/jobs", BEAT, AUTHOR), 200);   // policy off: written at once
            policy(c, JOB_POLICY);
            String id = data(send(c, "DELETE", "/jobs/beat", null, AUTHOR), 202).at("/pendingChange/id").asText();
            assertTrue(Files.exists(jobToon(root)), "a held delete deletes nothing");
            assertTrue(data(send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER), 200)
                    .get("applied").asBoolean());
            assertFalse(Files.exists(jobToon(root)));
        } finally {
            System.clearProperty("jobs.audit.dir");
        }
    }

    @Test
    void aRunTimeMaterializeIsNotHeld(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        new com.gamma.pipeline.ViewStore(root.resolve("views")).write(new com.gamma.pipeline.ViewDefinition("orders_view",
                "flow-x", List.of(), "SELECT * FROM (VALUES ('EU',10.0),('US',5.0)) AS t(region,amount)",
                "2026-09-28T00:00:00Z"));
        new ComponentStore(root.resolve("registry")).write("dataset", "orders", Map.of("view", "orders_view"));
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        System.setProperty("data.dir", tmp.resolve("data").toString());   // else the Parquet lands in the CWD
        try (Ctx c = open(cfg, root)) {
            // MaterializeTask resolves the default Space's registry through this property AT RUN TIME
            System.setProperty("assist.write.root", root.toString());
            policy(c, JOB_POLICY);   // job AND dataset governed
            HttpResponse<String> r = send(c, "POST", "/datasets/orders/materialize", "{\"target\":\"orders_daily\"}", "Bearer ops");
            assertEquals(202, r.statusCode(), r.body());
            assertTrue(r.body().contains("runId") && !r.body().contains("pendingChange"),
                    "a Job run is started, not a Pending Change: " + r.body());
            String run = JSON.readTree(r.body()).at("/data/runId").asText();
            for (int i = 0; i < 100; i++) {   // let the run finish, then look for anything it held
                String status = data(send(c, "GET", "/jobs/runs/" + run, null, ADMIN), 200).get("status").asText();
                if (!"running".equalsIgnoreCase(status) && !"queued".equalsIgnoreCase(status)) break;
                Thread.sleep(50);
            }
            JsonNode done = data(send(c, "GET", "/jobs/runs/" + run, null, ADMIN), 200);
            assertEquals("success", done.get("status").asText().toLowerCase(java.util.Locale.ROOT), done.toString());
            assertTrue(store(c).exists("dataset", "orders_daily"),
                    "the run wrote its governed target Dataset straight away");
            assertEquals(0, data(send(c, "GET", "/pending-changes", null, ADMIN), 200).get("total").asInt(),
                    "a Job's run-time writes are not held, by decision");
        } finally {
            System.clearProperty("assist.write.root");
            System.clearProperty("jobs.audit.dir");
            System.clearProperty("data.dir");
        }
    }
}

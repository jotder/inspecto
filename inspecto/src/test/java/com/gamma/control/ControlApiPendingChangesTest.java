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

    @BeforeEach
    void armAuthenticator() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case AUTHOR -> Optional.of(new Subject("author-1", Set.of("canAuthorWorkbench")));
            case CHECKER -> Optional.of(new Subject("checker-1", Set.of("canAuthorWorkbench", "canApproveChanges")));
            case SELF -> Optional.of(new Subject("author-1", Set.of("canAuthorWorkbench", "canApproveChanges")));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister")));
            case PLAIN -> Optional.of(new Subject("plain-1", Set.of("canApproveChanges")));
            default -> Optional.empty();
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
    void policyOffLeavesEveryWriteExactlyAsBefore(@TempDir Path cfg, @TempDir Path root) throws Exception {
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
    void thePolicyIsValidatedFailClosedAndAdministratorOnly(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(403, send(c, "PUT", "/settings/approval", PACK_POLICY, AUTHOR).statusCode(),
                    "an author cannot lift (or set) the policy");
            assertEquals(422, send(c, "PUT", "/settings/approval",
                    "{\"approval\":{\"job\":{\"required\":true}}}", ADMIN).statusCode(), "job is not governable");
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
    void aPolicyThatCouldNeverApproveIsRefusedOnABuildWithNoAuthenticator(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        Authenticators.forTest(null);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c, "PUT", "/settings/approval", PACK_POLICY, null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("signed-in"), r.body());
        }
    }

    @Test
    void anUnreadablePolicyFileHoldsEveryGovernableKind(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(root.resolve(ApprovalPolicy.FILE), "approval:\n  pattern-pack: 7\n");
            assertTrue(data(send(c, "GET", "/settings/approval", null, AUTHOR), 200).get("failedClosed").asBoolean());
            propose(c, "p1");
            assertFalse(store(c).exists("pattern-pack", "p1"), "fail closed: held, not written");
        }
    }

    // ── S2 + S3: propose → approve ──────────────────────────────────────────────────────────────────

    @Test
    void anAuthorProposesAndADifferentPersonApproves(@TempDir Path cfg, @TempDir Path root) throws Exception {
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
    void aChangeAgainstAStaleBaseIsRefusedAndClosedAsStale(@TempDir Path cfg, @TempDir Path root) throws Exception {
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

    @Test
    void declineClosesTheChangeUnappliedAndTheAuthorCannotDeclineTheirOwn(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
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

    @Test
    void oneChangePerTargetAtATime(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            policy(c, PACK_POLICY);
            propose(c, "p1");
            assertEquals(409, send(c, "POST", "/components/pattern-pack", "{\"id\":\"p1\",\"title\":\"again\"}",
                    AUTHOR).statusCode());
        }
    }

    @Test
    void theDecideGates(@TempDir Path cfg, @TempDir Path root) throws Exception {
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
            // PLAIN holds canApproveChanges but not the route's own canAuthorWorkbench: the replay refuses it,
            // and the change stays pending for someone who may apply it.
            HttpResponse<String> r = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", PLAIN);
            assertEquals(403, r.statusCode(), r.body());
            assertEquals("pending", data(send(c, "GET", "/pending-changes/" + id, null, CHECKER), 200)
                    .get("status").asText());
            assertFalse(store(c).exists("pattern-pack", "p1"));
        }
    }

    @Test
    void approvingWithNoSubjectIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
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
    void anExpiredChangeCannotBeApproved(@TempDir Path cfg, @TempDir Path root) throws Exception {
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
    void aPipelineEditThroughItsOwnRouteIsHeldAndAppliedThroughTheSameRoute(@TempDir Path root) throws Exception {
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
    void aRenameThatWouldRewriteAGovernedDependentIsRefused(@TempDir Path root) throws Exception {
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
}

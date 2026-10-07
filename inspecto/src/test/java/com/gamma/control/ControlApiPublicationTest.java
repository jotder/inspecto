package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.PublicationApproval;
import com.gamma.job.PublicationDestinations;
import com.gamma.service.CollectorService;
import com.gamma.service.ReservedConfigPaths;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.gamma.access.Roles;
import com.gamma.access.ComponentAccess;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-BI-PUBLICATION-1 (operator 2026-09-29): the per-Space publication destination allowlist, and the
 * MANDATORY four-eyes hold on a {@code publish.postgres} Job that pins its CONTENT — any Job parameter, through
 * {@code /jobs}, {@code /config/write} or {@code /config/patch}, is held; approval records the content fingerprint
 * the run re-checks; a Connection with {@code insecure_tls} needs an administrator to approve. Real HTTP.
 */
class ControlApiPublicationTest {

    @TempDir static Path auditDir;
    private static String priorAuditDir;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void pinJobsAuditDir() {
        priorAuditDir = System.getProperty("jobs.audit.dir");
        System.setProperty("jobs.audit.dir", auditDir.resolve("jobs_audit").toString());
    }

    @AfterAll
    static void restoreJobsAuditDir() {
        if (priorAuditDir != null) System.setProperty("jobs.audit.dir", priorAuditDir);
        else System.clearProperty("jobs.audit.dir");
    }

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** author = super (authors Jobs), checker = admin (approves, administers), approver = canApproveChanges only. */
    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            String[] who = switch (auth) {
                case "Bearer author" -> new String[] {"author-1", "super"};
                case "Bearer checker" -> new String[] {"checker-1", "admin"};
                default -> null;
            };
            if ("Bearer approver".equals(auth))
                return Optional.of(new Subject("approver-1", Set.of("canApproveChanges")));
            if ("Bearer builder".equals(auth))
                return Optional.of(new Subject("builder-1", Set.of("canAuthorWorkbench")));
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            ComponentAccess.heldRoles(ex, Set.of(who[1]));
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() { Authenticators.forTest(null); }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path root = Files.createDirectories(dir.resolve("wr"));
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            seedApproverRoster(root);   // OIDC-shaped Authenticator: the Space's approver roster decides
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /** Assert {@code r} was held for four-eyes and return the Pending Change id. */
    private static String held(HttpResponse<String> r) throws Exception {
        JsonNode pc = data(r, 202).get("pendingChange");
        assertEquals("job", pc.get("kind").asText(), r.body());
        assertTrue(pc.get("fourEyes").asBoolean());
        return pc.get("id").asText();
    }

    private static final String PUBLISH = "{\"name\":\"to-bi\",\"type\":\"publish.postgres\",\"connection\":\"BI\","
            + "\"datasets\":\"subs\",\"schema\":\"bi\"}";

    private void approvedPublication(Ctx c) throws Exception {
        String id = held(send(c, "POST", "/jobs", PUBLISH, "Bearer author"));
        assertEquals(403, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", "Bearer author").statusCode(),
                "four-eyes: not the author");
        JsonNode ok = data(send(c, "POST", "/pending-changes/" + id + "/approve", "{}", "Bearer checker"), 200);
        assertTrue(ok.get("applied").asBoolean(), ok.toString());
        assertTrue(PublicationApproval.approved(c.root(), "to-bi").isPresent(), "the approval pinned the content");
    }

    @Test
    void theDestinationAllowlistIsEmptyByDefaultAdministratorOnlyAndValidated(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(0, data(send(c, "GET", "/settings/publication-destinations", "", "Bearer builder"), 200)
                    .get("hosts").size(), "empty by default");
            assertEquals(403, send(c, "PUT", "/settings/publication-destinations", "{\"hosts\":[\"bi.example.com\"]}",
                    "Bearer builder").statusCode(), "canAdminister only");
            assertEquals(422, send(c, "PUT", "/settings/publication-destinations", "{\"hosts\":[\"a@b.example.com\"]}",
                    "Bearer checker").statusCode());
            assertEquals(422, send(c, "PUT", "/settings/publication-destinations", "{\"allow\":[]}", "Bearer checker").statusCode());
            data(send(c, "PUT", "/settings/publication-destinations", "{\"hosts\":[\"BI.Example.com\"]}", "Bearer checker"), 200);
            assertEquals(List.of("bi.example.com"), PublicationDestinations.hosts(c.root()));
            assertTrue(ReservedConfigPaths.reserved(PublicationDestinations.FILE), "no import may write it");
            assertTrue(ReservedConfigPaths.reserved(PublicationApproval.DIR + "to-bi.json"), "nor an approval");
        }
    }

    @Test
    void aCreateIsHeldApprovalPinsTheContentAndOtherJobsAreNotHeld(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertFalse(Files.exists(c.root().resolve("approval.toon")), "no approval policy in this Space");
            approvedPublication(c);
            assertTrue(c.svc().jobService().flatMap(s -> s.jobConfig("to-bi")).isPresent(), "applied on approval");
            HttpResponse<String> plain = send(c, "POST", "/jobs",
                    "{\"name\":\"tidy\",\"type\":\"maintenance\",\"task\":\"cleanup\"}", "Bearer author");
            assertTrue(plain.statusCode() / 100 == 2 && plain.statusCode() != 202, "other Jobs are not held: " + plain.body());
        }
    }

    @Test
    void aConfigPatchOfSchemaColumnsOrModeIsEachHeld(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            approvedPublication(c);
            for (String patch : List.of("{\"schema\":\"anywhere\"}", "{\"columns\":\"plan\"}", "{\"mode\":\"partition-incremental\"}")) {
                String id = held(send(c, "POST", "/config/patch",
                        "{\"type\":\"job\",\"name\":\"to-bi_job\",\"subdir\":\"jobs\",\"patch\":{\"job\":" + patch + "}}", "Bearer author"));
                data(send(c, "POST", "/pending-changes/" + id + "/decline", "{\"reason\":\"no\"}", "Bearer checker"), 200);
            }
            assertEquals("bi", c.svc().jobService().flatMap(s -> s.jobConfig("to-bi")).orElseThrow().params().get("schema"),
                    "nothing was written");
        }
    }

    @Test
    void aConfigWriteOfAPublicationIsHeld(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            held(send(c, "POST", "/config/write", "{\"type\":\"job\",\"config\":{\"job\":" + PUBLISH + "}}", "Bearer author"));
            assertTrue(c.svc().jobService().flatMap(s -> s.jobConfig("to-bi")).isEmpty(), "nothing was written");
        }
    }

    @Test
    void aConnectionWithInsecureTlsNeedsAnAdministratorToApprove(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            data(send(c, "POST", "/connections", "{\"id\":\"BI\",\"connector\":\"db\",\"options\":{\"jdbc_url\":"
                    + "\"jdbc:postgresql://bi.example.com:5432/bi?sslmode=require\",\"insecure_tls\":\"true\"}}", "Bearer checker"), 200);
            String id = held(send(c, "POST", "/jobs", PUBLISH, "Bearer author"));
            assertEquals(403, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", "Bearer approver").statusCode(),
                    "canApproveChanges alone cannot approve an insecure_tls publication");
            assertTrue(PublicationApproval.approved(c.root(), "to-bi").isEmpty());
            assertTrue(data(send(c, "POST", "/pending-changes/" + id + "/approve", "{}", "Bearer checker"), 200)
                    .get("applied").asBoolean());
        }
    }

    @Test
    void theApprovalIsBoundToItsPendingChangeByNonce(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            approvedPublication(c);
            Map<String, Object> rec = new java.util.LinkedHashMap<>(PublicationApproval.recordOf(c.root(), "to-bi").orElseThrow());
            assertTrue(PendingChangeRoutes.verifyPublicationApproval(c.root(), rec), rec.toString());
            rec.put("nonce", "forged");
            assertFalse(PendingChangeRoutes.verifyPublicationApproval(c.root(), rec), "a copied or forged record fails");
            rec.put("nonce", PublicationApproval.recordOf(c.root(), "to-bi").orElseThrow().get("nonce"));
            rec.put("job", "someone-else");
            assertFalse(PendingChangeRoutes.verifyPublicationApproval(c.root(), rec), "bound to that Job");
            rec.put("job", "to-bi");
            assertTrue(PendingChangeRoutes.verifyPublicationApproval(c.root(), rec));
            Map<String, Object> fps = new java.util.TreeMap<>((Map<String, Object>) rec.get("fingerprints"));
            fps.put("connection", "0".repeat(64));
            rec.put("fingerprints", fps);
            assertFalse(PendingChangeRoutes.verifyPublicationApproval(c.root(), rec),
                    "fingerprints edited on the host differ from the MAC'd Pending Change's");
        }
    }

    @Test
    void deletingThePublicationThroughJobsRemovesItsApproval(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            approvedPublication(c);
            data(send(c, "DELETE", "/jobs/to-bi", "", "Bearer author"), 200);
            assertTrue(PublicationApproval.approved(c.root(), "to-bi").isEmpty(), "the approval went with the Job");
            held(send(c, "POST", "/jobs", PUBLISH, "Bearer author"));   // an identical re-create needs a new approval
        }
    }

    @Test
    void deletingThePublicationThroughConfigRemovesItsApproval(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            approvedPublication(c);
            HttpResponse<String> r = send(c, "DELETE", "/config/job/to-bi_job?subdir=jobs", "", "Bearer author");
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(PublicationApproval.approved(c.root(), "to-bi").isEmpty(), "the approval went with the Job file");
        }
    }

    @Test
    void anApprovalRefusesWhenTheConnectionOrADatasetChangedSinceTheProposal(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = held(send(c, "POST", "/jobs", PUBLISH, "Bearer author"));
            data(send(c, "POST", "/connections", "{\"id\":\"BI\",\"connector\":\"db\",\"options\":{\"jdbc_url\":"
                    + "\"jdbc:postgresql://elsewhere.example.com:5432/bi\"}}", "Bearer checker"), 200);
            HttpResponse<String> r = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", "Bearer checker");
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("changed since it was proposed (connection)"), r.body());
            assertTrue(PublicationApproval.approved(c.root(), "to-bi").isEmpty(), "nothing approved");
            assertTrue(c.svc().jobService().flatMap(s -> s.jobConfig("to-bi")).isEmpty(), "nothing applied");
            data(send(c, "POST", "/pending-changes/" + id + "/decline", "{\"reason\":\"moved\"}", "Bearer checker"), 200);

            String id2 = held(send(c, "POST", "/jobs", PUBLISH, "Bearer author"));
            new com.gamma.pipeline.ComponentStore(c.root().resolve("registry")).write("dataset", "subs",
                    Map.of("physicalRef", "subs"));
            HttpResponse<String> d = send(c, "POST", "/pending-changes/" + id2 + "/approve", "{}", "Bearer checker");
            assertEquals(409, d.statusCode(), d.body());
            assertTrue(d.body().contains("dataset subs"), d.body());
            assertTrue(PublicationApproval.approved(c.root(), "to-bi").isEmpty());
        }
    }

    @Test
    void noImportMayCarryAPublishJob() {
        byte[] job = ("job:\n  name: to-bi\n  type: publish.postgres\n  connection: BI\n  datasets: subs\n  schema: bi\n")
                .getBytes(StandardCharsets.UTF_8);
        ApiException refused = assertThrows(ApiException.class,
                () -> ImportCapabilityGuard.checkFiles(null, Map.of("jobs/to-bi_job.toon", job), true));
        assertEquals(409, refused.status);
        assertThrows(ApiException.class, () -> ImportCapabilityGuard.checkItems(null,
                List.of(Map.of("kind", "job", "id", "to-bi", "content", Map.of("name", "to-bi", "type", "publish.postgres")))));
    }

    /** The Space's approver roster ({@link ApproverRoster}): every id this class's Authenticator mints. */
    private static void seedApproverRoster(Path root) throws java.io.IOException {
        java.nio.file.Files.createDirectories(root);
        java.nio.file.Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                java.util.Map.of("users", java.util.List.of("approver-1", "author-1", "builder-1", "checker-1"))));
    }
}

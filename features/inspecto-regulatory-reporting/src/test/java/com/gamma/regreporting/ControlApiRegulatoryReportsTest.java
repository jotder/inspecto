package com.gamma.regreporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.control.ApproverRoster;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.job.ApprovalFingerprint;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectServiceAccess;
import com.gamma.ops.link.LinkRelationship;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.workflow.ObjectType;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regulatory Reports over real HTTP ({@code REGULATORY-REPORTING-1}) WITH an armed Authenticator (with no Subject
 * {@code withCapability} is a no-op): the draft gates, request-approval, the four-eyes decide gates (author AND
 * requester excluded), the file-drop submission and its idempotent retry, tamper refusal, scope (a report is visible
 * exactly when its Case is; a partial Case is refused) and the live {@code sourceChanged} flag.
 */
class ControlApiRegulatoryReportsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTHOR = "Bearer author", ANALYST2 = "Bearer analyst2", CHECKER = "Bearer checker",
            CHECKER2 = "Bearer checker2", DEV = "Bearer dev", SCOPED = "Bearer scoped";
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir
    Path tmp;

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }

        ObjectService objects() {
            return ((ObjectServiceAccess) svc.objects().orElseThrow()).service();
        }
    }

    /** AUTHOR/ANALYST2 = operations (canWorkIncidents); CHECKER/CHECKER2 = admin; DEV = neither; SCOPED = operations, scope billing. */
    @BeforeEach
    void arm() {
        System.setProperty("assist.safety.roots", tmp.toString());
        Authenticators.forTest(ex -> {
            String auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if (SCOPED.equals(auth))
                return Optional.of(new Subject("scoped-1", Roles.effective(ex).get("operations").capabilities(), Set.of("billing")));
            String[] who = switch (auth) {
                case AUTHOR -> new String[] {"author-1", "operations"};
                case ANALYST2 -> new String[] {"analyst-2", "operations"};
                case CHECKER -> new String[] {"checker-1", "admin"};
                case CHECKER2 -> new String[] {"checker-2", "admin"};
                case DEV -> new String[] {"dev-1", "pipeline-developer"};
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
        System.clearProperty("assist.safety.roots");
    }

    private Ctx open(boolean writable) throws Exception {
        Path cfg = Files.createDirectories(tmp.resolve("pipes"));
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        Path root = writable ? Files.createDirectories(tmp.resolve("config")) : null;
        if (root != null) System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            if (root != null) Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("author-1", "analyst-2", "checker-1", "checker-2", "scoped-1"))));
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
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

    private static void status(HttpResponse<String> r, int status, String contains) {
        assertEquals(status, r.statusCode(), r.body());
        if (contains != null) assertTrue(r.body().contains(contains), r.body());
    }

    /** A Case containing two Incidents, one attachment on the Case. */
    private static String seedCase(Ctx c, Map<String, String> caseAttrs, Map<String, String> secondIncidentAttrs) {
        ObjectService o = c.objects();
        String kase = c.svc.objects().orElseThrow().open(ObjectType.CASE, "Structuring suspected", "d", "error", "t", caseAttrs);
        String i1 = c.svc.objects().orElseThrow().open(ObjectType.INCIDENT, "Three cash deposits", "d", "error", "t", Map.of());
        String i2 = c.svc.objects().orElseThrow().open(ObjectType.INCIDENT, "Fourth deposit", "d", "warn", "t", secondIncidentAttrs);
        o.link(kase, i1, LinkRelationship.CONTAINS, "t");
        o.link(kase, i2, LinkRelationship.CONTAINS, "t");
        o.attach(kase, "author-1", "statement.pdf", "application/pdf", "https://dms.example/1", "bank statement");
        return kase;
    }

    private static String draftBody(String caseId) {
        return "{\"template\":\"sample-sar\",\"caseId\":\"" + caseId + "\",\"reason\":\"monthly review\","
                + "\"inputs\":{\"reportingEntity\":\"Example Bank\",\"narrative\":\"Cash split across deposits\"}}";
    }

    private String draft(Ctx c, String caseId) throws Exception {
        JsonNode rec = data(send(c, "POST", "/regulatory-reports", draftBody(caseId), AUTHOR), 200);
        assertEquals("draft", rec.get("status").asText());
        return rec.get("id").asText();
    }

    private String pending(Ctx c, String caseId, String requester) throws Exception {
        String id = draft(c, caseId);
        assertEquals("pending", data(send(c, "POST", "/regulatory-reports/" + id + "/request-approval", "{}", requester), 200)
                .get("status").asText());
        return id;
    }

    // ── happy path ───────────────────────────────────────────────────────────────────────────────

    @Test
    void draftRequestApproveSubmitsTheExactBytesToTheDropFolder() throws Exception {
        try (Ctx c = open(true)) {
            String kase = seedCase(c, Map.of(), Map.of());
            JsonNode rec = data(send(c, "POST", "/regulatory-reports", draftBody(kase), AUTHOR), 200);
            String id = rec.get("id").asText();
            String content = rec.get("content").asText();
            assertTrue(content.contains("<subjectTitle>Structuring suspected</subjectTitle>"), content);
            assertTrue(content.contains("<title>Three cash deposits</title>") && content.contains("<title>Fourth deposit</title>"), content);
            assertTrue(content.contains("<uri>https://dms.example/1</uri>"), content);
            assertEquals(ApprovalFingerprint.sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    rec.get("contentSha256").asText());
            assertEquals(id + ".xml", rec.get("delivery").get("fileName").asText());

            JsonNode detail = data(send(c, "GET", "/regulatory-reports/" + id, null, AUTHOR), 200);
            assertFalse(detail.get("sourceChanged").asBoolean());

            status(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", CHECKER), 409, "not pending");
            data(send(c, "POST", "/regulatory-reports/" + id + "/request-approval", "{}", AUTHOR), 200);
            assertEquals("ok", data(send(c, "GET", "/regulatory-reports/" + id, null, CHECKER), 200).get("approverCheck").asText());

            JsonNode done = data(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{\"reason\":\"checked\"}", CHECKER), 200);
            assertEquals("submitted", done.get("status").asText(), done.toString());
            assertEquals("checker-1", done.get("approver").asText());
            Path file = Path.of(done.get("submission").get("file").asText());
            assertEquals(c.root.resolve("regulatory-submissions/sample-sar").resolve(id + ".xml").toAbsolutePath().normalize(), file);
            assertEquals(content, Files.readString(file), "the approver approved exactly what was submitted");

            JsonNode log = data(send(c, "GET", "/regulatory-reports?status=submitted", null, CHECKER), 200);
            assertEquals(1, log.get("total").asInt());
            assertNull(log.get("items").get(0).get("content"), "the list view never carries the content");
            List<String> trail = new java.util.ArrayList<>();
            done.get("history").forEach(h -> trail.add(h.get("status").asText()));
            assertEquals(List.of("draft", "pending", "approved", "submitted"), trail);
        }
    }

    @Test
    void templatesAreListed() throws Exception {
        try (Ctx c = open(true)) {
            JsonNode t = data(send(c, "GET", "/regulatory-reports/templates", null, AUTHOR), 200);
            assertEquals("sample-sar", t.get("items").get(0).get("id").asText());
            assertEquals(0, t.get("problems").size());
            status(send(c, "GET", "/regulatory-reports/templates", null, DEV), 403, "canWorkIncidents");
        }
    }

    // ── draft gates ──────────────────────────────────────────────────────────────────────────────

    @Test
    void draftGatesFailClosedInOrder() throws Exception {
        try (Ctx ro = open(false)) {
            status(send(ro, "POST", "/regulatory-reports", draftBody("x"), AUTHOR), 503, null);
        }
        try (Ctx c = open(true)) {
            String kase = seedCase(c, Map.of(), Map.of());
            status(send(c, "POST", "/regulatory-reports", draftBody(kase), DEV), 403, "canWorkIncidents");
            status(send(c, "POST", "/regulatory-reports", "{\"template\":\"sample-sar\",\"caseId\":\"" + kase + "\",\"x\":1}", AUTHOR), 422, "unknown key 'x'");
            status(send(c, "POST", "/regulatory-reports", draftBody(kase).replace("sample-sar", "nope"), AUTHOR), 422, "no Report Template 'nope'");
            status(send(c, "POST", "/regulatory-reports", "{\"template\":\"sample-sar\"}", AUTHOR), 422, "exactly one of");
            status(send(c, "POST", "/regulatory-reports", draftBody(kase).replace("\"reportingEntity\"", "\"secret\""), AUTHOR), 422, "input 'secret' is not read");
            status(send(c, "POST", "/regulatory-reports", draftBody("no-such-case"), AUTHOR), 404, "no case");
            status(send(c, "POST", "/regulatory-reports", draftBody(kase).replace(",\"narrative\":\"Cash split across deposits\"", ""), AUTHOR),
                    422, "required field 'narrative'");
            assertEquals(0, data(send(c, "GET", "/regulatory-reports", null, AUTHOR), 200).get("total").asInt(), "no refusal saved anything");
        }
    }

    @Test
    void aTemplateWhoseDropFolderEscapesTheJailIsRefused() throws Exception {
        try (Ctx c = open(true)) {
            String kase = seedCase(c, Map.of(), Map.of());
            Path dir = Files.createDirectories(c.root.resolve(ReportTemplate.SPACE_DIR));
            Files.writeString(dir.resolve("escape.toon"), """
                    id: escape
                    title: Escape
                    format: json
                    delivery:
                      kind: file-drop
                      dir: ../../../../../../../outside
                    fields[1]{name,source,required,maxLength}:
                      reportId,report.id,true,0
                    """);
            status(send(c, "POST", "/regulatory-reports", "{\"template\":\"escape\",\"caseId\":\"" + kase + "\"}", AUTHOR),
                    422, "delivery.dir is refused");
        }
    }

    // ── decide gates ─────────────────────────────────────────────────────────────────────────────

    @Test
    void fourEyesExcludesTheAuthorAndTheRequester() throws Exception {
        try (Ctx c = open(true)) {
            String kase = seedCase(c, Map.of(), Map.of());
            String id = pending(c, kase, CHECKER2);   // drafted by author-1, sent for approval by checker-2
            status(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", ANALYST2), 403, null);   // no canApproveChanges
            status(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", CHECKER2), 403, "four-eyes");
            status(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{\"content\":\"x\"}", CHECKER), 422, "unknown key 'content'");
            status(send(c, "POST", "/regulatory-reports/rr-bad/approve", "{}", CHECKER), 422, null);
            status(send(c, "POST", "/regulatory-reports/rr-20990101000000-000000/approve", "{}", CHECKER), 404, null);
            assertEquals("submitted", data(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", CHECKER), 200)
                    .get("status").asText());
            status(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", CHECKER), 409, "submitted, not pending");
        }
    }

    @Test
    void everyTransitionGatesItsCapabilityThenAnswersAnUnknownId404() throws Exception {
        try (Ctx c = open(true)) {
            status(send(c, "POST", "/regulatory-reports/rr-20990101000000-000000/request-approval", "{}", DEV), 403, "canWorkIncidents");
            status(send(c, "POST", "/regulatory-reports/rr-20990101000000-000000/request-approval", "{}", AUTHOR), 404, null);
            status(send(c, "POST", "/regulatory-reports/rr-20990101000000-000000/decline", "{}", AUTHOR), 403, "canApproveChanges");
            status(send(c, "POST", "/regulatory-reports/rr-20990101000000-000000/decline", "{}", CHECKER), 404, null);
            status(send(c, "POST", "/regulatory-reports/rr-20990101000000-000000/retry", "{}", AUTHOR), 403, "canApproveChanges");
            status(send(c, "POST", "/regulatory-reports/rr-20990101000000-000000/retry", "{}", CHECKER), 404, null);
        }
    }

    @Test
    void aDeclinedReportIsNeverSubmitted() throws Exception {
        try (Ctx c = open(true)) {
            String id = pending(c, seedCase(c, Map.of(), Map.of()), AUTHOR);
            assertEquals("declined", data(send(c, "POST", "/regulatory-reports/" + id + "/decline", "{\"reason\":\"thin\"}", CHECKER), 200)
                    .get("status").asText());
            status(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", CHECKER2), 409, "declined");
            assertFalse(Files.exists(c.root.resolve("regulatory-submissions")), "nothing was dropped");
        }
    }

    @Test
    void aTamperedRecordIsNeitherApprovedNorSubmitted() throws Exception {
        try (Ctx c = open(true)) {
            String id = pending(c, seedCase(c, Map.of(), Map.of()), AUTHOR);
            Path f = c.root.resolve("regulatory-reports").resolve(id + ".json");
            Files.writeString(f, Files.readString(f).replace("Cash split across deposits", "forged narrative"));
            status(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", CHECKER), 409, "integrity");
            assertFalse(Files.exists(c.root.resolve("regulatory-submissions")), "a tampered record is never submitted");
        }
    }

    @Test
    void aFailedSubmissionIsRetriedWithTheSameBytes() throws Exception {
        try (Ctx c = open(true)) {
            String id = pending(c, seedCase(c, Map.of(), Map.of()), AUTHOR);
            Path blocker = c.root.resolve("regulatory-submissions");
            Files.writeString(blocker, "a file where the drop folder should be");
            JsonNode failed = data(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", CHECKER), 200);
            assertEquals("failed", failed.get("status").asText(), failed.toString());
            assertNotNull(failed.get("lastError").asText());
            status(send(c, "POST", "/regulatory-reports/" + id + "/retry", "{}", AUTHOR), 403, null);   // retry needs canApproveChanges
            Files.delete(blocker);
            JsonNode ok = data(send(c, "POST", "/regulatory-reports/" + id + "/retry", "{}", CHECKER), 200);
            assertEquals("submitted", ok.get("status").asText(), ok.toString());
            assertEquals(failed.get("contentSha256").asText(),
                    ApprovalFingerprint.sha256(Files.readAllBytes(Path.of(ok.get("submission").get("file").asText()))));
            status(send(c, "POST", "/regulatory-reports/" + id + "/retry", "{}", CHECKER), 409, "not failed");
        }
    }

    // ── scope and source drift ───────────────────────────────────────────────────────────────────

    @Test
    void aReportIsVisibleExactlyWhenItsCaseIsAndAPartialCaseIsRefused() throws Exception {
        try (Ctx c = open(true)) {
            String fraud = seedCase(c, Map.of("caseType", "fraud"), Map.of());
            String id = draft(c, fraud);
            assertEquals(0, data(send(c, "GET", "/regulatory-reports", null, SCOPED), 200).get("total").asInt());
            status(send(c, "GET", "/regulatory-reports/" + id, null, SCOPED), 404, null);

            String mixed = seedCase(c, Map.of(), Map.of("caseType", "fraud"));   // the Case is visible, one member is not
            status(send(c, "POST", "/regulatory-reports", draftBody(mixed), SCOPED), 403, "outside your scope");
        }
    }

    @Test
    void theApproverSeesACaseEditedSinceTheReportWasRendered() throws Exception {
        try (Ctx c = open(true)) {
            String kase = seedCase(c, Map.of(), Map.of());
            String id = pending(c, kase, AUTHOR);
            c.objects().attach(kase, "analyst-2", "late.pdf", "application/pdf", "https://dms.example/2", "added later");
            JsonNode d = data(send(c, "GET", "/regulatory-reports/" + id, null, CHECKER), 200);
            assertTrue(d.get("sourceChanged").asBoolean(), d.toString());
            assertFalse(d.get("content").asText().contains("late.pdf"), "the pinned content is never re-rendered");
        }
    }
}

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
            Path file = c.root.resolve("regulatory-submissions/sample-sar").resolve(id + ".xml").toAbsolutePath().normalize();
            // a response never carries the server's absolute path: the folder as the template wrote it, the file by name
            assertEquals("regulatory-submissions/sample-sar", done.get("delivery").get("dir").asText());
            assertEquals(id + ".xml", done.get("submission").get("file").asText());
            assertFalse(done.toString().contains(c.root.toAbsolutePath().normalize().toString().replace("\\", "\\\\")), done.toString());
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
                    ApprovalFingerprint.sha256(Files.readAllBytes(
                            c.root.resolve("regulatory-submissions/sample-sar").resolve(id + ".xml"))));
            status(send(c, "POST", "/regulatory-reports/" + id + "/retry", "{}", CHECKER), 409, "not failed");
        }
    }

    // ── sealing: the .sha256 sidecar and GET /{id}/verify ──────────────────────────────────────────

    @Test
    void aSubmittedFileCarriesASha256SidecarAndVerifyReHashesBoth() throws Exception {
        try (Ctx c = open(true)) {
            String kase = seedCase(c, Map.of(), Map.of());
            String id = pending(c, kase, AUTHOR);
            status(send(c, "GET", "/regulatory-reports/" + id + "/verify", null, AUTHOR), 409, "not submitted");
            JsonNode done = data(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", CHECKER), 200);
            String sha = done.get("contentSha256").asText();
            Path dir = c.root.resolve("regulatory-submissions/sample-sar");
            assertEquals(sha + "  " + id + ".xml\n", Files.readString(dir.resolve(id + ".xml.sha256")),
                    "sha256sum format, so a receiver checks it with a stock tool");

            JsonNode v = data(send(c, "GET", "/regulatory-reports/" + id + "/verify", null, AUTHOR), 200);
            assertTrue(v.get("ok").asBoolean(), v.toString());
            assertEquals("match", v.get("fileState").asText());
            assertEquals("match", v.get("sidecar").asText());
            assertEquals(sha, v.get("actualSha256").asText());
            assertEquals(id + ".xml", v.get("file").asText());
            assertFalse(v.toString().contains(c.root.toAbsolutePath().normalize().toString().replace("\\", "\\\\")), v.toString());

            Files.writeString(dir.resolve(id + ".xml.sha256"), "0".repeat(64) + "  " + id + ".xml\n");
            v = data(send(c, "GET", "/regulatory-reports/" + id + "/verify", null, CHECKER), 200);
            assertFalse(v.get("ok").asBoolean());
            assertEquals("mismatch", v.get("sidecar").asText());

            Files.writeString(dir.resolve(id + ".xml"), "<tampered/>");
            Files.delete(dir.resolve(id + ".xml.sha256"));
            v = data(send(c, "GET", "/regulatory-reports/" + id + "/verify", null, CHECKER), 200);
            assertFalse(v.get("ok").asBoolean());
            assertEquals("mismatch", v.get("fileState").asText());
            assertEquals("missing", v.get("sidecar").asText());
            assertNotEquals(sha, v.get("actualSha256").asText());

            Files.delete(dir.resolve(id + ".xml"));
            v = data(send(c, "GET", "/regulatory-reports/" + id + "/verify", null, CHECKER), 200);
            assertEquals("missing", v.get("fileState").asText());
            assertTrue(v.get("actualSha256").isNull());

            status(send(c, "GET", "/regulatory-reports/" + id + "/verify", null, DEV), 403, "canWorkIncidents");
            status(send(c, "GET", "/regulatory-reports/rr-20200101000000-abcdef/verify", null, CHECKER), 404, null);
        }
    }

    @Test
    void aRetryAfterACrashBetweenFileAndSidecarWritesTheSidecar() throws Exception {
        try (Ctx c = open(true)) {
            String id = pending(c, seedCase(c, Map.of(), Map.of()), AUTHOR);
            data(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", CHECKER), 200);
            Path side = c.root.resolve("regulatory-submissions/sample-sar").resolve(id + ".xml.sha256");
            String line = Files.readString(side);
            Files.delete(side);
            FileDrop.deliver(c.root, "regulatory-submissions/sample-sar", id + ".xml",
                    Files.readString(c.root.resolve("regulatory-submissions/sample-sar").resolve(id + ".xml")),
                    line.substring(0, 64));
            assertEquals(line, Files.readString(side));
        }
    }

    // ── XSD validation at draft ──────────────────────────────────────────────────────────────────

    @Test
    void theBuiltInSampleValidatesAgainstItsSchemaAndASpaceSchemaOverridesIt() throws Exception {
        try (Ctx c = open(true)) {
            String kase = seedCase(c, Map.of(), Map.of());
            draft(c, kase);   // sample-sar names sample-sar.xsd: the happy path validates
            // a Space XSD of the same name wins over the built-in; this one caps reportingEntity at 3 chars
            String strict;
            try (var in = getClass().getResourceAsStream("/META-INF/inspecto/regulatory-templates/sample-sar.xsd")) {
                strict = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace(
                        "<xs:element name=\"reportingEntity\" type=\"text\"/>",
                        "<xs:element name=\"reportingEntity\"><xs:simpleType><xs:restriction base=\"xs:string\">"
                                + "<xs:maxLength value=\"3\"/></xs:restriction></xs:simpleType></xs:element>");
            }
            Files.createDirectories(c.root.resolve("regulatory-report-templates"));
            Files.writeString(c.root.resolve("regulatory-report-templates/sample-sar.xsd"), strict);
            status(send(c, "POST", "/regulatory-reports", draftBody(kase), AUTHOR), 422,
                    "does not validate against schema 'sample-sar.xsd'");
            assertEquals(1, data(send(c, "GET", "/regulatory-reports", null, AUTHOR), 200).get("total").asInt(),
                    "a draft that fails its schema is never saved");
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

    /** OBJECT-READ-MASKING-RESIDUALS-1 (1): the filing keeps the real key; a caller without canRevealLinkEntities reads it masked. */
    @Test
    void aNonRevealerReadsTheReportWithTheCasesClassifiedKeyMasked() throws Exception {
        try (Ctx c = open(true)) {
            String raw = "255712345678";
            String kase = seedCase(c, Map.of("key.msisdn", raw), Map.of());   // no `dataset`: fails closed as classified
            c.objects().attach(kase, "author-1", "cdr.csv", "text/csv", "https://dms.example/" + raw, "calls of " + raw);
            JsonNode drafted = data(send(c, "POST", "/regulatory-reports", draftBody(kase), AUTHOR), 200);
            String id = drafted.get("id").asText();
            assertFalse(drafted.toString().contains(raw), drafted.toString());
            assertTrue(drafted.get("content").asText().contains("masked:"), drafted.toString());
            JsonNode read = data(send(c, "GET", "/regulatory-reports/" + id, null, AUTHOR), 200);
            assertFalse(read.toString().contains(raw), read.toString());
            assertTrue(read.get("keyMasked").asBoolean());
            JsonNode pend = data(send(c, "POST", "/regulatory-reports/" + id + "/request-approval", "{}", AUTHOR), 200);
            assertFalse(pend.toString().contains(raw), pend.toString());

            JsonNode revealed = data(send(c, "GET", "/regulatory-reports/" + id, null, CHECKER), 200);
            assertTrue(revealed.get("content").asText().contains(raw), "a revealer reads the filing as delivered");
            JsonNode done = data(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", CHECKER), 200);
            assertEquals("submitted", done.get("status").asText(), done.toString());
            Path file = c.root.resolve("regulatory-submissions/sample-sar").resolve(id + ".xml");
            assertTrue(Files.readString(file).contains(raw), "the delivered filing carries the real identifier");
        }
    }
}

package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * D-6 — external references and the Dossier export bundle, over real HTTP and with an ARMED Authenticator (with no
 * Subject {@code withCapability} is a no-op and ownership is unenforced, so nothing would be proven).
 */
class ControlApiDossierBundleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"purpose\":\"test\",\"id\":\"inv-a\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String OWNER = "Bearer owner", OTHER = "Bearer other", NOCAP = "Bearer nocap";
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
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case OTHER -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents")));
            case NOCAP -> Optional.of(new Subject("analyst-1", Set.of()));
            default -> Optional.empty();
        });
    }

    private Ctx open(Path configDir, Path writeRoot, String maskingMode) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('alice','bob','voice'),('alice','carol','sms'),"
                            + "('bob','dave','voice')) AS t(caller,callee,channel)", "2026-10-03T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            Files.writeString(writeRoot.resolve("link-analysis.toon"), "masking_mode: " + maskingMode + "\n");
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
        assertEquals(200, r.statusCode(), method + " " + path + " → " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private int status(Ctx c, String method, String path, String body, String auth) throws Exception {
        return send(c, method, path, body, auth).statusCode();
    }

    /** seed alice → expand: an Investigation with a sealed read. */
    private void investigation(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, OWNER);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"alice\"]}", OWNER);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", OWNER);
    }

    private static String ref(String system, String type, String id) {
        return "{\"system\":\"" + system + "\",\"type\":\"" + type + "\",\"id\":\"" + id + "\"}";
    }

    private JsonNode verify(Ctx c, JsonNode bundle) throws Exception {
        return ok(c, "POST", INV + "/dossier/bundle/verify", bundle.toString(), OWNER);
    }

    // ── external references ────────────────────────────────────────────────────────────────────────────

    @Test
    void referencesAreAppendedListedAndNeverTrusted(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            JsonNode first = ok(c, "POST", INV + "/references",
                    "{\"system\":\"servicenow\",\"type\":\"incident\",\"id\":\"INC0012\",\"url\":\"https://sn.example.org/INC0012\","
                            + "\"label\":\"Fraud referral\"}", OWNER);
            assertEquals(1, first.get("count").asInt());
            ok(c, "POST", INV + "/references", ref("crm", "customer", "C-77"), OWNER);

            JsonNode list = ok(c, "GET", INV + "/references", null, OWNER);
            assertEquals(2, list.get("references").size());
            JsonNode r1 = list.at("/references/0");
            assertEquals(1, r1.get("seq").asInt());
            assertEquals("servicenow", r1.get("system").asText());
            assertEquals("analyst-1", r1.get("addedBy").asText());
            assertFalse(r1.get("trusted").asBoolean(), "a reference is never trusted data");
            assertEquals(2, list.at("/references/1/seq").asInt());

            // append-only: the same (system, type, id) is a conflict, not a second record
            assertEquals(409, status(c, "POST", INV + "/references", ref("crm", "customer", "C-77"), OWNER));
            assertEquals(2, ok(c, "GET", INV + "/references", null, OWNER).get("count").asInt());

            // it is outside the sealed header and log: the Working Set and the log did not move
            assertEquals(2, ok(c, "GET", INV + "/dossier", null, OWNER).get("ledger").size());
        }
    }

    @Test
    void aReferenceUrlThatCouldBeRenderedAsAnAttackIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            for (String bad : List.of("javascript:alert(1)", "file:///etc/passwd", "ftp://h/x", "https://user:pw@h.example/x",
                    "//h.example/x", "not a url", "https:///nohost")) {
                assertEquals(422, status(c, "POST", INV + "/references",
                        "{\"system\":\"s\",\"type\":\"t\",\"id\":\"1\",\"url\":\"" + bad + "\"}", OWNER), bad);
            }
            assertEquals(422, status(c, "POST", INV + "/references", "{\"system\":\"has space\",\"type\":\"t\",\"id\":\"1\"}", OWNER));
            assertEquals(422, status(c, "POST", INV + "/references", "{\"system\":\"s\",\"type\":\"t\"}", OWNER), "id required");
            assertEquals(422, status(c, "POST", INV + "/references", "{\"system\":\"s\",\"type\":\"t\",\"id\":\"a\\nb\"}", OWNER),
                    "no control characters");
            assertEquals(0, ok(c, "GET", INV + "/references", null, OWNER).get("count").asInt(), "nothing was stored");
        }
    }

    @Test
    void anInvestigationHoldsAtMostTwoHundredReferences(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            for (int i = 0; i < 200; i++) ok(c, "POST", INV + "/references", ref("s", "t", "id" + i), OWNER);
            assertEquals(409, status(c, "POST", INV + "/references", ref("s", "t", "one-too-many"), OWNER));
        }
    }

    @Test
    void referencesAreOwnerOnlyAndCapabilityGatedAndGrantNoAccess(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            assertEquals(403, status(c, "POST", INV + "/references", ref("s", "t", "1"), NOCAP), "capability gate");
            assertEquals(404, status(c, "POST", INV + "/references", ref("s", "t", "1"), OTHER), "non-owner reads as absent");
            assertEquals(404, status(c, "GET", INV + "/references", null, OTHER));
            assertEquals(404, status(c, "POST", "/inv/investigations/none/references", ref("s", "t", "1"), OWNER));
            assertEquals(401, status(c, "GET", INV + "/references", null, null), "no credentials");

            // a reference that names a Case shares nothing with that Case's team — only PUT …/case does
            ok(c, "POST", INV + "/references", ref("inspecto", "case", "CASE-1"), OWNER);
            assertEquals(404, status(c, "GET", INV + "/log", null, OTHER));
            assertEquals(404, status(c, "GET", INV + "/references", null, OTHER));
            assertEquals(1, ok(c, "GET", INV + "/references", null, OWNER).get("count").asInt());
            assertEquals(0, Files.list(root.resolve("audit/snapshots/investigations/inv-a")).filter(p ->
                    p.getFileName().toString().equals("case-link.json")).count(), "no Case link was made");
        }
    }

    @Test
    void aCaseMemberReadsReferencesAndTheBundleButCannotAppend(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Map<String, Map<String, Object>> cases = CaseTeamObjectEngine.arm();
        cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", false));
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            ok(c, "POST", INV + "/references", ref("crm", "customer", "C-77"), OWNER);
            assertEquals(404, status(c, "GET", INV + "/references", null, OTHER), "not linked yet: owner-only");
            ok(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", OWNER);

            assertEquals(1, ok(c, "GET", INV + "/references", null, OTHER).get("count").asInt());
            JsonNode b = ok(c, "GET", INV + "/dossier/bundle", null, OTHER);
            assertTrue(ok(c, "POST", INV + "/dossier/bundle/verify", b.toString(), OTHER).get("verified").asBoolean());
            assertEquals(404, status(c, "POST", INV + "/references", ref("s", "t", "2"), OTHER), "read-only: appending is the owner's");
            assertEquals(1, ok(c, "GET", INV + "/references", null, OWNER).get("count").asInt());
        }
    }

    // ── the Dossier bundle ─────────────────────────────────────────────────────────────────────────────

    @Test
    void theBundleIsSealedCarriesTheDossierAndReferencesAndVerifies(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            ok(c, "POST", INV + "/references", ref("crm", "customer", "C-77"), OWNER);
            JsonNode dossier = ok(c, "GET", INV + "/dossier", null, OWNER);
            JsonNode b = ok(c, "GET", INV + "/dossier/bundle", null, OWNER);

            assertEquals("inspecto-dossier-bundle/1", b.get("format").asText());
            assertEquals("inv-a", b.get("investigationId").asText());
            assertEquals(2, b.get("at").asInt());
            assertEquals(dossier.at("/manifest/root").asText(), b.at("/custody/manifestRoot").asText());
            assertEquals(dossier.at("/manifest/root").asText(), b.at("/dossier/manifest/root").asText());
            assertEquals(1, b.at("/custody/referencesCount").asInt());
            assertEquals("crm", b.at("/references/0/system").asText());
            assertFalse(b.at("/references/0/trusted").asBoolean());
            assertTrue(b.at("/seal/value").asText().startsWith("sha256:"));

            JsonNode v = verify(c, b);
            assertTrue(v.get("verified").asBoolean(), v.toString());
            assertTrue(v.get("sealIntact").asBoolean());
            assertTrue(v.get("referencesIntact").asBoolean());
            assertEquals(0, v.get("referencesAddedSince").asInt());
            // the same bundle may also be posted wrapped
            assertTrue(ok(c, "POST", INV + "/dossier/bundle/verify", "{\"bundle\":" + b + "}", OWNER).get("verified").asBoolean());
        }
    }

    @Test
    void anEditedBundleFailsItsSeal(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            ok(c, "POST", INV + "/references", ref("crm", "customer", "C-77"), OWNER);
            JsonNode b = ok(c, "GET", INV + "/dossier/bundle", null, OWNER);

            ObjectNode dossierEdit = b.deepCopy();   // a number in the dossier body changed
            ((ObjectNode) dossierEdit.get("dossier").get("summary")).put("entities", 99);
            JsonNode r1 = verify(c, dossierEdit);
            assertFalse(r1.get("verified").asBoolean());
            assertFalse(r1.get("sealIntact").asBoolean(), r1.toString());

            ObjectNode refEdit = b.deepCopy();       // a reference rewritten
            ((ObjectNode) refEdit.get("references").get(0)).put("id", "C-78");
            JsonNode r2 = verify(c, refEdit);
            assertFalse(r2.get("verified").asBoolean());
            assertFalse(r2.get("sealIntact").asBoolean(), r2.toString());

            ObjectNode resealed = b.deepCopy();      // edited AND re-sealed with a made-up value: custody still catches it
            ((ObjectNode) resealed.get("references").get(0)).put("id", "C-78");
            ((ObjectNode) resealed.get("seal")).put("value", b.at("/seal/value").asText());
            assertFalse(verify(c, resealed).get("verified").asBoolean());

            ObjectNode rootEdit = b.deepCopy();      // custody root no longer the embedded manifest's
            ((ObjectNode) rootEdit.get("custody")).put("manifestRoot", "sha256:00");
            JsonNode r3 = verify(c, rootEdit);
            assertFalse(r3.get("rootMatches").asBoolean(), r3.toString());
            assertFalse(r3.get("verified").asBoolean());
        }
    }

    @Test
    void aStoreEditedAfterExportFailsCustody(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            JsonNode b = ok(c, "GET", INV + "/dossier/bundle", null, OWNER);
            assertTrue(verify(c, b).get("verified").asBoolean());

            Path log = root.resolve("audit/snapshots/investigations/inv-a/log.jsonl");
            Files.writeString(log, Files.readString(log).replaceFirst("alice", "alicX"));
            JsonNode v = verify(c, b);
            assertFalse(v.get("verified").asBoolean(), v.toString());
            assertTrue(v.get("sealIntact").asBoolean(), "the bundle itself is untouched");
            assertFalse(v.at("/custody/verified").asBoolean());
        }
    }

    @Test
    void referencesAddedAfterExportDoNotBreakTheBundleButARewrittenOneDoes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            ok(c, "POST", INV + "/references", ref("crm", "customer", "C-77"), OWNER);
            JsonNode b = ok(c, "GET", INV + "/dossier/bundle", null, OWNER);
            ok(c, "POST", INV + "/references", ref("crm", "customer", "C-78"), OWNER);

            JsonNode v = verify(c, b);
            assertTrue(v.get("verified").asBoolean(), v.toString());
            assertEquals(1, v.get("referencesAddedSince").asInt());

            Path refs = root.resolve("audit/snapshots/investigations/inv-a/references.jsonl");
            Files.writeString(refs, Files.readString(refs).replace("C-77", "C-99"));
            JsonNode t = verify(c, b);
            assertFalse(t.get("referencesIntact").asBoolean(), t.toString());
            assertFalse(t.get("verified").asBoolean());
        }
    }

    @Test
    void maskingAppliesOnExportAndTheRootStaysVerifiable(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            ok(c, "POST", INV + "/references", ref("crm", "customer", "alice"), OWNER);
            JsonNode clear = ok(c, "GET", INV + "/dossier/bundle", null, OWNER);
            assertTrue(clear.toString().contains("alice"));

            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
            JsonNode masked = ok(c, "GET", INV + "/dossier/bundle", null, OWNER);
            assertEquals("all", masked.at("/masking/mode").asText());
            for (String raw : List.of("alice", "bob", "carol"))
                assertFalse(masked.toString().contains("\"" + raw + "\""), raw + " must not leave the system masked");
            assertTrue(masked.at("/references/0/id").asText().startsWith("masked:"), "a reference's id is masked too");

            // masked or not, the custody root is the same, and the masked bundle verifies
            assertEquals(clear.at("/custody/manifestRoot").asText(), masked.at("/custody/manifestRoot").asText());
            assertEquals(clear.at("/custody/referencesHash").asText(), masked.at("/custody/referencesHash").asText());
            assertFalse(clear.at("/seal/value").asText().equals(masked.at("/seal/value").asText()), "the seal covers what shipped");
            assertTrue(verify(c, masked).get("verified").asBoolean());
            assertTrue(verify(c, clear).get("verified").asBoolean());
        }
    }

    @Test
    void theBundleIsOwnerOnlyAndHonoursTheDatasetGate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            JsonNode b = ok(c, "GET", INV + "/dossier/bundle", null, OWNER);
            assertEquals(404, status(c, "GET", INV + "/dossier/bundle", null, OTHER));
            assertEquals(404, status(c, "POST", INV + "/dossier/bundle/verify", b.toString(), OTHER));
            assertEquals(401, status(c, "GET", INV + "/dossier/bundle", null, null));
            assertEquals(422, status(c, "GET", INV + "/dossier/bundle?at=99", null, OWNER));
            assertEquals(422, status(c, "POST", INV + "/dossier/bundle/verify", "{}", OWNER), "not a bundle");
            assertEquals(422, status(c, "POST", "/inv/investigations/inv-a/dossier/bundle/verify",
                    b.toString().replace("\"investigationId\":\"inv-a\"", "\"investigationId\":\"inv-b\""), OWNER));

            // R3: the bound Dataset shared away from the owner makes the export read as absent
            new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds",
                    Map.of("view", "calls_view", "owner", "someone-else", "shares", List.of()));
            assertEquals(404, status(c, "GET", INV + "/dossier/bundle", null, OWNER));
            assertEquals(404, status(c, "POST", INV + "/dossier/bundle/verify", b.toString(), OWNER));
        }
    }
}

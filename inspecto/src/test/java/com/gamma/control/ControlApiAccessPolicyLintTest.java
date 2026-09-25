package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Save-time guards for the policy-authoring failure modes F1–F9
 * ({@code docs/archived-documents/plans-archive/policy-authoring-ux-design.md} §1, §4 — operator D1, 2026-09-25: guard all
 * nine at save time). Each test is one failure mode; the ones that need the Enterprise engine (F6's
 * seed names, F7's lockout simulation) live in {@code ControlApiPolicyEnforcementTest}. Every probe
 * here is a document the pre-guard code ACCEPTED (200) or loaded silently — the red run proves it.
 */
class ControlApiAccessPolicyLintTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path writeRoot) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("cfg"));
        System.setProperty("assist.write.root", writeRoot.toString());
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), writeRoot);
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty("assist.write.root");
    }

    private static String doc(String policyJson) {
        return "{\"policies\":[" + policyJson + "]}";
    }

    // ── F1 — an unreadable doc is named, and a save cannot produce one ───────────────────────

    @Test
    void f1_anUnreadableDocNamesThePolicyAndCheck_andARolesSaveCannotMakeItUnreadable(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // (a) a hand edit with a bad value: the GET's error names the policy and what is wrong,
            // not a generic "unreadable" (D5 — the caller holds canConfigureAccess: Personal, no Subject).
            writeDoc(c, Map.of("name", "freeze-contractors", "effect", "block"));
            JsonNode broken = json(send(c.port, "GET", "/access/policies", null, null));
            String error = broken.get("error").asText();
            assertTrue(error.contains("freeze-contractors") && error.contains("effect"),
                    "the unreadable-doc error must name the policy and the check: " + error);

            // (b) a policy that references an allowlisted claim is valid only while the claim is
            // allowlisted — so a roles save that drops the claim would turn the policies doc
            // unreadable (deny-all) as a side effect. That save is refused before it takes effect.
            assertEquals(200, send(c.port, "PUT", "/access/roles",
                    "{\"roles\":[],\"identity\":{\"attributeClaims\":[\"employment\"]}}", null).statusCode());
            assertEquals(200, send(c.port, "PUT", "/access/policies", doc("""
                    {"name":"contractor-freeze","effect":"deny","target":{"actions":["write"]},
                     "when":"subject.employment == 'contractor'"}"""), null).statusCode());
            HttpResponse<String> drop = send(c.port, "PUT", "/access/roles", "{\"roles\":[]}", null);
            assertEquals(422, drop.statusCode(), drop.body());
            assertTrue(drop.body().contains("contractor-freeze"), drop.body());
            assertNull(json(send(c.port, "GET", "/access/policies", null, null)).get("error"),
                    "the refused roles save left the policies doc readable");
        }
    }

    @Test
    void f1_theNamedErrorIsShownOnlyToAccessConfigurers(@TempDir Path dir) throws Exception {
        // D5 (taken on recommendation, most fail-closed): the GET is ungated, so the detailed message
        // is for canConfigureAccess holders; everyone else gets the generic fail-closed notice.
        Authenticators.forTest(ex -> {
            String a = ex.getRequestHeaders().getFirst("Authorization");
            if (a == null) return Optional.empty();
            String id = a.substring(7);
            return Optional.of(new Subject(id, "admin".equals(id) ? Set.of("canConfigureAccess") : Set.of(), null, Map.of()));
        });
        try (Ctx c = open(dir)) {
            writeDoc(c, Map.of("name", "freeze-contractors", "effect", "block"));
            String admin = json(send(c.port, "GET", "/access/policies", null, "admin")).get("error").asText();
            String other = json(send(c.port, "GET", "/access/policies", null, "ana")).get("error").asText();
            assertTrue(admin.contains("freeze-contractors"), admin);
            assertFalse(other.contains("freeze-contractors"), "no detail without canConfigureAccess: " + other);
            assertTrue(other.contains("fail-closed"), other);
        }
    }

    // ── F2 — a mistyped attribute reference is a 422 ─────────────────────────────────────────

    @Test
    void f2_aMistypedAttributeReferenceIs422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String when : List.of(
                    "subject.role contains 'admin'",                  // subject.roles
                    "not (subject.capabilites contains 'canX')",      // subject.capabilities
                    "user.id == 'ana'",                               // unknown root
                    "env.method == 'PUT'",                            // env.{action,route,space}
                    "subject.employment == 'contractor'",             // claim not allowlisted in roles.toon
                    "subject == null")) {                             // a bare root names no attribute
                HttpResponse<String> r = send(c.port, "PUT", "/access/policies", doc(
                        "{\"name\":\"p\",\"effect\":\"deny\",\"target\":{\"actions\":[\"operate\"]},\"when\":\"" + when + "\"}"), null);
                assertEquals(422, r.statusCode(), when + " → " + r.body());
                assertTrue(r.body().contains("unknown-ref"), when + " → " + r.body());
            }
            // the closed vocabulary is accepted, and an allowlisted claim becomes a known subject key
            assertEquals(200, send(c.port, "PUT", "/access/policies", doc("""
                    {"name":"p","effect":"deny","target":{"actions":["operate"]},
                     "when":"subject.id == 'a' or subject.capabilities contains 'canOperateRuns' or 'x' in subject.dataScopes or subject.roles contains 'admin' or env.action == 'write' or env.route == '/x' or env.space == 'y' or resource.anything == 1"}"""),
                    null).statusCode());
            assertEquals(200, send(c.port, "PUT", "/access/roles",
                    "{\"roles\":[],\"identity\":{\"attributeClaims\":[\"employment\"]}}", null).statusCode());
            assertEquals(200, send(c.port, "PUT", "/access/policies", doc("""
                    {"name":"p","effect":"deny","target":{"actions":["operate"]},
                     "when":"subject.employment == 'contractor'"}"""), null).statusCode());
        }
    }

    // ── F3 — a mistyped capability / role literal is a warning ───────────────────────────────

    @Test
    void f3_aMistypedCapabilityOrRoleLiteralIsAWarning(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode saved = json(send(c.port, "PUT", "/access/policies", doc("""
                    {"name":"cap-typo","effect":"deny","target":{"actions":["write"]},
                     "when":"not (subject.capabilities contains 'canConfigureAcess')"},
                    {"name":"role-typo","effect":"deny","target":{"actions":["write"]},
                     "when":"'contractr' in subject.roles"},
                    {"name":"fine","effect":"deny","target":{"actions":["write"]},
                     "when":"subject.roles contains 'admin' and not (subject.capabilities contains 'canConfigureAccess')"}"""), null));
            List<String> codes = codes(saved, "cap-typo");
            assertTrue(codes.contains("unknown-capability"), "warnings: " + saved.get("warnings"));
            assertTrue(codes(saved, "role-typo").contains("unknown-role"), "warnings: " + saved.get("warnings"));
            assertTrue(codes(saved, "fine").isEmpty(), "known literals raise nothing: " + saved.get("warnings"));
            // the same warnings ride the GET, so a hand-edited doc shows them too
            assertTrue(codes(json(send(c.port, "GET", "/access/policies", null, null)), "cap-typo").contains("unknown-capability"));
        }
    }

    // ── F4 — an unknown resource kind is a warning ───────────────────────────────────────────

    @Test
    void f4_anUnknownResourceKindIsAWarning(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode saved = json(send(c.port, "PUT", "/access/policies", doc("""
                    {"name":"plural","effect":"deny","target":{"resourceKinds":["incidents"]},"when":"resource.caseType == 'billing'"},
                    {"name":"known","effect":"deny","target":{"resourceKinds":["Incident ","investigation"]},"when":"resource.caseType == 'billing'"}"""), null));
            assertTrue(codes(saved, "plural").contains("unknown-resource-kind"), "warnings: " + saved.get("warnings"));
            assertTrue(codes(saved, "known").isEmpty(), "warnings: " + saved.get("warnings"));
            assertEquals("[\"alert\",\"case\",\"incident\",\"investigation\",\"task\"]", saved.get("resourceKinds").toString(),
                    "the vocabulary is served, so the SPA never mirrors it");
        }
    }

    @Test
    void f4_theKnownResourceKindsCoverEveryObjectType() {
        // the row-level PEPs pass ObjectType lower-cased — a new type must join the lint vocabulary
        for (com.gamma.objects.ObjectType t : com.gamma.objects.ObjectType.values())
            assertTrue(AccessPolicies.RESOURCE_KINDS.contains(t.name().toLowerCase(java.util.Locale.ROOT)), t.name());
    }

    // ── F5 — a resource.* ref on a route-level policy is a warning ───────────────────────────

    @Test
    void f5_aResourceRefWithoutAResourceKindsTargetIsAWarning(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode saved = json(send(c.port, "PUT", "/access/policies", doc("""
                    {"name":"route-level","effect":"deny","target":{"actions":["write"]},"when":"resource.caseType == 'billing'"},
                    {"name":"row-level","effect":"deny","target":{"resourceKinds":["incident"]},"when":"resource.caseType == 'billing'"}"""), null));
            assertTrue(codes(saved, "route-level").contains("resource-ref-at-route-level"), "warnings: " + saved.get("warnings"));
            assertTrue(codes(saved, "row-level").isEmpty(), "warnings: " + saved.get("warnings"));
        }
    }

    // ── F8 — unknown keys are a 422, and an on-disk stray key is named (the D4 upgrade path) ─

    @Test
    void f8_anUnknownKeyIs422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String p : List.of(
                    "{\"name\":\"p\",\"effect\":\"deny\",\"target\":{\"actions\":[\"write\"]},\"wen\":\"subject.id == 'a'\"}",
                    "{\"name\":\"p\",\"effect\":\"deny\",\"target\":{\"resourcekinds\":[\"incident\"]},\"when\":\"subject.id == 'a'\"}",
                    "{\"name\":\"p\",\"effect\":\"deny\",\"target\":{\"resourceKinds\":[\"incident\"],\"resource_kinds\":[\"case\"]},\"when\":\"subject.id == 'a'\"}")) {
                HttpResponse<String> r = send(c.port, "PUT", "/access/policies", doc(p), null);
                assertEquals(422, r.statusCode(), p + " → " + r.body());
                assertTrue(r.body().contains("unknown-key") || r.body().contains("ambiguous-key"), p + " → " + r.body());
            }
        }
    }

    @Test
    void f8_upgradePath_anOnDiskDocWithAStrayKeyLoadsUnreadableNamingTheKey(@TempDir Path dir) throws Exception {
        // D4 (taken on recommendation): a doc that loaded before the guard, with a stray key, is
        // unreadable after it — fail-closed (deny-all on Enterprise), and the error names the fix.
        try (Ctx c = open(dir)) {
            writeDoc(c, Map.of("name", "contractor-freeze", "effect", "deny",
                    "target", Map.of("actions", List.of("write")), "wen", "subject.id == 'carl'"));
            JsonNode doc = json(send(c.port, "GET", "/access/policies", null, null));
            assertTrue(doc.get("policies").isEmpty());
            String error = doc.get("error").asText();
            assertTrue(error.contains("contractor-freeze") && error.contains("wen"), error);
        }
    }

    // ── F9 — an untargeted, unconditional deny is refused ────────────────────────────────────

    @Test
    void f9_anUntargetedUnconditionalDenyIs422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = send(c.port, "PUT", "/access/policies", doc("{\"name\":\"deny-all\",\"effect\":\"deny\"}"), null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("deny-everything"), r.body());
            // a targeted deny, a conditioned deny and an untargeted allow are all still fine
            assertEquals(200, send(c.port, "PUT", "/access/policies", doc("""
                    {"name":"a","effect":"deny","target":{"actions":["operate"]}},
                    {"name":"b","effect":"deny","when":"subject.id == 'mallory'"},
                    {"name":"c","effect":"allow"}"""), null).statusCode());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** A hand edit on disk — the path the PUT's 422s cannot reach. */
    private static void writeDoc(Ctx c, Map<String, Object> policy) throws Exception {
        Files.writeString(c.writeRoot().resolve("access-policies.toon"),
                dev.toonformat.jtoon.JToon.encode(Map.of("policies", List.of(policy))));
    }

    private static List<String> codes(JsonNode doc, String policy) {
        List<String> out = new ArrayList<>();
        JsonNode w = doc.get("warnings");
        if (w != null) for (JsonNode n : w) if (policy.equals(n.get("policy").asText())) out.add(n.get("code").asText());
        return out;
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), () -> "expected 200 but got " + r.statusCode() + ": " + r.body());
        return V1Body.of(r.body());
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String subject) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (subject != null) b.header("Authorization", "Bearer " + subject);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}

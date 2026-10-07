package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import com.gamma.access.Roles;
import com.gamma.access.ComponentAccess;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code CONFIG-WRITE-REGISTRY-1} (found in review 2026-09-27), over real HTTP with an ARMED Authenticator — a
 * Subject holding ONLY {@code canAuthorWorkbench}, the capability the {@code /config/*} write routes are gated on.
 *
 * <p><b>What was reachable before the fix</b> (probed on origin/master {@code d46739d55}): a caller {@code subdir}
 * of {@code registry/<dir>} — in every spelling below — let {@code POST /config/write type=meta} PLANT a
 * component file in any kind's registry directory, {@code overwrite:true} REPLACE an existing one,
 * {@code POST /config/patch} rewrite one, and {@code DELETE /config/meta/<id>?subdir=registry/<dir>} DELETE one.
 * Each bypassed the kind's own door: its capability ({@code canManageIncidents} for a findings-spec,
 * {@code canConfigureAccess} for an access-profile, {@code canAuthorAlertRules} for an alert-rule), its
 * {@code fromMap} validation (a KPI with no measure), and its maker-checker rule (held under {@code meta}, not
 * {@code kpi}). Deleting {@code registry/access-profiles/role-developer.toon} WIDENS that role — "a role with no
 * saved profile allows everywhere" ({@code AccessGrants}). A kind-shaped payload was refused only because the
 * {@code meta} spec 422s unknown keys; a {@code name}-only file landed. The same routes also wrote the reserved
 * Space documents: {@code meta} named {@code roles} / {@code approval} WAS {@code roles.toon} /
 * {@code approval.toon}. Every assertion here is a 403 plus an unchanged tree.
 */
class ControlApiConfigWriteRegistryJailTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** registry dir → kind → the id seeded there. */
    private static final List<String[]> KINDS = List.of(
            new String[]{"kpis", "kpi", "revenue"},
            new String[]{"findings-specs", "findings-spec", "case"},
            new String[]{"alert-rules", "alert-rule", "late-files"},
            new String[]{"access-profiles", "access-profile", "role-developer"});

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (!"Bearer author".equals(auth)) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of("developer"));
        return Optional.of(new Subject("author", Set.of(Roles.CAN_AUTHOR_WORKBENCH)));
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** A write root holding one component per kind (meta-shaped, so a pre-fix patch would have passed its spec)
     *  and an approval policy governing every one of those kinds. */
    private Ctx open(Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        Path root = Files.createDirectories(dir.resolve("wr"));
        StringBuilder policy = new StringBuilder("approval:\n");
        for (String[] k : KINDS) {
            Path d = Files.createDirectories(root.resolve("registry").resolve(k[0]));
            Files.writeString(d.resolve(k[2] + ".toon"), "name: " + k[2] + "\n");
            policy.append("  ").append(k[1]).append(":\n    required: true\n");
        }
        Files.writeString(root.resolve(ApprovalPolicy.FILE), policy.toString());
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", root.toString());
        try {
            ControlApi api = new ControlApi(svc, 0);   // captures the write root at construction
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    // ── premise ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void theKindsOwnDoorsAreNarrowerThanTheConfigRoutes(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(403, send(c, "POST", "/components/findings-spec", "{\"name\":\"x\",\"objectType\":\"x\",\"sections\":[]}").statusCode());
            assertEquals(403, send(c, "PUT", "/access/profiles/role-developer", "{\"grants\":{}}").statusCode());
            assertEquals(403, send(c, "DELETE", "/access/profiles/role-developer", null).statusCode());
            assertEquals(403, send(c, "DELETE", "/alerts/rules/late-files", null).statusCode());
            ApprovalPolicy policy = ApprovalPolicy.forRoot(c.root);
            for (String[] k : KINDS) assertNotNull(policy.ruleFor(k[1]), k[1] + " is governed by maker-checker");
            assertNull(policy.ruleFor("meta"), "…while the type /config/write files it under is not");
        }
    }

    // ── the gap, per kind ────────────────────────────────────────────────────────────────────────

    @Test
    void plantingAComponentUnderAnyRegistryKindIsRefused(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Map<String, String> before = tree(c.root);
            for (String[] k : KINDS) {
                HttpResponse<String> r = write(c, "registry/" + k[0], "planted", false);
                assertEquals(403, r.statusCode(), k[0] + ": " + r.body());
                assertTrue(r.body().contains("/components/" + k[1]), "names the kind's own door: " + r.body());
            }
            assertEquals(before, tree(c.root), "nothing landed");
        }
    }

    @Test
    void overwritePatchAndDeleteOfAnExistingComponentAreRefused(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Map<String, String> before = tree(c.root);
            for (String[] k : KINDS) {
                String sub = "registry/" + k[0];
                assertEquals(403, write(c, sub, k[2], true).statusCode(), k[0] + " overwrite");
                assertEquals(403, send(c, "POST", "/config/patch", "{\"type\":\"meta\",\"name\":\"" + k[2]
                        + "\",\"subdir\":\"" + sub + "\",\"patch\":{\"version\":2}}").statusCode(), k[0] + " patch");
                assertEquals(403, send(c, "DELETE", "/config/meta/" + k[2] + "?subdir=" + sub, null).statusCode(), k[0] + " delete");
                // an absent id answers 403 too — the refusal comes before the 404, so it is no existence oracle
                assertEquals(403, send(c, "DELETE", "/config/meta/absent?subdir=" + sub, null).statusCode(), k[0] + " absent");
            }
            assertEquals(before, tree(c.root), "every component byte-for-byte unchanged");
        }
    }

    // ── bypass spellings ─────────────────────────────────────────────────────────────────────────

    @Test
    void everySpellingOfTheRegistryIsRefused(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Map<String, String> before = tree(c.root);
            for (String sub : List.of("registry", "registry/kpis", "registry/../registry/kpis", "x/../registry/kpis",
                    "./registry/kpis", "registry\\\\kpis", "Registry/kpis", "REGISTRY/KPIS", "registry./kpis",
                    "registry/kpis/", "registry/nosuchkind")) {
                HttpResponse<String> r = write(c, sub, "planted", true);
                assertEquals(403, r.statusCode(), sub + ": " + r.body());
            }
            // absolute paths were already refused (400); pinned so the new gate cannot be reached around them
            for (String abs : List.of(c.root.resolve("registry/kpis").toString().replace("\\", "\\\\"), "/registry/kpis")) {
                assertTrue(write(c, abs, "planted", true).statusCode() >= 400, abs);
            }
            // a DELETE's subdir is a query parameter, decoded TWICE (URI + URLDecoder): single- and double-encoded
            // separators and dot-segments all arrive as registry/kpis
            for (String q : List.of("registry%2Fkpis", "registry%5Ckpis", "registry%252Fkpis", "Registry%2FKPIS",
                    "x%2F..%2Fregistry%2Fkpis", "registry.%2Fkpis")) {
                HttpResponse<String> r = send(c, "DELETE", "/config/meta/revenue?subdir=" + q, null);
                assertEquals(403, r.statusCode(), q + ": " + r.body());
            }
            assertEquals(before, tree(c.root), "nothing landed and nothing was deleted");
        }
    }

    @Test
    void aLinkIntoTheRegistryIsRefused(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            try {
                Files.createSymbolicLink(c.root.resolve("alias"), c.root.resolve("registry"));
            } catch (Exception unsupported) {
                assumeTrue(false, "symbolic links unavailable here: " + unsupported.getMessage());
            }
            Map<String, String> before = tree(c.root);
            assertEquals(403, write(c, "alias/kpis", "planted", true).statusCode());
            assertEquals(403, write(c, "alias/kpis", "revenue", true).statusCode());
            assertEquals(403, send(c, "DELETE", "/config/meta/revenue?subdir=alias/kpis", null).statusCode());
            assertEquals(403, send(c, "GET", "/config/meta/revenue?subdir=alias/kpis", null).statusCode());
            assertEquals(before, tree(c.root));
        }
    }

    // ── the read side ────────────────────────────────────────────────────────────────────────────

    /** {@code GET /config/{type}/{name}} is ungated, so a registry read here skipped the
     *  {@code ComponentAccess.requireView} that {@code /components/<kind>} applies to a private / shared-away one. */
    @Test
    void readsUnderTheRegistryAreRefusedInEverySpelling(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String[] k : KINDS) {
                for (String id : List.of(k[2], "absent")) {   // present and absent alike: no existence oracle
                    HttpResponse<String> r = send(c, "GET", "/config/meta/" + id + "?subdir=registry/" + k[0], null);
                    assertEquals(403, r.statusCode(), k[0] + "/" + id + ": " + r.body());
                    assertTrue(r.body().contains("/components/" + k[1]), r.body());
                }
            }
            for (String q : List.of("registry%2Fkpis", "registry%5Ckpis", "registry%252Fkpis", "Registry%2Fkpis",
                    "REGISTRY%2FKPIS", "registry.%2Fkpis", "x%2F..%2Fregistry%2Fkpis", "registry%2F..%2Fregistry%2Fkpis",
                    ".%2Fregistry%2Fkpis", "registry")) {
                HttpResponse<String> r = send(c, "GET", "/config/meta/revenue?subdir=" + q, null);
                assertEquals(403, r.statusCode(), q + ": " + r.body());
                assertFalse(r.body().contains("\"config\""), "no content served: " + r.body());
            }
        }
    }

    /** With no subdir a schema read falls back to a bounded scan of the write root — which must not find a
     *  registry schema either. Skipped, not refused: a 404 exactly like an absent name, so no oracle. */
    @Test
    void theSatelliteScanDoesNotServeARegistrySchema(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Path schemas = Files.createDirectories(c.root.resolve("registry/schemas"));
            Files.writeString(schemas.resolve("orphan.toon"), "raw:\n  name: orphan\n");
            assertEquals(404, send(c, "GET", "/config/schema/orphan", null).statusCode());
            assertEquals(404, send(c, "GET", "/config/schema/never-existed", null).statusCode());
        }
    }

    @Test
    void theReservedSpaceDocumentsAreNotReadable(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Files.writeString(c.root.resolve("demo-users.toon"), "name: demo-users\n");
            for (String name : List.of("approval", "demo-users", "roles", "access-policies", "egress", "space")) {
                HttpResponse<String> r = send(c, "GET", "/config/meta/" + name, null);
                assertEquals(403, r.statusCode(), name + ": " + r.body());
                assertFalse(r.body().contains("\"config\""), name + " served: " + r.body());
            }
        }
    }

    // ── the sibling: reserved Space documents ────────────────────────────────────────────────────

    @Test
    void theReservedSpaceDocumentsAreRefused(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Map<String, String> before = tree(c.root);
            for (String name : List.of("roles", "approval", "access-policies", "egress", "demo-users", "space")) {
                HttpResponse<String> r = write(c, null, name, true);
                assertEquals(403, r.statusCode(), name + ": " + r.body());
            }
            assertEquals(403, send(c, "POST", "/config/patch",
                    "{\"type\":\"meta\",\"name\":\"approval\",\"patch\":{\"version\":2}}").statusCode());
            assertEquals(403, send(c, "DELETE", "/config/meta/approval", null).statusCode());
            assertEquals(before, tree(c.root), "approval.toon unchanged, no roles.toon planted");
        }
    }

    // ── nothing legitimate moved ─────────────────────────────────────────────────────────────────

    @Test
    void ordinaryConfigWritesStillLand(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String sub : new String[]{null, "orders", "registry-notes", "notes/registry"}) {
                HttpResponse<String> r = write(c, sub, "semantics", false);
                assertEquals(200, r.statusCode(), sub + ": " + r.body());
                Path f = (sub == null ? c.root : c.root.resolve(sub)).resolve("semantics.toon");
                assertTrue(Files.isRegularFile(f), f.toString());
                HttpResponse<String> read = send(c, "GET", "/config/meta/semantics" + (sub == null ? "" : "?subdir=" + sub), null);
                assertEquals(200, read.statusCode(), sub + " read: " + read.body());
                assertEquals("semantics", V1Body.of(read.body()).at("/config/name").asText());
            }
            assertEquals(200, send(c, "POST", "/config/patch",
                    "{\"type\":\"meta\",\"name\":\"semantics\",\"subdir\":\"orders\",\"patch\":{\"version\":2}}").statusCode());
            assertEquals(200, send(c, "DELETE", "/config/meta/semantics?subdir=orders", null).statusCode());
            assertFalse(Files.exists(c.root.resolve("orders/semantics.toon")));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private HttpResponse<String> write(Ctx c, String subdir, String name, boolean overwrite) throws Exception {
        String body = "{\"type\":\"meta\"," + (subdir == null ? "" : "\"subdir\":\"" + subdir + "\",")
                + "\"overwrite\":" + overwrite + ",\"config\":{\"name\":\"" + name + "\"}}";
        return send(c, "POST", "/config/write", body);
    }

    /** Every file (sha256) and directory under the write root, minus the Pending Change store. */
    private static Map<String, String> tree(Path root) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> all = Files.walk(root)) {
            for (Path p : all.toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("pending-changes")) continue;
                out.put(rel, Files.isDirectory(p) ? "<dir>"
                        : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        }
        return out;
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        b.header("Authorization", "Bearer author");
        return client.send(b.build(), BodyHandlers.ofString());
    }
}

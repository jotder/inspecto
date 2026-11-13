package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.gamma.service.SpaceOrigin;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-5b — {@code GET|POST /spaces/{id}/origin/upgrade} over real HTTP under an ARMED authenticator. The proof of the
 * Space evolution plan §4.5: a Space made from {@code orders-starter}, one file edited, the template bumped with two files
 * changed (one of them the edited one) — the proposal lists the unedited one as {@code update} and the edited one as
 * {@code conflict}; the apply changes only the update; the origin record then names the new version. Plus 401, 403 per
 * route, every apply gate, 202 under an approval policy (one Pending Change, applied on approval) and 200 without.
 */
class ControlApiSpaceOriginUpgradeTest {

    private static final String KPI = "registry/widgets/orders_kpi_total.toon";
    private static final String REVENUE = "registry/widgets/orders_revenue_by_region.toon";

    private final HttpClient client = HttpClient.newHttpClient();

    /**
     * {@code admin} / {@code checker}: the seeded {@code admin} role, stamped as held (an approved change is applied as its
     * author, re-checked against the role table); {@code ops} administers and authors but holds no role and no
     * canOnboardConnections; {@code author} only canAuthorWorkbench; {@code viewer} nothing.
     */
    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        String user = a.substring(7);
        switch (user) {
            case "admin", "checker" -> {
                ComponentAccess.heldRoles(ex, Set.of("admin"));
                Set<String> caps = new HashSet<>(Roles.effective(ex).get("admin").capabilities());
                if (user.equals("admin")) caps.add("canAuthorWorkbench");
                return Optional.of(new Subject(user + "-1", caps));
            }
            case "ops" -> { return Optional.of(new Subject("ops-1", Set.of("canAdminister", "canAuthorWorkbench"))); }
            case "author" -> { return Optional.of(new Subject("author-1", Set.of("canAuthorWorkbench"))); }
            default -> { return Optional.of(new Subject(user, Set.of())); }
        }
    };

    @BeforeEach
    void isolate() {
        DiscoveredRoots.clear();
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        DiscoveredRoots.clear();
        MetricRegistry.global().reset();
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
        }

        Path shop() {
            return root.resolve("shop");
        }

        Path template() {
            return root.resolve("_templates").resolve("orders-starter");
        }
    }

    /** Space {@code shop} made from a copy of the shipped {@code orders-starter}, its KPI widget then edited; Space {@code plain} has no origin. */
    private Ctx open(Path root) throws Exception {
        copyTree(Path.of("..", "spaces", "_templates", "orders-starter"), root.resolve("_templates").resolve("orders-starter"));
        SpaceManager spaces = SpaceManager.discover(root);
        spaces.createFromTemplate(SpaceId.of("shop"), null, null, "orders-starter");
        spaces.create(SpaceId.of("plain"), "Plain", "");
        Path config = root.resolve("shop").resolve("config");
        Files.writeString(config.resolve(KPI), Files.readString(config.resolve(KPI)) + "title: Mine\n");   // the Space's own edit
        Files.writeString(config.resolve(ApproverRoster.FILE), "users[2]: admin-1,checker-1\n");
        Authenticators.forTest(FAKE);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port(), root);
    }

    /** Version "2" of the template: both widgets changed (the KPI one is the Space's edited file). */
    private static void bump(Ctx c) throws Exception {
        Path t = c.template();
        Files.writeString(t.resolve("template.toon"), Files.readString(t.resolve("template.toon")) + "version: \"2\"\n");
        for (String f : new String[] {KPI, REVENUE}) {
            Path p = t.resolve("config").resolve(f);
            Files.writeString(p, Files.readString(p) + "description: from version 2\n");
        }
    }

    private static void copyTree(Path src, Path dst) throws Exception {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.sorted().toList()) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String user, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> read(Ctx c, String space) throws Exception {
        return send(c, "GET", "/spaces/" + space + "/origin/upgrade", "author", null);
    }

    private HttpResponse<String> apply(Ctx c, String user, String body) throws Exception {
        return send(c, "POST", "/spaces/shop/origin/upgrade", user, body);
    }

    private static JsonNode entry(JsonNode proposal, String path) {
        for (JsonNode f : proposal.get("files")) if (f.get("path").asText().equals(path)) return f;
        return null;
    }

    private static String sha(Path p) throws Exception {
        return SpaceOrigin.sha256(Files.readAllBytes(p));
    }

    // ── auth gates ──

    @Test
    void noCredentialIs401(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(401, send(c, "GET", "/spaces/shop/origin/upgrade", null, null).statusCode());
            assertEquals(401, apply(c, null, "{\"toVersion\":\"2\"}").statusCode());
        }
    }

    @Test
    void readNeedsCanAuthorWorkbenchAndApplyNeedsCanAdminister(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            bump(c);
            assertEquals(403, send(c, "GET", "/spaces/shop/origin/upgrade", "viewer", null).statusCode());
            assertEquals(200, read(c, "shop").statusCode());
            String before = Files.readString(c.shop().resolve("config").resolve(REVENUE));
            assertEquals(403, apply(c, "author", "{\"toVersion\":\"2\"}").statusCode(), "an author cannot apply");
            assertEquals(before, Files.readString(c.shop().resolve("config").resolve(REVENUE)));
        }
    }

    // ── the read ──

    @Test
    void theReadSaysNoneSameVersionOrMissingWhenThereIsNothingToPropose(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals("none", V1Body.of(read(c, "plain").body()).get("status").asText());
            JsonNode same = V1Body.of(read(c, "shop").body());
            assertEquals("same-version", same.get("status").asText(), same.toString());
            assertEquals("unversioned", same.get("templateVersion").asText());
            copyTree(c.template(), root.resolve("_templates").resolve("moved"));
            deleteTree(c.template());
            assertEquals("missing", V1Body.of(read(c, "shop").body()).get("status").asText());
        }
    }

    // ── the proof (plan §4.5) ──

    @Test
    void theProposalUpdatesTheUneditedFileFlagsTheEditedOneAndTheApplyChangesOnlyTheUpdate(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            bump(c);
            Path config = c.shop().resolve("config");
            String recordedKpi = SpaceOrigin.read(c.shop()).orElseThrow().hashes().get(KPI);
            String editedKpi = Files.readString(config.resolve(KPI));

            HttpResponse<String> r = read(c, "shop");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode p = V1Body.of(r.body());
            assertEquals("proposal", p.get("status").asText(), p.toString());
            assertEquals("unversioned", p.get("fromVersion").asText());
            assertEquals("2", p.get("toVersion").asText());
            assertEquals("update", entry(p, REVENUE).get("change").asText(), p.toString());
            assertEquals("widget", entry(p, REVENUE).get("kind").asText());
            JsonNode conflict = entry(p, KPI);
            assertEquals("conflict", conflict.get("change").asText(), p.toString());
            assertEquals(recordedKpi, conflict.get("recordedSha256").asText());
            assertEquals(sha(config.resolve(KPI)), conflict.get("spaceSha256").asText());
            assertNotEquals(conflict.get("recordedSha256").asText(), conflict.get("templateSha256").asText());
            for (JsonNode f : p.get("files"))   // only the two widgets differ; egress.toon (server-written) is report-only
                assertTrue(Set.of(KPI, REVENUE, "egress.toon").contains(f.get("path").asText()), p.toString());
            assertEquals("template-removed", entry(p, "egress.toon").get("change").asText());
            assertEquals(1, p.get("applicable").size());
            assertEquals(REVENUE, p.get("applicable").get(0).asText());

            HttpResponse<String> ok = apply(c, "ops", "{\"toVersion\":\"2\",\"proposalId\":\"" + p.get("proposalId").asText() + "\"}");
            assertEquals(200, ok.statusCode(), ok.body());
            JsonNode done = V1Body.of(ok.body());
            assertEquals(REVENUE, done.get("applied").get(0).asText(), done.toString());
            assertEquals(1, done.get("applied").size());

            assertArrayEquals(Files.readAllBytes(c.template().resolve("config").resolve(REVENUE)), Files.readAllBytes(config.resolve(REVENUE)),
                    "the update now holds the template's file");
            assertEquals(editedKpi, Files.readString(config.resolve(KPI)), "the conflict is never applied");

            SpaceOrigin.Origin after = SpaceOrigin.read(c.shop()).orElseThrow();
            assertEquals("2", after.templateVersion());
            assertEquals("created", after.origin());
            assertEquals(sha(config.resolve(REVENUE)), after.hashes().get(REVENUE), "the applied file's hash is the new one");
            assertEquals(recordedKpi, after.hashes().get(KPI), "the conflict keeps its old recorded hash");
            assertEquals("same-version", V1Body.of(read(c, "shop").body()).get("status").asText());
            assertEquals("same-version", V1Body.of(send(c, "GET", "/spaces/shop/origin", "author", null).body())
                    .get("templateSince").get("status").asText());
        }
    }

    // ── apply gates ──

    @Test
    void applyRefusesEachGateInOrderAndWritesNothing(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Path config = c.shop().resolve("config");
            assertEquals(409, apply(c, "ops", "{\"toVersion\":\"2\"}").statusCode(), "already at the template's version");
            bump(c);
            Files.writeString(c.template().resolve("config").resolve("readme.md"), "not a config file\n");
            String revenue = Files.readString(config.resolve(REVENUE));

            assertEquals(400, apply(c, "ops", "{}").statusCode());
            assertEquals(400, apply(c, "ops", "{\"toVersion\":2}").statusCode());
            assertEquals(400, apply(c, "ops", "{\"toVersion\":\"2\",\"files\":[]}").statusCode());
            assertEquals(400, apply(c, "ops", "{\"toVersion\":\"2\",\"files\":\"" + REVENUE + "\"}").statusCode());
            assertEquals(400, apply(c, "ops", "{\"toVersion\":\"2\",\"proposalId\":7}").statusCode());
            assertEquals(422, apply(c, "ops", "{\"toVersion\":\"2 3\"}").statusCode());
            assertEquals(403, apply(c, "ops", "{\"toVersion\":\"2\",\"files\":[\"../plain/config/x.toon\"]}").statusCode());
            assertEquals(409, send(c, "POST", "/spaces/plain/origin/upgrade", "ops", "{\"toVersion\":\"2\"}").statusCode(),
                    "a Space with no origin has nothing to upgrade from");
            HttpResponse<String> stale = apply(c, "ops", "{\"toVersion\":\"3\"}");
            assertEquals(409, stale.statusCode());
            assertTrue(stale.body().contains("CONFLICT_STALE_VERSION"), stale.body());
            assertEquals(409, apply(c, "ops", "{\"toVersion\":\"2\",\"proposalId\":\"not-the-proposal\"}").statusCode());
            assertEquals(403, apply(c, "ops", "{\"toVersion\":\"2\",\"files\":[\"readme.md\"]}").statusCode(),
                    "a file no import may write is never written by an upgrade");
            assertEquals(409, apply(c, "ops", "{\"toVersion\":\"2\",\"files\":[\"" + KPI + "\"]}").statusCode(), "a conflict is never applied");
            assertEquals(409, apply(c, "ops", "{\"toVersion\":\"2\",\"files\":[\"egress.toon\"]}").statusCode());
            assertEquals(revenue, Files.readString(config.resolve(REVENUE)), "no refusal wrote config");
            assertFalse(Files.exists(config.resolve("readme.md")));
            assertEquals("unversioned", SpaceOrigin.read(c.shop()).orElseThrow().templateVersion(), "no refusal rewrote the record");

            deleteTree(c.template());
            assertEquals(404, apply(c, "ops", "{\"toVersion\":\"2\"}").statusCode(), "the template is no longer installed");
        }
    }

    @Test
    void aFileOfAKindWhoseRouteNeedsMoreIsRefusedForACallerWithoutThatCapability(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            bump(c);
            Files.writeString(c.template().resolve("config").resolve("ext_connection.toon"), "id: ext\ntype: local\n");
            JsonNode p = V1Body.of(read(c, "shop").body());
            assertEquals("add", entry(p, "ext_connection.toon").get("change").asText(), p.toString());
            String revenue = Files.readString(c.shop().resolve("config").resolve(REVENUE));
            assertEquals(403, apply(c, "ops", "{\"toVersion\":\"2\"}").statusCode(), "a Connection needs canOnboardConnections");
            assertEquals(revenue, Files.readString(c.shop().resolve("config").resolve(REVENUE)), "nothing of the set was written");
            assertFalse(Files.exists(c.shop().resolve("config").resolve("ext_connection.toon")));
        }
    }

    @Test
    void underAnApprovalPolicyTheSetIsOnePendingChangeAppliedOnApproval(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Path config = c.shop().resolve("config");
            Files.writeString(config.resolve("approval.toon"), "approval:\n  widget:\n    required: true\n");
            bump(c);
            String revenue = Files.readString(config.resolve(REVENUE));

            HttpResponse<String> held = apply(c, "admin", "{\"toVersion\":\"2\"}");
            assertEquals(202, held.statusCode(), held.body());
            JsonNode body = V1Body.of(held.body());
            assertEquals("pending", body.get("status").asText(), body.toString());
            assertEquals(SpaceOriginRoutes.UPGRADE_KIND, body.at("/pendingChange/kind").asText());
            assertEquals("orders-starter", body.at("/pendingChange/name").asText());
            String id = body.at("/pendingChange/id").asText();
            assertEquals(revenue, Files.readString(config.resolve(REVENUE)), "held: nothing written");
            assertEquals("unversioned", SpaceOrigin.read(c.shop()).orElseThrow().templateVersion());
            assertEquals(409, apply(c, "admin", "{\"toVersion\":\"2\"}").statusCode(), "one pending upgrade of a template at a time");

            HttpResponse<String> ok = send(c, "POST", "/spaces/shop/pending-changes/" + id + "/approve", "checker", "{}");
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(V1Body.of(ok.body()).get("applied").asBoolean(), ok.body());
            assertArrayEquals(Files.readAllBytes(c.template().resolve("config").resolve(REVENUE)), Files.readAllBytes(config.resolve(REVENUE)));
            assertEquals("2", SpaceOrigin.read(c.shop()).orElseThrow().templateVersion());
        }
    }

    @Test
    void anApprovedUpgradeWhoseSpaceMovedIsStale(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Path config = c.shop().resolve("config");
            Files.writeString(config.resolve("approval.toon"), "approval:\n  widget:\n    required: true\n");
            bump(c);
            String id = V1Body.of(apply(c, "admin", "{\"toVersion\":\"2\"}").body()).at("/pendingChange/id").asText();
            Files.writeString(config.resolve(REVENUE), Files.readString(config.resolve(REVENUE)) + "title: edited meanwhile\n");
            String edited = Files.readString(config.resolve(REVENUE));
            HttpResponse<String> r = send(c, "POST", "/spaces/shop/pending-changes/" + id + "/approve", "checker", "{}");
            assertEquals(409, r.statusCode(), r.body());
            assertEquals(edited, Files.readString(config.resolve(REVENUE)), "the Space's newer edit stands");
            assertEquals("unversioned", SpaceOrigin.read(c.shop()).orElseThrow().templateVersion());
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}

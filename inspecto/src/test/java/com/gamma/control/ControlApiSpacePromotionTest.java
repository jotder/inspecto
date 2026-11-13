package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.BundleExporter;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-6b — {@code POST /spaces/{id}/promotion/preview} and {@code /promotion/apply} over real HTTP under an ARMED
 * authenticator. A demo-like source Space (exported by {@link BundleExporter#exportSpace}, what {@code GET /export}
 * serves) is promoted into an existing target that differs in one file and has one extra file: the preview lists
 * exactly those; apply with no policy writes them; under an approval policy apply is ONE Pending Change (202) and
 * nothing changes until it is approved; a missing parameter is 422; a stale preview is 409; every gate per route.
 */
class ControlApiSpacePromotionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static final String ORDERS = "registry/datasets/orders.toon";
    private static final String SAME = "registry/datasets/same.toon";
    private static final String PARAM = "registry/datasets/param.toon";
    private static final String EXTRA = "registry/datasets/extra.toon";

    /** {@code admin} holds canAdminister, {@code author} only canAuthorWorkbench, {@code viewer} nothing. */
    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        String user = a.substring(7);
        Set<String> caps = switch (user) {
            case "admin" -> Set.of("canAdminister", "canAuthorWorkbench", "canOnboardConnections");
            case "author" -> Set.of("canAuthorWorkbench");
            default -> Set.of();
        };
        return Optional.of(new Subject(user, caps));
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
        System.clearProperty(SpacePromotion.PROPERTY);
    }

    // ── fixtures ──

    /** The source: demo-like config with a parameter, a settings document and an ops config a promotion never carries. */
    private static byte[] sourceBundle(Path dir) throws Exception {
        Path config = Files.createDirectories(dir.resolve("src-config"));
        write(config, ORDERS, "physicalRef: orders/database\ndescription: v2\n");
        write(config, SAME, "physicalRef: same/database\n");
        write(config, PARAM, "physicalRef: \"${REGION}/database\"\nowner: \"${SPACE}\"\n");
        write(config, "branding.toon", "title: Source\n");
        write(config, "ops/hot_tagrule.toon", "name: hot\n");
        return BundleExporter.exportSpace(config, null, "src");
    }

    /** The target's config: one file differs (orders), one is extra; the rest equals the bound source. */
    private static void seedTarget(Path config, String space) throws Exception {
        write(config, ORDERS, "physicalRef: orders/database\ndescription: v1\n");
        write(config, SAME, "physicalRef: same/database\n");
        write(config, PARAM, "physicalRef: eu/database\nowner: " + space + "\n");
        write(config, EXTRA, "physicalRef: extra/database\n");
    }

    private static void write(Path root, String rel, String text) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private static String body(byte[] zip, Map<String, Object> extra) throws Exception {
        ObjectNode n = JSON.createObjectNode();
        n.put("bundle", Base64.getEncoder().encodeToString(zip));
        n.set("parameters", JSON.valueToTree(Map.of("REGION", "eu")));
        extra.forEach((k, v) -> n.set(k, JSON.valueToTree(v)));
        return JSON.writeValueAsString(n);
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
        }
    }

    private Ctx open(Path root) throws Exception {
        Path config = Files.createDirectories(root.resolve("alpha/config"));
        Files.createDirectories(root.resolve("alpha/data"));
        seedTarget(config, "alpha");
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port(), config);
    }

    private HttpResponse<String> post(int port, String path, String user, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (user != null) b.header("Authorization", user.startsWith("Bearer ") ? user : "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode preview(Ctx c, byte[] zip) throws Exception {
        HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/preview", "admin", body(zip, Map.of()));
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static List<String> actions(JsonNode preview, String action) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : preview.get("changes")) if (action.equals(c.get("action").asText())) out.add(c.get("path").asText());
        return out;
    }

    // ── auth gates ──

    @Test
    void noCredentialIs401AndBothRoutesNeedCanAdminister(@TempDir Path root, @TempDir Path tmp) throws Exception {
        byte[] zip = sourceBundle(tmp);
        try (Ctx c = open(root)) {
            for (String route : List.of("preview", "apply")) {
                String path = "/spaces/alpha/promotion/" + route;
                String b = body(zip, Map.of("previewHash", "x"));
                assertEquals(401, post(c.port, path, null, b).statusCode(), route);
                assertEquals(403, post(c.port, path, "author", b).statusCode(), route + ": an author cannot promote");
                assertEquals(403, post(c.port, path, "viewer", b).statusCode(), route);
            }
            assertTrue(Files.readString(c.config.resolve(ORDERS)).contains("v1"), "no refusal wrote anything");
        }
    }

    // ── request gates ──

    @Test
    void badBodiesAre400AnUnknownSpaceIs404AndADataSourceBundleIs422(@TempDir Path root, @TempDir Path tmp) throws Exception {
        byte[] zip = sourceBundle(tmp);
        String b64 = Base64.getEncoder().encodeToString(zip);
        try (Ctx c = open(root)) {
            for (String route : List.of("preview", "apply")) {
                String path = "/spaces/alpha/promotion/" + route;
                assertEquals(400, post(c.port, path, "admin", "{}").statusCode(), route + ": no bundle");
                assertEquals(400, post(c.port, path, "admin", "{\"bundle\":\"%%%\",\"previewHash\":\"x\"}").statusCode(),
                        route + ": not base64");
                assertEquals(400, post(c.port, path, "admin", "{\"bundle\":\"" + Base64.getEncoder()
                        .encodeToString("not a zip".getBytes(StandardCharsets.UTF_8)) + "\",\"previewHash\":\"x\"}").statusCode(),
                        route + ": not a bundle");
                assertEquals(400, post(c.port, path, "admin", "{\"bundle\":\"" + b64
                        + "\",\"previewHash\":\"x\",\"parameters\":[1]}").statusCode(), route + ": parameters not an object");
                assertEquals(400, post(c.port, path, "admin", "{\"bundle\":\"" + b64
                        + "\",\"previewHash\":\"x\",\"parameters\":{\"region\":\"eu\"}}").statusCode(), route + ": lower case name");
                assertEquals(400, post(c.port, path, "admin", "{\"bundle\":\"" + b64
                        + "\",\"previewHash\":\"x\",\"parameters\":{\"SPACE\":\"beta\"}}").statusCode(), route + ": SPACE is bound");
                assertEquals(400, post(c.port, path, "admin", "{\"bundle\":\"" + b64
                        + "\",\"previewHash\":\"x\",\"parameters\":{\"REGION\":\"eu\\nroles: x\"}}").statusCode(),
                        route + ": a value that could inject a line");
                assertEquals(404, post(c.port, "/spaces/nope/promotion/" + route, "admin",
                        body(zip, Map.of("previewHash", "x"))).statusCode(), route + ": unknown Space");
                assertEquals(422, post(c.port, path, "admin", body(dataSourceBundle(), Map.of("previewHash", "x")))
                        .statusCode(), route + ": not a whole-Space bundle");
            }
            assertEquals(400, post(c.port, "/spaces/alpha/promotion/apply", "admin", body(zip, Map.of())).statusCode(),
                    "apply needs the preview's hash");
            assertEquals(400, post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(zip, Map.of("previewHash", "x", "removals", "yes"))).statusCode(), "removals is a boolean");
            assertTrue(Files.readString(c.config.resolve(ORDERS)).contains("v1"));
        }
    }

    private static byte[] dataSourceBundle() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry(BundleExporter.MANIFEST));
            z.write("kind: datasource\nschema_version: 1\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    @Test
    void aPlaceholderWithNoValueIs422NamingIt(@TempDir Path root, @TempDir Path tmp) throws Exception {
        byte[] zip = sourceBundle(tmp);
        try (Ctx c = open(root)) {
            String noParams = "{\"bundle\":\"" + Base64.getEncoder().encodeToString(zip) + "\",\"previewHash\":\"x\"}";
            for (String route : List.of("preview", "apply")) {
                HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/" + route, "admin", noParams);
                assertEquals(422, r.statusCode(), r.body());
                assertTrue(r.body().contains("REGION") && !r.body().contains("\"SPACE\""), r.body());
            }
            assertTrue(Files.readString(c.config.resolve(ORDERS)).contains("v1"));
        }
    }

    @Test
    void theKillSwitchAnswers503(@TempDir Path root, @TempDir Path tmp) throws Exception {
        byte[] zip = sourceBundle(tmp);
        try (Ctx c = open(root)) {
            System.setProperty(SpacePromotion.PROPERTY, "off");
            assertEquals(503, post(c.port, "/spaces/alpha/promotion/preview", "admin", body(zip, Map.of())).statusCode());
            assertEquals(503, post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(zip, Map.of("previewHash", "x"))).statusCode());
        }
    }

    // ── answers ──

    @Test
    void previewListsExactlyTheDifferingAndTheExtraFileAndWritesNothing(@TempDir Path root, @TempDir Path tmp)
            throws Exception {
        byte[] zip = sourceBundle(tmp);
        try (Ctx c = open(root)) {
            JsonNode p = preview(c, zip);
            assertEquals(List.of(ORDERS), actions(p, "change"), p.toString());
            assertEquals(List.of(EXTRA), actions(p, "remove"), p.toString());
            assertEquals(List.of(), actions(p, "add"), p.toString());
            assertEquals(List.of(PARAM, SAME), actions(p, "same"), "the bound parameters and SPACE make param.toon equal");
            List<String> excluded = new ArrayList<>();
            p.get("excluded").forEach(x -> excluded.add(x.get("path").asText()));
            assertTrue(excluded.containsAll(List.of("branding.toon", "ops/hot_tagrule.toon")), excluded.toString());
            assertEquals("direct", p.get("approval").asText());
            assertEquals("src", p.get("sourceSpace").asText());
            assertTrue(Files.readString(c.config.resolve(ORDERS)).contains("v1"), "a preview writes nothing");
            assertFalse(Files.exists(c.config.resolve("branding.toon")));
        }
    }

    @Test
    void applyWithNoPolicyWritesTheChangeAndRemovesOnlyWhenAskedThenAStalePreviewIs409(@TempDir Path root,
                                                                                       @TempDir Path tmp) throws Exception {
        byte[] zip = sourceBundle(tmp);
        try (Ctx c = open(root)) {
            String hash = preview(c, zip).get("changeSetHash").asText();
            HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(zip, Map.of("previewHash", hash)));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode a = V1Body.of(r.body());
            assertEquals(List.of(ORDERS), JSON.convertValue(a.get("written"), List.class));
            assertEquals(List.of(EXTRA), JSON.convertValue(a.get("removalsSkipped"), List.class));
            assertTrue(Files.readString(c.config.resolve(ORDERS)).contains("v2"), "the differing file now matches the source");
            assertTrue(Files.exists(c.config.resolve(EXTRA)), "removals are applied only on request");
            assertFalse(Files.exists(c.config.resolve("branding.toon")), "a settings document never travels");
            assertTrue(Files.readString(c.config.resolve(PARAM)).contains("eu/database"));

            // the old preview no longer describes the target: 409, nothing written
            HttpResponse<String> stale = post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(zip, Map.of("previewHash", hash, "removals", true)));
            assertEquals(409, stale.statusCode(), stale.body());
            assertTrue(Files.exists(c.config.resolve(EXTRA)));

            JsonNode again = preview(c, zip);
            assertEquals(List.of(EXTRA), actions(again, "remove"));
            assertEquals(List.of(), actions(again, "change"));
            HttpResponse<String> rm = post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(zip, Map.of("previewHash", again.get("changeSetHash").asText(), "removals", true)));
            assertEquals(200, rm.statusCode(), rm.body());
            assertEquals(List.of(EXTRA), JSON.convertValue(V1Body.of(rm.body()).get("removed"), List.class));
            assertFalse(Files.exists(c.config.resolve(EXTRA)));
        }
    }

    @Test
    void aTargetEditedAfterThePreviewIs409(@TempDir Path root, @TempDir Path tmp) throws Exception {
        byte[] zip = sourceBundle(tmp);
        try (Ctx c = open(root)) {
            String hash = preview(c, zip).get("changeSetHash").asText();
            write(c.config, SAME, "physicalRef: edited/database\n");
            HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(zip, Map.of("previewHash", hash)));
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(Files.readString(c.config.resolve(ORDERS)).contains("v1"), "a stale apply writes nothing");
        }
    }

    // ── approval policy (D-SE7): one Pending Change, nothing lands until it is approved ──

    private static final String ADMIN = "Bearer admin", CHECKER = "Bearer checker";

    private static final Authenticator OIDC_SHAPED = ex -> {
        String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
        String[] who = switch (h) {
            case ADMIN -> new String[] {"admin-1", "admin"};
            case CHECKER -> new String[] {"checker-1", "admin"};
            default -> null;
        };
        if (who == null) return Optional.empty();
        Roles.Def def = Roles.effective(ex).get(who[1]);
        ComponentAccess.heldRoles(ex, Set.of(who[1]));
        return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
    };

    @TempDir
    static Path auditDir;

    @Test
    void underAnApprovalPolicyApplyIsOnePendingChangeAndLandsOnlyOnApproval(@TempDir Path cfg, @TempDir Path writeRoot,
                                                                           @TempDir Path tmp) throws Exception {
        System.setProperty("jobs.audit.dir", auditDir.resolve("jobs_audit").toString());
        byte[] zip = sourceBundle(tmp);
        seedTarget(writeRoot, "default");
        Files.writeString(writeRoot.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                Map.of("users", List.of("admin-1", "checker-1"))));
        Authenticators.forTest(OIDC_SHAPED);
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        try {
            api.start();
            int port = api.port();
            HttpResponse<String> policy = put(port, "/settings/approval", ADMIN, "{\"approval\":{\"dataset\":{\"required\":true}}}");
            assertEquals(200, policy.statusCode(), policy.body());

            HttpResponse<String> pr = post(port, "/promotion/preview", ADMIN, body(zip, Map.of()));
            assertEquals(200, pr.statusCode(), pr.body());
            JsonNode p = V1Body.of(pr.body());
            assertEquals("pending-change", p.get("approval").asText());

            HttpResponse<String> held = post(port, "/promotion/apply", ADMIN,
                    body(zip, Map.of("previewHash", p.get("changeSetHash").asText(), "removals", true)));
            assertEquals(202, held.statusCode(), held.body());
            JsonNode h = V1Body.of(held.body());
            assertEquals(SpacePromotion.KIND, h.at("/pendingChange/kind").asText(), h.toString());
            assertEquals("canApproveChanges", h.at("/pendingChange/approverCapability").asText());
            assertTrue(h.at("/pendingChange/fourEyes").asBoolean());
            assertTrue(Files.readString(writeRoot.resolve(ORDERS)).contains("v1"), "nothing lands before approval");
            assertTrue(Files.exists(writeRoot.resolve(EXTRA)));
            String id = h.at("/pendingChange/id").asText();

            assertEquals(409, post(port, "/promotion/apply", ADMIN, body(zip, Map.of("previewHash",
                    p.get("changeSetHash").asText()))).statusCode(), "one pending promotion per Space at a time");

            HttpResponse<String> ok = post(port, "/pending-changes/" + id + "/approve", CHECKER, "{}");
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(V1Body.of(ok.body()).get("applied").asBoolean(), ok.body());
            assertTrue(Files.readString(writeRoot.resolve(ORDERS)).contains("v2"), "approval applied the change set");
            assertFalse(Files.exists(writeRoot.resolve(EXTRA)), "the opted-in removal landed with it");
        } finally {
            api.close();
            svc.close();
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> put(int port, String path, String auth, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

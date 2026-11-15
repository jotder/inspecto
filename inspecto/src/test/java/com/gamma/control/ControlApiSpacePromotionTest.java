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
import java.util.zip.ZipInputStream;
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
        System.clearProperty("jobs.audit.dir");
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
        return body(zip, Map.of(), extra);
    }

    /** As {@link #body(byte[], Map)}, with {@code params} bound beside {@code REGION}. */
    private static String body(byte[] zip, Map<String, String> params, Map<String, Object> extra) throws Exception {
        Map<String, String> all = new java.util.TreeMap<>(params);
        all.put("REGION", "eu");
        ObjectNode n = JSON.createObjectNode();
        n.put("bundle", Base64.getEncoder().encodeToString(zip));
        n.set("parameters", JSON.valueToTree(all));
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

    /** {@code zip} with one more entry: a hand-made bundle (an export never carries a literal secret). */
    private static byte[] withEntry(byte[] zip, String path, String text) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip));
             ZipOutputStream out = new ZipOutputStream(bos)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(in.readAllBytes());
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(path));
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bos.toByteArray();
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

    /**
     * SPACE-PROMOTION-EXT-CASE-1: a file's kind is read from its name as the filesystem resolves it, as the import
     * reads it ({@link ImportCapabilityGuard#normalizedPath}), so a connection spelled in another case is still a
     * connection: one carrying a literal secret is excluded, never proposed as an {@code add} or written.
     */
    @Test
    void aConnectionNamedInAnotherCaseIsStillAConnectionAndItsLiteralSecretIsNeverWritten(@TempDir Path root,
                                                                                         @TempDir Path tmp) throws Exception {
        List<String> names = List.of("connections/db_connection.TOON", "connections/dc_CONNECTION.toon",
                "Registry/connections/dd.toon");
        byte[] zip = sourceBundle(tmp);
        for (String n : names)
            zip = withEntry(zip, n, "connection:\n  id: db\n  connector: postgres\n  host: h\n  password: hunter2\n");
        try (Ctx c = open(root)) {
            JsonNode p = preview(c, zip);
            Map<String, String> excluded = new java.util.TreeMap<>();
            p.get("excluded").forEach(x -> excluded.put(x.get("path").asText(), x.get("reason").asText()));
            for (String n : names) {
                assertFalse(actions(p, "add").contains(n), n + " is not proposed: " + p);
                assertTrue(String.valueOf(excluded.get(n)).contains("literal secret"), n + ": " + excluded);
            }
            HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(zip, Map.of("previewHash", p.get("changeSetHash").asText())));
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(List.of(ORDERS), JSON.convertValue(V1Body.of(r.body()).get("written"), List.class));
            try (var walk = Files.walk(c.config)) {
                assertEquals(List.of(), walk.filter(Files::isRegularFile)
                        .filter(f -> f.toString().toLowerCase(java.util.Locale.ROOT).contains("connection"))
                        .map(Path::toString).toList(), "no connection file was written");
            }
        }
    }

    /**
     * SPACE-PROMOTION-EXT-CASE-1: a Pipeline spelled in another case is still judged by the config-safety check before
     * apply writes it, so one naming a path outside the allowed roots is 422 and nothing is written.
     */
    @Test
    void aPipelineNamedInAnotherCaseIsStillSafetyCheckedBeforeApply(@TempDir Path root, @TempDir Path tmp) throws Exception {
        for (String name : List.of("p_pipeline.toon", "p_pipeline.TOON")) {
            byte[] zip = withEntry(sourceBundle(tmp), name, "dirs:\n  errors: \"C:/Windows/evil\"\n");
            try (Ctx c = open(root.resolve(name.endsWith("TOON") ? "upper" : "lower"))) {
                JsonNode p = preview(c, zip);
                HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/apply", "admin",
                        body(zip, Map.of("previewHash", p.get("changeSetHash").asText())));
                assertEquals(422, r.statusCode(), name + ": " + r.body());
                assertTrue(r.body().contains("outside the allowed roots") && !r.body().contains("(parse)"), r.body());
                assertFalse(Files.exists(c.config.resolve(name)), name + " was not written");
            }
        }
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

    private static final String SHAPE = "registry/datasets/shape.toon";

    /**
     * SPACE-PROMOTION-PARAM-TYPES-1: a parameter value fills a value; one that makes it another kind of value (a list item
     * an object, text a list or {@code {}}) while the file still parses refuses the change set, naming the parameter and
     * the file. Nothing is written.
     */
    @Test
    void aParameterValueThatChangesTheKindOfTheValueItFillsIs422NamingItAndTheFile(@TempDir Path root, @TempDir Path tmp)
            throws Exception {
        List<List<String>> cases = List.of(   // the file's text, the value of P
                List.of("physicalRef: s/database\nlabels[1]:\n  - ${P}\n", "k: v"),
                List.of("physicalRef: s/database\ndescription: ${P}\n", "[2]: a,b"),
                List.of("physicalRef: s/database\ndescription: ${P}\n", ""),
                List.of("physicalRef: s/database\ndescription: \"${P}\"\n", "[2]: a,b"));   // [N]: makes a key even in quotes
        try (Ctx c = open(root)) {
            for (int i = 0; i < cases.size(); i++) {
                Path dir = tmp.resolve("case" + i);
                write(dir.resolve("src-config"), SHAPE, cases.get(i).get(0));
                byte[] zip = sourceBundle(dir);
                for (String route : List.of("preview", "apply")) {
                    HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/" + route, "admin",
                            body(zip, Map.of("P", cases.get(i).get(1)), Map.of("previewHash", "x")));
                    assertEquals(422, r.statusCode(), "case " + i + ", " + route + ": " + r.body());
                    assertTrue(r.body().contains("reshapedByParameters") && r.body().contains(SHAPE)
                            && r.body().contains("[\"P\"]"), "case " + i + ", " + route + ": " + r.body());
                }
            }
            assertFalse(Files.exists(c.config.resolve(SHAPE)), "nothing was written");
            assertTrue(Files.readString(c.config.resolve(ORDERS)).contains("v1"));
        }
    }

    /**
     * No false refusal: a scalar stays a scalar whatever its type ({@code 30} fills text as a number), a colon in text
     * is text, and a quoted placeholder keeps a list item or an empty value text.
     */
    @Test
    void aValueThatKeepsTheKindOfWhatItFillsPromotesAndAQuotedPlaceholderStaysText(@TempDir Path root, @TempDir Path tmp)
            throws Exception {
        write(tmp.resolve("src-config"), SHAPE, "physicalRef: s/database\ndescription: ${P}\nretentionDays: ${DAYS}\n"
                + "labels[1]:\n  - \"${L}\"\nnote: \"${E}\"\n");
        byte[] zip = sourceBundle(tmp);
        Map<String, String> params = Map.of("P", "EU: west", "DAYS", "30", "L", "k: v", "E", "");
        try (Ctx c = open(root)) {
            HttpResponse<String> pr = post(c.port, "/spaces/alpha/promotion/preview", "admin", body(zip, params, Map.of()));
            assertEquals(200, pr.statusCode(), pr.body());
            HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/apply", "admin", body(zip, params,
                    Map.of("previewHash", V1Body.of(pr.body()).get("changeSetHash").asText())));
            assertEquals(200, r.statusCode(), r.body());
            String text = Files.readString(c.config.resolve(SHAPE));
            Map<String, Object> landed = com.gamma.config.io.ConfigCodec.toMap(text);
            assertEquals("EU: west", landed.get("description"), text);
            assertInstanceOf(Number.class, landed.get("retentionDays"), text);
            assertEquals(List.of("k: v"), landed.get("labels"), text);
            assertEquals("", landed.get("note"), text);
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

    /**
     * CLASSIFICATION-BULK-IMPORT-UNGATED-1: a promotion writes the source's files as bytes, past every save gate, so a
     * promoted schema is judged for its column classes where it lands. An unknown one takes back the whole set, the
     * requested removal included; the same promotion under a known class lands.
     */
    @Test
    void aPromotedSchemaOfAnUnknownClassIsRefusedWithTheTargetUnchangedAndAKnownOnePromotes(@TempDir Path root,
                                                                                         @TempDir Path tmp) throws Exception {
        String schema = "raw:\n  name: calls\n  format: CSV\n  fields[2]{name,selector,type,classification}:\n"
                + "    A_NUMBER,\"0\",VARCHAR,MSISDN\n    CELL,\"1\",VARCHAR,%s\n";
        try (Ctx c = open(root)) {
            write(tmp.resolve("src-config"), "calls/calls_schema.toon", schema.formatted("CONFIDENTIAL"));
            byte[] zip = sourceBundle(tmp);
            JsonNode p = preview(c, zip);
            assertEquals(List.of("calls/calls_schema.toon"), actions(p, "add"), p.toString());
            Map<String, String> before = new java.util.TreeMap<>();
            try (var all = Files.walk(c.config)) {
                for (Path f : all.filter(Files::isRegularFile).toList()) before.put(c.config.relativize(f).toString(), Files.readString(f));
            }

            HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(zip, Map.of("previewHash", p.get("changeSetHash").asText(), "removals", true)));
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("ERR_UNKNOWN_CLASSIFICATION")
                    && r.body().contains("calls/calls_schema.toon: raw.fields[1].classification") && r.body().contains("'CONFIDENTIAL'"), r.body());
            Map<String, String> after = new java.util.TreeMap<>();
            try (var all = Files.walk(c.config)) {
                for (Path f : all.filter(Files::isRegularFile).toList()) after.put(c.config.relativize(f).toString(), Files.readString(f));
            }
            assertEquals(before, after, "the changed file, the added schema and the removal were all taken back");

            write(tmp.resolve("src-config"), "calls/calls_schema.toon", schema.formatted("INTERNAL"));
            byte[] known = sourceBundle(tmp);
            HttpResponse<String> ok = post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(known, Map.of("previewHash", preview(c, known).get("changeSetHash").asText())));
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(Files.readString(c.config.resolve("calls/calls_schema.toon")).contains("INTERNAL"));
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

    @Test
    void aCarriedPublicationJobRefusesTheApplyAndARemovedJobStopsAtOnce(@TempDir Path root, @TempDir Path tmp)
            throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src-config"));
        write(src, ORDERS, "physicalRef: orders/database\ndescription: v2\n");
        write(src, SAME, "physicalRef: same/database\n");
        write(src, PARAM, "physicalRef: eu/database\nowner: alpha\n");
        write(src, "jobs/pub_job.toon", "job:\n  name: pub\n  type: publish.postgres\n  enabled: false\n");
        byte[] withPublication = BundleExporter.exportSpace(src, null, "src");
        Files.delete(src.resolve("jobs/pub_job.toon"));
        byte[] plain = BundleExporter.exportSpace(src, null, "src");
        Path config = Files.createDirectories(root.resolve("alpha/config"));
        write(config, "jobs/hello_job.toon",
                "job:\n  name: hello\n  type: sample.hello\n  cron: \"0 * * * *\"\n  enabled: true\n");
        try (Ctx c = open(root)) {
            HttpResponse<String> jobsBefore = send(c, "GET", "/spaces/alpha/jobs");
            assertTrue(jobsBefore.body().contains("\"hello\""), jobsBefore.body());

            String hash = preview(c, withPublication).get("changeSetHash").asText();
            HttpResponse<String> pub = post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(withPublication, Map.of("previewHash", hash)));
            assertEquals(409, pub.statusCode(), "a publish.postgres Job needs its own four-eyes approval: " + pub.body());
            assertFalse(Files.exists(c.config.resolve("jobs/pub_job.toon")));
            assertTrue(Files.readString(c.config.resolve(ORDERS)).contains("v1"), "nothing was written");

            JsonNode p = preview(c, plain);
            assertTrue(actions(p, "remove").contains("jobs/hello_job.toon"), p.toString());
            HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(plain, Map.of("previewHash", p.get("changeSetHash").asText(), "removals", true)));
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(Files.exists(c.config.resolve("jobs/hello_job.toon")));
            HttpResponse<String> jobsAfter = send(c, "GET", "/spaces/alpha/jobs");
            assertFalse(jobsAfter.body().contains("\"hello\""), "a removed Job stops at once: " + jobsAfter.body());
        }
    }

    /**
     * SPACE-PROMOTION-CASE-PAIRING-1: the change set pairs files by spelling, so a bundle path naming a target file in
     * another case was an add plus a remove of one file on a case-insensitive filesystem, and with {@code removals: true}
     * the remove deleted what the add wrote. Such a path, or two bundle paths that differ only in case, is refused (422
     * {@code caseCollisions}) by both routes, and nothing is written or removed.
     */
    @Test
    void aBundlePathSpelledInAnotherCaseThanATargetFileOrAnotherBundlePathIs422(@TempDir Path root, @TempDir Path tmp)
            throws Exception {
        byte[] againstTarget = withEntry(sourceBundle(tmp.resolve("a")), "Registry/datasets/extra.toon",
                "physicalRef: extra2/database\n");
        byte[] withinBundle = withEntry(withEntry(sourceBundle(tmp.resolve("b")), "registry/datasets/new.toon",
                "physicalRef: new/database\n"), "Registry/datasets/NEW.toon", "physicalRef: new2/database\n");
        try (Ctx c = open(root)) {
            for (byte[] zip : List.of(againstTarget, withinBundle)) {
                boolean target = zip == againstTarget;
                for (String route : List.of("preview", "apply")) {
                    HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/" + route, "admin",
                            body(zip, Map.of("previewHash", "x", "removals", true)));
                    assertEquals(422, r.statusCode(), route + ": " + r.body());
                    JsonNode collisions = JSON.readTree(r.body()).findValue("caseCollisions");
                    assertNotNull(collisions, r.body());
                    if (target) assertEquals(EXTRA, collisions.path("Registry/datasets/extra.toon").asText(), r.body());
                    else assertEquals("Registry/datasets/NEW.toon", collisions.path("registry/datasets/new.toon").asText(),
                            r.body());
                }
            }
            assertEquals("physicalRef: extra/database\n", Files.readString(c.config.resolve(EXTRA)), "nothing was removed");
            assertTrue(Files.readString(c.config.resolve(ORDERS)).contains("v1"), "nothing was written");
            assertFalse(Files.exists(c.config.resolve("registry/datasets/new.toon")));
        }
    }

    /**
     * SPACE-PROMOTION-CASE-PAIRING-1: removing a {@code *_job.TOON} that no loader reads (they match names as spelled) only
     * deletes the file; it does not stop the live Job of the same name, nor revoke its approvals.
     */
    @Test
    void removingAJobFileNoLoaderReadsLeavesTheLiveJobOfThatNameRunning(@TempDir Path root, @TempDir Path tmp)
            throws Exception {
        String hello = "job:\n  name: hello\n  type: sample.hello\n  cron: \"0 * * * *\"\n  enabled: true\n";
        Path src = Files.createDirectories(tmp.resolve("src-config"));
        write(src, ORDERS, "physicalRef: orders/database\ndescription: v2\n");
        write(src, SAME, "physicalRef: same/database\n");
        write(src, PARAM, "physicalRef: eu/database\nowner: alpha\n");
        write(src, "jobs/hello_job.toon", hello);
        byte[] zip = BundleExporter.exportSpace(src, null, "src");
        Path config = Files.createDirectories(root.resolve("alpha/config"));
        write(config, "jobs/hello_job.toon", hello);
        write(config, "old/hello_job.TOON", hello);
        try (Ctx c = open(root)) {
            assertTrue(send(c, "GET", "/spaces/alpha/jobs").body().contains("\"hello\""));
            JsonNode p = preview(c, zip);
            assertTrue(actions(p, "remove").contains("old/hello_job.TOON"), p.toString());
            HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(zip, Map.of("previewHash", p.get("changeSetHash").asText(), "removals", true)));
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(Files.exists(c.config.resolve("old/hello_job.TOON")), "the file is removed");
            HttpResponse<String> jobs = send(c, "GET", "/spaces/alpha/jobs");
            assertTrue(jobs.body().contains("\"hello\""), "the live Job of that name still runs: " + jobs.body());
        }
    }

    /**
     * SPACE-PROMOTION-CASE-PAIRING-1: a connection named in another case with only secret references is written (no literal
     * secret excludes it) but not registered live, since no loader reads it and a restart would drop it.
     */
    @Test
    void aConnectionNamedInAnotherCaseIsWrittenButNotRegisteredLive(@TempDir Path root, @TempDir Path tmp) throws Exception {
        String conn = "connection:\n  id: %s\n  connector: postgres\n  host: h\n  password: ${DB_PASS}\n";
        byte[] zip = withEntry(withEntry(sourceBundle(tmp), "connections/low_connection.toon", conn.formatted("low")),
                "connections/up_connection.TOON", conn.formatted("up"));
        try (Ctx c = open(root)) {
            JsonNode p = preview(c, zip);
            HttpResponse<String> r = post(c.port, "/spaces/alpha/promotion/apply", "admin",
                    body(zip, Map.of("previewHash", p.get("changeSetHash").asText())));
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(Files.exists(c.config.resolve("connections/up_connection.TOON")), "it is written");
            List<String> live = new ArrayList<>();
            V1Body.of(send(c, "GET", "/spaces/alpha/connections").body()).forEach(n -> live.add(n.get("id").asText()));
            assertTrue(live.contains("low"), "a connection a loader reads is registered: " + live);
            assertFalse(live.contains("up"), "one named in another case is not: " + live);
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", "Bearer admin").method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
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
    void underAnApprovalPolicyApplyIsOnePendingChangeLandsOnlyOnApprovalAndNeverHoldsALiteralSecret(@TempDir Path cfg, @TempDir Path writeRoot,
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

            // SPACE-PROMOTION-PARAM-TYPES-1 (2): a held apply records its request, bundle included, so a hand-made bundle
            // whose connection carries a literal secret is refused before it is held - nothing is stored
            // (SPACE-PROMOTION-EXT-CASE-1: whatever the case of its name)
            for (String name : List.of("connections/db_connection.toon", "connections/db_connection.TOON")) {
                byte[] secret = withEntry(zip, name,
                        "connection:\n  id: db\n  connector: postgres\n  host: h\n  password: hunter2\n");
                JsonNode sp = V1Body.of(post(port, "/promotion/preview", ADMIN, body(secret, Map.of())).body());
                HttpResponse<String> refused = post(port, "/promotion/apply", ADMIN,
                        body(secret, Map.of("previewHash", sp.get("changeSetHash").asText())));
                assertEquals(422, refused.statusCode(), name + ": " + refused.body());
                assertTrue(refused.body().contains(name), refused.body());
            }
            Path pending = writeRoot.resolve("pending-changes");
            if (Files.isDirectory(pending)) try (var files = Files.list(pending)) {
                assertEquals(List.of(), files.toList(), "nothing was held");
            }

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

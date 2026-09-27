package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code IMPORT-DEDICATED-ONLY-KINDS-1}, over real HTTP with an ARMED Authenticator: {@code access-profile},
 * {@code access-catalog} and {@code requirement} are written through their dedicated routes only. The generic
 * {@code /components/{kind}} door refuses them for ANY caller (a {@code canAuthorWorkbench} builder deleting an Access
 * Profile would WIDEN that subject's access; a content write of a Requirement skips its triage lifecycle), and so does
 * every import door but a new Space's; a kind with a stricter own route needs that capability on the generic door
 * too; and the raw {@code /import} classifies each entry as the filesystem resolves it.
 */
class ControlApiDedicatedOnlyKindsTest {

    private static final String BUILDER = "Bearer builder", BOTH = "Bearer both", ADMIN = "Bearer admin";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case BUILDER -> Optional.of(new Subject("builder-1", Set.of("canAuthorWorkbench")));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAuthorWorkbench", "canAdminister")));
            case BOTH -> Optional.of(new Subject("lead-1", Set.of("canAuthorWorkbench", "canOnboardConnections",
                    "canAdminister", "canConfigureAccess", "canTriageRequirements", "canAuthorAlertRules",
                    "canManageIncidents")));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path root) throws Exception {
        Path base = root.resolve("alpha");
        Path config = base.resolve("config");
        Files.createDirectories(config);
        Path tmp = TestConfigs.csv(base.resolve("data").resolve("etl"), PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Files.writeString(base.resolve("space.toon"), "display_name: \"Alpha\"\ndescription: \"x\"\ncreated_at: \"2026-09-27\"\n");
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config);
    }

    private static Map<String, String> tree(Path config) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> all = Files.walk(config)) {
            for (Path p : all.toList()) {
                String rel = config.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".history") || rel.startsWith("audit")) continue;   // not what an import writes
                out.put(rel, Files.isDirectory(p) ? "<dir>"
                        : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        }
        return out;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] dataSourceZip(String path, String content) throws Exception {
        Map<String, byte[]> all = new LinkedHashMap<>();
        all.put("bundle.toon", "kind: datasource\n".getBytes(StandardCharsets.UTF_8));
        all.put(path, content.getBytes(StandardCharsets.UTF_8));
        return zip(all);
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body, String auth) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/alpha" + path))
                .header("Authorization", auth).method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] metadataBundle(Map<String, Object> item) throws Exception {
        return JSON.writeValueAsBytes(Map.of("bundle",
                Map.of("format", "inspecto-metadata-bundle", "version", 1, "items", List.of(item))));
    }

    private static void dedicated(HttpResponse<String> r, String kind) {
        assertEquals(403, r.statusCode(), r.body());
        assertTrue(r.body().contains("'" + kind + "'") && r.body().contains("only"), r.body());
    }

    private static byte[] json(Object o) throws Exception {
        return JSON.writeValueAsBytes(o);
    }

    // ── A: the generic door ──────────────────────────────────────────────────────────────────────

    @Test
    void theGenericDoorNeverWritesAnAccessProfileEvenForAFullyPrivilegedCaller(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            new com.gamma.pipeline.ComponentStore(c.config.resolve("registry"))
                    .write("access-profile", "analyst-1", Map.of("subject", "analyst-1", "denied", List.of("secret")));
            Map<String, String> before = tree(c.config);
            for (String auth : List.of(BUILDER, BOTH)) {
                dedicated(send(c, "POST", "/components/access-profile", json(Map.of("id", "x", "subject", "x")), auth),
                        "access-profile");
                dedicated(send(c, "PUT", "/components/access-profile/analyst-1", json(Map.of("subject", "analyst-1")),
                        auth), "access-profile");
                dedicated(send(c, "DELETE", "/components/access-profile/analyst-1", new byte[0], auth), "access-profile");
                dedicated(send(c, "POST", "/components/access-profile/analyst-1/versions/1/restore", new byte[0], auth),
                        "access-profile");
                dedicated(send(c, "POST", "/components/access-catalog", json(Map.of("id", "x")), auth), "access-catalog");
                dedicated(send(c, "POST", "/components/access%252Dprofile", json(Map.of("id", "x")), auth),
                        "access-profile");
            }
            assertEquals(before, tree(c.config), "the profile is untouched");
        }
    }

    @Test
    void theGenericDoorNeverWritesARequirement(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, String> before = tree(c.config);
            dedicated(send(c, "POST", "/components/requirement",
                    json(Map.of("id", "r1", "title", "t", "status", "delivered")), BOTH), "requirement");
            dedicated(send(c, "PUT", "/components/requirement/r1", json(Map.of("status", "delivered")), BUILDER),
                    "requirement");
            assertEquals(before, tree(c.config));
        }
    }

    // ── B: the generic door asks a stricter kind's own capability ───────────────────────────────

    @Test
    void theGenericDoorAsksAnAlertRulesOwnCapability(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, String> before = tree(c.config);
            HttpResponse<String> r = send(c, "POST", "/components/alert-rule",
                    json(Map.of("id", "loud", "name", "loud")), BUILDER);
            assertEquals(403, r.statusCode(), r.body());
            assertTrue(r.body().contains("canAuthorAlertRules"), r.body());
            assertEquals(before, tree(c.config));
        }
    }

    // ── A: the import doors ──────────────────────────────────────────────────────────────────────

    @Test
    void aBundleCarryingARequirementIsRefusedWhole(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, Object> view = Map.of("kind", "dataset", "id", "harmless", "content", Map.of("name", "harmless"));
            Map<String, Object> req = Map.of("kind", "requirement", "id", "r1",
                    "content", Map.of("title", "t", "status", "delivered"));
            byte[] body = json(Map.of("bundle", Map.of("format", "inspecto-metadata-bundle", "version", 1,
                    "items", List.of(view, req))));
            Map<String, String> before = tree(c.config);
            dedicated(send(c, "POST", "/bundle/import", body, BOTH), "requirement");
            assertEquals(before, tree(c.config), "atomic: the dataset item was not written either");
        }
    }

    @Test
    void aRawImportCarryingARequirementIsRefusedWhole(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, String> before = tree(c.config);
            dedicated(send(c, "POST", "/import", dataSourceZip("registry/requirements/r1.toon",
                    "title: t\nstatus: delivered\n"), BOTH), "requirement");
            // another spelling is refused too (ImportPaths' shape rule may answer first; either way nothing lands)
            assertEquals(403, send(c, "POST", "/import", dataSourceZip("REGISTRY/requirements./r1.toon",
                    "title: t\n"), BOTH).statusCode());
            assertEquals(before, tree(c.config));
        }
    }

    @Test
    void aNewSpaceMayStillCarryItsRequirements(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> ok = client.send(HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + c.port + "/api/v1/spaces/import?id=beta"))
                    .header("Authorization", ADMIN).POST(HttpRequest.BodyPublishers.ofByteArray(
                            dataSourceZip("registry/requirements/r1.toon", "title: t\n"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(Files.exists(root.resolve("beta/config/registry/requirements/r1.toon")));
        }
    }

    // ── C: classification is spelling-proof ──────────────────────────────────────────────────────

    @Test
    void aFindingsSpecInAnySpellingNeverLandsForABuilder(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, String> before = tree(c.config);
            for (String p : List.of("Registry/findings-specs/x.toon", "REGISTRY/FINDINGS-SPECS/x.toon",
                    "registry./findings-specs/x.toon", "registry/findings-specs./x.toon",
                    "registry /findings-specs/x.toon", "./registry//findings-specs/x.toon")) {
                HttpResponse<String> r = send(c, "POST", "/import", dataSourceZip(p, "name: x\n"), BUILDER);
                assertTrue(r.statusCode() >= 400 && r.statusCode() < 500, p + " -> " + r.statusCode() + " " + r.body());
            }
            assertEquals(before, tree(c.config), "no spelling lands a findings-spec");
        }
    }

    // ── E: a bundle-imported kpi meets the /components/kpi save gate ─────────────────────────────

    private static Map<String, Object> kpi(String dataset, String timeField) {
        return Map.of("title", "Revenue", "dataset", dataset, "measure", "sum(amount)", "timeField", timeField,
                "grain", "month", "comparison", "previous", "direction", "up");
    }

    private static byte[] bundleOf(Map<?, ?>... items) throws Exception {
        return json(Map.of("bundle", Map.of("format", "inspecto-metadata-bundle", "version", 1, "items", List.of(items))));
    }

    private static com.fasterxml.jackson.databind.JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void aBundleImportedKpiMeetsTheComponentsKpiSaveGate(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            new com.gamma.pipeline.ViewStore(c.config.resolve("views")).write(new com.gamma.pipeline.ViewDefinition("orders_view",
                    "pipeline-x", List.of(), "SELECT * FROM (VALUES (DATE '2026-07-02', 10.0), (DATE '2026-08-01', 20.0))"
                    + " AS t(order_date, amount)", "2026-08-01T00:00:00Z"));
            com.gamma.pipeline.ComponentStore store = new com.gamma.pipeline.ComponentStore(c.config.resolve("registry"));

            var ghost = data(send(c, "POST", "/bundle/import", bundleOf(Map.of("kind", "kpi", "id", "k1",
                    "content", kpi("ghost", "order_date"))), BUILDER));
            assertEquals(1, ghost.get("failed").asInt(), ghost.toString());
            assertTrue(ghost.at("/results/0/message").asText().contains("does not exist"), ghost.toString());
            assertTrue(store.get("kpi", "k1").isEmpty(), "a KPI over a missing Dataset is not written");

            // the Dataset travels in the same bundle: it is applied first, so the KPI's Measure resolves
            var both = data(send(c, "POST", "/bundle/import", bundleOf(
                    Map.of("kind", "kpi", "id", "k2", "content", kpi("orders", "order_date")),
                    Map.of("kind", "dataset", "id", "orders", "content", Map.of("view", "orders_view"))), BUILDER));
            assertEquals(2, both.get("imported").asInt(), both.toString());
            assertTrue(store.get("kpi", "k2").isPresent());

            var notATime = data(send(c, "POST", "/bundle/import", bundleOf(Map.of("kind", "kpi", "id", "k3",
                    "content", kpi("orders", "amount"))), BUILDER));
            assertEquals(1, notATime.get("failed").asInt(), notATime.toString());
            assertTrue(notATime.at("/results/0/message").asText().contains("DATE, TIMESTAMP"), notATime.toString());
            assertTrue(store.get("kpi", "k3").isEmpty(), "a KPI cut on a non-date column is not written");
        }
    }
}

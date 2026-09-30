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
 * `IMPORT-CONNECTION-JOB-GATE-1`, over real HTTP with an ARMED Authenticator (without a Subject every capability
 * check is a no-op and these would pass vacuously): an import is never a way around a kind's own route gate. A
 * {@code canAuthorWorkbench}-only builder importing a Connection ({@code /connections}: canOnboardConnections), an
 * {@code event_prune} Job ({@code /jobs}: canAdminister), an Alert Rule or a Findings Spec is refused 403 naming
 * the kind and the capability, with the config tree byte-for-byte unchanged — at every door.
 */
class ControlApiImportCapabilityGateTest {

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
                    "canAdminister")));
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

    private static final String PRUNE_JOB = "job:\n  name: retention\n  type: maintenance\n  task: event_prune\n"
            + "  retention_days: 30\n";

    private static void refused(HttpResponse<String> r, String kind, String capability) {
        assertEquals(403, r.statusCode(), r.body());
        assertTrue(r.body().contains("'" + kind + "'") && r.body().contains(capability), r.body());
    }

    // ── /bundle/import ───────────────────────────────────────────────────────────────────────────

    @Test
    void aBundleConnectionNeedsCanOnboardConnections(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] body = metadataBundle(Map.of("kind", "connection", "id", "carried",
                    "content", Map.of("connector", "local", "base_path", root.toString().replace('\\', '/'))));
            Map<String, String> before = tree(c.config);
            refused(send(c, "POST", "/bundle/import", body, BUILDER), "connection", "canOnboardConnections");
            assertEquals(before, tree(c.config), "nothing written");

            HttpResponse<String> ok = send(c, "POST", "/bundle/import", body, BOTH);
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(ok.body().contains("\"imported\""), ok.body());
        }
    }

    @Test
    void aBundleAlertRuleNeedsCanAuthorAlertRules(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, String> before = tree(c.config);
            refused(send(c, "POST", "/bundle/import", metadataBundle(Map.of("kind", "alert-rule", "id", "loud",
                    "content", Map.of("name", "loud"))), BUILDER), "alert-rule", "canAuthorAlertRules");
            assertEquals(before, tree(c.config), "nothing written");
        }
    }

    @Test
    void aBundleEventPruneJobNeedsCanAdministerBeforeAnyItemIsWritten(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, Object> job = Map.of("kind", "job", "id", "retention", "content",
                    Map.of("type", "maintenance", "task", "event_prune", "retention_days", "30"));
            Map<String, Object> view = Map.of("kind", "dataset", "id", "harmless", "content", Map.of("name", "harmless"));
            byte[] body = JSON.writeValueAsBytes(Map.of("bundle", Map.of("format", "inspecto-metadata-bundle",
                    "version", 1, "items", List.of(view, job))));
            Map<String, String> before = tree(c.config);
            refused(send(c, "POST", "/bundle/import", body, BUILDER), "job (event_prune)", "canAdminister");
            assertEquals(before, tree(c.config), "atomic: the dataset item was not written either");
        }
    }

    // ── /import (the raw config zip) ─────────────────────────────────────────────────────────────

    @Test
    void aRawImportConnectionNeedsCanOnboardConnections(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] body = dataSourceZip("connections/carried_connection.toon", "connection:\n  id: CARRIED\n"
                    + "  connector: local\n  base_path: " + root.toString().replace('\\', '/') + "\n");
            Map<String, String> before = tree(c.config);
            refused(send(c, "POST", "/import", body, BUILDER), "connection", "canOnboardConnections");
            assertEquals(before, tree(c.config), "nothing written");

            HttpResponse<String> ok = send(c, "POST", "/import", body, BOTH);
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(Files.exists(c.config.resolve("connections/carried_connection.toon")));
        }
    }

    @Test
    void aRawImportEventPruneJobNeedsCanAdministerButAnOrdinaryJobDoesNot(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] prune = dataSourceZip("jobs/retention_job.toon", PRUNE_JOB);
            Map<String, String> before = tree(c.config);
            refused(send(c, "POST", "/import", prune, BUILDER), "job (event_prune)", "canAdminister");
            assertEquals(before, tree(c.config), "nothing written");

            HttpResponse<String> plain = send(c, "POST", "/import", dataSourceZip("jobs/tidy_job.toon",
                    "job:\n  name: tidy\n  type: maintenance\n  task: heartbeat\n"), BUILDER);
            assertEquals(200, plain.statusCode(), "an ordinary Job stays a builder's: " + plain.body());

            HttpResponse<String> ok = send(c, "POST", "/import", prune, BOTH);
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(Files.exists(c.config.resolve("jobs/retention_job.toon")));
        }
    }

    /** SEC-INGEST-EXPR-EXTERNAL-ACCESS-1: a carried Pipeline whose data home is the Space root, config/ or a
     *  .secrets dir would hand its sealed ingest connection the Pending Change key — refused, nothing written. */
    @Test
    void aRawImportPipelineWhoseDataHomeIsTheSpaceRootOrConfigIsRefused(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String spaceRoot = c.config.getParent().toString().replace('\\', '/');
            for (String[] bad : new String[][]{{"errors", "."}, {"errors", spaceRoot}, {"backup", "config/x"},
                    {"temp", "config.secrets"}}) {
                Map<String, String> before = tree(c.config);
                Map<String, Object> leak = new LinkedHashMap<>(ConfigCodec.toMap(Files.readString(c.config.resolve("etl_pipeline.toon"))));
                leak.put("name", "leak");
                @SuppressWarnings("unchecked") Map<String, Object> dirs = new LinkedHashMap<>((Map<String, Object>) leak.get("dirs"));
                dirs.put(bad[0], bad[1]);
                leak.put("dirs", dirs);
                HttpResponse<String> r = send(c, "POST", "/import",
                        dataSourceZip("leak/leak_pipeline.toon", ConfigCodec.toToon(leak)), BOTH);
                assertEquals(403, r.statusCode(), bad[1] + ": " + r.body());
                assertTrue(r.body().contains("dirs." + bad[0]), r.body());
                assertEquals(before, tree(c.config), "nothing written for " + bad[1]);
            }
        }
    }

    @Test
    void aRawImportFindingsSpecNeedsCanManageIncidents(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, String> before = tree(c.config);
            refused(send(c, "POST", "/import", dataSourceZip("registry/findings-specs/mine.toon", "name: mine\n"), BUILDER),
                    "findings-spec", "canManageIncidents");
            assertEquals(before, tree(c.config), "nothing written");
        }
    }

    // ── /pipelines/import (a satellite) ──────────────────────────────────────────────────────────

    @Test
    void aPipelineSatelliteConnectionNeedsCanOnboardConnections(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                            + "/api/v1/spaces/alpha/pipelines/test_etl/bundle")).header("Authorization", BUILDER).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, r.statusCode());
            LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(r.body()))) {
                for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry())
                    if (!e.isDirectory()) entries.put(e.getName(), zis.readAllBytes());
            }
            Map<String, Object> manifest = ConfigCodec.toMap(new String(entries.get("manifest.toon"), StandardCharsets.UTF_8));
            List<Object> sats = new ArrayList<>(manifest.get("satellites") instanceof List<?> l ? l : List.of());
            sats.add(new LinkedHashMap<>(Map.of("path", "carried_connection.toon")));
            manifest.put("satellites", sats);
            entries.put("manifest.toon", ConfigCodec.toToon(manifest).getBytes(StandardCharsets.UTF_8));
            entries.put("carried_connection.toon", "connection:\n  id: CARRIED\n  connector: local\n".getBytes(StandardCharsets.UTF_8));
            Map<String, String> before = tree(c.config);
            refused(send(c, "POST", "/pipelines/import?name=copy", zip(entries), BUILDER), "connection", "canOnboardConnections");
            assertEquals(before, tree(c.config), "nothing written");
        }
    }

    // ── adversarial additions (verifier 2026-09-27) ──────────────────────────────────────────────

    @Test
    void aTaskSpelledInAnotherCaseIsStillAnEventPruneJob(@TempDir Path root) throws Exception {
        // MaintenanceJob lower-cases the task before dispatch, so EVENT_PRUNE runs the prune
        try (Ctx c = open(root)) {
            byte[] prune = dataSourceZip("jobs/retention_job.toon", PRUNE_JOB.replace("event_prune", "EVENT_Prune"));
            Map<String, String> before = tree(c.config);
            refused(send(c, "POST", "/import", prune, BUILDER), "job (event_prune)", "canAdminister");
            assertEquals(before, tree(c.config), "nothing written");
        }
    }

    @Test
    void aRawImportRestoreJobInAnyCasingNeedsCanAdminister(@TempDir Path root) throws Exception {
        // MAINT-RESTORE-ESCALATION-1: the import guard asks JobRoutes.isAdministerOnlyMaintenance, which judges the
        // JobConfig task string — inspecto-backup (where the restore task lives) is not on this module's classpath
        try (Ctx c = open(root)) {
            for (String task : List.of("restore", "RESTORE", "Restore")) {
                byte[] restore = dataSourceZip("jobs/back_job.toon", "job:\n  name: back\n  type: maintenance\n"
                        + "  task: " + task + "\n  archive: x.zip\n  target: config\n  overwrite: true\n");
                Map<String, String> before = tree(c.config);
                refused(send(c, "POST", "/import", restore, BUILDER), "job (restore)", "canAdminister");
                assertEquals(before, tree(c.config), "nothing written for task " + task);
            }
        }
    }

    @Test
    void aMixedRawImportWhoseForbiddenEntryComesLastWritesNothing(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, byte[]> all = new LinkedHashMap<>();
            all.put("bundle.toon", "kind: datasource\n".getBytes(StandardCharsets.UTF_8));
            all.put("jobs/tidy_job.toon", "job:\n  name: tidy\n  type: maintenance\n  task: heartbeat\n"
                    .getBytes(StandardCharsets.UTF_8));
            all.put("jobs/retention_job.toon", PRUNE_JOB.getBytes(StandardCharsets.UTF_8));
            Map<String, String> before = tree(c.config);
            refused(send(c, "POST", "/import", zip(all), BUILDER), "job (event_prune)", "canAdminister");
            assertEquals(before, tree(c.config), "the harmless entry ahead of the refused one is not written either");
        }
    }

    @Test
    void aNewSpaceBundleCarryingAConnectionNeedsCanOnboardConnectionsEvenForAnAdministrator(@TempDir Path root)
            throws Exception {
        try (Ctx c = open(root)) {
            byte[] body = dataSourceZip("connections/carried_connection.toon",
                    "connection:\n  id: carried\n  connector: local\n");
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + c.port + "/api/v1/spaces/import?id=beta"))
                    .header("Authorization", ADMIN).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            refused(r, "connection", "canOnboardConnections");
            assertTrue(Files.notExists(root.resolve("beta")), "no Space directory is created");

            HttpResponse<String> ok = client.send(HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + c.port + "/api/v1/spaces/import?id=beta"))
                    .header("Authorization", BOTH).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(Files.exists(root.resolve("beta/config/connections/carried_connection.toon")));
        }
    }
}

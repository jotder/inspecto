package com.gamma.control;

import com.gamma.config.spec.FindingCodes;
import com.gamma.entitystore.EntityTypes.EntityType;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLASSIFICATION-BULK-IMPORT-UNGATED-1 over real HTTP: the importers that land config files as bytes refuse a column
 * classification the target Space does not know, and leave nothing behind. One door per test: the Pipeline bundle
 * ({@code POST /pipelines/import}), the zip bundle Collector import ({@code POST /import}) and a Space created from a
 * bundle ({@code POST /spaces/import}). Every bundle is one the server itself exported from Space {@code alpha}, whose
 * schema file is named by its Pipeline ({@code schema_<hex>.toon}), not by a suffix. Each refusal is paired with the
 * same bundle under a known class, which imports. The template upgrade apply is in
 * {@link ControlApiSpaceOriginUpgradeTest} and the template seed in {@link ControlApiSpaceTemplateSeedGateTest}.
 */
class ControlApiClassificationBulkImportTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); MetricRegistry.global().reset(); }

        Path config(String space) {
            return root.resolve(space).resolve("config");
        }
    }

    /** The mini schema with its ID column classed {@code cls}. */
    private static String schema(String cls) {
        return PipelineConfigBatchTest.miniSchema()
                .replace("fields[3]{name,selector,type}:", "fields[3]{name,selector,type,classification}:")
                .replace("ID,\"0\",VARCHAR", "ID,\"0\",VARCHAR," + cls)
                .replace("AMT,\"1\",DOUBLE", "AMT,\"1\",DOUBLE,INTERNAL")
                .replace("EVENT_DATE,\"2\",DATE", "EVENT_DATE,\"2\",DATE,INTERNAL");
    }

    /** Space {@code alpha} hosts {@code test_etl}, its schema's ID column classed {@code cls}; {@code beta} is empty. */
    private Ctx open(Path root, String cls) throws Exception {
        Path config = root.resolve("alpha").resolve("config");
        Files.createDirectories(config);
        Path tmp = ControlApiBundleImportTest.dataDirsOutOfConfig(TestConfigs.csv(config, schema(cls)).write(),
                config, config.getParent().resolve("data"));
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Files.createDirectories(root.resolve("beta").resolve("config"));
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), root);
    }

    /** Re-class the ID column of alpha's schema file on disk: the next export carries it. */
    private static void reclass(Ctx c, String from, String to) throws Exception {
        try (Stream<Path> files = Files.list(c.config("alpha"))) {
            Path schema = files.filter(p -> p.getFileName().toString().startsWith("schema_")).findFirst().orElseThrow();
            assertTrue(Files.readString(schema).contains("VARCHAR," + from), "the ID row this test reclassifies");
            Files.writeString(schema, Files.readString(schema).replace("VARCHAR," + from, "VARCHAR," + to));
        }
    }

    private static void entityTypeClaiming(Path config, String cls) throws Exception {
        List<EntityType> own = List.of(new EntityType("badge", "Staff badge", "upper-trim", true, List.of(cls)));
        new LinkAnalysisSettings(null, null, null, null, null, null, own, null, null, null, null, null, null, null, null)
                .write(config.resolve(LinkAnalysisSettings.FILE));
    }

    private void assertUnknownClass(HttpResponse<String> r, String cls) {
        assertEquals(422, r.statusCode(), r.body());
        assertTrue(r.body().contains(FindingCodes.ERR_UNKNOWN_CLASSIFICATION), r.body());
        assertTrue(r.body().contains("'ID'") && r.body().contains("'" + cls + "'"), "names the column and the class: " + r.body());
        assertTrue(r.body().contains("schema_"), "names the file: " + r.body());
    }

    // ── the Pipeline bundle ──────────────────────────────────────────────────────────────────────

    @Test
    void aPipelineBundleWhoseSchemaSatelliteStatesAnUnknownClassIsRefusedAndAKnownOneImports(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "CONFIDENTIAL")) {
            byte[] bundle = getBytes(c, "/spaces/alpha/pipelines/test_etl/bundle").body();
            Map<String, String> before = tree(c.config("beta"));

            HttpResponse<String> r = post(c, "/spaces/beta/pipelines/import?name=copy_one", bundle);
            assertUnknownClass(r, "CONFIDENTIAL");
            assertTrue(r.body().contains("\"written\":false"), r.body());
            assertEquals(before, tree(c.config("beta")), "the satellite that landed first was taken back");
            assertEquals(404, getBytes(c, "/spaces/beta/pipelines/copy_one/graph/raw").statusCode(), "and nothing was registered");

            reclass(c, "CONFIDENTIAL", "MSISDN");
            HttpResponse<String> ok = post(c, "/spaces/beta/pipelines/import?name=copy_one",
                    getBytes(c, "/spaces/alpha/pipelines/test_etl/bundle").body());
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(Files.exists(c.config("beta").resolve("copy_one").resolve("copy_one_pipeline.toon")));
            assertEquals(200, getBytes(c, "/spaces/beta/pipelines/copy_one/graph/raw").statusCode());
        }
    }

    // ── the zip bundle Collector import ──────────────────────────────────────────────────────────

    @Test
    void aCollectorBundleWhoseSchemaStatesAnUnknownClassIsRefusedAndAKnownOneImports(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "CONFIDENTIAL")) {
            byte[] bundle = getBytes(c, "/spaces/alpha/datasources/test_etl/export").body();
            Map<String, String> before = tree(c.config("beta"));

            HttpResponse<String> r = post(c, "/spaces/beta/import", bundle);
            assertUnknownClass(r, "CONFIDENTIAL");
            assertTrue(r.body().contains("CONFIG_VALIDATION_FAILED"), r.body());
            assertEquals(before, tree(c.config("beta")), "nothing of the bundle stayed written");
            assertFalse(new String(getBytes(c, "/spaces/beta/datasources").body(), StandardCharsets.UTF_8).contains("test_etl"),
                    "and nothing was registered");

            reclass(c, "CONFIDENTIAL", "MSISDN");
            HttpResponse<String> ok = post(c, "/spaces/beta/import", getBytes(c, "/spaces/alpha/datasources/test_etl/export").body());
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(new String(getBytes(c, "/spaces/beta/datasources").body(), StandardCharsets.UTF_8).contains("test_etl"));
        }
    }

    @Test
    void aCollectorBundleWhoseDatasetEntryStatesAnUnknownClassIsRefusedAndAKnownOneImports(@TempDir Path root) throws Exception {
        String dataset = """
                physicalRef: calls/database
                columns[2]{name,type,classification}:
                  a_party,string,MSISDN
                  cell,string,%s
                """;
        try (Ctx c = open(root, "MSISDN")) {
            LinkedHashMap<String, byte[]> entries = unzip(getBytes(c, "/spaces/alpha/datasources/test_etl/export").body());
            Map<String, String> before = tree(c.config("beta"));

            entries.put("registry/datasets/calls.toon", dataset.formatted("CONFIDENTIAL").getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> r = post(c, "/spaces/beta/import", zip(entries));
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains(FindingCodes.ERR_UNKNOWN_CLASSIFICATION), r.body());
            assertTrue(r.body().contains("registry/datasets/calls.toon: columns[1].classification") && r.body().contains("'cell'"),
                    "names the file and the column: " + r.body());
            assertEquals(before, tree(c.config("beta")), "the Pipeline and schema that travelled with it were taken back too");

            entries.put("registry/datasets/calls.toon", dataset.formatted("INTERNAL").getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> ok = post(c, "/spaces/beta/import", zip(entries));
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(Files.exists(c.config("beta").resolve("registry").resolve("datasets").resolve("calls.toon")));
        }
    }

    /** {@code link-analysis.toon} is reserved at this door, so the Entity Type that makes a class known is the target's own. */
    @Test
    void aClassAnEntityTypeOfTheTargetSpaceClaimsIsKnownThereAndNowhereElse(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "BADGE_NO")) {
            byte[] bundle = getBytes(c, "/spaces/alpha/datasources/test_etl/export").body();
            Map<String, String> before = tree(c.config("beta"));
            assertUnknownClass(post(c, "/spaces/beta/import", bundle), "BADGE_NO");
            assertEquals(before, tree(c.config("beta")));

            entityTypeClaiming(c.config("beta"), "BADGE_NO");
            HttpResponse<String> ok = post(c, "/spaces/beta/import", bundle);
            assertEquals(200, ok.statusCode(), ok.body());
        }
    }

    // ── a Space created from a bundle ────────────────────────────────────────────────────────────

    @Test
    void aSpaceBundleWhoseSchemaStatesAnUnknownClassCreatesNoSpaceAndTheFixedBundleDoes(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "CONFIDENTIAL")) {
            HttpResponse<String> r = post(c, "/spaces/import?id=gamma", getBytes(c, "/spaces/alpha/export").body());
            assertUnknownClass(r, "CONFIDENTIAL");
            assertTrue(r.body().contains("CONFIG_VALIDATION_FAILED"), r.body());
            assertFalse(Files.exists(root.resolve("gamma")), "a refused bundle leaves no Space directory");
            assertFalse(new String(getBytes(c, "/spaces").body(), StandardCharsets.UTF_8).contains("\"gamma\""), "and no Space");

            reclass(c, "CONFIDENTIAL", "MSISDN");
            HttpResponse<String> ok = post(c, "/spaces/import?id=gamma", getBytes(c, "/spaces/alpha/export").body());
            assertEquals(200, ok.statusCode(), "the retry under the same id is not answered 'already exists': " + ok.body());
            assertTrue(new String(getBytes(c, "/spaces/gamma/datasources").body(), StandardCharsets.UTF_8).contains("test_etl"));
        }
    }

    /** A new Space takes the bundle's own {@code link-analysis.toon}, so its classes are judged against the bundle's Entity Types. */
    @Test
    void aSpaceBundleIsJudgedAgainstTheEntityTypesItBrings(@TempDir Path root, @TempDir Path scratch) throws Exception {
        try (Ctx c = open(root, "BADGE_NO")) {
            LinkedHashMap<String, byte[]> entries = unzip(getBytes(c, "/spaces/alpha/export").body());
            assertFalse(entries.containsKey(LinkAnalysisSettings.FILE), "alpha has no Entity Types of its own to export");
            assertUnknownClass(post(c, "/spaces/import?id=gamma", zip(entries)), "BADGE_NO");
            assertFalse(Files.exists(root.resolve("gamma")));

            entityTypeClaiming(scratch, "BADGE_NO");
            entries.put(LinkAnalysisSettings.FILE, Files.readAllBytes(scratch.resolve(LinkAnalysisSettings.FILE)));
            HttpResponse<String> ok = post(c, "/spaces/import?id=gamma", zip(entries));
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(Files.exists(c.config("gamma").resolve(LinkAnalysisSettings.FILE)));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Every file (with its sha256) and directory under the config root: the "nothing written" oracle. */
    private static Map<String, String> tree(Path config) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> all = Files.walk(config)) {
            for (Path p : all.toList()) {
                String rel = config.relativize(p).toString().replace('\\', '/');
                out.put(rel, Files.isDirectory(p) ? "<dir>"
                        : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        }
        return out;
    }

    private static LinkedHashMap<String, byte[]> unzip(byte[] zip) throws Exception {
        LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry())
                if (!e.isDirectory()) out.put(e.getName(), zis.readAllBytes());
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

    private HttpResponse<byte[]> getBytes(Ctx c, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .GET().build(), BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> post(Ctx c, String path, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/zip")
                .POST(BodyPublishers.ofByteArray(body)).build(), BodyHandlers.ofString());
    }
}

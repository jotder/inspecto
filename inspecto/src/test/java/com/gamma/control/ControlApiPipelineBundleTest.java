package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The TRANSFER-ARCH-1 selective bundle over real HTTP ({@code PipelineBundleRoutes}):
 * {@code GET /pipelines/{name}/bundle} exports one canonical pipeline + its file closure as a zip,
 * {@code POST /pipelines/import} lands it retargeted, inactive, satellites first. Every gate is
 * covered, and the round-trip asserts FILE-level truth (bytes read off disk), not fromMap shapes —
 * the config-format lesson.
 */
class ControlApiPipelineBundleTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots)
            implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    private Ctx open(Path dir, Path writeRoot, Path toon) throws Exception {
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    /** The registered fixture pipeline + a companion enrichment at the write root that triggers on it. */
    private static Path fixture(Path dir) throws Exception {
        return TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).name("test_etl").write();
    }

    private static void companion(Path writeRoot, String pipelineDb) throws Exception {
        Files.createDirectories(writeRoot);
        String enrich = """
                name: TEST_DAILY
                version: 1

                input:
                  database: %s
                  format: PARQUET

                output:
                  database: data/reports/test_daily
                  format: PARQUET

                triggers:
                  on_pipeline: test_etl
                  schedule_seconds: 3600

                transform: "SELECT * FROM input"
                """.formatted(pipelineDb);
        Files.writeString(writeRoot.resolve("test_daily_enrich.toon"), enrich);
    }

    // ── round-trip: export → import under a new name ─────────────────────────────

    @Test
    void bundleRoundTripsUnderANewName(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Path toon = fixture(dir);
        Map<String, Object> raw = ConfigCodec.toMap(Files.readString(toon));
        String sourceDb = String.valueOf(((Map<?, ?>) raw.get("dirs")).get("database"));
        companion(wr, sourceDb);
        Path schema = Path.of(String.valueOf(((Map<?, ?>) raw.get("processing")).get("schema_file")));
        byte[] schemaBytes = Files.readAllBytes(schema);
        String schemaBase = schema.getFileName().toString();

        try (Ctx c = open(dir, wr, toon)) {
            // export: a zip whose manifest names the pipeline, the schema satellite and the companion
            HttpResponse<byte[]> zip = sendZip(c.port, "GET", "/pipelines/test_etl/bundle", null);
            assertEquals(200, zip.statusCode());
            assertEquals("application/zip", zip.headers().firstValue("Content-Type").orElse(""));
            Map<String, byte[]> entries = unzip(zip.body());
            Map<String, Object> manifest = ConfigCodec.toMap(
                    new String(entries.get("manifest.toon"), StandardCharsets.UTF_8));
            assertEquals("inspecto-pipeline-bundle", manifest.get("format"));
            assertEquals("test_etl", manifest.get("pipeline"));
            assertArrayEquals(schemaBytes, entries.get(schemaBase), "the satellite travels byte-verbatim");
            assertTrue(entries.containsKey("test_daily_enrich.toon"), "the companion travels");

            // import under a NEW name
            HttpResponse<String> imp = send(c.port, "POST", "/pipelines/import?name=copy_one", zip.body());
            assertEquals(200, imp.statusCode(), imp.body());
            JsonNode body = V1Body.of(imp.body());
            assertTrue(body.get("written").asBoolean());
            assertEquals("copy_one", body.get("pipeline").asText());
            assertFalse(body.get("active").asBoolean());

            // FILE-level assertions — read what actually landed on disk
            Path written = wr.resolve("copy_one").resolve("copy_one_pipeline.toon");
            assertTrue(Files.exists(written), "the canonical pipeline file was written");
            Map<String, Object> imported = ConfigCodec.toMap(Files.readString(written));
            assertEquals("copy_one", imported.get("name"));
            assertEquals("copy_one", imported.get("id"));
            assertEquals(Boolean.FALSE, imported.get("active"), "an import is ALWAYS an inactive draft");
            Map<?, ?> dirs = (Map<?, ?>) imported.get("dirs");
            assertTrue(String.valueOf(dirs.get("poll")).endsWith("data/inbox/copy_one"),
                    "dirs are re-derived from the space convention, not inherited: " + dirs.get("poll"));
            assertTrue(String.valueOf(dirs.get("database")).endsWith("data/copy_one/database"));
            assertEquals(schemaBase, ((Map<?, ?>) imported.get("processing")).get("schema_file"),
                    "the schema ref was rewritten to the portable bare basename beside the file");

            // satellite: byte-identical to the source's
            assertArrayEquals(schemaBytes, Files.readAllBytes(wr.resolve("copy_one").resolve(schemaBase)),
                    "the imported satellite byte-compares with the exported one");

            // companion: retargeted INSIDE the body
            Path enrich = wr.resolve("copy_one").resolve("copy_one_test_daily_enrich.toon");
            assertTrue(Files.exists(enrich), "the retargeted companion was written");
            Map<String, Object> e = ConfigCodec.toMap(Files.readString(enrich));
            assertEquals("copy_one_TEST_DAILY", e.get("name"));
            assertEquals("copy_one", ((Map<?, ?>) e.get("triggers")).get("on_pipeline"));
            assertEquals(String.valueOf(dirs.get("database")),
                    ((Map<?, ?>) e.get("input")).get("database"),
                    "the companion reads the IMPORTED pipeline's database, not the source's");

            // the import is registered and visible
            assertEquals(200, send(c.port, "GET", "/pipelines/copy_one/graph/raw", (String) null).statusCode());
        }
    }

    // ── conflict matrix: refuse / overwrite / rename ─────────────────────────────

    @Test
    void conflictPolicyMatrix(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Path toon = fixture(dir);
        try (Ctx c = open(dir, wr, toon)) {
            byte[] zip = sendZip(c.port, "GET", "/pipelines/test_etl/bundle", null).body();
            assertEquals(200, send(c.port, "POST", "/pipelines/import?name=twin", zip).statusCode());

            // default = refuse → 409, and the existing file is untouched
            byte[] before = Files.readAllBytes(wr.resolve("twin").resolve("twin_pipeline.toon"));
            assertEquals(409, send(c.port, "POST", "/pipelines/import?name=twin", zip).statusCode());
            assertArrayEquals(before, Files.readAllBytes(wr.resolve("twin").resolve("twin_pipeline.toon")));

            // overwrite → lands on the registered file, still one pipeline
            HttpResponse<String> over = send(c.port, "POST", "/pipelines/import?name=twin&conflict=overwrite", zip);
            assertEquals(200, over.statusCode(), over.body());
            assertEquals("twin", V1Body.of(over.body()).get("pipeline").asText());

            // rename → a fresh id beside the original
            HttpResponse<String> ren = send(c.port, "POST", "/pipelines/import?name=twin&conflict=rename", zip);
            assertEquals(200, ren.statusCode(), ren.body());
            JsonNode body = V1Body.of(ren.body());
            assertEquals("twin_2", body.get("pipeline").asText());
            assertEquals("twin", body.get("renamedFrom").asText());
            assertTrue(Files.exists(wr.resolve("twin_2").resolve("twin_2_pipeline.toon")));
        }
    }

    // ── zip-slip refusal ──────────────────────────────────────────────────────────

    @Test
    void aManifestEntryEscapingTheDestinationIs403(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Path toon = fixture(dir);
        try (Ctx c = open(dir, wr, toon)) {
            byte[] good = sendZip(c.port, "GET", "/pipelines/test_etl/bundle", null).body();
            Map<String, byte[]> entries = unzip(good);
            Map<String, Object> manifest = ConfigCodec.toMap(
                    new String(entries.get("manifest.toon"), StandardCharsets.UTF_8));
            manifest.put("satellites", List.of(new LinkedHashMap<>(Map.of("path", "../evil.toon"))));
            entries.put("manifest.toon", ConfigCodec.toToon(manifest).getBytes(StandardCharsets.UTF_8));
            entries.put("../evil.toon", "name: evil".getBytes(StandardCharsets.UTF_8));

            assertEquals(403, send(c.port, "POST", "/pipelines/import?name=slip", rezip(entries)).statusCode());
            assertFalse(Files.exists(wr.resolve("evil.toon")), "nothing escaped the destination");
            assertFalse(Files.exists(wr.resolve("slip")), "nothing was written at all");
        }
    }

    // ── a bound connection travels as a requirement, never as secret material ────

    @Test
    void aConnectionTravelsAsARequirementWithNoSecretMaterial(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Path toon = fixture(dir);
        // bind a connection by id — the profile itself must NOT travel
        Map<String, Object> raw = ConfigCodec.toMap(Files.readString(toon));
        Map<String, Object> collector = new LinkedHashMap<>();
        collector.put("connection", "prod_sftp");
        raw.put("collector", collector);
        Files.writeString(toon, ConfigCodec.toToon(raw));

        try (Ctx c = open(dir, wr, toon)) {
            HttpResponse<byte[]> zip = sendZip(c.port, "GET", "/pipelines/test_etl/bundle", null);
            assertEquals(200, zip.statusCode());
            Map<String, byte[]> entries = unzip(zip.body());
            Map<String, Object> manifest = ConfigCodec.toMap(
                    new String(entries.get("manifest.toon"), StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reqs = (List<Map<String, Object>>) manifest.get("requirements");
            assertEquals(1, reqs.size());
            assertEquals("connection", reqs.get(0).get("kind"));
            assertEquals("prod_sftp", reqs.get(0).get("profile"));
            assertTrue(entries.keySet().stream().noneMatch(k -> k.endsWith("_connection.toon")),
                    "no connection profile travels in the zip");

            // the import reports the requirement back to the caller
            HttpResponse<String> imp = send(c.port, "POST", "/pipelines/import?name=needy", zip.body());
            assertEquals(200, imp.statusCode(), imp.body());
            assertEquals("prod_sftp", V1Body.of(imp.body()).get("requirements").get(0).get("profile").asText());
        }
    }

    // ── the remaining gates ───────────────────────────────────────────────────────

    @Test
    void importWithoutAWriteRootIs503(@TempDir Path dir) throws Exception {
        Path toon = fixture(dir);
        try (Ctx c = open(dir, null, toon)) {
            assertEquals(503, send(c.port, "POST", "/pipelines/import", zipOf(Map.of("x", new byte[0])))
                    .statusCode());
        }
    }

    @Test
    void exportOfAnUnknownPipelineIs404(@TempDir Path dir) throws Exception {
        Path toon = fixture(dir);
        try (Ctx c = open(dir, dir.resolve("wr"), toon)) {
            assertEquals(404, sendZip(c.port, "GET", "/pipelines/ghost/bundle", null).statusCode());
        }
    }

    @Test
    void aZipThatIsNotAPipelineBundleIs422(@TempDir Path dir) throws Exception {
        Path toon = fixture(dir);
        try (Ctx c = open(dir, dir.resolve("wr"), toon)) {
            // no manifest at all
            assertEquals(422, send(c.port, "POST", "/pipelines/import",
                    zipOf(Map.of("stray.toon", "name: x".getBytes(StandardCharsets.UTF_8)))).statusCode());
            // wrong format id
            byte[] wrong = zipOf(Map.of("manifest.toon",
                    "format: inspecto-metadata-bundle\nversion: 1\n".getBytes(StandardCharsets.UTF_8)));
            assertEquals(422, send(c.port, "POST", "/pipelines/import", wrong).statusCode());
            // a satellite whose bytes were tampered with after export → sha256 mismatch
            byte[] good = sendZip(c.port, "GET", "/pipelines/test_etl/bundle", null).body();
            Map<String, byte[]> entries = unzip(good);
            String satellite = entries.keySet().stream()
                    .filter(k -> !k.equals("manifest.toon") && !k.endsWith("_pipeline.toon")).findFirst().orElseThrow();
            entries.put(satellite, "tampered".getBytes(StandardCharsets.UTF_8));
            assertEquals(422, send(c.port, "POST", "/pipelines/import?name=tampered", rezip(entries)).statusCode());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** POST a zip body (raw application/zip — the transport the route documents). */
    private HttpResponse<String> send(int port, String method, String path, byte[] zip) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/zip")
                .method(method, BodyPublishers.ofByteArray(zip)).build();
        return client.send(r, BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> sendZip(int port, String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/zip").method(method, BodyPublishers.ofByteArray(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofByteArray());
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry())
                if (!e.isDirectory()) out.put(e.getName(), zis.readAllBytes());
        }
        return out;
    }

    private static byte[] rezip(Map<String, byte[]> entries) throws Exception {
        return zipOf(entries);
    }

    private static byte[] zipOf(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}

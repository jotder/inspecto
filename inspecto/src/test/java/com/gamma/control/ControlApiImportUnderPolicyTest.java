package com.gamma.control;

import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maker-checker at the import door (`ASSURE-MAKER-CHECKER-1`, over the upstream import judge): under an approval
 * policy an import cannot be ONE Pending Change, so {@code POST /import} refuses (409) any bundle carrying a
 * governed kind — or a file whose kind cannot be told — BEFORE the first write, so the tree is untouched. With no
 * policy the same bundle imports as before.
 */
class ControlApiImportUnderPolicyTest {

    private static final String BUILDER = "Bearer builder";
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
        Authenticators.forTest(ex -> BUILDER.equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("builder-1", Set.of("canAuthorWorkbench"))) : Optional.empty());
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path root) throws Exception {
        Path base = root.resolve("alpha");
        Path config = Files.createDirectories(base.resolve("config"));
        Path tmp = TestConfigs.csv(base.resolve("data").resolve("etl"), PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Files.writeString(base.resolve("space.toon"), "display_name: \"Alpha\"\ndescription: \"x\"\ncreated_at: \"2026-06-23\"\n");
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config);
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            Map<String, String> all = new LinkedHashMap<>();
            all.put("bundle.toon", "kind: datasource\n");
            all.putAll(entries);
            for (Map.Entry<String, String> e : all.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private HttpResponse<String> importZip(Ctx c, byte[] zip) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/alpha/import"
                        + "?on_conflict=overwrite")).header("Authorization", BUILDER)
                .method("POST", HttpRequest.BodyPublishers.ofByteArray(zip)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Long> tree(Path config) throws Exception {
        Map<String, Long> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(config)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) out.put(config.relativize(p).toString(), Files.size(p));
        }
        return out;
    }

    @Test
    void underAPolicyAnImportCarryingAGovernedKindIsRefusedBeforeAnyWrite(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Files.writeString(c.config.resolve(ApprovalPolicy.FILE), "approval:\n  pipeline:\n    required: true\n");
            Map<String, Long> before = tree(c.config);
            String pipeline = Files.readString(c.config.resolve("etl_pipeline.toon")).replaceAll("(?i)test_etl", "other_etl");
            HttpResponse<String> r = importZip(c, zip(Map.of("other/other_pipeline.toon", pipeline)));
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("other/other_pipeline.toon (pipeline)"), r.body());
            assertEquals(before, tree(c.config), "refused before the first write: the tree is untouched");
        }
    }

    @Test
    void withNoPolicyTheSameImportLands(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String pipeline = Files.readString(c.config.resolve("etl_pipeline.toon")).replaceAll("(?i)test_etl", "other_etl");
            HttpResponse<String> r = importZip(c, zip(Map.of("other/other_pipeline.toon", pipeline)));
            assertTrue(r.statusCode() < 300, r.statusCode() + " " + r.body());
            assertTrue(Files.exists(c.config.resolve("other/other_pipeline.toon")), "the probe would otherwise succeed");
        }
    }

    @Test
    void underAPolicyAFileWhoseKindCannotBeToldIsRefusedToo(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Files.writeString(c.config.resolve(ApprovalPolicy.FILE), "approval:\n  pattern-pack:\n    required: true\n");
            // a shape the upstream import judge accepts (a View) but maker-checker cannot tell a kind for
            HttpResponse<String> r = importZip(c, zip(Map.of("views/orders_view.toon", "x: 1\n")));
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("unclassified"), r.body());
        }
    }
}

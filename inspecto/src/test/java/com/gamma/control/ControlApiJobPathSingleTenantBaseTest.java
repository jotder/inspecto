package com.gamma.control;

import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.metrics.MetricRegistry;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceManager;
import com.gamma.etl.TestConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code JOB-PATH-SINGLE-TENANT-GATE-BASE-1} — <b>the save gate judges a pipeline job's paths from the
 * base the pipeline runner reads them from.</b>
 *
 * <p>In the single-tenant layout the write root ({@code -Dassist.write.root}, e.g. {@code <example>/out/write})
 * and the launch directory are different directories. {@code PipelineJobRunner} resolves {@code pipeline_config}
 * / {@code data_dir} against the config READ root (the launch dir, registered by {@code SpaceManager.single()}),
 * while the three job save gates ({@code POST|PUT /jobs}, bundle import, {@code /config/write}) resolved every
 * job key against the write root. So {@code pipeline_config: orders_pipeline.toon} — the committed shape in
 * {@code inspecto/examples/07-steps/*}, which RUNS — was refused at save ({@code PathJail.resolveJobPath}'s
 * "nothing exists there, while ... does" refusal), and re-saving such a job returned 422.
 *
 * <p>The fixture reproduces the layout for real: the pipeline file lives under the process working directory
 * (the launch dir this JVM was started in), the write root is a temp dir elsewhere, and the value is authored
 * relative — exactly the example's shape.
 */
class ControlApiJobPathSingleTenantBaseTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** A throwaway dir under the launch dir ({@code target/} of this module), relative to the CWD. */
    private Path launchRelDir;
    private String priorWriteRoot;
    private String priorRoots;

    @BeforeEach
    void setUp() throws Exception {
        DiscoveredRoots.clear();
        SpaceConfigRoot.clear();
        priorWriteRoot = System.getProperty("assist.write.root");
        priorRoots = System.getProperty("assist.safety.roots");
        launchRelDir = Path.of("target", "job-path-base-" + UUID.randomUUID());
        Files.createDirectories(launchRelDir);
        Files.writeString(launchRelDir.resolve("orders_pipeline.toon"), "pipeline:\n  name: orders\n");
        Files.createDirectories(launchRelDir.resolve("out"));
    }

    @AfterEach
    void tearDown() throws Exception {
        DiscoveredRoots.clear();
        SpaceConfigRoot.clear();
        restore("assist.write.root", priorWriteRoot);
        restore("assist.safety.roots", priorRoots);
        try (Stream<Path> s = Files.walk(launchRelDir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private record Single(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); MetricRegistry.global().reset(); }
    }

    /** The single-tenant boot: one service, {@code -Dassist.write.root} set for the life of the server. */
    private Single single(Path dir, Path writeRoot) throws Exception {
        Files.createDirectories(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Single(svc, api, api.port());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        b.header("Content-Type", "application/json");
        return client.send(b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private String rel(String leaf) {
        return launchRelDir.resolve(leaf).toString().replace("\\", "/");
    }

    private String pipelineJob(String name) {
        return """
                {"name":"%s","type":"pipeline","pipeline_config":"%s","data_dir":"%s"}"""
                .formatted(name, rel("orders_pipeline.toon"), rel("out"));
    }

    /** The premise, asserted: the value exists from the launch dir and NOT from the write root. */
    private void assertLayout(Path writeRoot) {
        assertTrue(Files.isRegularFile(Path.of("").toAbsolutePath().resolve(rel("orders_pipeline.toon"))),
                "the pipeline file sits under the launch dir");
        assertFalse(Files.exists(writeRoot.resolve(rel("orders_pipeline.toon"))),
                "and not under the write root — the two bases must be separated by the fixture");
        assertEquals(Path.of("").toAbsolutePath(), SpaceConfigRoot.currentConfigReadRoot(),
                "SpaceManager.single() published the launch dir as the runner's read root");
    }

    @Test
    void aPipelineJobThatRunsIsAcceptedByPostAndPutJobs(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("out").resolve("write");
        try (Single c = single(dir, writeRoot)) {
            assertLayout(writeRoot);

            HttpResponse<String> post = send(c.port, "POST", "/jobs", pipelineJob("filter_rollup"));
            assertEquals(200, post.statusCode(),
                    "pipeline_config/data_dir are judged from the runner's base (the launch dir): " + post.body());

            HttpResponse<String> put = send(c.port, "PUT", "/jobs/filter_rollup", pipelineJob("filter_rollup"));
            assertEquals(200, put.statusCode(), "re-saving the same job must not 422: " + put.body());
        }
    }

    @Test
    void bundleImportAndConfigWriteAcceptTheSameJob(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("out").resolve("write");
        try (Single c = single(dir, writeRoot)) {
            assertLayout(writeRoot);

            String bundle = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"exportedAt\":\"2026-09-23T00:00:00Z\","
                    + "\"sourceSpace\":null,\"items\":[{\"kind\":\"job\",\"id\":\"imported\",\"content\":"
                    + pipelineJob("imported") + "}]}";
            HttpResponse<String> imp = send(c.port, "POST", "/bundle/import", bundle);
            assertEquals(200, imp.statusCode(), imp.body());
            assertTrue(imp.body().contains("\"imported\":1"), "bundle import accepts it: " + imp.body());

            HttpResponse<String> write = send(c.port, "POST", "/config/write",
                    "{\"type\":\"job\",\"config\":{\"job\":" + pipelineJob("written") + "}}");
            assertEquals(200, write.statusCode(), "/config/write accepts it: " + write.body());
        }
    }

    /**
     * The split is PER KEY: a maintenance task's {@code dir} is read at run time against the write root
     * ({@code CleanupTask} → {@code SpaceConfigRoot.current()}), so the same launch-dir-relative value is
     * still refused there. A fix that simply moved every job key to the launch dir would turn this green.
     */
    @Test
    void aMaintenanceKeyIsStillJudgedFromTheWriteRoot(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("out").resolve("write");
        try (Single c = single(dir, writeRoot)) {
            assertLayout(writeRoot);
            HttpResponse<String> r = send(c.port, "POST", "/jobs", """
                    {"name":"sweep","type":"maintenance","task":"cleanup","retention_days":"30","dir":"%s"}"""
                    .formatted(rel("out")));
            assertEquals(422, r.statusCode(), "a maintenance dir still resolves from the write root: " + r.body());
            assertTrue(r.body().contains("job.dir"), r.body());
        }
    }

    /** Multi-Space mode is unchanged: a named Space judges {@code pipeline_config} from its own config root. */
    @Test
    void aNamedSpaceJudgesPipelineConfigFromItsConfigRoot(@TempDir Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            System.setProperty("assist.safety.roots", root.resolve("acme").toString());

            String probe = "../../outside/p_pipeline.toon";
            HttpResponse<String> r = send(port, "POST", "/spaces/acme/jobs", """
                    {"name":"pj","type":"pipeline","pipeline_config":"%s"}""".formatted(probe));
            String fromSpaceRoot = root.resolve("acme").resolve("config").resolve(probe)
                    .toAbsolutePath().normalize().toString().replace("\\", "\\\\");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains(fromSpaceRoot),
                    "resolved against <space>/config, not the launch dir: " + r.body());
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }
}

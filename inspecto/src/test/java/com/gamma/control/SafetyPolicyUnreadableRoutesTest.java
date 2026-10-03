package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.CollectorService;
import com.gamma.service.PipelineRun;
import com.gamma.service.SpaceManager;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code DUCKLE-C6-POLICY-NARROWING-1} slice S2b ({@code policy-narrowing-design.md} §5): an unreadable Safety
 * Policy file is a 422 finding at every plan-time gate, a failed Run for every run in scope, and a degraded
 * {@code /health} - and never a 500, never "no policy". T5 / T6 at run level, the route gate at HTTP level.
 */
class SafetyPolicyUnreadableRoutesTest {

    private static final String CODE = "ERR_SAFETY_POLICY_UNREADABLE";

    @TempDir Path dir;
    private final HttpClient client = HttpClient.newHttpClient();
    private String inheritedRoots;

    @BeforeEach
    void isolate() {
        inheritedRoots = System.getProperty("assist.safety.roots");
        DiscoveredRoots.clear();
    }

    @AfterEach
    void restore() {
        if (inheritedRoots == null) System.clearProperty("assist.safety.roots");
        else System.setProperty("assist.safety.roots", inheritedRoots);
        System.clearProperty("system.config.dir");
        DiscoveredRoots.clear();
    }

    private static void write(Path dir, String text) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("safety-policy.toon"), text);
    }

    /** The default Space's base, registered so its {@code config/safety-policy.toon} is in scope. */
    private Path defaultBase() throws Exception {
        Path base = Files.createDirectories(dir.resolve("base").resolve("config")).getParent();
        DiscoveredRoots.register("default", base);
        return base;
    }

    private static Path pipeline(Path root) throws Exception {
        Files.createDirectories(root.resolve("inbox"));
        Path schema = root.resolve("schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        Files.writeString(root.resolve("inbox").resolve("data.csv"), "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n");
        String r = root.toString().replace('\\', '/');
        String toon = """
                name: safety_probe
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                  quarantine: %1$s/quarantine
                  markers: %1$s/markers
                  status_dir: %1$s/status
                  log_dir: %1$s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                  schema_file: "%2$s"
                  csv_settings:
                    delimiter: ","
                    has_header: true
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(r, schema.toString().replace('\\', '/'));
        Path p = root.resolve("safety_probe_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    private interface Arrange { void apply() throws Exception; }

    /** Boot a service over a valid policy, THEN {@code arrange} the policy files, then trigger one run. A pipeline
     *  loaded after the file breaks never registers at all (ConfigRegistry drops what will not load), so the
     *  refusal under test is the RUN's, not the registry's. */
    private PipelineRun runOnce(Path work, Arrange arrange) throws Exception {
        Path stale = dir.resolve("base").resolve("config").resolve("safety-policy.toon");
        if (Files.isDirectory(stale)) Files.delete(stale);
        else Files.deleteIfExists(stale);
        System.clearProperty("system.config.dir");
        Path toon = pipeline(work);
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1)) {
            arrange.apply();
            String id = svc.triggerRunAsync("safety_probe").orElseThrow();
            for (int i = 0; i < 400; i++) {
                PipelineRun r = svc.pipelineRunById(id).orElseThrow();
                if (!"RUNNING".equals(r.status())) return r;
                Thread.sleep(50);
            }
            return fail("run did not settle");
        }
    }

    private void assertRefused(PipelineRun r) {
        assertEquals("FAILED", r.status(), String.valueOf(r));
        assertTrue(r.message().contains(CODE), "the reason names the stable code: " + r.message());
        assertEquals(0, r.total(), "a refused run touches nothing");
    }

    // ── T5 ──────────────────────────────────────────────────────────────────────────

    @Test
    void t5_modeInASpaceFileRefusesTheSpacesRuns_butIsAcceptedInTheServerFile() throws Exception {
        Path base = defaultBase();
        assertRefused(runOnce(dir.resolve("a"), () -> write(base.resolve("config"), "mode: audit\n")));

        Path server = dir.resolve("server");
        assertEquals("SUCCESS", runOnce(dir.resolve("b"), () -> {
            write(server, "mode: audit\n");
            System.setProperty("system.config.dir", server.toString());
        }).status(), "the same word in the server file is legal");
    }

    // ── T6 ──────────────────────────────────────────────────────────────────────────

    @Test
    void t6_aDamagedUnknownKeyOrDirectoryFileRefusesTheRun_andTheSameFileValidRuns() throws Exception {
        Path base = defaultBase();
        Path cfg = base.resolve("config");
        Path file = cfg.resolve("safety-policy.toon");
        int n = 0;
        for (String bad : List.of("permit:\n  network: maybe\n", "alow:\n  hosts[1]: a.example\n")) {
            assertRefused(runOnce(dir.resolve("r" + n++), () -> write(cfg, bad)));
        }
        assertRefused(runOnce(dir.resolve("r" + n++), () -> Files.createDirectories(file)));   // a directory named like the file forces the IO branch
        assertEquals("SUCCESS", runOnce(dir.resolve("ok"), () -> write(cfg, "allow:\n  hosts[1]: a.example\n")).status());
    }

    // ── S3: an unreadable file at LOAD time must not make the Pipeline vanish ─────────

    @Test
    void s3_aPipelineLoadedOverAnUnreadablePolicyStillRegistersAndItsRunIsTheVisibleRefusal() throws Exception {
        Path base = defaultBase();
        Path cfg = base.resolve("config");
        write(cfg, "alow:\n  hosts[1]: a.example\n");                   // broken BEFORE the service loads the pipeline
        Path toon = pipeline(dir.resolve("boot"));
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1)) {
            assertTrue(svc.pipelineLoadFailures().isEmpty(), "loading is not running: no load failure, no vanished pipeline: "
                    + svc.pipelineLoadFailures());
            assertTrue(svc.configFor("safety_probe").isPresent(), "the pipeline is registered");
            String id = svc.triggerRunAsync("safety_probe").orElseThrow();
            PipelineRun r;
            int i = 0;
            while ("RUNNING".equals((r = svc.pipelineRunById(id).orElseThrow()).status()) && i++ < 400) Thread.sleep(50);
            assertRefused(r);
        }
        // twin: the same pipeline over a valid file registers AND runs
        write(cfg, "allow:\n  hosts[1]: a.example\n");
        try (CollectorService svc = new CollectorService(List.of(pipeline(dir.resolve("boot2"))), 3600, 1)) {
            assertTrue(svc.pipelineLoadFailures().isEmpty());
            String id = svc.triggerRunAsync("safety_probe").orElseThrow();
            PipelineRun r;
            int i = 0;
            while ("RUNNING".equals((r = svc.pipelineRunById(id).orElseThrow()).status()) && i++ < 400) Thread.sleep(50);
            assertEquals("SUCCESS", r.status());
        }
    }

    // ── the plan-time gates, over HTTP ──────────────────────────────────────────────

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        b.header("Content-Type", "application/json");
        return client.send(b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    @Test
    void theSaveGateAnswers422WithAFindingAndHealthReportsDegraded() throws Exception {
        SpaceManager spaces = SpaceManager.discover(dir.resolve("root"));
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String job = "{\"name\":\"j1\",\"type\":\"maintenance\",\"task\":\"cleanup\",\"retention_days\":\"30\"}";
            assertEquals(200, send(port, "POST", "/spaces/acme/jobs", job).statusCode(), "positive control");
            assertTrue(send(port, "GET", "/health", null).body().contains("\"UP\""), "healthy before the file breaks");

            write(dir.resolve("root").resolve("acme").resolve("config"), "mode: audit\n");   // illegal in a Space file
            HttpResponse<String> refused = send(port, "POST", "/spaces/acme/jobs", job.replace("j1", "j2"));
            assertEquals(422, refused.statusCode(), refused.body());
            JsonNode body = V1Body.envelope(refused.body());
            assertTrue(refused.body().contains(CODE), refused.body());
            assertTrue(refused.body().contains("safety-policy.toon"), "the finding names the file: " + refused.body());
            assertNotNull(body);

            HttpResponse<String> health = send(port, "GET", "/health", null);
            assertEquals(200, health.statusCode(), "the control plane keeps serving");
            assertTrue(health.body().contains("DEGRADED"), health.body());
            assertTrue(health.body().contains("safety-policy.toon"), health.body());
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }
}

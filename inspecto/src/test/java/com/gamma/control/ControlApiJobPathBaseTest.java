package com.gamma.control;

import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.metrics.MetricRegistry;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code JOB-PATH-PATCH-ROUTE-WRONG-BASE-1} — <b>one job path value, two write gates, one answer.</b>
 *
 * <p>A job's relative path resolves against the <b>Space config root</b> and nothing else (operator
 * 2026-09-16, {@code JOB-DIR-CWD-CONTAINMENT-1}; {@link com.gamma.config.safety.PathJail#resolveJobPath}
 * is the single rule). Two control-plane routes can author a job config and both run the 422 gate:
 * {@code POST|PUT /jobs} ({@code JobRoutes}) and {@code POST /config/patch} ({@code ConfigWriteRoutes}).
 * The patch route handed the gate {@code target.getParent()} — the config file's own directory, which
 * is what a <em>pipeline's</em> {@code schema_file} ref needs — so a job patched with an explicit
 * {@code subdir:"jobs"} was judged from {@code <space>/config/jobs} while {@code POST /jobs} judged the
 * same string from {@code <space>/config}. One value, two answers, one directory level apart.
 *
 * <p>⚠ The divergence needs the {@code subdir}: with no subdir the patch route resolves a job to
 * {@code <space>/config/<name>.toon} and {@code getParent()} happens to equal the Space config root, so
 * the two gates agreed by coincidence. {@code subdir} is a documented body field of the route, so the
 * disagreeing shape is reachable — and it is the only shape that can address a job written by
 * {@code POST /jobs}, which always lands in {@code jobs/}.
 */
class ControlApiJobPathBaseTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code DiscoveredRoots} is process-global static: a root another test class left behind widens the
     *  allowed-roots union and makes a containment probe pass for the wrong reason. Clear it both ways. */
    @BeforeEach
    @AfterEach
    void isolateAllowedRoots() {
        DiscoveredRoots.clear();
    }

    /** Restored after the test — {@code assist.safety.roots} is a JVM-wide property the whole fork shares. */
    private String inheritedRoots;

    @BeforeEach
    void rememberRoots() {
        inheritedRoots = System.getProperty("assist.safety.roots");
    }

    @AfterEach
    void restoreRoots() {
        if (inheritedRoots == null) System.clearProperty("assist.safety.roots");
        else System.setProperty("assist.safety.roots", inheritedRoots);
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        b.header("Content-Type", "application/json");
        return client.send(b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    /**
     * The same authored value through both gates must produce the same verdict — and, when it is
     * refused, a message naming the same resolved path.
     *
     * <p>The probe climbs two levels, against an allowed-root list narrowed to the space's own base.
     * From the Space config root {@code ../../x} lands <em>above</em> the space and is refused; from
     * {@code config/jobs} — one level deeper — the same string lands back <em>inside</em> it and is
     * accepted. One segment of difference between the two bases, and the verdict flips.
     *
     * <p>&#9888; Narrowing the roots is load-bearing, not decoration. The reactor's surefire grants the
     * execution root and {@code java.io.tmpdir} as a test sandbox, and every {@code @TempDir} lives under
     * {@code java.io.tmpdir} — so with the inherited list <b>both</b> bases resolve to a contained path
     * and the probe passes for the wrong reason, which is exactly what it did on the first run. The
     * parent POM's own note points at this: a test that asserts containment is <em>enforced</em> sets its
     * own narrower roots.
     */
    @Test
    void jobPathIsJudgedFromTheSpaceConfigRootThroughBothWriteGates(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";

            // A job written the ordinary way: POST /jobs lands it at <space>/config/jobs/sweep_job.toon.
            assertEquals(200, send(c.port, "POST", base + "/jobs", """
                    {"name":"sweep","type":"maintenance","task":"cleanup","retention_days":"30"}""").statusCode());
            Path onDisk = root.resolve("acme").resolve("config").resolve("jobs").resolve("sweep_job.toon");
            assertTrue(Files.isRegularFile(onDisk), "POST /jobs writes into config/jobs: " + onDisk);

            // Narrow the allowed roots to this space's base, so containment can actually refuse.
            System.setProperty("assist.safety.roots", root.resolve("acme").toString());

            // One value, authored once, sent through each gate.
            String probe = "../../outside/backups";

            HttpResponse<String> viaJobs = send(c.port, "PUT", base + "/jobs/sweep", """
                    {"name":"sweep","type":"maintenance","task":"cleanup","retention_days":"30",
                     "backup_dir":"%s"}""".formatted(probe));

            HttpResponse<String> viaPatch = send(c.port, "POST", base + "/config/patch", """
                    {"type":"job","name":"sweep_job","subdir":"jobs",
                     "patch":{"job":{"backup_dir":"%s"}}}""".formatted(probe));

            // What the value MEANS under the one rule: resolved against <space>/config.
            String fromSpaceRoot = root.resolve("acme").resolve("config").resolve(probe)
                    .toAbsolutePath().normalize().toString().replace("\\", "\\\\");
            // What it meant under the patch route's old base, <space>/config/jobs — one level deeper.
            String fromJobsDir = root.resolve("acme").resolve("config").resolve("jobs").resolve(probe)
                    .toAbsolutePath().normalize().toString().replace("\\", "\\\\");
            assertNotEquals(fromSpaceRoot, fromJobsDir, "the probe must separate the two bases");

            assertEquals(422, viaJobs.statusCode(), "the reference gate refuses it: " + viaJobs.body());
            assertTrue(viaJobs.body().contains(fromSpaceRoot),
                    "POST/PUT /jobs resolves from the Space config root: " + viaJobs.body());

            assertEquals(422, viaPatch.statusCode(),
                    "the same value must be refused by /config/patch for the same reason — it was accepted, "
                            + "so that gate is still resolving from " + fromJobsDir + ": " + viaPatch.body());
            assertTrue(viaPatch.body().contains(fromSpaceRoot),
                    "/config/patch must resolve from the Space config root (" + fromSpaceRoot
                            + "), not from config/jobs (" + fromJobsDir + "): " + viaPatch.body());
        }
    }

    /**
     * The third base: {@code POST /config/write} runs its gate <em>before</em> the target path is
     * derived and so passed no base at all, leaving a job judged against the process working directory —
     * the rule {@code JOB-DIR-CWD-CONTAINMENT-1} retired. It accepts {@code type:"job"}
     * ({@code ConfigWriteRoutes.identityFields} maps it to {@code job.name}), so the route is armed even
     * though no client sends a job through it today.
     */
    @Test
    void configWriteJudgesAJobFromTheSpaceConfigRootToo(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            System.setProperty("assist.safety.roots", root.resolve("acme").toString());

            String probe = "../../outside/backups";
            HttpResponse<String> r = send(c.port, "POST", "/spaces/acme/config/write", """
                    {"type":"job","config":{"job":{"name":"wsweep","type":"maintenance","task":"cleanup",
                     "retention_days":"30","backup_dir":"%s"}}}""".formatted(probe));

            String fromSpaceRoot = root.resolve("acme").resolve("config").resolve(probe)
                    .toAbsolutePath().normalize().toString().replace("\\", "\\\\");
            assertEquals(422, r.statusCode(),
                    "a job's relative path is judged from the Space config root on /config/write too: " + r.body());
            assertTrue(r.body().contains(fromSpaceRoot),
                    "the finding names the Space-root resolution, not a CWD-relative one: " + r.body());
        }
    }
}

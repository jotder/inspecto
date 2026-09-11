package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.job.JobConfig;
import com.gamma.job.JobType;
import com.gamma.service.CollectorService;
import com.gamma.util.StoreHealth;
import com.gamma.util.Topology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real-HTTP tests for {@code GET /health/details} (System Maintenance MNT-15). */
class ControlApiHealthDetailsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a real service+API; {@code writeRoot} is passed verbatim so a test can point it at garbage. */
    private Ctx open(Path cfg, String writeRoot, List<JobConfig> jobs) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot);
        try {
            CollectorService svc = new CollectorService(List.of(pipe), List.of(), jobs, 3600L, 1, null);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private JsonNode details(int port) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1" + "/health/details")).GET().build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    @Test
    void reportsPerSubsystemStatusWithJobsRegistered(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JobConfig hb = new JobConfig("hd-hb", JobType.MAINTENANCE, "0 3 * * *", null, true, false, Map.of("task", "heartbeat"));
        try (Ctx c = open(cfg, root.toString(), List.of(hb))) {
            JsonNode body = details(c.port);
            assertEquals("UP", body.get("status").asText(), body.toString());
            JsonNode subs = body.get("subsystems");
            assertEquals("UP", subs.get("configStore").get("status").asText(), subs.toString());
            assertEquals("UP", subs.get("scheduler").get("status").asText(), subs.toString());
            assertTrue(subs.get("scheduler").get("detail").asText().contains("1 job(s), 1 cron-scheduled"), subs.toString());
            assertEquals("NOT_CONFIGURED", subs.get("jobRunsProjection").get("status").asText(),
                    "-Djobs.backend unset in this harness: " + subs);
            assertEquals("UP", subs.get("pipelines").get("status").asText(), subs.toString());
        }
    }

    @Test
    void unconfiguredSubsystemsAreNotFailures(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root.toString(), List.of())) {   // no jobs at all
            JsonNode body = details(c.port);
            assertEquals("UP", body.get("status").asText(), "absence of optional subsystems is not DOWN: " + body);
            assertEquals("NOT_CONFIGURED", body.get("subsystems").get("scheduler").get("status").asText());
        }
    }

    @Test
    void brokenWriteRootFlagsConfigStoreDown(@TempDir Path cfg, @TempDir Path junk) throws Exception {
        Path file = Files.writeString(junk.resolve("not-a-dir"), "x");   // a FILE as write root
        try (Ctx c = open(cfg, file.toString(), List.of())) {
            JsonNode body = details(c.port);
            assertEquals("DOWN", body.get("status").asText(), body.toString());
            assertEquals("DOWN", body.get("subsystems").get("configStore").get("status").asText(), body.toString());
        }
    }

    // ---- store families (scale-out phase A, VER-3) -------------------------------------------------
    // Before these, thirteen openers could catch an open failure, log WARN and hand back an in-memory or
    // null store with nothing above ever learning of it. VER-3 asserts the opposite — "every intended
    // subsystem UP, NOT silently NOT_CONFIGURED or an in-memory fallback" — so a store the operator asked
    // for and did not get has to reach this route.

    /** {@link StoreHealth} is process-static, so a leftover entry would leak between tests in this fork. */
    @BeforeEach
    void resetStoreHealth() {
        StoreHealth.clearAll();
    }

    /**
     * ⛔ Restores the PRIOR value rather than clearing: the surefire fork pins {@code -Dstatus.backend}
     * (TEST-CWD-DB-1), and a blanket {@code clearProperty} has already un-pinned it for a whole fork once.
     */
    private static <T> T withProperty(String key, String value, ThrowingSupplier<T> body) throws Exception {
        String prior = System.getProperty(key);
        System.setProperty(key, value);
        try {
            return body.get();
        } finally {
            if (prior == null) System.clearProperty(key); else System.setProperty(key, prior);
        }
    }

    private interface ThrowingSupplier<T> { T get() throws Exception; }

    /**
     * ⛔ A job MUST be registered for the job-run family to be reported at all: {@code openJobRunStore} is an
     * argument to the {@code JobService} constructor, and {@code CollectorService} skips that constructor
     * entirely when {@code jobConfigs.isEmpty()}. With no jobs the opener never runs and the family is absent
     * — which is how the first version of these tests failed.
     */
    private static JobConfig aJob() {
        return new JobConfig("hd-store", JobType.MAINTENANCE, "0 3 * * *", null, true, false,
                Map.of("task", "heartbeat"));
    }

    @Test
    void aStoreThatWasAskedForAndCouldNotOpenIsDown(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // A DuckDB file underneath a REGULAR FILE. Creating anything under a non-directory fails on every
        // OS, so the open failure is deterministic — unlike a merely missing parent directory, which an
        // engine is free to create for you.
        Path blocker = Files.writeString(root.resolve("blocker"), "not-a-directory");
        String url = "jdbc:duckdb:" + blocker.resolve("jobs.duckdb");
        JsonNode body = withProperty("jobs.backend", url, () -> {
            try (Ctx c = open(cfg, root.toString(), List.of(aJob()))) {
                return details(c.port);
            }
        });
        JsonNode fam = body.get("subsystems").get("store.jobRuns");
        assertNotNull(fam, "the job-run family must be reported: " + body);
        assertEquals("DOWN", fam.get("status").asText(), body.toString());
        assertTrue(fam.get("detail").asText().contains("job reporting disabled"),
                "the detail must say what the operator lost: " + fam);
        assertEquals("DOWN", body.get("status").asText(),
                "a store the operator configured and did not get makes the service DOWN (VER-3): " + body);
    }

    @Test
    void aStoreThatOpensIsUp(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String url = "jdbc:duckdb:" + root.resolve("jobs.duckdb");
        JsonNode body = withProperty("jobs.backend", url, () -> {
            try (Ctx c = open(cfg, root.toString(), List.of(aJob()))) {
                return details(c.port);
            }
        });
        assertEquals("UP", body.get("subsystems").get("store.jobRuns").get("status").asText(), body.toString());
        assertEquals("UP", body.get("status").asText(), body.toString());
    }

    /**
     * The Personal case: nothing is configured, so nothing may report DOWN. This is the falsification arm —
     * without it the two tests above would pass equally if every family reported DOWN unconditionally.
     *
     * <p>Asserted on {@code events} because it is opened on EVERY boot ({@code CollectorService} calls
     * {@code openEventStore} unconditionally), so it is present whatever else the harness configures.
     */
    @Test
    void familiesNobodyAskedForAreNotFailures(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root.toString(), List.of())) {
            JsonNode body = details(c.port);
            JsonNode subs = body.get("subsystems");
            JsonNode events = subs.get("store.events");
            assertNotNull(events, "the event store opens on every boot, so it must be reported: " + subs);
            assertEquals("NOT_CONFIGURED", events.get("status").asText(),
                    "-Devents.backend defaults to memory: " + subs);
            assertEquals("UP", body.get("status").asText(),
                    "an unconfigured family is not a failure — Personal configures none of them: " + body);
        }
    }

    /**
     * 🔴 Phase A's invariant, end to end: with {@code -Dinspecto.topology=partitioned} a store that cannot
     * open its backend must FAIL THE BOOT, not degrade to memory.
     *
     * <p>⛔ This test exists because reading the call path is not evidence that the exception survives it.
     * {@code StoreHealth.record} throws from inside a {@code catch} block, and a single swallowing
     * {@code try} anywhere between there and the constructor would turn the guard into a no-op that still
     * looks present. Its falsification arm is {@link #aStoreThatWasAskedForAndCouldNotOpenIsDown}, which
     * boots the SAME broken configuration successfully with the flag unset.
     */
    @Test
    void aDegradedStoreFailsTheBootWhenPartitioned(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Path blocker = Files.writeString(root.resolve("blocker"), "not-a-directory");
        String url = "jdbc:duckdb:" + blocker.resolve("jobs.duckdb");
        IllegalStateException boom = withProperty(Topology.PROPERTY, "partitioned", () ->
                withProperty("jobs.backend", url, () ->
                        assertThrows(IllegalStateException.class, () -> {
                            try (Ctx c = open(cfg, root.toString(), List.of(aJob()))) {
                                fail("the service booted with a degraded store while partitioned: " + c.port);
                            }
                        })));
        assertTrue(boom.getMessage().contains("jobRuns"),
                "the refusal must name the store that degraded: " + boom.getMessage());
        assertTrue(boom.getMessage().contains("partitioned"),
                "and why it is fatal here but not on a single node: " + boom.getMessage());
    }

    /**
     * 🔴 A raw {@code jdbc:} backend value is a URL, not a keyword: it carries a path, a database name and
     * credentials, and Postgres treats all three case-sensitively. Six openers used to lowercase the value
     * itself before using it as the URL, so {@code jdbc:postgresql://db/MyDb?user=Alice} silently became
     * {@code mydb}/{@code alice}. Asserted through {@code /health/details}, which now reports the target the
     * store actually opened — revert the fix and the detail reads {@code mixedcasedir}.
     */
    @Test
    void aRawJdbcBackendKeepsItsCase(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve("MixedCaseDir"));
        String url = "jdbc:duckdb:" + dir.resolve("Jobs.duckdb");
        JsonNode body = withProperty("jobs.backend", url, () -> {
            try (Ctx c = open(cfg, root.toString(), List.of(aJob()))) {
                return details(c.port);
            }
        });
        JsonNode fam = body.get("subsystems").get("store.jobRuns");
        assertNotNull(fam, body.toString());
        assertEquals("UP", fam.get("status").asText(), body.toString());
        assertTrue(fam.get("detail").asText().contains("MixedCaseDir"),
                "the raw jdbc: value must reach the driver case-intact: " + fam);
    }

    /**
     * ⚠ Absence is not health. A family whose opener never ran must be MISSING rather than reported green —
     * reporting it {@code UP} would claim a measurement nobody took.
     */
    @Test
    void aFamilyWhoseOpenerNeverRanIsAbsentRatherThanGreen(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root.toString(), List.of())) {       // no jobs ⇒ openJobRunStore never called
            JsonNode subs = details(c.port).get("subsystems");
            assertNull(subs.get("store.jobRuns"),
                    "no job configured means the job-run store was never opened, so nothing was measured: " + subs);
        }
    }
}

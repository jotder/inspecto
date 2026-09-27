package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
import com.gamma.service.CollectorService;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MAINT-TASK-AUTHORITY-1: a Job carries a SERVER-STAMPED author, the runner refuses an administrator-only task whose
 * last editor no longer holds {@code canAdminister} (or that records no author), and a {@code cleanup} that sweeps or
 * archives into the Space config root is administrator-only. Real HTTP, armed Subjects with held roles (the run-time
 * re-check re-resolves those roles against the role table, as the request gate does).
 */
class ControlApiJobAuthorityTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path wr) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer builder" -> {
                ComponentAccess.heldRoles(ex, Set.of("developer"));
                yield Optional.of(new Subject("builder-1", Set.of("canAuthorWorkbench", "canOperateRuns")));
            }
            case "Bearer admin" -> {
                ComponentAccess.heldRoles(ex, Set.of("admin"));
                yield Optional.of(new Subject("admin-1", Set.of("canAuthorWorkbench", "canOperateRuns", "canAdminister")));
            }
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        System.clearProperty("jobs.audit.dir");
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path wr = Files.createDirectories(dir.resolve("wr"));
        System.setProperty("assist.write.root", wr.toString());
        // the flat SpaceRoot's run log is CWD-relative "jobs_audit" — keep it in the @TempDir
        System.setProperty("jobs.audit.dir", dir.resolve("jobs_audit").toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), wr);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        return client.send(b.method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static String json(Path p) {
        return p.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    private static String cleanup(String name, String dirJson, String extra) {
        return "{\"name\":\"" + name + "\",\"type\":\"maintenance\",\"task\":\"cleanup\",\"dir\":\"" + dirJson + "\""
                + extra + "}";
    }

    private static final String PRUNE =
            "{\"name\":\"retention\",\"type\":\"maintenance\",\"task\":\"event_prune\",\"retention_days\":\"1\"}";

    /** Fire {@code job} and wait for THIS run's terminal row; returns it. */
    private String fireAndWait(Ctx c, String job) throws Exception {
        HttpResponse<String> fire = send(c, "POST", "/jobs/" + job + "/trigger", "", "Bearer admin");
        assertEquals(202, fire.statusCode(), fire.body());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"runId\"\\s*:\\s*\"([^\"]+)\"").matcher(fire.body());
        assertTrue(m.find(), fire.body());
        long deadline = System.currentTimeMillis() + 20_000;
        String run = "";
        while (System.currentTimeMillis() < deadline) {
            run = send(c, "GET", "/jobs/runs/" + m.group(1), "", "Bearer admin").body();
            if (run.contains("\"status\"") && !run.contains("RUNNING")) return run;
            Thread.sleep(100);
        }
        fail("no terminal run for " + job + ": " + run);
        return run;
    }

    private static JobConfig stored(Ctx c, String name) {
        return c.svc().jobServiceOrCreate().jobConfig(name).orElseThrow();
    }

    // ── (1) the author is server-stamped ─────────────────────────────────────────────────────────

    @Test
    void aClientSuppliedAuthorIsDiscardedAtEveryDoor(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String forged = ",\"createdBy\":\"admin-1\",\"updatedBy\":\"admin-1\",\"updatedByRoles\":\"admin,super\"";
            String body = "{\"name\":\"tidy\",\"type\":\"maintenance\",\"task\":\"noop\"" + forged + "}";
            assertTrue(send(c, "POST", "/jobs", body, "Bearer builder").statusCode() < 300);
            JobConfig j = stored(c, "tidy");
            assertEquals("builder-1", j.params().get("createdBy"));
            assertEquals("builder-1", j.params().get("updatedBy"));
            assertEquals("developer", j.params().get("updatedByRoles"));
            assertTrue(Files.readString(c.wr().resolve("jobs/tidy_job.toon")).contains("builder-1"), "stamped on disk");

            // PUT by another editor: createdBy is the stored one, updatedBy the writer — forged values ignored
            String etag = send(c, "GET", "/jobs/tidy", "", "Bearer admin").headers().firstValue("ETag").orElse("*");
            HttpRequest put = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/jobs/tidy"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer admin")
                    .header("If-Match", etag).PUT(BodyPublishers.ofString(body.replace("admin-1", "someone"))).build();
            assertTrue(client.send(put, BodyHandlers.ofString()).statusCode() < 300);
            j = stored(c, "tidy");
            assertEquals("builder-1", j.params().get("createdBy"));
            assertEquals("admin-1", j.params().get("updatedBy"));
            assertEquals("admin", j.params().get("updatedByRoles"));

            // /config/write
            String write = "{\"type\":\"job\",\"config\":{\"job\":{\"name\":\"cw\",\"type\":\"maintenance\",\"task\":\"noop\""
                    + forged + "}}}";
            HttpResponse<String> cw = send(c, "POST", "/config/write", write, "Bearer builder");
            assertTrue(cw.statusCode() < 300, cw.body());
            Path cwFile;
            try (var walk = Files.walk(c.wr())) {
                cwFile = walk.filter(p -> p.getFileName().toString().startsWith("cw")).findFirst().orElseThrow(() -> new AssertionError(cw.body()));
            }
            String onDisk = Files.readString(cwFile);
            assertTrue(onDisk.contains("builder-1") && !onDisk.contains("admin-1") && !onDisk.contains("super"), onDisk);

            // /bundle/import — a job item
            String bundle = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":[{\"kind\":\"job\",\"id\":\"imp\",\"content\":{\"name\":\"imp\",\"type\":\"maintenance\","
                    + "\"task\":\"noop\"" + forged + "}}]}";
            HttpResponse<String> imp = send(c, "POST", "/bundle/import", bundle, "Bearer builder");
            assertTrue(imp.statusCode() < 300, imp.body());
            assertEquals("builder-1", stored(c, "imp").params().get("updatedBy"), imp.body());
            assertEquals("developer", stored(c, "imp").params().get("updatedByRoles"));
        }
    }

    // ── (1) the runner re-checks ─────────────────────────────────────────────────────────────────

    @Test
    void anAdministratorOnlyJobRunsWhileItsEditorHoldsCanAdministerAndIsRefusedOnceTheRoleLosesIt(@TempDir Path dir)
            throws Exception {
        try (Ctx c = open(dir)) {
            assertTrue(send(c, "POST", "/jobs", PRUNE, "Bearer admin").statusCode() < 300);
            String ok = fireAndWait(c, "retention");
            assertFalse(ok.contains("REJECTED"), "the probe: an administrator's prune is not refused — " + ok);

            // the role table no longer grants 'admin' canAdminister
            Files.writeString(c.wr().resolve("roles.toon"), "roles:\n  admin:\n    capabilities[1]: canOperateRuns\n");
            String refused = fireAndWait(c, "retention");
            assertTrue(refused.contains("REJECTED") && refused.contains("no longer holds"), refused);
        }
    }

    @Test
    void aLegacyAdministratorOnlyJobWithNoAuthorIsRefusedUntilReSaved(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            c.svc().jobServiceOrCreate().upsertJob(JobConfig.fromMap(Map.of("job", Map.of(
                    "name", "retention", "type", "maintenance", "task", "event_prune", "retention_days", "1"))));
            String refused = fireAndWait(c, "retention");
            assertTrue(refused.contains("REJECTED") && refused.contains("records no author"), refused);

            String etag = send(c, "GET", "/jobs/retention", "", "Bearer admin").headers().firstValue("ETag").orElse("*");
            HttpRequest put = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/jobs/retention"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer admin")
                    .header("If-Match", etag).PUT(BodyPublishers.ofString(PRUNE)).build();
            assertTrue(client.send(put, BodyHandlers.ofString()).statusCode() < 300);
            String runs = fireAndWait(c, "retention");
            assertFalse(runs.contains("REJECTED"), "re-saved by an administrator, it runs: " + runs);
        }
    }

    // ── (2) cleanup of the config root ───────────────────────────────────────────────────────────

    @Test
    void aCleanupReachingTheConfigRootIsAdministratorOnlyAndOtherCleanupsStayABuilders(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Files.createDirectories(dir.resolve("logs"));
            List<String> intoConfig = List.of(
                    cleanup("c1", json(c.wr()), ""),                                    // the root itself
                    cleanup("c2", "registry", ""),                                      // relative, inside it
                    cleanup("c3", json(dir), ""),                                       // an ancestor walks into it
                    cleanup("c4", json(c.wr().resolve("jobs").resolve("..").resolve("audit")), ""),   // a reserved dir
                    cleanup("c5", json(dir.resolve("logs")),
                            ",\"archive_instead_of_delete\":\"true\",\"archive_dir\":\"" + json(c.wr()) + "\""));
            for (String job : intoConfig) {
                HttpResponse<String> b = send(c, "POST", "/jobs", job, "Bearer builder");
                assertEquals(403, b.statusCode(), job + " → " + b.body());
                assertTrue(b.body().contains("canAdminister"), b.body());
            }
            // the same door, /config/write
            HttpResponse<String> cw = send(c, "POST", "/config/write",
                    "{\"type\":\"job\",\"config\":{\"job\":" + intoConfig.getFirst() + "}}", "Bearer builder");
            assertEquals(403, cw.statusCode(), cw.body());
            for (String job : intoConfig)
                assertTrue(send(c, "POST", "/jobs", job, "Bearer admin").statusCode() < 300, job);

            HttpResponse<String> elsewhere = send(c, "POST", "/jobs", cleanup("logs", json(dir.resolve("logs")), ""),
                    "Bearer builder");
            assertTrue(elsewhere.statusCode() < 300, elsewhere.body());
            assertFalse(fireAndWait(c, "logs").contains("REJECTED"), "a builder's cleanup elsewhere runs");
        }
    }

    @Test
    void aConfigRootCleanupIsRefusedAtRunTimeOnceItsEditorLosesCanAdminister(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Path doomed = Files.writeString(Files.createDirectories(c.wr().resolve("registry")).resolve("old.toon"), "x: 1\n");
            Files.setLastModifiedTime(doomed, java.nio.file.attribute.FileTime.fromMillis(0));
            assertTrue(send(c, "POST", "/jobs", cleanup("sweep", "registry", ",\"retention_days\":\"1\""), "Bearer admin")
                    .statusCode() < 300);
            Files.writeString(c.wr().resolve("roles.toon"), "roles:\n  admin:\n    capabilities[1]: canOperateRuns\n");
            // the runner resolves 'registry' against the Space config root, as in production (open() cleared it)
            System.setProperty("assist.write.root", c.wr().toString());
            try {
                String runs = fireAndWait(c, "sweep");
                assertTrue(runs.contains("REJECTED"), runs);
                assertTrue(Files.exists(doomed), "nothing under the config root was retired");
            } finally {
                System.clearProperty("assist.write.root");
            }
        }
    }
}

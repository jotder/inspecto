package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.job.JobConfig;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for {@code POST /datasets/{id}/materialize} (STUDIO-HALVES-1) — one per fail-closed
 * gate plus the happy path: writes disabled → 503, unknown source Dataset → 404, a missing / unsafe /
 * self-referential {@code target} → 422, a materialize of the same target already in flight → 409, and
 * the accepted case → 202 + {@code runId} + a {@code Location} to poll.
 *
 * <p>⚠ The route is ASYNCHRONOUS, so these assert what the ROUTE decides, not what the run produces: a
 * 202 says the request was admitted and a run id exists to poll. The materialize itself is
 * {@code MaterializeTask}'s contract and is covered by its own tests — asserting produced Parquet here
 * would be testing the task through an HTTP keyhole.
 *
 * <p>🔴 <b>Concretely: the admitted runs in this class all FAIL, by construction, and a green suite here
 * is therefore NOT evidence that materialize works over HTTP.</b> {@code open()} clears
 * {@code assist.write.root} once the server has booted (the property is captured by {@code ControlApi}'s
 * constructor, so the ROUTE still sees a write root) — but {@code MaterializeTask} re-reads that same
 * property on the worker thread, finds it gone, and throws. The gate assertions are unaffected because
 * every one of them is decided before the run is submitted. ⛔ Do not read "9 green" as an end-to-end
 * proof, and do not "fix" the failing runs by leaking the property for the whole JVM — other tests in the
 * fork depend on it being absent.
 *
 * <p>🔴 The 409 is deterministic ONLY because of how it is staged, and the staging is the point:
 * a run-permit cap of 1 plus a 5-second {@code sleep_ms} heartbeat occupies the single run permit, and {@code submitAdhocRun} records a run {@code RUNNING} SYNCHRONOUSLY at submit while the
 * worker then blocks acquiring that permit. So the first materialize is reliably still {@code RUNNING}
 * when the second request arrives. ⛔ Do not "simplify" this to firing twice back to back — a materialize
 * over a dataset with no data fails in milliseconds, and the test would pass on a fast box and flake on a
 * slow one, which is worse than no test.
 */
class ControlApiDatasetMaterializeTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            // 🔴 Cleared HERE, not in open()'s finally. The JobService these tests use is created LAZILY,
            // inside the request (jobServiceOrCreate), so it reads jobs.audit.dir long after open() has
            // returned — clearing it there silently restored the default and wrote jobs_audit/ INTO THE
            // REPO anyway. Exactly the shape of the product defect this class also pins: a property read
            // later than the code that sets it assumes.
            System.clearProperty("jobs.audit.dir");
        }
    }

    /** Boot a server. {@code writeRoot == null} ⇒ writes disabled; otherwise a {@code dataset:orders} is seeded. */
    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        return open(configDir, writeRoot, false);
    }

    private Ctx open(Path configDir, Path writeRoot, boolean withSlowJob) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) {
            System.setProperty("assist.write.root", writeRoot.toString());
            new ComponentStore(writeRoot.resolve("registry"))
                    .write("dataset", "orders", Map.of("id", "orders", "name", "Orders", "kind", "virtual"));
        } else {
            System.clearProperty("assist.write.root");
        }
        // ⚠ Redirect the job audit dir into the temp tree. Without this the default is relative to the
        // module CWD, so a test run writes jobs_audit/ INTO THE REPO — caught here only because the files
        // turned up in `git status` on the way to a commit.
        System.setProperty("jobs.audit.dir", configDir.resolve("jobs_audit").toString());
        try {
            List<JobConfig> jobs = withSlowJob
                    ? List.of(new JobConfig("slowpoke", "maintenance", null, null, true, false,
                            Map.of("task", "heartbeat", "sleep_ms", "5000"), null, null))
                    : List.<JobConfig>of();
            CollectorService svc = new CollectorService(List.of(pipe), List.of(), jobs, 3600, 1, null);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            // ⚠ Pin the run-permit cap on the LIVE instance rather than via -Djobs.maxConcurrentRuns:
            // the bootstrap property is consulted only when nothing is installed, and the installed value
            // is a process-global static, so an earlier test in the same fork could have set it. An
            // explicit setMaxConcurrentRuns cannot be pre-empted that way.
            if (withSlowJob) svc.jobService().orElseThrow().setMaxConcurrentRuns(1);
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json");
        return client.send(b.method("POST", body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> materialize(int port, String dataset, String body) throws Exception {
        return post(port, "/datasets/" + dataset + "/materialize", body);
    }

    // ── gate 1: writes disabled ───────────────────────────────────────────────────

    @Test
    void refusedWhenNoWriteRootConfigured(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = materialize(c.port, "orders", """
                    {"target":"orders_daily"}""");
            assertEquals(503, r.statusCode(), r.body());
        }
    }

    @Test
    void acceptedWhenTheTwoWriteRootsAgree(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // The single-space case, which is EVERY Personal deployment: the JVM property and the space's
            // config root are the same directory, and MaterializeTask resolves through SpaceConfigRoot's
            // default-space fallback to exactly that. Pinned because the other tests leave the property
            // UNSET, so this is the only one that exercises the fallback actually resolving something.
            System.setProperty("assist.write.root", root.toString());
            try {
                HttpResponse<String> r = materialize(c.port, "orders", """
                        {"target":"orders_daily"}""");
                assertEquals(202, r.statusCode(), r.body());
            } finally {
                System.clearProperty("assist.write.root");
            }
        }
    }

    // ── 404: the source Dataset must exist ────────────────────────────────────────

    @Test
    void unknownSourceDatasetIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = materialize(c.port, "no_such_dataset", """
                    {"target":"orders_daily"}""");
            assertEquals(404, r.statusCode(), r.body());
            assertTrue(r.body().contains("no dataset 'no_such_dataset'"), r.body());
        }
    }

    // ── gate 2: the target ────────────────────────────────────────────────────────

    @Test
    void missingTargetIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = materialize(c.port, "orders", "{}");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("target"), r.body());
        }
    }

    @Test
    void traversingTargetIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = materialize(c.port, "orders", """
                    {"target":"../escape"}""");
            assertEquals(422, r.statusCode(), r.body());
        }
    }

    @Test
    void targetEqualToTheSourceIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = materialize(c.port, "orders", """
                    {"target":"orders"}""");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("must differ"), r.body());
        }
    }

    // ── the happy path ────────────────────────────────────────────────────────────

    @Test
    void acceptedMaterializeReturnsARunIdToPoll(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = materialize(c.port, "orders", """
                    {"target":"orders_daily"}""");
            assertEquals(202, r.statusCode(), r.body());

            JsonNode out = V1Body.of(r.body());
            assertFalse(out.get("runId").asText().isBlank(), r.body());
            assertEquals("orders", out.get("dataset").asText());
            assertEquals("orders_daily", out.get("target").asText());
            assertEquals("running", out.get("status").asText());
            assertEquals("/api/v1/jobs/runs/" + out.get("runId").asText(),
                    r.headers().firstValue("Location").orElse(null),
                    "202 must point at the run it created");
        }
    }

    @Test
    void theAdHocRunIsNotAddedToTheJobRegistry(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(202, materialize(c.port, "orders", """
                    {"target":"orders_daily"}""").statusCode());
            // The whole reason this is a route and not a created job: nothing is authored by asking for it.
            assertTrue(c.svc.jobService().orElseThrow().jobs().isEmpty(),
                    "a materialize must not register a job — that is the side effect the route exists to avoid");
        }
    }

    // ── gate 4: non-overlap per target ────────────────────────────────────────────

    @Test
    void secondMaterializeOfTheSameTargetIs409(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            // Occupy the single run permit for 5s, so the materialize below is admitted but cannot finish.
            assertEquals(202, post(c.port, "/jobs/slowpoke/trigger", null).statusCode());

            assertEquals(202, materialize(c.port, "orders", """
                    {"target":"orders_daily"}""").statusCode());

            HttpResponse<String> second = materialize(c.port, "orders", """
                    {"target":"orders_daily"}""");
            assertEquals(409, second.statusCode(), second.body());
            assertTrue(second.body().contains("already running"), second.body());
        }
    }

    @Test
    void aDifferentTargetIsNotBlockedByAnInFlightMaterialize(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            assertEquals(202, post(c.port, "/jobs/slowpoke/trigger", null).statusCode());
            assertEquals(202, materialize(c.port, "orders", """
                    {"target":"orders_daily"}""").statusCode());

            // Non-overlap is keyed by TARGET, not by "a materialize is happening" — the second target is free.
            HttpResponse<String> other = materialize(c.port, "orders", """
                    {"target":"orders_weekly"}""");
            assertEquals(202, other.statusCode(), other.body());
        }
    }
}

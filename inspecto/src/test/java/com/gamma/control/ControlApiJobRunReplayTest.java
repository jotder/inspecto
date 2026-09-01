package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
import com.gamma.job.JobType;
import com.gamma.service.CollectorService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for {@code POST /jobs/runs/{runId}/replay} (E3 — the at-rest analog of
 * {@code POST /runs/{name}/reprocess}): 404 on an unknown runId, 409 while the run's job is still
 * in flight (non-overlap), 409 once the job is unregistered, the happy path with the
 * {@code replayOf} linkage riding both the response and the new run's {@code trigger}, and the
 * {@code canOperateRuns} capability declaration.
 */
class ControlApiJobRunReplayTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** One seeded pipeline + a fast heartbeat job and a slow (sleep_ms) one, over real HTTP. */
    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Files.createDirectories(dir.resolve("inbox"));
        JobConfig fast = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false,
                Map.of("task", "heartbeat"));
        JobConfig slow = new JobConfig("slowpoke", JobType.MAINTENANCE, null, null, true, false,
                Map.of("task", "heartbeat", "sleep_ms", "5000"));
        System.setProperty("jobs.audit.dir", dir.resolve("jobs_audit").toString());
        CollectorService svc = new CollectorService(List.of(toon), List.of(), List.of(fast, slow), 3600, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }

    /** Poll a run (by its poll path) to a terminal status, or fail after 10s. */
    private JsonNode awaitTerminal(int port, String runId) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            JsonNode run = json(send(port, "GET", "/jobs/runs/" + runId, null));
            if (!"RUNNING".equals(run.get("status").asText())) return run;
            Thread.sleep(50);
        }
        return fail("run " + runId + " did not reach a terminal status within 10s");
    }

    @Test
    void unknownRunIdIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = send(c.port, "POST", "/jobs/runs/no-such-run/replay", null);
            assertEquals(404, r.statusCode(), r.body());
            assertTrue(r.body().contains("no run 'no-such-run'"), r.body());
        }
    }

    @Test
    void replayWhileTheJobIsRunningIs409(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // The slow heartbeat is registered RUNNING synchronously at submit and holds for 5s.
            HttpResponse<String> fire = send(c.port, "POST", "/jobs/slowpoke/trigger", null);
            assertEquals(202, fire.statusCode(), fire.body());
            String runId = json(fire).get("runId").asText();

            HttpResponse<String> replay = send(c.port, "POST", "/jobs/runs/" + runId + "/replay", null);
            assertEquals(409, replay.statusCode(), replay.body());
            assertTrue(replay.body().contains("currently running"), replay.body());
        }
    }

    @Test
    void replaySucceedsAndLinksTheNewRunToTheOriginal(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> fire = send(c.port, "POST", "/jobs/hb/trigger", null);
            assertEquals(202, fire.statusCode(), fire.body());
            String original = json(fire).get("runId").asText();
            awaitTerminal(c.port, original);

            HttpResponse<String> replay = send(c.port, "POST", "/jobs/runs/" + original + "/replay", null);
            assertEquals(202, replay.statusCode(), replay.body());
            assertEquals("/api/v1/jobs/runs/" + json(replay).get("runId").asText(),
                    replay.headers().firstValue("Location").orElseThrow());
            JsonNode body = json(replay);
            String replayed = body.get("runId").asText();
            assertNotEquals(original, replayed, "the replay is a NEW run");
            assertEquals(original, body.get("replayOf").asText(), "the response carries the linkage");
            assertEquals("hb", body.get("job").asText());
            // Run parameters are not persisted in the ledger — the response must say what a replay means.
            assertTrue(body.get("note").asText().contains("parameters are not persisted"), body.toString());

            JsonNode done = awaitTerminal(c.port, replayed);
            assertEquals("SUCCESS", done.get("status").asText(), done.toString());
            assertTrue(done.get("trigger").asText().startsWith("replay:" + original),
                    "the linkage rides the run record's trigger: " + done);
        }
    }

    @Test
    void replayOfAnUnregisteredJobIs409(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> fire = send(c.port, "POST", "/jobs/hb/trigger", null);
            String original = json(fire).get("runId").asText();
            awaitTerminal(c.port, original);

            c.svc.jobService().orElseThrow().removeJob("hb");
            HttpResponse<String> replay = send(c.port, "POST", "/jobs/runs/" + original + "/replay", null);
            assertEquals(409, replay.statusCode(), replay.body());
            assertTrue(replay.body().contains("no longer registered"), replay.body());
        }
    }

    @Test
    void replayIsDeclaredAsACanOperateRunsWrite() {
        // The capability gate itself is enforced by ApiContext.withCapability; the manifest is the
        // contract the RBAC drift guard (CapabilityManifestTest) verifies against the call sites.
        assertTrue(CapabilityManifest.ENTRIES.stream().anyMatch(en ->
                        "POST".equals(en.method())
                                && "/jobs/runs/([^/]+)/replay".equals(en.pattern())
                                && Roles.CAN_OPERATE_RUNS.equals(en.capability())),
                "POST /jobs/runs/{runId}/replay is manifest-declared as canOperateRuns");
    }
}

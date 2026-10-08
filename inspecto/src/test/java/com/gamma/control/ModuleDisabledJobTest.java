package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.metrics.MetricRegistry;
import com.gamma.module.ModuleManifest;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.AfterEach;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MODULE-REORG-1 P4e} - the Space's module gate reaches background work. The test class path installs no
 * optional module, so the owner table is a stand-in: the built-in {@code maintenance} Job Type is declared as
 * contributed by a module whose feature is {@code testDiscovered} (the one feature this class path registers, see
 * {@link TestDiscoveredRoutes}).
 *
 * <p>Verdicts, switched off in the Space: a manual trigger and a replay answer 404 {@code MODULE_DISABLED} (the routes'
 * envelope); a scheduled fire is recorded {@code SKIPPED}; the Job stays listed and its file is untouched; switching the
 * module back on resumes it. A Job Type no manifest declares is never gated. Another Space is unaffected.
 */
class ModuleDisabledJobTest {

    private static final String FEATURE = TestDiscoveredRoutes.FEATURE;
    private static final String OFF = "disabled[1]: " + FEATURE + "\n";
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void restore() {
        JobModuleGate.ownersForTest(null);
    }

    private static void ownMaintenance() {
        ModuleManifest m = new ModuleManifest("zz-owner", "Owner", "implementation", "optional", "boot",
                new ModuleManifest.Provides(List.of(FEATURE), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of("maintenance")),
                ModuleManifest.Requires.NONE, null);
        JobModuleGate.ownersForTest(Map.of("maintenance", m));
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

    /** Spaces acme and beta, each with a manual Job {@code hb} and (acme only) a scheduled Job {@code tick}; returns acme's modules.toon. */
    private Path seed(Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());
        }
        for (String space : List.of("acme", "beta")) {
            Path jobs = Files.createDirectories(root.resolve(space).resolve("config").resolve("jobs"));
            Files.writeString(jobs.resolve("hb_job.toon"), "job:\n  name: hb\n  type: maintenance\n  enabled: true\n  task: heartbeat\n");
        }
        Files.writeString(root.resolve("acme/config/jobs/tick_job.toon"),
                "job:\n  name: tick\n  type: maintenance\n  enabled: true\n  cron: \"* * * * * *\"\n  task: heartbeat\n");
        return root.resolve("acme").resolve("config").resolve("modules.toon");
    }

    @Test
    void aSwitchedOffModulesJobsDoNotRunAndTheTriggerAnswersModuleDisabled(@TempDir Path root) throws Exception {
        ownMaintenance();
        Path modules = seed(root);
        Files.writeString(modules, OFF);
        Path hbFile = root.resolve("acme/config/jobs/hb_job.toon");
        byte[] before = Files.readAllBytes(hbFile);
        try (Ctx c = open(root)) {
            c.spaces.startAll();   // arms the cron Job: the scheduler is what is being gated
            HttpResponse<String> r = send(c.port, "POST", "/spaces/acme/jobs/hb/trigger", null);
            assertEquals(404, r.statusCode(), r.body());
            assertTrue(r.body().contains("MODULE_DISABLED") && r.body().contains(FEATURE) && r.body().contains("maintenance"), r.body());

            // the scheduled Job's fires are SKIPPED with the reason, never SUCCESS
            JsonNode runs = awaitRuns(c, "acme", "tick");
            assertEquals("SKIPPED", runs.get(0).get("status").asText(), runs.toString());
            assertTrue(runs.get(0).get("message").asText().contains("switched off in this Space"), runs.toString());
            for (JsonNode run : runs) assertNotEquals("SUCCESS", run.get("status").asText(), runs.toString());

            // the config is untouched and still listed; another Space is unaffected
            assertEquals(200, send(c.port, "GET", "/spaces/acme/jobs/hb", null).statusCode());
            assertArrayEquals(before, Files.readAllBytes(hbFile), "the Job file is never rewritten");
            assertEquals(202, send(c.port, "POST", "/spaces/beta/jobs/hb/trigger", null).statusCode(),
                    "a Space that did not switch the module off still runs it");

            // switched back on: the same Job triggers and runs; the replay of that run is refused again once off
            Files.writeString(modules, "disabled[0]:\n");
            HttpResponse<String> on = send(c.port, "POST", "/spaces/acme/jobs/hb/trigger", null);
            assertEquals(202, on.statusCode(), on.body());
            String runId = V1Body.of(on.body()).get("runId").asText();
            awaitStatus(c, "acme", runId, "SUCCESS");
            Files.writeString(modules, OFF);
            HttpResponse<String> replay = send(c.port, "POST", "/spaces/acme/jobs/runs/" + runId + "/replay", null);
            assertEquals(404, replay.statusCode(), replay.body());
            assertTrue(replay.body().contains("MODULE_DISABLED"), replay.body());
        }
    }

    @Test
    void aTypeNoManifestDeclaresIsNeverGated(@TempDir Path root) throws Exception {
        JobModuleGate.ownersForTest(Map.of());   // no module owns anything
        Path modules = seed(root);
        Files.writeString(modules, OFF);
        try (Ctx c = open(root)) {
            assertEquals(202, send(c.port, "POST", "/spaces/acme/jobs/hb/trigger", null).statusCode());
        }
    }

    private JsonNode awaitRuns(Ctx c, String space, String job) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            JsonNode runs = V1Body.of(send(c.port, "GET", "/spaces/" + space + "/jobs/" + job + "/runs", null).body());
            if (runs.size() > 0) return runs;
            Thread.sleep(100);
        }
        throw new AssertionError("no run of " + job + " within 10s");
    }

    private void awaitStatus(Ctx c, String space, String runId, String status) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        String last = "";
        while (System.nanoTime() < deadline) {
            last = V1Body.of(send(c.port, "GET", "/spaces/" + space + "/jobs/runs/" + runId, null).body()).path("status").asText();
            if (status.equals(last)) return;
            Thread.sleep(100);
        }
        throw new AssertionError("run " + runId + " is " + last + ", wanted " + status);
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}

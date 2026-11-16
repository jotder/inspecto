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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MODULE-REORG-P4-3} over real HTTP: {@code PUT /settings/modules} switching a module off stops that module's
 * Runs already in flight in the Space. The test class path installs no optional module, so the owner table is a
 * stand-in: the built-in {@code heartbeat} maintenance task is declared as contributed by a module whose feature is
 * {@code testDiscovered} (the one feature this class path registers); the {@code noop} task (the same body) belongs to
 * no module. Both hold their Run with {@code sleep_ms}.
 *
 * <p>Switched off in acme: acme's heartbeat Run is CANCELLED with "module switched off"; acme's noop Run (no module) and
 * beta's heartbeat Run (another Space) keep running and end SUCCESS. Switched back on: the heartbeat Job runs again.
 */
class ModuleSwitchOffStopsRunsTest {

    private static final String FEATURE = TestDiscoveredRoutes.FEATURE;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void restore() {
        ModuleGate.ownersForTest(null, null);
    }

    private static void ownHeartbeat() {
        ModuleManifest m = new ModuleManifest("zz-owner", "Owner", "implementation", "optional", "boot",
                new ModuleManifest.Provides(List.of(FEATURE), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of("heartbeat")),
                ModuleManifest.Requires.NONE, null);
        ModuleGate.ownersForTest(Map.of(), Map.of("heartbeat", m));
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

    private static void job(Path root, String space, String name, String task, long sleepMs) throws Exception {
        Path jobs = Files.createDirectories(root.resolve(space).resolve("config").resolve("jobs"));
        Files.writeString(jobs.resolve(name + "_job.toon"), "job:\n  name: " + name + "\n  type: maintenance\n  enabled: true\n"
                + "  task: " + task + "\n  sleep_ms: " + sleepMs + "\n");
    }

    @Test
    void switchingAModuleOffCancelsItsRunningRunAndLeavesOtherRunsAlone(@TempDir Path root) throws Exception {
        ownHeartbeat();
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());
        }
        job(root, "acme", "hb", "heartbeat", 30_000);
        job(root, "acme", "nop", "noop", 3_000);
        job(root, "beta", "hb", "heartbeat", 3_000);
        job(root, "acme", "quick", "heartbeat", 0);
        try (Ctx c = open(root)) {
            String hb = trigger(c, "acme", "hb");
            String nop = trigger(c, "acme", "nop");
            String betaHb = trigger(c, "beta", "hb");
            awaitStatus(c, "acme", hb, "RUNNING");
            Thread.sleep(300);   // the Runs reach their sleep

            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":[\"" + FEATURE + "\"]}");
            assertEquals(200, put.statusCode(), put.body());

            JsonNode cancelled = awaitStatus(c, "acme", hb, "CANCELLED");
            String message = cancelled.path("message").asText();
            assertTrue(message.startsWith("module switched off: ") && message.contains("'" + FEATURE + "' module is switched off")
                    && message.contains("heartbeat"), cancelled.toString());
            assertEquals("RUNNING", run(c, "acme", nop).path("status").asText(), "a Run of no module keeps running");
            assertEquals("RUNNING", run(c, "beta", betaHb).path("status").asText(), "another Space's Run keeps running");
            awaitStatus(c, "acme", nop, "SUCCESS");
            awaitStatus(c, "beta", betaHb, "SUCCESS");

            // switched back on: the module's Job runs again
            assertEquals(200, send(c.port, "PUT", "/spaces/acme/settings/modules", "{\"disabled\":[]}").statusCode());
            awaitStatus(c, "acme", trigger(c, "acme", "quick"), "SUCCESS");
        }
    }

    private String trigger(Ctx c, String space, String job) throws Exception {
        HttpResponse<String> r = send(c.port, "POST", "/spaces/" + space + "/jobs/" + job + "/trigger", null);
        assertEquals(202, r.statusCode(), r.body());
        return V1Body.of(r.body()).get("runId").asText();
    }

    private JsonNode run(Ctx c, String space, String runId) throws Exception {
        return V1Body.of(send(c.port, "GET", "/spaces/" + space + "/jobs/runs/" + runId, null).body());
    }

    private JsonNode awaitStatus(Ctx c, String space, String runId, String status) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = run(c, space, runId);
            if (status.equals(last.path("status").asText())) return last;
            Thread.sleep(50);
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

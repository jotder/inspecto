package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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
 * The Scheduler's per-job actions over real HTTP (the UI's live path): single-job detail
 * ({@code GET /jobs/{name}}), the enable/disable toggle and reschedule (all three persist the job's
 * TOON under the space's write root and hot-apply on the live JobService), and the UI-shaped run-log
 * view ({@code GET .../runs/{id}/logs} → {@code {logs:[{ts,level,message}], events:[]}}).
 */
class ControlApiJobActionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

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

    @Test
    void detailToggleAndRescheduleRoundTrip(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";

            // create a cron job through the existing CRUD, then read it back as a single-job detail
            assertEquals(200, send(c.port, "POST", base + "/jobs",
                    "{\"name\":\"nightly_cleanup\",\"type\":\"maintenance\",\"task\":\"cleanup\",\"cron\":\"0 3 * * *\"}")
                    .statusCode());
            JsonNode detail = json(send(c.port, "GET", base + "/jobs/nightly_cleanup", null));
            assertEquals("nightly_cleanup", detail.get("name").asText());
            assertEquals("0 3 * * *", detail.get("cron").asText());
            assertTrue(detail.get("enabled").asBoolean());

            // unknown job -> 404 (and the fixed /jobs sub-paths still resolve to their own routes)
            assertEquals(404, send(c.port, "GET", base + "/jobs/nope", null).statusCode());
            assertEquals(200, send(c.port, "GET", base + "/jobs/types", null).statusCode());

            // disable -> persisted enabled:false + reflected in the detail; enable flips it back
            JsonNode disabled = json(send(c.port, "POST", base + "/jobs/nightly_cleanup/disable", "{}"));
            assertFalse(disabled.get("enabled").asBoolean());
            Path toon = root.resolve("acme").resolve("config").resolve("jobs").resolve("nightly_cleanup_job.toon");
            assertTrue(Files.readString(toon).contains("enabled: false"), "persisted to the job TOON");
            assertTrue(json(send(c.port, "POST", base + "/jobs/nightly_cleanup/enable", "{}"))
                    .get("enabled").asBoolean());

            // reschedule replaces the cron (422 without one)
            JsonNode moved = json(send(c.port, "POST", base + "/jobs/nightly_cleanup/reschedule",
                    "{\"cron\":\"30 4 * * *\"}"));
            assertEquals("30 4 * * *", moved.get("cron").asText());
            assertTrue(Files.readString(toon).contains("30 4 * * *"));
            assertEquals(422, send(c.port, "POST", base + "/jobs/nightly_cleanup/reschedule", "{}").statusCode());

            // the UI-shaped run-log view: trigger one run, then read its /logs
            JsonNode fired = json(send(c.port, "POST", base + "/jobs/nightly_cleanup/trigger", null));
            String runId = fired.has("runId") ? fired.get("runId").asText() : null;
            if (runId != null) {
                Thread.sleep(300);   // the run executes off the request thread
                JsonNode logs = json(send(c.port, "GET", base + "/jobs/nightly_cleanup/runs/" + runId + "/logs", null));
                assertTrue(logs.has("logs") && logs.get("logs").isArray(), logs.toString());
                assertTrue(logs.has("events") && logs.get("events").isArray());
                if (!logs.get("logs").isEmpty()) {
                    JsonNode line = logs.get("logs").get(0);
                    assertTrue(line.has("ts") && line.has("level") && line.has("message"));
                }
            }
        }
    }

    /**
     * Operator 2026-09-25: a DISABLED job is "not scheduled", not "not runnable" — the KPI & Reports
     * "Run now" ({@code POST /jobs/{name}/trigger}) runs it (202 → SUCCESS) where it used to answer a
     * misleading 404 "no job named" while {@code GET /jobs} listed it. A Decision Rule's {@code start-job}
     * consequence depends on HOW the rule is applied: a person's {@code POST /decision-rules/{name}/apply}
     * runs the disabled job like Run now (attributed to that person); an AUTOMATIC application (the
     * {@code automatic=true} seam) skips it. An unknown name stays 404.
     */
    @Test
    void aDisabledJobRunsOnAManualDecisionApplyButIsSkippedByAnAutomaticOne(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";
            assertEquals(200, send(c.port, "POST", base + "/jobs",
                    "{\"name\":\"cfo_pack\",\"type\":\"maintenance\",\"task\":\"heartbeat\","
                            + "\"cron\":\"0 6 * * *\",\"enabled\":false}").statusCode());
            assertFalse(json(send(c.port, "GET", base + "/jobs/cfo_pack", null)).get("enabled").asBoolean());

            HttpResponse<String> fired = send(c.port, "POST", base + "/jobs/cfo_pack/trigger", null);
            assertEquals(202, fired.statusCode(), fired.body());
            String runId = json(fired).get("runId").asText();
            assertEquals("SUCCESS", awaitRun(c.port, base, runId), "the disabled job's manual run succeeds");

            assertEquals(404, send(c.port, "POST", base + "/jobs/nope/trigger", null).statusCode(),
                    "a genuinely unknown name is still 404");

            assertEquals(200, send(c.port, "POST", base + "/decision-rules",
                    "{\"name\":\"kick\",\"targetType\":\"job\",\"target\":\"cfo_pack\","
                            + "\"consequences\":[{\"action\":\"start-job\",\"target\":{\"id\":\"cfo_pack\"}}]}")
                    .statusCode());

            // a PERSON applying the rule runs the disabled job, like Run now, attributed to that person
            JsonNode manual = json(send(c.port, "POST", base + "/decision-rules/kick/apply", null))
                    .get("executed").get(0);
            assertEquals("executed", manual.get("status").asText(), manual.toString());
            String manualRun = manual.get("runId").asText();
            assertEquals("SUCCESS", awaitRun(c.port, base, manualRun), "the manual apply's run succeeds");
            assertEquals("manual:appUser",
                    json(send(c.port, "GET", base + "/jobs/runs/" + manualRun, null)).get("trigger").asText());

            // an AUTOMATIC application of the same rule skips the disabled job
            JsonNode automatic = applyAutomatically(c, "acme", "kick", "cfo_pack");
            assertEquals("skipped", automatic.get("status").asText(), automatic.toString());
            assertTrue(automatic.get("detail").asText().contains("disabled"), automatic.toString());
            assertFalse(automatic.has("runId"), automatic.toString());

            // enabled, both ways start it (positive control for the automatic skip above)
            send(c.port, "POST", base + "/jobs/cfo_pack/enable", "{}");
            JsonNode started = json(send(c.port, "POST", base + "/decision-rules/kick/apply", null))
                    .get("executed").get(0);
            assertEquals("executed", started.get("status").asText(), started.toString());
            awaitRun(c.port, base, started.get("runId").asText());
            JsonNode auto = applyAutomatically(c, "acme", "kick", "cfo_pack");
            assertEquals("executed", auto.get("status").asText(), auto.toString());
            assertEquals("manual:decision-rule:kick", json(send(c.port, "GET",
                    base + "/jobs/runs/" + auto.get("runId").asText(), null)).get("trigger").asText());
        }
    }

    /** Drive the shared apply seam as an engine-driven caller would: bound to {@code space}, {@code automatic=true}. */
    private static JsonNode applyAutomatically(Ctx c, String space, String rule, String job) {
        org.slf4j.MDC.put(com.gamma.event.EventLog.SPACE_MDC_KEY, space);
        try {
            java.util.Map<String, Object> result = DecisionRoutes.applyConsequences(c.api, rule, java.util.Map.of(
                    "consequences", java.util.List.of(java.util.Map.of("action", "start-job",
                            "target", java.util.Map.of("id", job)))), true, "appUser");
            return JSON.valueToTree(result).get("executed").get(0);
        } finally {
            org.slf4j.MDC.remove(com.gamma.event.EventLog.SPACE_MDC_KEY);
        }
    }

    private String awaitRun(int port, String base, String runId) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        String status = "RUNNING";
        while ("RUNNING".equals(status) && System.nanoTime() < deadline) {
            Thread.sleep(50);
            status = json(send(port, "GET", base + "/jobs/runs/" + runId, null)).get("status").asText();
        }
        return status;
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return V1Body.of(r.body()); }
}

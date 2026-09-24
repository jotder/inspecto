package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.AlertRule;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.job.FreshnessSweep;
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
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DUCKLE-C1 residual (1) over real HTTP: an Alert Rule with {@code maximumAge} arms the minute-cadence
 * freshness sweep as a SYSTEM job that {@code GET /jobs} lists, that the job CRUD refuses to edit, that
 * the last such rule's deletion disarms, and that a restart re-derives from the rules alone.
 */
class ControlApiFreshnessSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FRESHNESS_RULE =
            "{\"name\":\"sales-fresh\",\"dataset\":\"sales_ds\",\"maximumAge\":\"6h\",\"severity\":\"WARNING\"}";
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void professionalBuild(@TempDir Path audit) {
        com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        // the lazily-created job scheduler's run journal — kept out of the working directory
        System.setProperty("jobs.audit.dir", audit.toString());
    }

    @AfterEach
    void restore() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        System.clearProperty("jobs.audit.dir");
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot, List<AlertRule> bootRules) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), List.of(), bootRules,
                    3600, 1, null);
            svc.start();   // the sweep is derived at start — a rule change before it must not start the scheduler
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        HttpRequest.BodyPublisher pub = body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body);
        return client.send(b.method(method, pub).build(), BodyHandlers.ofString());
    }

    /** The sweep's row in {@code GET /jobs}, or {@code null} when it is not armed. */
    private JsonNode sweepRow(int port) throws Exception {
        HttpResponse<String> r = send(port, "GET", "/jobs", null);
        assertEquals(200, r.statusCode(), r.body());
        for (JsonNode row : JSON.readTree(r.body()).findParents("name"))
            if (FreshnessSweep.JOB_NAME.equals(row.get("name").asText())) return row;
        return null;
    }

    @Test
    void aMaximumAgeRuleArmsAListedSystemJobAndItsDeletionDisarmsIt(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root, List.of())) {
            assertNull(sweepRow(c.port), "no freshness rule ⇒ no sweep");

            // a LEDGER rule is not a freshness rule: still nothing to sweep
            assertEquals(200, send(c.port, "POST", "/alerts/rules", "{\"name\":\"errs\",\"metric\":\"error_rate\",\"comparator\":\"gt\","
                    + "\"threshold\":0.1,\"window\":\"1h\",\"severity\":\"WARNING\"}").statusCode());
            assertNull(sweepRow(c.port));

            assertEquals(200, send(c.port, "POST", "/alerts/rules", FRESHNESS_RULE).statusCode());
            JsonNode row = sweepRow(c.port);
            assertNotNull(row, "a maximumAge rule arms the sweep with no job authored by anyone");
            assertTrue(row.get("system").asBoolean(), "listed AS a system job: " + row);
            assertEquals("alert.evaluate", row.get("type").asText());
            assertEquals("* * * * *", row.get("cron").asText());

            assertEquals(409, send(c.port, "DELETE", "/jobs/" + FreshnessSweep.JOB_NAME, null).statusCode(),
                    "deleting the sweep would silently switch freshness off — it is derived, not authored");
            assertEquals(409, send(c.port, "POST", "/jobs/" + FreshnessSweep.JOB_NAME + "/disable", null).statusCode());
            assertEquals(409, send(c.port, "POST", "/jobs/" + FreshnessSweep.JOB_NAME + "/reschedule",
                    "{\"cron\":\"0 * * * *\"}").statusCode());
            assertNotNull(sweepRow(c.port), "the refused writes left it armed");

            assertEquals(200, send(c.port, "DELETE", "/alerts/rules/sales-fresh", null).statusCode());
            assertNull(sweepRow(c.port), "the last maximumAge rule gone ⇒ disarmed");
        }
    }

    @Test
    void aRestartReDerivesTheSweepFromTheBootRules(@TempDir Path cfg, @TempDir Path root) throws Exception {
        AlertRule fresh = AlertRule.fromMap(JSON.readValue(FRESHNESS_RULE,
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
        try (Ctx c = open(cfg, root, List.of(fresh))) {
            JsonNode row = sweepRow(c.port);
            assertNotNull(row, "nothing is persisted for the sweep; boot re-derives it from the armed rules");
            assertTrue(row.get("system").asBoolean());
        }
    }
}

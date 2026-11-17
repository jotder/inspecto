package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.ConsignmentSource;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
import com.gamma.job.JobType;
import com.gamma.pipeline.exec.StepReceipt;
import com.gamma.service.CollectorService;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for {@code GET /consignments/{consignmentId}} (X5 L4, plan
 * {@code docs/superpower/2026-11-17-x5-stepinfo-envelope-plan.md} §3.7): the {@code canOperateRuns} gate through the
 * real capability chain (401 without a credential, 403 for a Subject lacking it — without a Subject
 * {@code withCapability} is a no-op and would prove nothing), 404 for an id no store knows, receipts switched off
 * as an ABSENT {@code steps} with {@code known.steps=false}, the ingest → job hop both ways through the
 * {@code run_id} join, and masked text staying masked.
 *
 * <p>The stores are seeded directly: the receipt and source writers are pinned by {@code PipelineJobRunnerTest},
 * {@code DbConsignmentOutputStoreTest} and {@code DbJobRunStoreTest}; what is under test here is the route.
 */
class ControlApiConsignmentTrailTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer operator".equals(auth)) return Optional.of(new Subject("olly", Set.of("canOperateRuns")));
        if ("Bearer plain".equals(auth)) return Optional.of(new Subject("nobody", Set.of()));
        return Optional.empty();
    };

    @BeforeEach
    void setUp() {
        Authenticators.forTest(FAKE);
        System.setProperty("jobs.backend", "duckdb");
        System.setProperty("jobs.db.url", "jdbc:duckdb:");
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        ConsignmentOutputStores.use(null);
        for (String k : List.of("jobs.backend", "jobs.db.url", "consignment.steps", "jobs.audit.dir"))
            System.clearProperty(k);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, DbConsignmentOutputStore store)
            implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            store.close();
        }
    }

    /** One seeded pipeline, a heartbeat Job, a run store, and an in-memory Consignment registry over real HTTP. */
    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Files.createDirectories(dir.resolve("inbox"));
        JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        System.setProperty("jobs.audit.dir", dir.resolve("jobs_audit").toString());
        CollectorService svc = new CollectorService(List.of(toon), List.of(), List.of(hb), 3600, 1, null);
        DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:");
        ConsignmentOutputStores.use(store);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), store);
    }

    private HttpResponse<String> get(int port, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method("GET", BodyPublishers.noBody());
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode trail(int port, String id) throws Exception {
        HttpResponse<String> r = get(port, "/consignments/" + id, "operator");
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static ConsignmentOutput output(String consignment, String runId, String table, String producer) {
        return new ConsignmentOutput(consignment, runId, table, "dt=2026-11-17", "2026-11-17",
                "/out/" + consignment + ".parquet", 5, 500, "2026-11-17T10:00:00Z", ConsignmentOutput.State.LIVE,
                null, null, producer);
    }

    private static StepReceipt receipt(String consignment, String runId, int seq, String step,
                                       StepReceipt.Diagnostics diag) {
        return new StepReceipt(StepReceipt.VERSION, consignment, "cdr_rollup", StepReceipt.LANE_JOB, runId, seq,
                step, "transform.filter", diag == null ? StepReceipt.Outcome.OK : StepReceipt.Outcome.FAILED, 10,
                List.of(new StepReceipt.Outlet("data", 9), new StepReceipt.Outlet("dropped", 1)),
                null, null, diag, "2026-11-17T10:15:02.114Z", 3, false, false);
    }

    /** Fire the heartbeat Job and wait for its run to finish, so the run store holds a real run row to join. */
    private String finishedRun(Ctx c) throws Exception {
        HttpRequest fire = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/jobs/hb/trigger"))
                .header("Authorization", "Bearer operator").POST(BodyPublishers.noBody()).build();
        HttpResponse<String> r = client.send(fire, BodyHandlers.ofString());
        assertEquals(202, r.statusCode(), r.body());
        String runId = V1Body.of(r.body()).get("runId").asText();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            JsonNode run = V1Body.of(get(c.port, "/jobs/runs/" + runId, "operator").body());
            if (!"RUNNING".equals(run.get("status").asText())) return runId;
            Thread.sleep(50);
        }
        return fail("run " + runId + " did not finish within 10s");
    }

    @Test
    void theTrailIsGatedOnCanOperateRunsAndAnUnknownIdIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            c.store.record(List.of(output("c-known", "ing-1", "cdr", "cdr_etl")));

            assertEquals(401, get(c.port, "/consignments/c-known", null).statusCode(), "no credential");
            HttpResponse<String> denied = get(c.port, "/consignments/c-known", "plain");
            assertEquals(403, denied.statusCode(), "a Subject without canOperateRuns: " + denied.body());

            JsonNode body = trail(c.port, "c-known");
            assertEquals("c-known", body.get("consignmentId").asText());
            assertEquals("cdr_etl", body.get("pipeline").asText(), "the registry's producer names the Pipeline");
            assertEquals(1, body.get("outputs").size(), body.toString());
            assertEquals(1, body.get("outputsTotal").asInt());
            assertTrue(body.get("known").get("outputs").asBoolean());
            assertTrue(body.get("known").get("steps").asBoolean(), "receipts are on by default");
            assertEquals(0, body.get("steps").size(), "on and empty: known, and nothing recorded");

            HttpResponse<String> unknown = get(c.port, "/consignments/no-such-consignment", "operator");
            assertEquals(404, unknown.statusCode(), "no store knows it: 404, never an empty document " + unknown.body());
        }
    }

    /** ⛔ Switched off is "this install cannot say" — an ABSENT key, never {@code []} (which reads as "no Steps"). */
    @Test
    void receiptsSwitchedOffLeaveStepsAbsentAndSaySo(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            c.store.record(List.of(output("c-off", "run-x", "cdr_daily", "cdr_rollup")));
            c.store.recordSteps(List.of(receipt("c-off", "run-x", 1, "src", null)));

            System.setProperty("consignment.steps", "false");
            JsonNode off = trail(c.port, "c-off");
            assertFalse(off.has("steps"), "switched off: steps ABSENT, never []: " + off);
            assertFalse(off.has("stepsTotal"), off.toString());
            assertFalse(off.get("known").get("steps").asBoolean(), off.toString());
            assertTrue(off.get("known").get("outputs").asBoolean(), "the other stores still answer");

            System.clearProperty("consignment.steps");
            JsonNode on = trail(c.port, "c-off");
            assertEquals(1, on.get("steps").size(), on.toString());
            assertEquals("reject:dropped", on.get("steps").get(0).get("outlets").get(1).get("outlet").asText(),
                    "a receipt is served in its stored shape, outlets canonical");
            assertEquals("job", on.get("lanes").get(0).asText());
            assertTrue(on.get("known").get("steps").asBoolean());
        }
    }

    /**
     * The ingest → job hop: a job run read the ingest Consignment (X2 sources) and wrote its own; the ingest trail's
     * {@code downstream[0].consignmentId} is that run's OWN Consignment (the {@code run_id} join), and reading it back
     * shows {@code upstream} = the ingest id.
     */
    @Test
    void followsAnIngestConsignmentToTheJobRunThatReadItAndBack(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String runId = finishedRun(c);
            c.store.record(List.of(output("c-ingest", "ing-1", "cdr", "test_etl")));
            c.svc.jobService().orElseThrow().runStore().orElseThrow()
                    .recordSources(runId, List.of(new ConsignmentSource("c-ingest", "test_etl", "cdr")));
            c.store.recordSteps(List.of(receipt("hb-" + runId, runId, 1, "src", null),
                    receipt("hb-" + runId, runId, 2, "flt", null)));

            JsonNode ingest = trail(c.port, "c-ingest");
            assertTrue(ingest.get("known").get("runs").asBoolean(), ingest.toString());
            JsonNode down = ingest.get("downstream");
            assertEquals(1, down.size(), ingest.toString());
            assertEquals(runId, down.get(0).get("runId").asText());
            assertEquals("hb", down.get(0).get("job").asText());
            String jobConsignment = down.get(0).get("consignmentId").asText();
            assertEquals("hb-" + runId, jobConsignment, "the run's OWN Consignment, through the run_id join");
            assertEquals(0, ingest.get("upstream").size(), "an ingest Consignment read nothing");

            JsonNode job = trail(c.port, jobConsignment);
            assertEquals(1, job.get("upstream").size(), job.toString());
            assertEquals("c-ingest", job.get("upstream").get(0).get("consignmentId").asText(),
                    "followed back: upstream is the first id");
            assertEquals("test_etl", job.get("upstream").get(0).get("pipeline").asText());
            assertEquals(2, job.get("steps").size());
            assertEquals("cdr_rollup", job.get("pipeline").asText());
            assertFalse(job.get("status").isNull(), "a job-lane Consignment's status is its run's: " + job);
        }
    }

    /** A Signal message scrubbed when emitted stays scrubbed here, and a receipt's raw diagnostic is masked on read. */
    @Test
    void maskedTextStaysMasked(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            c.store.record(List.of(output("c-mask", "run-m", "cdr_daily", "cdr_rollup")));
            c.store.recordSteps(List.of(receipt("c-mask", "run-m", 1, "cast", new StepReceipt.Diagnostics(
                    StepReceipt.SQL_ERROR, "Conversion Error: Could not convert string 'PAN4111111111111111' to INT32",
                    Map.of()))));
            c.svc.eventLog().emit(new Signal(null, "pipeline.batch.failed", Instant.now(), Severity.ERROR,
                    Ref.of("pipeline", "cdr_rollup"), null, "c-mask", null, null, null,
                    "commit failed: password=hunter2", Map.of(), 1).toEvent());

            JsonNode body = trail(c.port, "c-mask");
            JsonNode signals = body.get("signals");
            assertEquals(1, signals.size(), body.toString());
            String message = signals.get(0).get("message").asText();
            assertFalse(message.contains("hunter2"), "the secret stays masked: " + message);
            assertTrue(message.contains("redacted"), message);
            assertEquals("pipeline.batch.failed", signals.get(0).get("type").asText());

            String diag = body.get("steps").get(0).get("diag").get("msg").asText();
            assertFalse(diag.contains("4111"), "the value a receipt quoted is not served: " + diag);
            assertTrue(diag.startsWith("Conversion Error: Could not convert string"), "the diagnosis survives: " + diag);
            assertFalse(body.toString().contains("4111111111111111"), body.toString());
        }
    }
}

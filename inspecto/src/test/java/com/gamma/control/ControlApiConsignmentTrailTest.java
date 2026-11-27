package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.ConsignmentSource;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.ConsignmentManifest;
import com.gamma.etl.ManifestStore;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.StatusStore;
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
 * {@code docs/superpower/2026-11-17-x5-stepinfo-envelope-plan.md} §3.7): an open read (an armed Subject with no
 * run capability reads it), 404 for an id no store knows, receipts switched off as an ABSENT {@code steps} and
 * {@code lanes} with {@code known.steps=false}, no Consignment registry at all, the ingest → job hop both ways
 * through the {@code run_id} join, the run-store status fallback, the output cap with its true total, and masked
 * text staying masked.
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
        return open(dir, null);
    }

    /** As {@link #open(Path)} over the given status store ({@code null} = the file-backed one). */
    private Ctx open(Path dir, StatusStore status) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Files.createDirectories(dir.resolve("inbox"));
        JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        System.setProperty("jobs.audit.dir", dir.resolve("jobs_audit").toString());
        CollectorService svc = new CollectorService(List.of(toon), List.of(), List.of(hb), 3600, 1, status);
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

    /** An open read like its siblings {@code /runs/{name}/batches|files|outputs|errors}: a Subject with no run
     *  capability reads it. */
    @Test
    void theTrailIsAnOpenReadAndAnUnknownIdIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            c.store.record(List.of(output("c-known", "ing-1", "cdr", "cdr_etl")));

            HttpResponse<String> plain = get(c.port, "/consignments/c-known", "plain");
            assertEquals(200, plain.statusCode(), "a Subject without run capabilities reads it: " + plain.body());

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
            assertFalse(off.has("lanes"), "lanes come from the receipts: absent too, never []: " + off);
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

    /** Basic-like: no Consignment registry. A Consignment the run store knows reads 200 with every registry-backed key
     *  absent and {@code known.outputs=false}; an unknown one is a 404, never a 500. */
    @Test
    void withNoConsignmentRegistryTheTrailSaysSoInsteadOfFailing(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            ConsignmentOutputStores.use(null);
            String runId = finishedRun(c);
            c.svc.jobService().orElseThrow().runStore().orElseThrow()
                    .recordSources(runId, List.of(new ConsignmentSource("c-basic", "test_etl", "cdr")));

            JsonNode body = trail(c.port, "c-basic");
            assertFalse(body.get("known").get("outputs").asBoolean(), body.toString());
            assertFalse(body.get("known").get("steps").asBoolean(), "no registry, no receipts: " + body);
            for (String k : List.of("outputs", "outputsTotal", "steps", "lanes"))
                assertFalse(body.has(k), k + " is absent, never []: " + body);
            assertEquals(runId, body.get("downstream").get(0).get("runId").asText());
            assertTrue(body.get("downstream").get(0).get("consignmentId").isNull(), "no registry: the hop cannot be named");

            assertEquals(404, get(c.port, "/consignments/c-nowhere", "operator").statusCode());
        }
    }

    /** After a restart the live-run registry is empty: a job-lane Consignment's status comes from the run store. */
    @Test
    void aJobConsignmentsStatusFallsBackToTheRunStore(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            c.svc.jobService().orElseThrow().runStore().orElseThrow().record(new com.gamma.job.JobRun("before-restart",
                    "rollup", "PIPELINE", "schedule", "2026-11-17T09:00:00Z", "2026-11-17T09:00:05Z", "FAILED", 5000,
                    "boom"));
            c.store.recordSteps(List.of(receipt("c-old", "before-restart", 1, "src", null)));

            JsonNode body = trail(c.port, "c-old");
            assertEquals("FAILED", body.get("status").asText(), "the durable run row answers: " + body);
        }
    }

    /** Collections are cut, and the total beside each is the true one. */
    @Test
    void outputsAreCutAndTheTotalIsTrue(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            List<ConsignmentOutput> many = new java.util.ArrayList<>();
            for (int i = 0; i < ConsignmentRoutes.MAX_OUTPUTS + 5; i++)
                many.add(new ConsignmentOutput("c-many", "run-many", "cdr", "p=" + i, null, "/out/many-" + i + ".parquet",
                        1, 10, "2026-11-17T10:00:00Z", ConsignmentOutput.State.LIVE, null, null, "cdr_rollup"));
            c.store.record(many);

            JsonNode body = trail(c.port, "c-many");
            assertEquals(ConsignmentRoutes.MAX_OUTPUTS, body.get("outputs").size());
            assertEquals(ConsignmentRoutes.MAX_OUTPUTS + 5, body.get("outputsTotal").asInt());
        }
    }

    /**
     * A status store whose ledger is what the test puts in it, read through the interface's default by-id methods (the
     * file-backed store's path), counting how often each Pipeline is asked for a Consignment.
     */
    private static final class ListStatusStore implements StatusStore {
        final List<Map<String, String>> batches = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Map<String, Integer> asked = new java.util.concurrent.ConcurrentHashMap<>();
        public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
        public List<Map<String, String>> batches(PipelineConfig cfg) { return List.copyOf(batches); }
        public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
        public List<Map<String, String>> lineage(PipelineConfig cfg, String b) { return List.of(); }
        public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        @Override
        public Optional<Map<String, String>> consignment(PipelineConfig cfg, String id) {
            asked.merge(cfg.identity().pipelineName(), 1, Integer::sum);
            return StatusStore.super.consignment(cfg, id);
        }
    }

    /** The parked manifest of {@code id}, so a PARKED ledger row has a {@code parked} block to serve. */
    private static PipelineConfig parkManifest(Ctx c, String id) throws Exception {
        PipelineConfig cfg = c.svc.loadedPipelines().get(0);
        assertNotNull(cfg.dirs().manifestsDir(), "the test pipeline keeps manifests");
        ConsignmentManifest m = new ConsignmentManifest();
        m.batchId = id;
        m.pipeline = cfg.identity().pipelineName();
        m.members = List.of();
        m.parkedAt = List.of("sink_b");
        m.parkedTables = Map.of("sink_b", "park/" + id + "_sink_b");
        ManifestStore.write(cfg.dirs().manifestsDir(), m);
        return cfg;
    }

    /**
     * Re-review: a drain appends the SUCCESS row after the PARKED row of one Consignment. The trail is served from
     * the NEWEST row — through the default by-id read (the file-backed store's path).
     */
    @Test
    void aDrainedConsignmentIsServedByItsNewestLedgerRow_storeDefault(@TempDir Path dir) throws Exception {
        ListStatusStore ledger = new ListStatusStore();
        try (Ctx c = open(dir, ledger)) {
            parkManifest(c, "c-drained");
            ledger.batches.add(Map.of("consignment_id", "c-drained", "status", "PARKED"));
            JsonNode parked = trail(c.port, "c-drained");
            assertEquals("PARKED", parked.get("status").asText(), parked.toString());
            assertEquals("sink_b", parked.get("parked").get("at").get(0).asText(), "the setup serves a parked block");

            ledger.batches.add(Map.of("consignment_id", "c-drained", "status", "SUCCESS"));
            JsonNode drained = trail(c.port, "c-drained");
            assertEquals("SUCCESS", drained.get("status").asText(), "the drain's row, not the stale PARKED one: " + drained);
            assertFalse(drained.has("parked"), drained.toString());
        }
    }

    /** As above, through {@code DbStatusStore}'s by-id query. */
    @Test
    void aDrainedConsignmentIsServedByItsNewestLedgerRow_dbStore(@TempDir Path dir) throws Exception {
        java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:duckdb:");
        try (Ctx c = open(dir, new com.gamma.service.DbStatusStore(conn))) {
            PipelineConfig cfg = parkManifest(c, "c-drained");
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String insert = "INSERT INTO inspecto_status_batches VALUES (?, ?, ?)";
            try (var ps = conn.prepareStatement(insert)) {
                ps.setString(1, cfg.identity().pipelineName());
                ps.setLong(2, 1);
                ps.setString(3, mapper.writeValueAsString(Map.of("consignment_id", "c-drained", "status", "PARKED")));
                ps.executeUpdate();
            }
            JsonNode parked = trail(c.port, "c-drained");
            assertEquals("PARKED", parked.get("status").asText(), parked.toString());
            assertTrue(parked.has("parked"), "the setup serves a parked block: " + parked);

            try (var ps = conn.prepareStatement(insert)) {
                ps.setString(1, cfg.identity().pipelineName());
                ps.setLong(2, 2);
                ps.setString(3, mapper.writeValueAsString(Map.of("consignment_id", "c-drained", "status", "SUCCESS")));
                ps.executeUpdate();
            }
            JsonNode drained = trail(c.port, "c-drained");
            assertEquals("SUCCESS", drained.get("status").asText(), "the drain's row, not the stale PARKED one: " + drained);
            assertFalse(drained.has("parked"), drained.toString());
        }
    }

    /** {@code stepsTotal} is the stored count: a receipt this build cannot read is skipped from the page, not the total. */
    @Test
    void stepsTotalCountsAnUnreadableReceipt(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            c.store.recordSteps(List.of(receipt("c-bad", "run-b", 1, "src", null)));
            c.store.browseSource().run(conn -> {
                try (var st = conn.createStatement()) {
                    st.execute("INSERT INTO consignment_steps (consignment_id, run_id, pipeline, lane, seq, receipt) "
                            + "VALUES ('c-bad', 'run-b', 'cdr_rollup', 'job', 2, 'not a receipt')");
                }
            });
            JsonNode body = trail(c.port, "c-bad");
            assertEquals(1, body.get("steps").size(), body.toString());
            assertEquals(2, body.get("stepsTotal").asInt(), "the stored count, not the parsed page: " + body);
        }
    }

    /** The hint Pipeline (the registry's producer) is asked for the ledger row once, never again in the scan. */
    @Test
    void theHintPipelineIsAskedOnce(@TempDir Path dir) throws Exception {
        ListStatusStore ledger = new ListStatusStore();
        try (Ctx c = open(dir, ledger)) {
            String name = c.svc.loadedPipelines().get(0).identity().pipelineName();
            c.store.record(List.of(output("c-hinted", "run-h", "cdr", name)));
            trail(c.port, "c-hinted");
            assertEquals(Map.of(name, 1), ledger.asked, "asked once, though it is both the hint and registered");
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
                    "commit failed: password=hunter2", Map.of("dryRun", false, "status", "FAILED", "durationMs", 0L), 1).toEvent());

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

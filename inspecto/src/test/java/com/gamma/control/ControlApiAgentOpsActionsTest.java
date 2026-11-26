package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.alert.AlertRule;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.job.JobConfig;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGENT-KIT-1 widening plan W7 (D-AK11 signed 2026-11-25) over real HTTP: evaluating the Expectations (all, or one) and
 * the Alert Rules, and a by-day Job's backlog (drain, pause, resume, run one day, give up on one day) are action routes.
 * A person's agent acts at once within the person's rights and its rows name the agent client; a business user's agent
 * gets the route's own refusal; a machine agent's call answers 202 with a Pending Action — carrying the action and its
 * reason for the approver — and nothing changes until a person approves; a declined give-up discards nothing; a machine
 * without {@code canOperateRuns} gets the route's own 403 and leaves no record.
 */
class ControlApiAgentOpsActionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** AGENT = op-1 (power) through agent client claude-code; BOT = machine agent ops-bot (power); VIEWER_AGENT = a business user's agent. */
    private static final String AGENT = "Bearer agent", BOT = "Bearer bot", CHECKER = "Bearer checker",
            ADMIN = "Bearer admin", VIEWER_AGENT = "Bearer viewer-agent", WEAK_BOT = "Bearer weak-bot";
    private static final String JOB = "w7_enrich", DAY = "2026-11-22", EXPECTATION = "w7_ids";
    private static final AlertRule RULE = new AlertRule("low-revenue", null, "lt", 1000, null, "WARNING", null,
            "sales_ds", "sum(amount)");
    private final HttpClient client = HttpClient.newHttpClient();
    /** The Expectation's Dataset, under the legacy data root ({@code database/}); unique per test, removed after. */
    private final String target = "w7_ops_" + System.nanoTime();

    @TempDir
    static Path auditDir;

    private record Ctx(CollectorService svc, ControlApi api, int port, DbConsignmentOutputStore outputs) implements AutoCloseable {
        public void close() throws Exception {
            api.close();
            svc.close();
            ConsignmentOutputStores.use(null);
            outputs.close();
        }
    }

    @BeforeEach
    void arm() {
        System.setProperty("jobs.audit.dir", auditDir.resolve("jobs_audit").toString());
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            String role = VIEWER_AGENT.equals(h) || WEAK_BOT.equals(h) ? "business"
                    : AGENT.equals(h) || BOT.equals(h) ? "power" : "super";
            Roles.Def def = Roles.effective(ex).get(role);
            if (def == null) return Optional.empty();
            ComponentAccess.heldRoles(ex, Set.of(role));
            Set<String> caps = new java.util.HashSet<>(def.capabilities());
            return switch (h) {
                case AGENT -> {
                    caps.removeAll(Roles.AGENT_NEVER_CAPABILITIES);
                    yield Optional.of(new Subject("op-1", caps, null, Map.of(), null, null, null, "claude-code"));
                }
                case VIEWER_AGENT -> Optional.of(new Subject("viewer-1", caps, null, Map.of(), null, null, null, "claude-code"));
                case BOT, WEAK_BOT -> Optional.of(new Subject("client:ops-bot", caps, null, Map.of(), null, null, "svc-ops", "ops-bot"));
                case CHECKER -> Optional.of(new Subject("checker-1", caps));
                case ADMIN -> Optional.of(new Subject("admin-1", caps));
                default -> Optional.empty();
            };
        });
    }

    @AfterEach
    void disarm() throws Exception {
        Authenticators.forTest(null);
        Path data = Path.of("database").resolve(target);
        if (Files.exists(data))
            try (var paths = Files.walk(data)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
    }

    /**
     * One Space with: an Alert Rule whose measure breaches (evaluating it fires one Alert); an Expectation on a seeded
     * Dataset that passes; and a by-day Job {@link #JOB} whose input Stream holds day {@link #DAY}, settled.
     */
    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        JobConfig job = JobConfig.fromMap(Map.of("job", Map.of("name", JOB, "type", "pipeline", "input_stream", "w7_in",
                "pipeline", "w7_enrich_flow", "schedule", Map.of("mode", "by_day", "quiet", "5m"))));
        Path root = Files.createDirectories(dir.resolve("root"));
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(job), List.of(), List.of(),
                    3600, 1, null);
            svc.alertService().orElseThrow().measureProbe((d, m) -> OptionalDouble.of(750));
            svc.alertService().orElseThrow().upsert(RULE);
            DbConsignmentOutputStore outputs = DbConsignmentOutputStore.open("jdbc:duckdb:" + dir.resolve("outputs.duckdb"));
            ConsignmentOutputStores.use(outputs);
            outputs.record(List.of(new ConsignmentOutput("c1", "run1", "w7_in", "dt=" + DAY, DAY,
                    dir.resolve("w7_in/22.parquet").toString(), 10, 100, Instant.now().minusSeconds(600).toString(),
                    ConsignmentOutput.State.LIVE, null, null, "w7_in")));
            Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("admin-1", "checker-1"))));
            Roles.write(root, Map.of(), List.of(), List.of("ops-bot"));   // the Space admits the machine client
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), outputs);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth, String... headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        if (headers.length > 0) b.headers(headers);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private List<String> audit(Ctx c, String needle) throws Exception {
        List<String> rows = new ArrayList<>();
        for (JsonNode row : data(send(c, "GET", "/audit/search?type=AUDIT&limit=500", null, ADMIN), 200))
            if (row.toString().contains(needle)) rows.add(row.toString());
        return rows;
    }

    private void expectation(Ctx c) throws Exception {
        Path part = Files.createDirectories(Path.of("database").resolve(target).resolve("p=0"));
        String parquet = part.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("w7_ops_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT 1 AS ID UNION ALL SELECT 2) TO '" + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        data(send(c, "POST", "/expectations", "{\"name\":\"" + EXPECTATION + "\",\"kind\":\"non_null\",\"target\":\""
                + target + "\",\"column\":\"ID\"}", ADMIN), 200);
    }

    /** The Expectation's saved result status, or {@code null} while it has none. */
    private String lastResult(Ctx c) throws Exception {
        for (JsonNode e : data(send(c, "GET", "/expectations", null, ADMIN), 200))
            if (EXPECTATION.equals(e.path("name").asText()))
                return e.path("lastResult").isObject() ? e.path("lastResult").path("status").asText() : null;
        throw new AssertionError("no expectation " + EXPECTATION);
    }

    private int alerts(Ctx c) throws Exception {
        return data(send(c, "GET", "/alerts", null, ADMIN), 200).size();
    }

    private JsonNode backlog(Ctx c) throws Exception {
        for (JsonNode j : data(send(c, "GET", "/jobs/backlog", null, ADMIN), 200))
            if (JOB.equals(j.path("jobName").asText())) return j;
        throw new AssertionError("no by-day Job " + JOB + " on the backlog");
    }

    private boolean dayListed(Ctx c) throws Exception {
        for (JsonNode d : backlog(c).path("days")) if (DAY.equals(d.path("day").asText())) return true;
        return false;
    }

    private JsonNode approve(Ctx c, String id) throws Exception {
        JsonNode approved = data(send(c, "POST", "/pending-actions/" + id + "/approve", "{\"reason\":\"ok\"}", CHECKER), 200);
        assertTrue(approved.get("ran").asBoolean(), approved.toString());
        return approved;
    }

    private static final List<String> EVALUATE = List.of("/expectations/evaluate", "/expectations/" + EXPECTATION + "/evaluate",
            "/alerts/evaluate");
    private static final List<String> BACKLOG = List.of("/jobs/" + JOB + "/backlog/drain", "/jobs/" + JOB + "/backlog/pause",
            "/jobs/" + JOB + "/backlog/resume", "/jobs/" + JOB + "/backlog/days/" + DAY + "/run",
            "/jobs/" + JOB + "/backlog/days/" + DAY + "/give-up");

    @Test
    void aPersonsAgentEvaluatesAtOnceWithinThePersonsRights(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            expectation(c);
            for (String path : EVALUATE)
                assertEquals(403, send(c, "POST", path, "{}", VIEWER_AGENT).statusCode(), "never beyond the person's rights: " + path);
            assertNull(lastResult(c), "a refused evaluation saved nothing");
            assertEquals(0, alerts(c), "a refused evaluation fired nothing");

            JsonNode one = data(send(c, "POST", "/expectations/" + EXPECTATION + "/evaluate", "{}", AGENT), 200);
            assertEquals("PASSED", one.at("/lastResult/status").asText(), one.toString());
            JsonNode all = data(send(c, "POST", "/expectations/evaluate", "{}", AGENT), 200);
            assertEquals(1, all.size(), all.toString());
            assertEquals(1, data(send(c, "POST", "/alerts/evaluate", "{}", AGENT), 200).size(), "the breach fired");
            assertEquals(1, alerts(c));
            for (String path : EVALUATE)
                assertTrue(audit(c, path).stream().anyMatch(r -> r.contains("\"agent_client\":\"claude-code\"")),
                        "the row of " + path + " names the agent client");
        }
    }

    @Test
    void aMachinesEvaluationIsHeldWithItsReasonUntilAPersonApprovesIt(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            expectation(c);
            for (String path : EVALUATE) assertEquals(403, send(c, "POST", path, "{}", WEAK_BOT).statusCode(), path);
            assertEquals(0, data(send(c, "GET", "/pending-actions", null, CHECKER), 200).get("total").asInt(),
                    "no record for a machine that may not operate runs");

            JsonNode held = data(send(c, "POST", "/expectations/" + EXPECTATION + "/evaluate", "{}", BOT,
                    "X-Change-Reason", "the source was re-delivered"), 202);
            assertFalse(held.get("ran").asBoolean(), held.toString());
            assertEquals("POST /expectations/" + EXPECTATION + "/evaluate", held.at("/pendingAction/action").asText());
            assertNull(lastResult(c), "held: nothing evaluated");
            String id = held.at("/pendingAction/id").asText();
            // the approver's list and read show the action's name and the machine's reason
            JsonNode listed = data(send(c, "GET", "/pending-actions?status=pending", null, CHECKER), 200).get("items").get(0);
            assertEquals("POST /expectations/" + EXPECTATION + "/evaluate", listed.get("action").asText(), listed.toString());
            assertEquals("the source was re-delivered", listed.get("reason").asText(), listed.toString());
            assertEquals("the source was re-delivered",
                    data(send(c, "GET", "/pending-actions/" + id, null, CHECKER), 200).get("reason").asText());
            approve(c, id);
            assertEquals("PASSED", lastResult(c), "approved: it evaluated");

            String all = data(send(c, "POST", "/expectations/evaluate", "{}", BOT), 202).at("/pendingAction/id").asText();
            String fire = data(send(c, "POST", "/alerts/evaluate", "{}", BOT), 202).at("/pendingAction/id").asText();
            assertEquals(0, alerts(c), "held: no Alert fired");
            approve(c, all);
            approve(c, fire);
            assertEquals(1, alerts(c), "approved: the breach fired");
        }
    }

    @Test
    void aPersonsAgentWorksTheBacklogAtOnceWithinThePersonsRights(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertTrue(dayListed(c), "the seeded day is on the backlog: " + backlog(c));
            for (String path : BACKLOG)
                assertEquals(403, send(c, "POST", path, "{}", VIEWER_AGENT).statusCode(), "never beyond the person's rights: " + path);
            assertFalse(backlog(c).get("paused").asBoolean());
            assertTrue(dayListed(c), "a refused give-up discarded nothing");

            assertTrue(data(send(c, "POST", "/jobs/" + JOB + "/backlog/pause", "{}", AGENT), 200).get("paused").asBoolean());
            assertTrue(backlog(c).get("paused").asBoolean(), "D-AK5: a person's agent acts at once");
            data(send(c, "POST", "/jobs/" + JOB + "/backlog/resume", "{}", AGENT), 200);
            assertFalse(backlog(c).get("paused").asBoolean());
            JsonNode gaveUp = data(send(c, "POST", "/jobs/" + JOB + "/backlog/days/" + DAY + "/give-up", "{}", AGENT), 200);
            assertTrue(gaveUp.get("skipped").asBoolean(), gaveUp.toString());
            assertFalse(dayListed(c), "the day was given up");
            // nothing is left: each reaches its handler, which finds no day to run
            assertEquals(0, data(send(c, "POST", "/jobs/" + JOB + "/backlog/drain", "{}", AGENT), 200).get("submitted").asInt());
            assertFalse(data(send(c, "POST", "/jobs/" + JOB + "/backlog/days/" + DAY + "/run", "{}", AGENT), 200)
                    .get("submitted").asBoolean());
            for (String path : BACKLOG)
                assertTrue(audit(c, path).stream().anyMatch(r -> r.contains("\"agent_client\":\"claude-code\"")),
                        "the row of " + path + " names the agent client");
        }
    }

    @Test
    void aMachinesGiveUpIsHeldAndADeclinedOneDiscardsNothing(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String path : BACKLOG) assertEquals(403, send(c, "POST", path, "{}", WEAK_BOT).statusCode(), path);
            assertEquals(0, data(send(c, "GET", "/pending-actions", null, CHECKER), 200).get("total").asInt(),
                    "no record for a machine that may not operate runs");

            String giveUp = "/jobs/" + JOB + "/backlog/days/" + DAY + "/give-up";
            JsonNode held = data(send(c, "POST", giveUp, "{}", BOT, "X-Change-Reason", "the reference will never arrive"), 202);
            assertEquals("POST " + giveUp, held.at("/pendingAction/action").asText(), held.toString());
            assertEquals("the reference will never arrive", held.at("/pendingAction/reason").asText());
            assertTrue(dayListed(c), "held: the day is still waiting");
            String first = held.at("/pendingAction/id").asText();
            assertEquals("declined", data(send(c, "POST", "/pending-actions/" + first + "/decline", "{}", CHECKER), 200)
                    .at("/pendingAction/status").asText());
            assertTrue(dayListed(c), "declined: nothing was discarded");

            String pause = data(send(c, "POST", "/jobs/" + JOB + "/backlog/pause", "{}", BOT), 202).at("/pendingAction/id").asText();
            assertFalse(backlog(c).get("paused").asBoolean(), "held: not paused");
            approve(c, pause);
            assertTrue(backlog(c).get("paused").asBoolean(), "approved: paused");

            String second = data(send(c, "POST", giveUp, "{}", BOT), 202).at("/pendingAction/id").asText();
            approve(c, second);
            assertFalse(dayListed(c), "approved: the day was given up");
        }
    }
}

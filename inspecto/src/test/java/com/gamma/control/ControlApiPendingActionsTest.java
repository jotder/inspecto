package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Operational actions and Pending Actions over real HTTP (AGENT-KIT-1 AK-2d, plan §4.6 and §6.2 lane B, D-AK5 and D-AK6
 * as signed): a person's agent pauses and resumes a run at once, within the person's rights, its rows naming the agent
 * client; a machine agent's pause is held as a Pending Action (202, nothing ran) that the machine never approves, the
 * change approvers decide (four eyes) and that then runs as the machine; a person without the run capability gets
 * nothing through an agent; the machine may withdraw its own; a record naming a non-action route dispatches nothing.
 */
class ControlApiPendingActionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** AGENT = op-1 (power) through agent client claude-code; BOT = machine agent ops-bot (power); VIEWER_AGENT = a business user's agent. */
    private static final String AGENT = "Bearer agent", BOT = "Bearer bot", CHECKER = "Bearer checker",
            ADMIN = "Bearer admin", VIEWER_AGENT = "Bearer viewer-agent", WEAK_BOT = "Bearer weak-bot";
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir
    static Path auditDir;

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
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
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        Path root = Files.createDirectories(dir.resolve("root"));
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("admin-1", "checker-1"))));
            Roles.write(root, Map.of(), List.of(), List.of("ops-bot"));   // the Space admits the machine client
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private String pipeline(Ctx c) throws Exception {
        JsonNode runs = data(send(c, "GET", "/runs", null, ADMIN), 200);
        JsonNode first = runs.isArray() ? runs.get(0) : runs.get("items").get(0);
        return first.get("name").asText();
    }

    private boolean paused(Ctx c, String name) throws Exception {
        JsonNode runs = data(send(c, "GET", "/runs", null, ADMIN), 200);
        for (JsonNode r : runs.isArray() ? runs : runs.get("items"))
            if (name.equals(r.get("name").asText())) return r.path("paused").asBoolean();
        throw new AssertionError("no run " + name);
    }

    private List<String> audit(Ctx c, String needle) throws Exception {
        List<String> rows = new ArrayList<>();
        for (JsonNode row : data(send(c, "GET", "/audit/search?type=AUDIT&limit=500", null, ADMIN), 200))
            if (row.toString().contains(needle)) rows.add(row.toString());
        return rows;
    }

    @Test
    void aPersonsAgentActsAtOnceWithinThePersonsRights(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String run = pipeline(c);
            JsonNode p = data(send(c, "POST", "/runs/" + run + "/pause", "{}", AGENT), 200);
            assertTrue(p.get("paused").asBoolean(), p.toString());
            assertTrue(paused(c, run), "D-AK5: a person's agent acts at once");
            data(send(c, "POST", "/runs/" + run + "/resume", "{}", AGENT), 200);
            assertFalse(paused(c, run));
            assertTrue(audit(c, "/runs/" + run + "/pause").stream().anyMatch(r -> r.contains("\"agent_client\":\"claude-code\"")),
                    "the action's row names the agent client");
            HttpResponse<String> viewer = send(c, "POST", "/runs/" + run + "/pause", "{}", VIEWER_AGENT);
            assertEquals(403, viewer.statusCode(), "never beyond the person's own rights: " + viewer.body());
            assertFalse(paused(c, run));
        }
    }

    @Test
    void aMachineAgentsActionIsHeldUntilAnotherPersonApprovesItThenRunsAsTheMachine(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String run = pipeline(c);
            JsonNode held = data(send(c, "POST", "/runs/" + run + "/pause", "{}", BOT), 202);
            assertFalse(held.get("ran").asBoolean(), held.toString());
            assertEquals("ops-bot", held.at("/pendingAction/agentClient").asText(), held.toString());
            assertEquals("POST /runs/" + run + "/pause", held.at("/pendingAction/action").asText());
            assertFalse(paused(c, run), "nothing ran");
            String id = held.at("/pendingAction/id").asText();
            assertTrue(id.startsWith("pa-"), id);

            assertEquals(409, send(c, "POST", "/runs/" + run + "/pause", "{}", BOT).statusCode(), "one pending per action");
            assertEquals(403, send(c, "POST", "/pending-actions/" + id + "/approve", "{}", BOT).statusCode(),
                    "a machine agent never approves");
            JsonNode listed = data(send(c, "GET", "/pending-actions?status=pending", null, CHECKER), 200);
            assertEquals(1, listed.get("total").asInt(), listed.toString());
            assertFalse(listed.get("items").get(0).has("request"), "the list carries no raw request");

            JsonNode approved = data(send(c, "POST", "/pending-actions/" + id + "/approve", "{\"reason\":\"ok\"}", CHECKER), 200);
            assertTrue(approved.get("ran").asBoolean(), approved.toString());
            assertEquals("approved", approved.at("/pendingAction/status").asText());
            assertTrue(paused(c, run), "approved: it ran");
            assertTrue(audit(c, "pending-action.approved").stream().anyMatch(r -> r.contains(id)), "audited");
            assertEquals(409, send(c, "POST", "/pending-actions/" + id + "/approve", "{}", ADMIN).statusCode(),
                    "an action never runs twice from one approval");
        }
    }

    @Test
    void theMachineWithdrawsItsOwnAndADeclinedActionNeverRuns(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String run = pipeline(c);
            String first = data(send(c, "POST", "/runs/" + run + "/pause", "{}", BOT), 202).at("/pendingAction/id").asText();
            assertEquals(403, send(c, "POST", "/pending-actions/" + first + "/withdraw", "{}", CHECKER).statusCode(),
                    "only the one who asked withdraws");
            JsonNode w = data(send(c, "POST", "/pending-actions/" + first + "/withdraw", "{}", BOT), 200);
            assertEquals("withdrawn", w.at("/pendingAction/status").asText());

            String second = data(send(c, "POST", "/runs/" + run + "/pause", "{}", BOT), 202).at("/pendingAction/id").asText();
            JsonNode d = data(send(c, "POST", "/pending-actions/" + second + "/decline", "{}", CHECKER), 200);
            assertEquals("declined", d.at("/pendingAction/status").asText());
            assertFalse(paused(c, run), "declined: nothing ran");
        }
    }

    @Test
    void aMachineWithoutTheRunCapabilityIsRefusedAndLeavesNoRecord(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String run = pipeline(c);
            HttpResponse<String> r = send(c, "POST", "/runs/" + run + "/pause", "{}", WEAK_BOT);
            assert403(r);
            assertEquals(0, data(send(c, "GET", "/pending-actions", null, CHECKER), 200).get("total").asInt(),
                    "no record for a machine that may not operate runs");
        }
    }

    @Test
    void actionsOnTheSameRunWithDifferentBodiesAreDifferentActions(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String run = pipeline(c);
            data(send(c, "POST", "/runs/" + run + "/drain", "{\"batchId\":\"a\"}", BOT), 202);
            data(send(c, "POST", "/runs/" + run + "/drain", "{\"batchId\":\"b\"}", BOT), 202);
            assertEquals(409, send(c, "POST", "/runs/" + run + "/drain", "{\"batchId\":\"a\"}", BOT).statusCode(),
                    "the same drain of the same batch is already waiting");
        }
    }

    private static void assert403(HttpResponse<String> r) {
        assertEquals(403, r.statusCode(), r.body());
    }

    @Test
    void aRecordNamingANonActionRouteDispatchesNothing() {
        Map<String, Object> rec = new java.util.LinkedHashMap<>();
        rec.put("route", "POST /runs/([^/]+)/pause");
        rec.put("request", Map.of("method", "POST", "path", "/runs/orders/pause"));
        assertTrue(PendingActions.actionRoute(rec));
        rec.put("request", Map.of("method", "POST", "path", "/settings/approval"));
        assertFalse(PendingActions.actionRoute(rec), "the path must match the recorded action route");
        rec.put("route", "PUT /settings/approval");
        rec.put("request", Map.of("method", "PUT", "path", "/settings/approval"));
        assertFalse(PendingActions.actionRoute(rec), "a route outside the action bucket is never replayed");
    }
}

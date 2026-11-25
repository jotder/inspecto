package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.workflow.ObjectType;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Case actions over real HTTP (AGENT-KIT-1 AK-2e, plan §4.6 analyst rows, D-AK5 and D-AK6 as signed; operator
 * 2026-11-25: the §4.6 list, a new Case for people only): a person's agent comments on, accepts and assigns an Incident at
 * once, within the person's rights, its rows naming the agent client; a business user's agent gets nothing; a machine
 * agent's Case transition and comment are held as Pending Actions (202, nothing changed) that a change approver
 * approves, and then run as the machine; a machine agent's new Case from entities is held as a Pending Action too (it
 * keeps {@code canManageIncidents} since W2 step 3, operator 2026-11-25).
 */
class ControlApiAgentCaseActionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** AGENT = analyst-1 (power) through agent client claude-code; BOT = machine agent triage-bot (power role). */
    private static final String AGENT = "Bearer agent", VIEWER_AGENT = "Bearer viewer-agent", BOT = "Bearer bot",
            CHECKER = "Bearer checker", ADMIN = "Bearer admin", WEAK_BOT = "Bearer weak-bot";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            String role = VIEWER_AGENT.equals(h) || WEAK_BOT.equals(h) ? "business" : AGENT.equals(h) || BOT.equals(h) ? "power" : "super";
            ComponentAccess.heldRoles(ex, Set.of(role));
            Set<String> caps = new HashSet<>(Roles.SEED.get(role).capabilities());
            caps.removeAll(AGENT.equals(h) || VIEWER_AGENT.equals(h) || BOT.equals(h) || WEAK_BOT.equals(h) ? Roles.AGENT_NEVER_CAPABILITIES : Set.of());
            return switch (h) {
                case AGENT -> Optional.of(new Subject("analyst-1", caps, null, Map.of(), null, null, null, "claude-code"));
                case VIEWER_AGENT -> Optional.of(new Subject("viewer-1", caps, null, Map.of(), null, null, null, "claude-code"));
                case BOT, WEAK_BOT -> Optional.of(new Subject("client:triage-bot", caps, null, Map.of(), null, null, "svc-triage", "triage-bot"));
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
        Path toon = TestConfigs.csv(Files.createDirectories(dir.resolve("cfg")), PipelineConfigBatchTest.miniSchema()).write();
        Path root = Files.createDirectories(dir.resolve("root"));
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("admin-1", "checker-1"))));
            Roles.write(root, Map.of(), List.of(), List.of("triage-bot"));   // the Space admits the machine client
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

    private static String open(Ctx c, ObjectType type, String title) {
        return TestOpsEngine.of(c.svc).open(type, title, "d", "MAJOR", null, Map.of()).id();
    }

    private static String status(Ctx c, String id) {
        return TestOpsEngine.of(c.svc).get(id).orElseThrow().status();
    }

    @Test
    void aPersonsAgentWorksAnIncidentAtOnceWithinThePersonsRights(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = open(c, ObjectType.INCIDENT, "late feed");
            data(send(c, "POST", "/objects/" + id + "/comments", "{\"body\":\"the feed is late again\"}", AGENT), 200);
            data(send(c, "POST", "/objects/" + id + "/transition", "{\"action\":\"accept\"}", AGENT), 200);
            assertEquals("DIAGNOSING", status(c, id));
            data(send(c, "POST", "/objects/" + id + "/assign", "{\"assignee\":\"dana\"}", AGENT), 200);
            assertEquals("dana", TestOpsEngine.of(c.svc).get(id).orElseThrow().assignee(), "D-AK5: it acted at once");
            boolean named = false;
            for (JsonNode row : data(send(c, "GET", "/audit/search?type=AUDIT&limit=500", null, ADMIN), 200))
                named |= row.toString().contains("/objects/" + id + "/assign") && row.toString().contains("\"agent_client\":\"claude-code\"");
            assertTrue(named, "the action's row names the agent client");
            JsonNode comments = data(send(c, "GET", "/objects/" + id + "/comments", null, ADMIN), 200);
            assertEquals("analyst-1", comments.get(0).get("author").asText(), "the comment is the person's");

            String before = status(c, id);
            HttpResponse<String> viewer = send(c, "POST", "/objects/" + id + "/ack", "{}", VIEWER_AGENT);
            assertEquals(403, viewer.statusCode(), "never beyond the person's own rights: " + viewer.body());
            assertEquals(before, status(c, id));
            assertEquals(0, data(send(c, "GET", "/pending-actions", null, CHECKER), 200).get("total").asInt(),
                    "a person's agent is never held");
        }
    }

    @Test
    void aMachinesCaseTransitionIsHeldThenRunsAsTheMachineOnceApproved(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = open(c, ObjectType.CASE, "suspicious refunds");
            String before = status(c, id);
            JsonNode held = data(send(c, "POST", "/objects/" + id + "/transition", "{\"action\":\"investigate\"}", BOT), 202);
            assertFalse(held.get("ran").asBoolean(), held.toString());
            assertEquals("POST /objects/" + id + "/transition", held.at("/pendingAction/action").asText());
            assertEquals(before, status(c, id), "nothing changed while it waits");
            String pa = held.at("/pendingAction/id").asText();

            assertEquals(403, send(c, "POST", "/pending-actions/" + pa + "/approve", "{}", BOT).statusCode(),
                    "a machine agent never approves");
            JsonNode one = data(send(c, "GET", "/pending-actions/" + pa, null, CHECKER), 200);
            assertEquals("{\"action\":\"investigate\"}", one.at("/request/body").asText(), "the approver sees the move");

            JsonNode approved = data(send(c, "POST", "/pending-actions/" + pa + "/approve", "{}", CHECKER), 200);
            assertTrue(approved.get("ran").asBoolean(), approved.toString());
            assertEquals("INVESTIGATING", status(c, id), "approved: it ran");
        }
    }

    @Test
    void aMachinesCommentIsHeldAndOnApprovalIsTheMachines(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = open(c, ObjectType.INCIDENT, "late feed");
            String pa = data(send(c, "POST", "/objects/" + id + "/comments", "{\"body\":\"source host unreachable\"}", BOT), 202)
                    .at("/pendingAction/id").asText();
            assertEquals(0, data(send(c, "GET", "/objects/" + id + "/comments", null, ADMIN), 200).size(),
                    "nothing posted while it waits");
            data(send(c, "POST", "/pending-actions/" + pa + "/approve", "{}", CHECKER), 200);
            JsonNode comments = data(send(c, "GET", "/objects/" + id + "/comments", null, ADMIN), 200);
            assertEquals(1, comments.size(), comments.toString());
            assertEquals("client:triage-bot", comments.get(0).get("author").asText(), "the replay runs as the machine");
        }
    }

    @Test
    void aMachineWithNoRightCannotQueueCommentsOnAnyId(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // a comment needs no capability, but the hold runs before the scope guard: a machine without canWorkIncidents
            // must not fill the approvers' queue (phase-3 security review)
            for (String id : List.of(open(c, ObjectType.INCIDENT, "late feed"), "no-such-object")) {
                HttpResponse<String> r = send(c, "POST", "/objects/" + id + "/comments", "{\"body\":\"x\"}", WEAK_BOT);
                assertEquals(403, r.statusCode(), r.body());
            }
            assertEquals(0, data(send(c, "GET", "/pending-actions", null, CHECKER), 200).get("total").asInt(), "no record");
        }
    }

    /**
     * Until AK-2 widening W2 step 3 a machine agent never kept {@code canManageIncidents} and was refused a new Case. The
     * operator's decision of 2026-11-25 (W2 step 3, D-AK9 Entity List edits, granted globally) lets it keep the right, so
     * its request for a new Case is now HELD as a Pending Action: nothing is opened until a person approves.
     */
    @Test
    void aMachinesNewCaseFromEntitiesIsHeldNotRun(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String body = "{\"title\":\"ring\",\"entities\":[{\"id\":\"+15550001\",\"dataset\":\"calls\",\"label\":\"A\"}]}";
            JsonNode held = data(send(c, "POST", "/cases/from-entities", body, BOT), 202);
            assertFalse(held.get("ran").asBoolean(), held.toString());
            assertEquals("POST /cases/from-entities", held.at("/pendingAction/action").asText(), held.toString());
            assertEquals("pending", held.at("/pendingAction/status").asText(), held.toString());
            assertEquals(403, send(c, "POST", "/pending-actions/" + held.at("/pendingAction/id").asText() + "/approve", "{}",
                    BOT).statusCode(), "a machine agent never approves");
        }
    }
}

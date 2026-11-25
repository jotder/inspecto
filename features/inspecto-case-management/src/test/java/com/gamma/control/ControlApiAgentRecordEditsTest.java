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
 * An agent edits an Incident's or Case's record over real HTTP (AGENT-KIT-1, widening plan W11, D-AK8 signed 2026-11-25):
 * for each of the six routes — postmortem, category, impact, Findings, RCA, link and unlink — a person's agent writes at
 * once, within the person's rights, its audit row naming the agent client; a business user's agent gets exactly what the
 * business user gets (the route's own answer); a machine agent's edit is held as a Pending Action (202, nothing changed)
 * that a change approver approves, and then applies; a machine without {@code canWorkIncidents} is refused on every route,
 * the {@code collaboration} ones included, and leaves no record. An agent cannot re-attribute a link or an RCA to someone
 * else through a body field.
 */
class ControlApiAgentRecordEditsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** AGENT = analyst-1 (power) through agent client claude-code; BOT = machine agent triage-bot (power role). */
    private static final String AGENT = "Bearer agent", VIEWER_AGENT = "Bearer viewer-agent", VIEWER = "Bearer viewer",
            BOT = "Bearer bot", CHECKER = "Bearer checker", ADMIN = "Bearer admin", WEAK_BOT = "Bearer weak-bot";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /** One record edit: the route's method and tail, a body, and how to see that it landed on {@code id}. */
    private record Edit(String name, String method, String tail, String body) {}

    /** The six routes, unlink as its query; {@code %s} is the other object of a link. */
    private static final List<Edit> EDITS = List.of(
            new Edit("postmortem", "PUT", "/postmortem", "{\"postmortem\":{\"summary\":\"feed host down 02:10-03:40\"}}"),
            new Edit("category", "PUT", "/category", "{\"category\":\"Billing / Rating / Late feed\"}"),
            new Edit("impact", "PUT", "/impact", "{\"impact\":{\"confirmed\":\"1250.00\",\"currency\":\"EUR\"}}"),
            new Edit("findings", "PUT", "/findings", "{\"findings\":{\"recordsAffected\":\"42\"}}"),
            new Edit("rca", "POST", "/rca", "{\"sections\":[\"What happened\",\"Why\"]}"),
            new Edit("link", "POST", "/links", "{\"to\":\"%s\"}"),
            new Edit("unlink", "DELETE", "/links?to=%s", null));

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            boolean business = VIEWER_AGENT.equals(h) || VIEWER.equals(h) || WEAK_BOT.equals(h);
            String role = business ? "business" : AGENT.equals(h) || BOT.equals(h) ? "power" : "super";
            ComponentAccess.heldRoles(ex, Set.of(role));
            Set<String> caps = new HashSet<>(Roles.SEED.get(role).capabilities());
            caps.removeAll(AGENT.equals(h) || VIEWER_AGENT.equals(h) || BOT.equals(h) || WEAK_BOT.equals(h) ? Roles.AGENT_NEVER_CAPABILITIES : Set.of());
            return switch (h) {
                case AGENT -> Optional.of(new Subject("analyst-1", caps, null, Map.of(), null, null, null, "claude-code"));
                case VIEWER_AGENT -> Optional.of(new Subject("viewer-1", caps, null, Map.of(), null, null, null, "claude-code"));
                case VIEWER -> Optional.of(new Subject("viewer-1", caps));
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

    private HttpResponse<String> edit(Ctx c, Edit e, String id, String other, String auth) throws Exception {
        return send(c, e.method(), "/objects/" + id + e.tail().formatted(other),
                e.body() == null ? null : e.body().formatted(other), auth);
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static String open(Ctx c, ObjectType type, String title) {
        return TestOpsEngine.of(c.svc).open(type, title, "d", "MAJOR", null, Map.of()).id();
    }

    /** The part of {@code id}'s record {@code e} writes, as it stands now (a link: whether {@code id → other} exists). */
    private String part(Ctx c, Edit e, String id, String other) throws Exception {
        var o = TestOpsEngine.of(c.svc).get(id).orElseThrow();
        return switch (e.name()) {
            case "postmortem", "category", "impact", "findings" -> String.valueOf(o.attributes().get(e.name()));
            case "rca" -> data(send(c, "GET", "/objects/" + id + "/comments", null, ADMIN), 200).toString();
            default -> String.valueOf(TestOpsEngine.of(c.svc).linksOf(id).stream().anyMatch(l -> l.toId().equals(other)));
        };
    }

    /** A Case and the Incident it may link to; for unlink, already linked (by an admin). */
    private String[] pair(Ctx c, Edit e) {
        String id = open(c, ObjectType.CASE, "suspicious refunds"), other = open(c, ObjectType.INCIDENT, "late feed");
        if (e.name().equals("unlink")) TestOpsEngine.of(c.svc).link(id, other, null, "admin-1");
        return new String[] {id, other};
    }

    private int pendingTotal(Ctx c) throws Exception {
        return data(send(c, "GET", "/pending-actions", null, CHECKER), 200).get("total").asInt();
    }

    @Test
    void aPersonsAgentEditsEachPartOfTheRecordAtOnceAndIsNamedOnTheAuditRow(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (Edit e : EDITS) {
                String[] p = pair(c, e);
                String before = part(c, e, p[0], p[1]);
                HttpResponse<String> r = edit(c, e, p[0], p[1], AGENT);
                assertEquals(200, r.statusCode(), e.name() + ": " + r.body());
                assertNotEquals(before, part(c, e, p[0], p[1]), e.name() + ": D-AK8, it wrote at once");
                String path = "/objects/" + p[0] + e.tail().replace("?to=%s", "");
                boolean named = false;
                for (JsonNode row : data(send(c, "GET", "/audit/search?type=AUDIT&limit=500", null, ADMIN), 200))
                    named |= row.toString().contains(path) && row.toString().contains("\"" + e.method() + "\"")
                            && row.toString().contains("\"agent_client\":\"claude-code\"");
                assertTrue(named, e.name() + ": the edit's audit row names the agent client");
            }
            assertEquals(0, pendingTotal(c), "a person's agent is never held");
        }
    }

    @Test
    void aBusinessUsersAgentGetsWhatTheBusinessUserGets(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (Edit e : EDITS) {
                String[] mine = pair(c, e), theirs = pair(c, e);
                int person = edit(c, e, mine[0], mine[1], VIEWER).statusCode();
                String before = part(c, e, theirs[0], theirs[1]);
                HttpResponse<String> agent = edit(c, e, theirs[0], theirs[1], VIEWER_AGENT);
                assertEquals(person, agent.statusCode(), e.name() + ": the route's own answer, never more: " + agent.body());
                if (Set.of("postmortem", "category", "impact").contains(e.name())) {
                    assertEquals(403, agent.statusCode(), e.name() + " needs canWorkIncidents");
                    assertEquals(before, part(c, e, theirs[0], theirs[1]), e.name() + ": refused, nothing written");
                }
            }
            assertEquals(0, pendingTotal(c));
        }
    }

    @Test
    void aMachinesEditIsHeldThenAppliesOnceAnotherPersonApproves(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (Edit e : EDITS) {
                String[] p = pair(c, e);
                String before = part(c, e, p[0], p[1]);
                JsonNode held = data(edit(c, e, p[0], p[1], BOT), 202);
                assertFalse(held.get("ran").asBoolean(), e.name() + ": " + held);
                assertEquals(e.method() + " /objects/" + p[0] + e.tail().replace("?to=%s", ""),
                        held.at("/pendingAction/action").asText(), e.name());
                assertEquals(before, part(c, e, p[0], p[1]), e.name() + ": nothing changed while it waits");
                String pa = held.at("/pendingAction/id").asText();
                assertEquals(403, send(c, "POST", "/pending-actions/" + pa + "/approve", "{}", BOT).statusCode(),
                        "a machine agent never approves");
                JsonNode one = data(send(c, "GET", "/pending-actions/" + pa, null, CHECKER), 200);
                assertEquals("/objects/" + p[0] + e.tail().formatted(p[1]), one.at("/request/path").asText(),
                        e.name() + ": the approver sees the whole request, query included");
                JsonNode approved = data(send(c, "POST", "/pending-actions/" + pa + "/approve", "{}", CHECKER), 200);
                assertTrue(approved.get("ran").asBoolean(), e.name() + ": " + approved);
                assertNotEquals(before, part(c, e, p[0], p[1]), e.name() + ": approved, it applied");
            }
        }
    }

    @Test
    void twoUnlinksOfDifferentIncidentsAreTwoPendingActionsNotADuplicate(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String kase = open(c, ObjectType.CASE, "suspicious refunds");
            String a = open(c, ObjectType.INCIDENT, "a"), b = open(c, ObjectType.INCIDENT, "b");
            TestOpsEngine.of(c.svc).link(kase, a, null, "admin-1");
            TestOpsEngine.of(c.svc).link(kase, b, null, "admin-1");
            data(send(c, "DELETE", "/objects/" + kase + "/links?to=" + a, null, BOT), 202);
            data(send(c, "DELETE", "/objects/" + kase + "/links?to=" + b, null, BOT), 202);
            assertEquals(409, send(c, "DELETE", "/objects/" + kase + "/links?to=" + a, null, BOT).statusCode(),
                    "the very same unlink again is the duplicate");
            assertEquals(2, pendingTotal(c));
        }
    }

    @Test
    void aMachineWithoutCanWorkIncidentsIsRefusedOnEveryRouteAndLeavesNoRecord(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // findings, rca, link and unlink are open to anyone who can see the object, but the hold runs before the
            // route's scope guard: without canWorkIncidents a machine must not fill the approvers' queue (W11 review)
            for (Edit e : EDITS) {
                String[] p = pair(c, e);
                for (String id : List.of(p[0], "no-such-object")) {
                    HttpResponse<String> r = edit(c, e, id, p[1], WEAK_BOT);
                    assertEquals(403, r.statusCode(), e.name() + " on " + id + ": " + r.body());
                }
            }
            assertEquals(0, pendingTotal(c), "no record");
        }
    }

    @Test
    void anAgentCannotReattributeALinkOrAnRcaThroughABodyField(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String kase = open(c, ObjectType.CASE, "suspicious refunds"), inc = open(c, ObjectType.INCIDENT, "late feed");
            data(send(c, "POST", "/objects/" + kase + "/rca", "{\"sections\":[\"Why\"],\"actor\":\"alice\"}", AGENT), 200);
            for (JsonNode n : data(send(c, "GET", "/objects/" + kase + "/comments", null, ADMIN), 200))
                assertEquals("analyst-1", n.get("author").asText(), "the RCA is the signed-in person's: " + n);
            List<String> actors = new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.function.BiConsumer<com.gamma.event.EventLog, com.gamma.audit.Event> tap = (log, ev) -> {
                if (kase.equals(ev.attributes().get("from")) && ev.attributes().containsKey("relationship"))
                    actors.add(String.valueOf(ev.attributes().get("actor")));
            };
            com.gamma.event.EventLog.addTap(tap);
            try {
                data(send(c, "POST", "/objects/" + kase + "/links", "{\"to\":\"" + inc + "\",\"actor\":\"alice\"}", AGENT), 200);
                data(send(c, "DELETE", "/objects/" + kase + "/links?to=" + inc + "&actor=alice", null, AGENT), 200);
            } finally {
                com.gamma.event.EventLog.removeTap(tap);
            }
            assertEquals(List.of("analyst-1", "analyst-1"), actors,
                    "the link and unlink events name the signed-in person, never the body's or the query's actor");
        }
    }
}

package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
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
 * The agent marker over real HTTP (AGENT-KIT-1 AK-2a, plan {@code 2026-11-20-agent-kit-ak2-plan.md} §4.1, D-AK1): an
 * agent's config write is held because of WHO writes — with no approval policy at all, through the route directly —
 * while the same person's own token writes; the host refuses an agent every route outside its buckets (a
 * {@code holdRefusing}-only import, a settings write) and every approval; four eyes always, so neither the agent nor its
 * person approves; another person's approval replays the change as the agent proposed it, and every audit row names the
 * verified agent client.
 */
class ControlApiAgentClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** AGENT = author-1 (pipeline-developer) through agent client claude-code; AUTHOR = the same person, own token. */
    private static final String AGENT = "Bearer agent", AUTHOR = "Bearer author", SELF = "Bearer self",
            CHECKER = "Bearer checker", ADMIN = "Bearer admin", AGENT_ADMIN = "Bearer agent-admin";
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
            String[] who = switch (h) {
                case AGENT, AUTHOR -> new String[] {"author-1", "pipeline-developer"};
                case SELF -> new String[] {"author-1", "admin"};
                case CHECKER -> new String[] {"checker-1", "admin"};
                case ADMIN -> new String[] {"admin-1", "admin"};
                // An agent token that still holds canApproveChanges: the gate and the route refuse it on their own,
                // whatever the Authenticator dropped (OidcAuthenticator drops it; this one deliberately does not).
                case AGENT_ADMIN -> new String[] {"admin-2", "admin"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            ComponentAccess.heldRoles(ex, Set.of(who[1]));
            String agent = AGENT.equals(h) || AGENT_ADMIN.equals(h) ? "claude-code" : null;
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes(), Map.of(), null, null, null, agent));
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
                    Map.of("users", List.of("admin-1", "admin-2", "author-1", "checker-1"))));
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

    private static String pack(String id) {
        return "{\"id\":\"" + id + "\",\"title\":\"Carousel\"}";
    }

    private boolean exists(Ctx c, String id) {
        return new ComponentStore(c.root.resolve("registry")).exists("pattern-pack", id);
    }

    /** The AUDIT / ACCESS_DENIED rows whose text contains {@code needle}, read by an administrator. */
    private List<String> audit(Ctx c, String type, String needle) throws Exception {
        List<String> rows = new ArrayList<>();
        for (JsonNode row : data(send(c, "GET", "/audit/search?type=" + type + "&limit=500", null, ADMIN), 200))
            if (row.toString().contains(needle)) rows.add(row.toString());
        return rows;
    }

    @Test
    void anAgentsDirectWriteIsHeldWithNoPolicyWhileTheSamePersonsOwnTokenWrites(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode held = data(send(c, "POST", "/components/pattern-pack", pack("agent-pack"), AGENT), 202);
            assertEquals("pending", held.get("status").asText(), held.toString());
            assertFalse(held.get("written").asBoolean());
            assertEquals("claude-code", held.at("/pendingChange/agentClient").asText(), held.toString());
            assertTrue(held.at("/pendingChange/fourEyes").asBoolean(), "four eyes always (D-AK1)");
            assertEquals("canApproveChanges", held.at("/pendingChange/approverCapability").asText());
            assertFalse(exists(c, "agent-pack"), "nothing written");

            data(send(c, "POST", "/components/pattern-pack", pack("own-pack"), AUTHOR), 200);
            assertTrue(exists(c, "own-pack"), "the person's own token is not held — no policy covers the kind");
        }
    }

    @Test
    void theHostRefusesAnAgentEveryRouteOutsideItsBucketsButNotItsReads(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // holdRefusing only: a bundle import does not hold when no policy covers its kinds, so an agent may not call it
            HttpResponse<String> imp = send(c, "POST", "/bundle/import", "{\"items\":[]}", AGENT);
            assertEquals(403, imp.statusCode(), imp.body());
            assertTrue(imp.body().contains("agent client"), imp.body());
            assertNotEquals(403, send(c, "POST", "/bundle/import", "{\"items\":[]}", AUTHOR).statusCode(),
                    "the person's own token reaches the route");
            HttpResponse<String> settings = send(c, "PUT", "/settings/approval", "{\"approval\":{}}", AGENT_ADMIN);
            assertEquals(403, settings.statusCode(), "an administrator's agent may not change settings: " + settings.body());
            assertEquals(200, send(c, "GET", "/pending-changes", null, AGENT).statusCode(), "reads stay open");
            // on the hold's route list, but a path writes before the hold: refused, never trusted to hold
            assertEquals(403, send(c, "POST", "/pipelines/orders/save-as-template", "{\"id\":\"t1\"}", AGENT).statusCode());
            assertEquals(403, send(c, "POST", "/origin/upgrade", "{}", AGENT_ADMIN).statusCode());
            assertFalse(audit(c, "ACCESS_DENIED", "/bundle/import").stream().noneMatch(r -> r.contains("claude-code")),
                    "the refusal is audited with the agent client");
        }
    }

    @Test
    void approvalIsFourEyesByAnotherPersonAndReplaysAsTheAgentProposedIt(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = data(send(c, "POST", "/components/pattern-pack", pack("p1"), AGENT), 202)
                    .at("/pendingChange/id").asText();
            HttpResponse<String> byAgent = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", AGENT_ADMIN);
            assertEquals(403, byAgent.statusCode(), "an agent token never approves: " + byAgent.body());
            HttpResponse<String> byPerson = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", SELF);
            assertEquals(403, byPerson.statusCode(), "the person the agent acts for never approves: " + byPerson.body());
            assertTrue(byPerson.body().contains("four-eyes"), byPerson.body());
            assertFalse(exists(c, "p1"));

            JsonNode approved = data(send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER), 200);
            assertTrue(approved.get("applied").asBoolean(), approved.toString());
            assertTrue(exists(c, "p1"), "another person's approval applies it");
            // The applied write's own row: the author (the person), the approver, and the agent client — which only the
            // replayed Subject can carry (the approver's own request has none).
            List<String> rows = audit(c, "AUDIT", "approvedBy");
            assertTrue(rows.stream().anyMatch(r -> r.contains("checker-1") && r.contains("author-1")
                    && r.contains("\"agent_client\":\"claude-code\"")), String.join("\n", rows));
        }
    }

    @Test
    void everyAuditRowOfAnAgentsRequestNamesTheAgentClient(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = data(send(c, "POST", "/components/pattern-pack", pack("p2"), AGENT), 202)
                    .at("/pendingChange/id").asText();
            // the hold's own row (PendingChanges.audit, not AuditTrail) and the route's 202 row
            List<String> proposed = audit(c, "AUDIT", "pending-change.proposed");
            assertTrue(proposed.stream().anyMatch(r -> r.contains(id) && r.contains("\"agent_client\":\"claude-code\"")),
                    String.join("\n", proposed));
            data(send(c, "POST", "/components/pattern-pack", pack("own-row"), AUTHOR), 200);
            List<String> own = audit(c, "AUDIT", "/components/pattern-pack").stream()
                    .filter(r -> r.contains("\"http_status\":\"200\"")).toList();   // the person's write, not the agent's 202
            assertFalse(own.isEmpty(), String.join("\n", audit(c, "AUDIT", "pattern-pack")));
            assertTrue(own.stream().noneMatch(r -> r.contains("agent_client")),
                    "a person's own row names no agent client:\n" + String.join("\n", own));
        }
    }

    @Test
    void anAgentMayWithdrawItsOwnProposal(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = data(send(c, "POST", "/components/pattern-pack", pack("p3"), AGENT), 202)
                    .at("/pendingChange/id").asText();
            JsonNode w = data(send(c, "POST", "/pending-changes/" + id + "/withdraw", "{}", AGENT), 200);
            assertEquals("withdrawn", w.at("/pendingChange/status").asText(), w.toString());
        }
    }
}

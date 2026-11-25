package com.gamma.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.control.ControlApi;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AK-2 widening plan W2 over real HTTP ({@code 2026-11-25-agent-kit-ak2-widening-plan.md} §3 W2, D-AK9): every kind that
 * joined {@code propose_change} is held. Per kind an agent's proposal answers a Pending Change and writes nothing, another
 * person's approval applies it, the same token's direct REST call is held the same way, and a machine agent's proposal is
 * held too; a kind outside the allowlist is refused by the tool before any replay.
 */
class ControlApiMcpProposeKindsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** AGENT = dev-1 (pipeline-developer) through agent client claude-code; BOT = the machine agent ops-bot; CHECKER approves. */
    private static final String AGENT = "Bearer agent", BOT = "Bearer bot", CHECKER = "Bearer checker";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }

        ComponentStore registry() {
            return new ComponentStore(root.resolve("registry"));
        }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            switch (h) {
                case AGENT -> {
                    Roles.Def def = Roles.effective(ex).get("pipeline-developer");
                    ComponentAccess.heldRoles(ex, Set.of("pipeline-developer"));
                    return Optional.of(new Subject("dev-1", def.capabilities(), def.dataScopes(), Map.of(), null, null, null,
                            "claude-code"));
                }
                case BOT -> {
                    // what OidcAuthenticator admits for a machine agent: its roles' grants, filtered to the machine agent set
                    Roles.Def admin = Roles.effective(ex).get("admin");
                    ComponentAccess.heldRoles(ex, Set.of("admin"));
                    Set<String> keeps = new LinkedHashSet<>(Roles.MACHINE_CAPABILITIES);
                    keeps.addAll(Roles.AGENT_MACHINE_CAPABILITIES);
                    Set<String> caps = new LinkedHashSet<>(admin.capabilities());
                    caps.retainAll(keeps);
                    return Optional.of(new Subject("client:ops-bot", caps, null, Map.of(), null, null, "svc-ops", "ops-bot"));
                }
                case CHECKER -> {
                    Roles.Def def = Roles.effective(ex).get("admin");
                    ComponentAccess.heldRoles(ex, Set.of("admin"));
                    return Optional.of(new Subject("checker-1", def.capabilities(), def.dataScopes()));
                }
                default -> {
                    return Optional.empty();
                }
            }
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
            Files.writeString(root.resolve("approvers.toon"), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("checker-1", "dev-1"))));
            Roles.write(root, Map.of(), List.of(), List.of("ops-bot"));   // the Space admits the machine client
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> rpc(int port, String auth, Map<String, Object> params) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>(params);
        p.put("_meta", Map.of(McpRoutes.META_VERSION, McpRoutes.MODERN,
                McpRoutes.META_CLIENT, Map.of("name", "TestAgent", "version", "1.0")));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/mcp"))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .header("MCP-Protocol-Version", McpRoutes.MODERN).header("Mcp-Method", "tools/call")
                .header("Mcp-Name", String.valueOf(p.get("name")))
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", p))));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** {@code propose_change} → the JSON-RPC {@code result}. */
    private JsonNode propose(int port, String auth, Map<String, Object> args) throws Exception {
        HttpResponse<String> res = rpc(port, auth, Map.of("name", "propose_change", "arguments", args));
        assertEquals(200, res.statusCode(), res.body());
        JsonNode result = JSON.readTree(res.body()).get("result");
        assertNotNull(result, res.body());
        return result;
    }

    private HttpResponse<String> rest(Ctx c, String method, String path, Object body, String auth) throws Exception {
        String json = body == null ? "" : JSON.writeValueAsString(body);
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .method(method, BodyPublishers.ofString(json)).build(), BodyHandlers.ofString());
    }

    /** The Pending Change id of a held tool result; fails when it was not held. */
    private static String heldId(JsonNode r) {
        assertFalse(r.get("isError").asBoolean(), r.toString());
        JsonNode held = r.at("/structuredContent/data");
        assertEquals("pending", held.get("status").asText(), r.toString());
        return held.at("/pendingChange/id").asText();
    }

    private void approve(Ctx c, String changeId) throws Exception {
        HttpResponse<String> approved = rest(c, "POST", "/pending-changes/" + changeId + "/approve", Map.of(), CHECKER);
        assertEquals(200, approved.statusCode(), approved.body());
    }

    private static Map<String, Object> args(String kind, String op, String id, Map<String, Object> content) {
        Map<String, Object> a = new LinkedHashMap<>(Map.of("kind", kind, "operation", op, "reason", "W2 " + kind + " " + op));
        if (id != null) a.put("id", id);
        if (content != null) a.put("content", content);
        return a;
    }

    /**
     * One create-update-delete kind's whole round: the agent's create is held and nothing is live; the checker's approval
     * applies it; the agent's update and delete are held and leave it as approved; the same token's direct create is held
     * the same way; a machine agent's create is held too.
     */
    private void crudKindIsHeldEveryWay(Ctx c, String kind, String type, String restPath, String id,
                                        java.util.function.Function<String, Map<String, Object>> content) throws Exception {
        String pc = heldId(propose(c.port, AGENT, args(kind, "create", null, content.apply(id))));
        assertFalse(c.registry().exists(type, id), kind + ": nothing is live before approval");
        approve(c, pc);
        assertTrue(c.registry().exists(type, id), kind + ": another person's approval applies it");

        String update = heldId(propose(c.port, AGENT, args(kind, "update", id, content.apply(id))));
        // one pending change per target: take the update back before the delete is proposed
        HttpResponse<String> withdrawn = rest(c, "POST", "/pending-changes/" + update + "/withdraw", Map.of(), AGENT);
        assertEquals(200, withdrawn.statusCode(), withdrawn.body());
        heldId(propose(c.port, AGENT, args(kind, "delete", id, null)));
        assertTrue(c.registry().exists(type, id), kind + ": a held delete removes nothing");

        HttpResponse<String> direct = rest(c, "POST", restPath, content.apply(id + "_direct"), AGENT);
        assertEquals(202, direct.statusCode(), kind + ": the same token's direct call is held: " + direct.body());
        assertFalse(c.registry().exists(type, id + "_direct"), kind + ": the direct call wrote nothing");

        JsonNode byBot = propose(c.port, BOT, args(kind, "create", null, content.apply(id + "_bot")));
        heldId(byBot);
        assertEquals("ops-bot", byBot.at("/structuredContent/data/pendingChange/agentClient").asText(), byBot.toString());
        assertFalse(c.registry().exists(type, id + "_bot"), kind + ": a machine agent's proposal wrote nothing");
    }

    // ── W2 step 1: Decision Rule and Notification Rule ─────────────────────────────────────────

    private static Map<String, Object> decisionRule(String name) {
        return Map.of("name", name, "targetType", "pipeline", "target", "orders",
                "consequences", List.of(Map.of("action", "emit-signal", "params", Map.of("type", "agent.proposed"))));
    }

    private static Map<String, Object> notificationRule(String id) {
        return Map.of("id", id, "eventType", "BATCH_FAILED", "category", "pipeline", "minLevel", "ERROR");
    }

    @Test
    void aDecisionRuleIsHeldEveryWay(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            crudKindIsHeldEveryWay(c, "decision-rule", "decision-rule", "/decision-rules", "late_orders",
                    ControlApiMcpProposeKindsTest::decisionRule);
        }
    }

    @Test
    void aNotificationRuleIsHeldEveryWay(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            crudKindIsHeldEveryWay(c, "notification-rule", "notification-rule", "/notifications/rules", "failed_batches",
                    ControlApiMcpProposeKindsTest::notificationRule);
        }
    }

    /** A Notification Channel is not on the allowlist (plan W2 step 1): the tool refuses it before any replay. */
    @Test
    void aKindOutsideTheAllowlistIsRefusedBeforeAnyReplay(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String kind : List.of("notification-channel", "enrichment", "connection")) {
                HttpResponse<String> res = rpc(c.port, AGENT, Map.of("name", "propose_change", "arguments",
                        args(kind, "create", null, Map.of("id", "ch1", "kind", "WEBHOOK", "target", "https://example.test/h"))));
                assertEquals(McpRoutes.INVALID_PARAMS, JSON.readTree(res.body()).at("/error/code").asInt(), kind + " → " + res.body());
            }
            HttpResponse<String> pending = rest(c, "GET", "/pending-changes", null, CHECKER);
            assertFalse(pending.body().contains("ch1"), "no Pending Change was recorded: " + pending.body());
        }
    }
}

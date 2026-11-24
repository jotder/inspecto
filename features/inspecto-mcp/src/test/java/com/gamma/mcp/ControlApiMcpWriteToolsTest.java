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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The AK-2b write MCP Tools over real HTTP (AGENT-KIT-1, plan {@code 2026-11-20-agent-kit-ak2-plan.md} §4.4, D-AK1, D-AK3):
 * an agent's {@code propose_change} replays the kind's own write route and comes back as a Pending Change (202) that
 * another person approves; the Pending Change names the agent client and the MCP Tool; a token from the console's own
 * client is refused every write tool but keeps the reads; validate, dry-run and the Pending Change reads work for an
 * agent; an agent withdraws its own proposal.
 */
class ControlApiMcpWriteToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** AGENT = dev-1 (pipeline-developer) through agent client claude-code; DEV = the same person, own token. */
    private static final String AGENT = "Bearer agent", DEV = "Bearer dev", CHECKER = "Bearer checker";
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
            String[] who = switch (h) {
                case AGENT, DEV -> new String[] {"dev-1", "pipeline-developer"};
                case CHECKER -> new String[] {"checker-1", "admin"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            ComponentAccess.heldRoles(ex, Set.of(who[1]));
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes(), Map.of(), null, null, null,
                    AGENT.equals(h) ? "claude-code" : null));
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
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> rpc(int port, String auth, String method, Map<String, Object> params) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>(params);
        p.put("_meta", Map.of(McpRoutes.META_VERSION, McpRoutes.MODERN,
                McpRoutes.META_CLIENT, Map.of("name", "TestAgent", "version", "1.0")));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/mcp"))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .header("MCP-Protocol-Version", McpRoutes.MODERN).header("Mcp-Method", method)
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", p))));
        if (p.get("name") != null) b.header("Mcp-Name", String.valueOf(p.get("name")));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** {@code tools/call} → the JSON-RPC {@code result}. */
    private JsonNode call(int port, String auth, String tool, Map<String, Object> args) throws Exception {
        HttpResponse<String> res = rpc(port, auth, "tools/call", Map.of("name", tool, "arguments", args));
        assertEquals(200, res.statusCode(), res.body());
        JsonNode result = JSON.readTree(res.body()).get("result");
        assertNotNull(result, res.body());
        return result;
    }

    private static String text(JsonNode result) {
        return result.at("/content/0/text").asText();
    }

    private HttpResponse<String> rest(Ctx c, String method, String path, String body, String auth) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static Map<String, Object> board(String id) {
        return Map.of("kind", "dashboard", "operation", "create",
                "content", Map.of("id", id, "tiles", List.of()), "reason", "a board for the on-call rota");
    }

    /** AGENT-PROPOSE-500-1: the first live golden run's 500s - a reason with characters no HTTP header carries
     *  ("—", "→", "€") made the replay throw before the route ran. It is held, and the approver reads it unchanged. */
    @Test
    void aReasonNoHeaderCanCarryIsHeldAndKeptExactly(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String reason = "Shipments board — count tile → cost by carrier (€)";
            JsonNode r = call(c.port, AGENT, "propose_change", Map.of("kind", "dashboard", "operation", "create",
                    "content", Map.of("id", "ship_board", "tiles", List.of()), "reason", reason));
            assertFalse(r.get("isError").asBoolean(), text(r));
            assertEquals("pending", r.at("/structuredContent/data/status").asText(), text(r));
            assertEquals(reason, r.at("/structuredContent/data/pendingChange/reason").asText());

            // a direct request still says why with the header (PendingChanges.hold's fallback)
            HttpResponse<String> direct = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                            + "/api/v1/components/dashboard"))
                    .header("Content-Type", "application/json").header("Authorization", AGENT)
                    .header("X-Change-Reason", "the on-call board, sent directly")
                    .POST(BodyPublishers.ofString("{\"id\":\"direct_board\",\"tiles\":[]}")).build(), BodyHandlers.ofString());
            assertEquals(202, direct.statusCode(), direct.body());
            assertEquals("the on-call board, sent directly",
                    JSON.readTree(direct.body()).at("/data/pendingChange/reason").asText(), direct.body());
        }
    }

    @Test
    void toolsListOffersTheWriteToolsAnnotatedAsNotReadOnly(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode tools = JSON.readTree(rpc(c.port, DEV, "tools/list", Map.of()).body()).at("/result/tools");
            Map<String, JsonNode> byName = new LinkedHashMap<>();
            tools.forEach(t -> byName.put(t.get("name").asText(), t));
            assertEquals(McpTools.ALL_TOOLS.size(), byName.size());
            for (String w : List.of("propose_change", "withdraw_change", "propose_action", "run_pipeline_now")) {
                assertFalse(byName.get(w).at("/annotations/readOnlyHint").asBoolean(true), w);
                assertFalse(byName.get(w).at("/annotations/destructiveHint").asBoolean(true), w);
            }
            for (String r : List.of("validate_config", "dry_run_pipeline", "list_pending_changes", "get_pending_change",
                    "diff_pending_change", "export_bundle", "preview_promotion"))
                assertTrue(byName.get(r).at("/annotations/readOnlyHint").asBoolean(false), r);
            assertEquals(List.of("alert-rule", "dashboard", "expectation", "job", "pipeline", "widget"),
                    JSON.convertValue(byName.get("propose_change").at("/inputSchema/properties/kind/enum"), List.class));
        }
    }

    @Test
    void anAgentsProposalIsAPendingChangeThatAnotherPersonApplies(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode r = call(c.port, AGENT, "propose_change", board("oncall_board"));
            assertFalse(r.get("isError").asBoolean(), text(r));
            JsonNode held = r.at("/structuredContent/data");
            assertEquals("pending", held.get("status").asText(), text(r));
            assertEquals("claude-code", held.at("/pendingChange/agentClient").asText());
            assertEquals("propose_change", held.at("/pendingChange/mcpTool").asText());
            assertEquals("a board for the on-call rota", held.at("/pendingChange/reason").asText(), "X-Change-Reason rode the replay");
            assertFalse(new ComponentStore(c.root.resolve("registry")).exists("dashboard", "oncall_board"), "nothing written");
            String id = held.at("/pendingChange/id").asText();

            assertTrue(text(call(c.port, AGENT, "get_pending_change", Map.of("change", id))).contains("oncall_board"));
            assertFalse(call(c.port, AGENT, "diff_pending_change", Map.of("change", id)).get("isError").asBoolean());
            assertTrue(text(call(c.port, AGENT, "list_pending_changes", Map.of("status", "pending"))).contains(id));

            HttpResponse<String> approved = rest(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER);
            assertEquals(200, approved.statusCode(), approved.body());
            assertTrue(new ComponentStore(c.root.resolve("registry")).exists("dashboard", "oncall_board"),
                    "another person's approval applies it");
        }
    }

    @Test
    void aConsoleTokenIsRefusedEveryWriteToolButKeepsTheReads(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (Map.Entry<String, Map<String, Object>> w : Map.of(
                    "propose_change", board("b1"),
                    "withdraw_change", Map.<String, Object>of("change", "pc-20261120000000-abcdef"),
                    "propose_action", Map.<String, Object>of("body", Map.of("connection", "hook")),
                    "run_pipeline_now", Map.<String, Object>of("pipeline", "orders")).entrySet()) {
                JsonNode r = call(c.port, DEV, w.getKey(), w.getValue());
                assertTrue(r.get("isError").asBoolean(), w.getKey());
                assertTrue(text(r).startsWith("HTTP 403") && text(r).contains("agent client"), w.getKey() + ": " + text(r));
            }
            assertFalse(new ComponentStore(c.root.resolve("registry")).exists("dashboard", "b1"));
            assertFalse(call(c.port, DEV, "list_pipelines", Map.of()).get("isError").asBoolean(), "reads stay open");
            assertFalse(call(c.port, DEV, "list_pending_changes", Map.of()).get("isError").asBoolean());
        }
    }

    @Test
    void anAgentValidatesDryRunsAndWithdrawsItsOwnProposal(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode v = call(c.port, AGENT, "validate_config", Map.of("type", "job", "config", Map.of("name", "x")));
            assertFalse(text(v).startsWith("HTTP 403") || text(v).startsWith("HTTP 500"), "the validate route answered: " + text(v));
            JsonNode d = call(c.port, AGENT, "dry_run_pipeline", Map.of("pipeline", "no_such_pipeline"));
            assertTrue(text(d).startsWith("HTTP 404"), "the dry-run route answered, not the gate: " + text(d));
            JsonNode run = call(c.port, AGENT, "run_pipeline_now", Map.of("pipeline", "no_such_pipeline"));
            assertFalse(text(run).contains("agent client"), "D-AK2: a delegated person may run: " + text(run));

            String id = call(c.port, AGENT, "propose_change", board("b2")).at("/structuredContent/data/pendingChange/id").asText();
            JsonNode w = call(c.port, AGENT, "withdraw_change", Map.of("change", id));
            assertFalse(w.get("isError").asBoolean(), text(w));
            assertEquals("withdrawn", w.at("/structuredContent/data/pendingChange/status").asText());
        }
    }

    /** On a read-only install (an expired entitlement) a write tool's replay is refused 423 like a direct write; reads answer. */
    @Test
    void onAReadOnlyInstallAWriteToolIsRefusedAndTheReadsStillAnswer(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            System.setProperty(com.gamma.module.Entitlements.FILE_PROPERTY, dir.resolve("absent.toon").toString());
            com.gamma.module.Entitlements.reset();
            com.gamma.module.Entitlements.requireFile("test: no entitlement file");
            try {
                assertTrue(com.gamma.module.Entitlements.readOnly(), "precondition: read-only");
                JsonNode r = call(c.port, AGENT, "propose_change", board("ro_board"));
                assertTrue(text(r).startsWith("HTTP 423"), text(r));
                JsonNode list = call(c.port, AGENT, "list_pending_changes", Map.of());
                assertFalse(list.get("isError").asBoolean(), "reads still answer: " + text(list));
                assertFalse(text(list).contains("ro_board"), "no Pending Change was recorded: " + text(list));
                assertFalse(call(c.port, AGENT, "list_pipelines", Map.of()).get("isError").asBoolean(), "reads still answer");
            } finally {
                System.clearProperty(com.gamma.module.Entitlements.FILE_PROPERTY);
                com.gamma.module.Entitlements.reset();
            }
        }
    }

    @Test
    void aMalformedProposalIsAnInvalidParamsErrorAndReachesNoRoute(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (Map<String, Object> bad : List.<Map<String, Object>>of(
                    Map.of("kind", "connection", "operation", "create", "content", Map.of()),   // not an allowlisted kind
                    Map.of("kind", "widget", "operation", "update", "content", Map.of()),      // no id
                    Map.of("kind", "widget", "operation", "delete", "id", "w1", "content", Map.of()),
                    Map.of("kind", "widget", "operation", "create"),
                    Map.of("kind", "widget", "operation", "create", "content", Map.of(), "reason", "two\r\nlines"),
                    Map.of("kind", "widget", "operation", "create", "content", Map.of(), "reason", "r".repeat(501)))) {
                JsonNode res = JSON.readTree(rpc(c.port, AGENT, "tools/call",
                        Map.of("name", "propose_change", "arguments", bad)).body());
                assertEquals(McpRoutes.INVALID_PARAMS, res.at("/error/code").asInt(), bad + " → " + res);
            }
        }
    }
}

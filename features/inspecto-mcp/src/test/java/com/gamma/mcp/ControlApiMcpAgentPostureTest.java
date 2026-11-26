package com.gamma.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.control.ControlApi;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code MCP-AGENT-POSTURE-1} over real HTTP: an agent learns its posture from the server. {@code get_agent_posture}
 * (and {@code GET /agent-posture}) answer {@code person} for a person's agent, {@code machine} for a machine agent and
 * {@code none} for a token no agent client acts for; the answer equals what the gate then does with an action (a
 * person's agent's runs at once, a machine agent's is held as a Pending Action); the {@code initialize} /
 * {@code server/discover} instructions name each caller's own posture and never another's.
 */
class ControlApiMcpAgentPostureTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** AGENT = op-1 (power: canOperateRuns; minus the never-list) through claude-code; BOT = the machine agent ops-bot
     *  (power, filtered to the machine agent set); PLAIN = lead-1 (admin), its own token, no agent client. */
    private static final String AGENT = "Bearer agent", BOT = "Bearer bot", PLAIN = "Bearer plain";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            Roles.Def admin = Roles.effective(ex).get("admin"), power = Roles.effective(ex).get("power");
            switch (h) {
                case AGENT -> {
                    // what OidcAuthenticator leaves an agent of a person: the person's grants minus the never-list
                    ComponentAccess.heldRoles(ex, Set.of("power"));
                    Set<String> caps = new LinkedHashSet<>(power.capabilities());
                    caps.removeAll(Roles.AGENT_NEVER_CAPABILITIES);
                    return Optional.of(new Subject("op-1", caps, power.dataScopes(), Map.of(), null, null, null, "claude-code"));
                }
                case BOT -> {
                    // what OidcAuthenticator admits for a machine agent: its roles' grants, filtered to the machine agent set
                    ComponentAccess.heldRoles(ex, Set.of("power"));
                    Set<String> keeps = new LinkedHashSet<>(Roles.MACHINE_CAPABILITIES);
                    keeps.addAll(Roles.AGENT_MACHINE_CAPABILITIES);
                    Set<String> caps = new LinkedHashSet<>(power.capabilities());
                    caps.retainAll(keeps);
                    return Optional.of(new Subject("client:ops-bot", caps, null, Map.of(), null, null, "svc-ops", "ops-bot"));
                }
                case PLAIN -> {
                    ComponentAccess.heldRoles(ex, Set.of("admin"));
                    return Optional.of(new Subject("lead-1", admin.capabilities(), admin.dataScopes()));
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
        Path root = Files.createDirectories(dir.resolve("root"));
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            Roles.write(root, Map.of(), List.of(), List.of("ops-bot"));   // the Space admits the machine client
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** A modern-era JSON-RPC call; {@code method} {@code initialize} is sent in the legacy era (no {@code _meta}). */
    private HttpResponse<String> rpc(int port, String auth, String method, Map<String, Object> params) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>(params);
        boolean legacy = "initialize".equals(method);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/mcp"))
                .header("Content-Type", "application/json").header("Authorization", auth);
        if (legacy) {
            p.put("protocolVersion", "2025-11-25");
        } else {
            p.put("_meta", Map.of(McpRoutes.META_VERSION, McpRoutes.MODERN,
                    McpRoutes.META_CLIENT, Map.of("name", "TestAgent", "version", "1.0")));
            b.header("MCP-Protocol-Version", McpRoutes.MODERN).header("Mcp-Method", method);
            if (p.get("name") != null) b.header("Mcp-Name", String.valueOf(p.get("name")));
        }
        b.POST(BodyPublishers.ofString(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", p))));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode result(int port, String auth, String method, Map<String, Object> params) throws Exception {
        HttpResponse<String> res = rpc(port, auth, method, params);
        assertEquals(200, res.statusCode(), res.body());
        JsonNode result = JSON.readTree(res.body()).get("result");
        assertNotNull(result, res.body());
        return result;
    }

    private JsonNode tool(int port, String auth, String name, Map<String, Object> args) throws Exception {
        JsonNode r = result(port, auth, "tools/call", Map.of("name", name, "arguments", args));
        assertFalse(r.get("isError").asBoolean(), name + ": " + r.at("/content/0/text").asText());
        return r.get("structuredContent");
    }

    private HttpResponse<String> get(int port, String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET();
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /**
     * The parity: what {@code get_agent_posture} says is what the gate does with an action. A person's agent's
     * {@code evaluate_alerts} runs at once; a machine agent's comes back as a Pending Action and evaluates nothing.
     */
    @Test
    void thePostureAnAgentIsToldIsWhatTheGateDoesWithItsAction(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String token : List.of(AGENT, BOT)) {
                JsonNode posture = tool(c.port, token, "get_agent_posture", Map.of()).path("data");
                JsonNode acted = tool(c.port, token, "evaluate_alerts", Map.of("reason", "posture parity"));
                boolean held = acted.at("/data/pendingAction/id").isTextual();
                assertEquals(held ? "machine" : "person", posture.path("posture").asText(),
                        token + ": told " + posture + " but the gate answered " + acted);
            }
            // and each token is what it was minted as - the parity above is not a constant answer
            assertEquals("person", tool(c.port, AGENT, "get_agent_posture", Map.of()).at("/data/posture").asText());
            JsonNode bot = tool(c.port, BOT, "get_agent_posture", Map.of()).path("data");
            assertEquals("machine", bot.path("posture").asText(), bot.toString());
            assertEquals("ops-bot", bot.path("agentClient").asText());
            assertTrue(bot.path("effect").asText().contains("Pending Action"), bot.toString());
            JsonNode person = tool(c.port, AGENT, "get_agent_posture", Map.of()).path("data");
            assertEquals("claude-code", person.path("agentClient").asText());
            assertTrue(person.path("effect").asText().contains("runs at once"), person.toString());
            assertEquals(1, JSON.readTree(get(c.port, "/pending-actions", PLAIN).body()).at("/data/total").asInt(),
                    "only the machine's action was held");
        }
    }

    /** A token no agent client acts for: {@code none} over MCP and REST; no credential: 401. */
    @Test
    void aTokenWithoutAnAgentClientIsToldNoneAndNoCredentialIsRefused(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode none = tool(c.port, PLAIN, "get_agent_posture", Map.of()).path("data");
            assertEquals("none", none.path("posture").asText(), none.toString());
            assertTrue(none.path("agentClient").isNull(), none.toString());
            for (String[] t : new String[][] {{AGENT, "person"}, {BOT, "machine"}, {PLAIN, "none"}}) {
                HttpResponse<String> r = get(c.port, "/agent-posture", t[0]);
                assertEquals(200, r.statusCode(), r.body());
                assertEquals(t[1], JSON.readTree(r.body()).at("/data/posture").asText(), r.body());
            }
            assertEquals(401, get(c.port, "/agent-posture", null).statusCode());
        }
    }

    /**
     * The instructions name each caller's own posture: interleaved callers on one server never see another's line, the
     * discovery result stays {@code cacheScope: private}, and the stale "acts as the signed-in person" is gone.
     */
    @Test
    void theInstructionsNameEachCallersOwnPostureAndNeverLeak(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Map<String, String> expected = Map.of(AGENT, "person", BOT, "machine", PLAIN, "none");
            for (int round = 0; round < 2; round++) {
                for (String token : List.of(BOT, AGENT, PLAIN)) {
                    JsonNode d = result(c.port, token, "server/discover", Map.of());
                    assertEquals("private", d.path("cacheScope").asText(), d.toString());
                    assertOnly(expected, token, d.path("instructions").asText());
                    assertOnly(expected, token, result(c.port, token, "initialize", Map.of()).path("instructions").asText());
                }
            }
        }
    }

    private static void assertOnly(Map<String, String> expected, String token, String instructions) {
        assertTrue(instructions.startsWith(McpRoutes.INSTRUCTIONS), instructions);
        assertFalse(instructions.contains("acts as the signed-in person"), instructions);
        assertTrue(instructions.contains("get_agent_posture"), instructions);
        for (Map.Entry<String, String> e : expected.entrySet()) {
            boolean own = e.getKey().equals(token);
            assertEquals(own, instructions.contains("Your posture: " + e.getValue()),
                    token + (own ? " is not told its own posture " : " is told another caller's posture ") + e.getValue()
                            + ": " + instructions);
        }
    }
}

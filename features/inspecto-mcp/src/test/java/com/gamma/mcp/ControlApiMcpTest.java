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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /mcp} over real HTTP (AGENT-KIT-1 AK-1): the transport's refusals in order, both protocol eras, that
 * every tool call is a replay of an existing route AS THE CALLER (a capability the person lacks is refused through
 * the MCP endpoint exactly as through the route), and the audit row naming the person and the agent client.
 */
class ControlApiMcpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPS = "Bearer ops", ADMIN = "Bearer admin", DEV = "Bearer dev";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /** OPS = operations (canWorkIncidents); ADMIN = admin; DEV = pipeline-developer, which lacks canWorkIncidents. */
    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String[] who = switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
                case OPS -> new String[] {"ops-1", "operations"};
                case ADMIN -> new String[] {"admin-1", "admin"};
                case DEV -> new String[] {"dev-1", "pipeline-developer"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            ComponentAccess.heldRoles(ex, Set.of(who[1]));
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        System.clearProperty("control.cors");
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        Path writeRoot = Files.createDirectories(dir.resolve("root"));
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    // ── request builders ────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> meta() {
        return Map.of(McpRoutes.META_VERSION, McpRoutes.MODERN,
                McpRoutes.META_CLIENT, Map.of("name", "TestAgent", "version", "1.0"),
                "io.modelcontextprotocol/clientCapabilities", Map.of());
    }

    /** A modern request with its mirrored headers set correctly. */
    private HttpResponse<String> modern(int port, String auth, String method, Map<String, Object> params) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>(params);
        p.put("_meta", meta());
        Map<String, String> h = new LinkedHashMap<>();
        h.put("MCP-Protocol-Version", McpRoutes.MODERN);
        h.put("Mcp-Method", method);
        Object name = p.get("name") != null ? p.get("name") : p.get("uri");
        if (name != null) h.put("Mcp-Name", String.valueOf(name));
        return post(port, auth, h, Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", p));
    }

    private HttpResponse<String> call(int port, String auth, String tool, Map<String, Object> args) throws Exception {
        return modern(port, auth, "tools/call", Map.of("name", tool, "arguments", args));
    }

    private HttpResponse<String> post(int port, String auth, Map<String, String> headers, Object body) throws Exception {
        return postRaw(port, auth, headers, JSON.writeValueAsString(body));
    }

    private HttpResponse<String> postRaw(int port, String auth, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(BodyPublishers.ofString(body));
        if (auth != null) b.header("Authorization", auth);
        headers.forEach(b::header);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> res) throws Exception {
        return JSON.readTree(res.body());
    }

    /** The AUDIT rows whose text names {@code needle}, read over the audit route as an administrator. */
    private List<String> auditRows(int port, String type, String needle) throws Exception {
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/v1/audit/search?type=" + type + "&limit=200"))
                .header("Authorization", ADMIN).GET().build(), BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        List<String> rows = new ArrayList<>();
        for (JsonNode row : JSON.readTree(res.body()).path("data")) if (row.toString().contains(needle)) rows.add(row.toString());
        return rows;
    }

    // ── the transport ───────────────────────────────────────────────────────────────────────────

    @Test
    void discoverAndToolsListAnswerTheModernEra(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode d = body(modern(c.port, OPS, "server/discover", Map.of())).path("result");
            assertTrue(d.path("supportedVersions").toString().contains(McpRoutes.MODERN), d.toString());
            assertEquals("complete", d.path("resultType").asText());
            assertEquals("inspecto", d.path("_meta").path(McpRoutes.META_SERVER).path("name").asText());
            assertEquals("private", d.path("cacheScope").asText());

            HttpResponse<String> res = modern(c.port, OPS, "tools/list", Map.of());
            assertEquals(200, res.statusCode(), res.body());
            JsonNode tools = body(res).path("result").path("tools");
            assertEquals(McpTools.TOOLS.size(), tools.size());
            for (JsonNode t : tools) {
                assertTrue(t.path("annotations").path("readOnlyHint").asBoolean(), "AK-1 is read-only: " + t);
                assertEquals("object", t.path("inputSchema").path("type").asText());
            }
            assertEquals("edition_and_modules", tools.get(0).path("name").asText(), "a deterministic order");
        }
    }

    @Test
    void noCredentialIsRefusedByTheHostBeforeTheEndpointRuns(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(401, modern(c.port, null, "tools/list", Map.of()).statusCode());
        }
    }

    /** Falsified: without the Subject check a signed-out Standard install would serve tools/list (200) anonymously. */
    @Test
    void anInstallWithoutSignInRefusesTheEndpoint(@TempDir Path dir) throws Exception {
        Authenticators.forTest(null);
        try (Ctx c = open(dir)) {
            HttpResponse<String> res = modern(c.port, null, "tools/list", Map.of());
            assertEquals(503, res.statusCode(), res.body());
            assertTrue(res.body().contains("needs sign-in"), res.body());
        }
    }

    @Test
    void aBrowserOriginOtherThanTheConsoleIsRefused(@TempDir Path dir) throws Exception {
        System.setProperty("control.cors", "https://console.example");
        try (Ctx c = open(dir)) {
            assertEquals(403, post(c.port, OPS, Map.of("Origin", "https://evil.example", "MCP-Protocol-Version", McpRoutes.MODERN,
                    "Mcp-Method", "tools/list"), Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list",
                    "params", Map.of("_meta", meta()))).statusCode());
            assertEquals(200, post(c.port, OPS, Map.of("Origin", "https://console.example", "MCP-Protocol-Version", McpRoutes.MODERN,
                    "Mcp-Method", "tools/list"), Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list",
                    "params", Map.of("_meta", meta()))).statusCode());
        }
    }

    @Test
    void mirroredHeadersMustMatchTheBody(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Map<String, Object> req = Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call",
                    "params", Map.of("name", "list_spaces", "arguments", Map.of(), "_meta", meta()));
            HttpResponse<String> wrongName = post(c.port, OPS, Map.of("MCP-Protocol-Version", McpRoutes.MODERN,
                    "Mcp-Method", "tools/call", "Mcp-Name", "health"), req);
            assertEquals(400, wrongName.statusCode());
            assertEquals(McpRoutes.HEADER_MISMATCH, body(wrongName).path("error").path("code").asInt());

            HttpResponse<String> noVersion = post(c.port, OPS, Map.of("Mcp-Method", "tools/call", "Mcp-Name", "list_spaces"), req);
            assertEquals(McpRoutes.HEADER_MISMATCH, body(noVersion).path("error").path("code").asInt());

            String encoded = "=?base64?" + Base64.getEncoder().encodeToString("list_spaces".getBytes(StandardCharsets.UTF_8)) + "?=";
            assertEquals(200, post(c.port, OPS, Map.of("MCP-Protocol-Version", McpRoutes.MODERN,
                    "Mcp-Method", "tools/call", "Mcp-Name", encoded), req).statusCode(), "the base64 sentinel is decoded");
        }
    }

    @Test
    void anUnknownVersionNamesTheSupportedOnes(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> res = post(c.port, OPS, Map.of("MCP-Protocol-Version", "1900-01-01", "Mcp-Method", "tools/list"),
                    Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list",
                            "params", Map.of("_meta", Map.of(McpRoutes.META_VERSION, "1900-01-01"))));
            assertEquals(400, res.statusCode());
            JsonNode err = body(res).path("error");
            assertEquals(McpRoutes.UNSUPPORTED_VERSION, err.path("code").asInt());
            assertTrue(err.path("data").path("supported").toString().contains(McpRoutes.MODERN));
        }
    }

    @Test
    void malformedBodiesAndNotificationsAndGet(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> batch = postRaw(c.port, OPS, Map.of(), "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}]");
            assertEquals(400, batch.statusCode());
            assertEquals(McpRoutes.INVALID_REQUEST, body(batch).path("error").path("code").asInt());
            assertEquals(McpRoutes.PARSE_ERROR, body(postRaw(c.port, OPS, Map.of(), "{not json")).path("error").path("code").asInt());
            assertEquals(202, post(c.port, OPS, Map.of(), Map.of("jsonrpc", "2.0", "method", "notifications/initialized")).statusCode());
            HttpResponse<String> get = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/mcp"))
                    .header("Authorization", OPS).GET().build(), BodyHandlers.ofString());
            assertEquals(405, get.statusCode());

            // A numeric "jsonrpc" is not the string "2.0".
            assertEquals(McpRoutes.INVALID_REQUEST, body(postRaw(c.port, OPS, Map.of("MCP-Protocol-Version", "2025-11-25"),
                    "{\"jsonrpc\":2.0,\"id\":1,\"method\":\"tools/list\"}")).path("error").path("code").asInt());
            // The modern header without the modern _meta is a mismatch, not an unsupported version.
            assertEquals(McpRoutes.HEADER_MISMATCH, body(postRaw(c.port, OPS, Map.of("MCP-Protocol-Version", McpRoutes.MODERN,
                    "Mcp-Method", "tools/list"), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).path("error").path("code").asInt());
        }
    }

    /** A request id is echoed exactly, an integer beyond 64 bits included; legacy clients may ping. */
    @Test
    void idsAreEchoedExactlyAndLegacyPingAnswers(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String big = "123456789012345678901234567890";
            HttpResponse<String> res = postRaw(c.port, OPS, Map.of("MCP-Protocol-Version", "2025-11-25"),
                    "{\"jsonrpc\":\"2.0\",\"id\":" + big + ",\"method\":\"ping\"}");
            assertEquals(200, res.statusCode(), res.body());
            assertTrue(res.body().contains("\"id\":" + big), res.body());
            assertTrue(body(res).path("result").isObject(), res.body());
        }
    }

    @Test
    void theLegacyEraIsServedWithoutASession(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> init = post(c.port, OPS, Map.of(), Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                    "params", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "OldAgent", "version", "0.9"))));
            assertEquals(200, init.statusCode(), init.body());
            assertEquals("2025-11-25", body(init).path("result").path("protocolVersion").asText());
            assertTrue(init.headers().firstValue("Mcp-Session-Id").isEmpty(), "no session is ever minted");
            HttpResponse<String> list = post(c.port, OPS, Map.of("MCP-Protocol-Version", "2025-11-25"),
                    Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"));
            assertEquals(200, list.statusCode(), list.body());
            assertEquals(McpTools.TOOLS.size(), body(list).path("result").path("tools").size());
            assertTrue(body(list).path("result").path("resultType").isMissingNode(), "legacy results keep the legacy shape");
        }
    }

    // ── no side door: the route's own gate decides ─────────────────────────────────────────────

    /**
     * {@code get_incident_briefs} replays {@code GET /incidents/{id}/briefs}, gated {@code canWorkIncidents}. A person
     * without it is refused THROUGH the endpoint (403 inside an error result), one with it gets past the gate.
     * Falsified: replacing the replay's inherited Subject with none (no check runs) turns the DEV call into a pass.
     */
    @Test
    void aCapabilityThePersonLacksIsRefusedThroughTheEndpoint(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode denied = body(call(c.port, DEV, "get_incident_briefs", Map.of("incident", "INC-1"))).path("result");
            assertTrue(denied.path("isError").asBoolean(), denied.toString());
            assertTrue(denied.path("content").get(0).path("text").asText().startsWith("HTTP 403"), denied.toString());

            JsonNode allowed = body(call(c.port, OPS, "get_incident_briefs", Map.of("incident", "INC-1"))).path("result");
            assertFalse(allowed.path("content").get(0).path("text").asText().startsWith("HTTP 403"),
                    "the person who holds canWorkIncidents passes the gate: " + allowed);

            List<String> denials = auditRows(c.port, "ACCESS_DENIED", "dev-1");
            assertTrue(denials.stream().anyMatch(r -> r.contains("canWorkIncidents")),
                    "the replayed route records its own refusal, naming the missing capability: " + denials);
            List<String> mcp = auditRows(c.port, "AUDIT", "mcp.tool.called");
            assertTrue(mcp.stream().anyMatch(r -> r.contains("dev-1") && r.contains("get_incident_briefs") && r.contains("403")),
                    "the endpoint's own row says the call was refused: " + mcp);
        }
    }

    @Test
    void aToolCallIsAuditedNamingThePersonAndTheAgentClient(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> res = call(c.port, OPS, "list_spaces", Map.of());
            JsonNode result = body(res).path("result");
            assertFalse(result.path("isError").asBoolean(), res.body());
            assertTrue(result.has("structuredContent"), res.body());

            List<String> rows = auditRows(c.port, "AUDIT", "mcp.tool.called");
            String row = rows.stream().filter(r -> r.contains("list_spaces")).findFirst().orElseThrow(() ->
                    new AssertionError("no mcp.tool.called row for list_spaces: " + rows));
            assertTrue(row.contains("ops-1"), "the person: " + row);
            assertTrue(row.contains("TestAgent/1.0"), "the agent client: " + row);
            assertTrue(row.contains("GET /spaces"), "the route it replayed: " + row);
            assertTrue(row.contains(McpRoutes.MODERN), row);
        }
    }

    @Test
    void argumentsCannotChooseARoute(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (Map<String, Object> args : List.<Map<String, Object>>of(Map.of("dataset", ".."), Map.of("dataset", "a/b"),
                    Map.of("dataset", "x?group=ops:status"))) {
                JsonNode err = body(call(c.port, OPS, "describe_dataset", args)).path("error");
                assertEquals(McpRoutes.INVALID_PARAMS, err.path("code").asInt(), args + " -> " + err);
            }
            JsonNode group = body(call(c.port, OPS, "query_dataset",
                    Map.of("dataset", "orders", "sql", "SELECT 1", "group", "ops:objects"))).path("error");
            assertEquals(McpRoutes.INVALID_PARAMS, group.path("code").asInt(), "query_dataset never takes a group: " + group);
            assertEquals(McpRoutes.INVALID_PARAMS,
                    body(call(c.port, OPS, "drop_everything", Map.of())).path("error").path("code").asInt());
        }
    }

    @Test
    void theGlossaryResourceIsPackaged(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode list = body(modern(c.port, OPS, "resources/list", Map.of())).path("result").path("resources");
            assertEquals(McpTools.RESOURCES.size(), list.size());
            JsonNode read = body(modern(c.port, OPS, "resources/read", Map.of("uri", "inspecto://glossary"))).path("result");
            JsonNode content = read.path("contents").get(0);
            assertEquals("text/markdown", content.path("mimeType").asText());
            assertTrue(content.path("text").asText().contains("Pipeline"), "the canonical vocabulary is served");
        }
    }

    /** The per-Space switch: an administrator turns feature {@code mcp} off and the host refuses the endpoint. */
    @Test
    void aSpaceThatSwitchedTheEndpointOffRefusesIt(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> off = client.send(HttpRequest.newBuilder(URI.create(
                    "http://localhost:" + c.port + "/api/v1/settings/modules")).header("Authorization", ADMIN)
                    .header("Content-Type", "application/json")
                    .PUT(BodyPublishers.ofString("{\"disabled\":[\"mcp\"]}")).build(), BodyHandlers.ofString());
            assertEquals(200, off.statusCode(), off.body());
            HttpResponse<String> res = modern(c.port, OPS, "tools/list", Map.of());
            assertTrue(res.statusCode() >= 400, "the switched-off endpoint answers nothing: " + res.statusCode() + " " + res.body());
        }
    }
}

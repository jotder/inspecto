package com.gamma.mcp;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.AnswerMasking;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGENT-KIT-1 W6 S0 over real HTTP, {@code POST /db/query} on both doors (direct REST and the MCP {@code query_dataset}
 * replay): an agent's answer is masked and stamped (D-AK13); with every stamp made a no-op the answer is withheld as 500
 * {@code masking not proven} and carries no row, while a person's request is unchanged; a Space whose masking mode is
 * {@code none} answers an agent raw (D-AK14); the agent budget answers 429 past its bound on each door, per agent client
 * and subject, and never spends the person's own budget (D-AK17); no request value reaches an audit row or a log line,
 * and the MCP call leaves its {@code mcp.tool.called} row (D-AK19).
 */
class ControlApiAgentDataSeamTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "441700900101", B = "441700900202";
    /** PERSON = analyst-1 itself; AGENT / AGENT2 = analyst-1 through two agent clients; ADMIN reads the audit trail. */
    private static final String PERSON = "Bearer person", AGENT = "Bearer agent", AGENT2 = "Bearer agent2", ADMIN = "Bearer admin";
    private static final String BUDGET = "control.rateLimit.agent.capacity", REFILL = "control.rateLimit.agent.refillPerSecond";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
        }
    }

    @BeforeEach
    void arm() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.INVESTIGATIONS));   // masking on
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            String role = ADMIN.equals(h) ? "admin" : "operations";
            Roles.Def def = Roles.effective(ex).get(role);
            ComponentAccess.heldRoles(ex, Set.of(role));
            Set<String> agentCaps = new LinkedHashSet<>(def.capabilities());
            agentCaps.removeAll(Roles.AGENT_NEVER_CAPABILITIES);
            return switch (h) {
                case PERSON -> Optional.of(new Subject("analyst-1", def.capabilities(), def.dataScopes()));
                case AGENT -> Optional.of(new Subject("analyst-1", agentCaps, def.dataScopes(), Map.of(), null, null, null, "claude-code"));
                case AGENT2 -> Optional.of(new Subject("analyst-1", agentCaps, def.dataScopes(), Map.of(), null, null, null, "cursor"));
                case ADMIN -> Optional.of(new Subject("admin-1", def.capabilities(), def.dataScopes()));
                default -> Optional.empty();
            };
        });
    }

    @AfterEach
    void disarm() {
        AnswerMasking.ignoreStampsForTest(false);
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        System.clearProperty(BUDGET);
        System.clearProperty(REFILL);
    }

    /** Space s1 with the Parquet store {@code calls}, its msisdn classified through the Dataset {@code calls_ds}. */
    private Ctx open(Path root, String laSettings) throws Exception {
        Path base = root.resolve("s1");
        Path config = base.resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Path partition = Files.createDirectories(base.resolve("data").resolve("calls").resolve("dt=2026"));
        String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("s0seam_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT *, current_date AS seen_on FROM (VALUES ('" + A + "','EU',10.0),('" + B + "','US',7.0)) "
                    + "t(msisdn,region,amount)) TO '" + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        new ComponentStore(config.resolve("registry")).write("dataset", "calls_ds", Map.of("physicalRef", "calls",
                "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "region"))));
        new ComponentStore(config.resolve("registry")).write("kpi", "callers", Map.of("dataset", "calls_ds",
                "measure", "countDistinct(msisdn)", "timeField", "seen_on", "grain", "month", "comparison", "previous",
                "direction", "up"));
        if (laSettings != null) Files.writeString(config.resolve("link-analysis.toon"), laSettings);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private HttpResponse<String> query(Ctx c, String auth, String sql) throws Exception {
        String body = JSON.writeValueAsString(Map.of("table", "calls", "sql", sql));
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/s1/db/query"))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** The MCP {@code query_dataset} tool call; its {@code result} (an {@code isError} tool result when the replay failed). */
    private JsonNode tool(Ctx c, String auth, String sql) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "query_dataset");
        params.put("arguments", Map.of("dataset", "calls", "sql", sql));
        params.put("_meta", Map.of(McpRoutes.META_VERSION, McpRoutes.MODERN,
                McpRoutes.META_CLIENT, Map.of("name", "TestAgent", "version", "1.0")));
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/s1/mcp"))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .header("MCP-Protocol-Version", McpRoutes.MODERN).header("Mcp-Method", "tools/call").header("Mcp-Name", "query_dataset")
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", params)))).build(), BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        JsonNode result = JSON.readTree(res.body()).get("result");
        assertNotNull(result, res.body());
        return result;
    }

    private static String text(JsonNode toolResult) {
        return toolResult.at("/content/0/text").asText();
    }

    private static void assertNoRaw(String what, String body) {
        for (String raw : List.of(A, B)) assertFalse(body.contains(raw), what + " leaked " + raw + ": " + body);
    }

    @Test
    void anAgentsQueryIsMaskedAndStampedOnBothDoors(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            HttpResponse<String> direct = query(c, AGENT, "SELECT msisdn, region FROM calls");
            assertEquals(200, direct.statusCode(), direct.body());
            assertNoRaw("direct", direct.body());
            assertTrue(direct.body().contains("masked:"), direct.body());

            JsonNode viaMcp = tool(c, AGENT, "SELECT msisdn, region FROM calls");
            assertFalse(viaMcp.path("isError").asBoolean(), viaMcp.toString());
            assertNoRaw("MCP", viaMcp.toString());
            assertTrue(viaMcp.toString().contains("masked:"), viaMcp.toString());
        }
    }

    @Test
    void anUnstampedAnswerIsWithheldOnBothDoorsAndAPersonIsUnchanged(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            AnswerMasking.ignoreStampsForTest(true);   // the route's masking step no longer proves itself
            HttpResponse<String> direct = query(c, AGENT, "SELECT msisdn, region FROM calls");
            assertEquals(500, direct.statusCode(), direct.body());
            assertTrue(direct.body().contains(ControlApi.MASKING_NOT_PROVEN), direct.body());
            assertNoRaw("direct", direct.body());
            assertFalse(direct.body().contains("masked:") || direct.body().contains("EU"), "no row leaves: " + direct.body());

            JsonNode viaMcp = tool(c, AGENT, "SELECT msisdn, region FROM calls");
            assertTrue(viaMcp.path("isError").asBoolean(), viaMcp.toString());
            assertTrue(text(viaMcp).startsWith("HTTP 500"), viaMcp.toString());
            assertNoRaw("MCP", viaMcp.toString());
            assertFalse(viaMcp.toString().contains("masked:") || viaMcp.toString().contains("EU"), "no row leaves: " + viaMcp);

            HttpResponse<String> person = query(c, PERSON, "SELECT msisdn, region FROM calls");
            assertEquals(200, person.statusCode(), "a person's request does not pass the seam: " + person.body());
            assertTrue(person.body().contains("masked:"), "and is masked as before: " + person.body());
        }
    }

    private HttpResponse<String> get(Ctx c, String auth, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/s1" + path))
                .header("Authorization", auth).GET().build(), BodyHandlers.ofString());
    }

    /**
     * AGENT-GET-DATA-READS-1 (D-AK13): the masked value GETs of this fixture, each an agent data route:
     * "GET /datasets/([^/]+)/rows" and "GET /db/table" (masked by BiResultMasking.apply), and
     * "GET /kpis/([^/]+)/value" (a Measure vetted, stamped in KpiRoutes). An agent's answer is stamped and holds no raw
     * key; with every stamp a no-op the agent gets 500 with no row while a person still gets the answer. Falsified:
     * removing the KpiRoutes stamp.
     */
    @Test
    void anAgentsValueGetsAreProvenMaskedOrWithheld(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            List<String> paths = List.of("/datasets/calls_ds/rows", "/db/table?name=calls", "/kpis/callers/value");
            for (String path : paths) {
                HttpResponse<String> agent = get(c, AGENT, path);
                assertEquals(200, agent.statusCode(), path + ": " + agent.body());
                assertNoRaw(path, agent.body());
            }
            AnswerMasking.ignoreStampsForTest(true);
            for (String path : paths) {
                HttpResponse<String> agent = get(c, AGENT, path);
                assertEquals(500, agent.statusCode(), path + ": " + agent.body());
                assertTrue(agent.body().contains(ControlApi.MASKING_NOT_PROVEN), agent.body());
                assertNoRaw(path + " unstamped", agent.body());
                assertFalse(agent.body().contains("masked:") || agent.body().contains("EU"), "no row leaves: " + agent.body());
                assertEquals(200, get(c, PERSON, path).statusCode(), "a person is unchanged: " + path);
            }
        }
    }

    @Test
    void aSpaceWithMaskingModeNoneAnswersAnAgentRaw(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "masking_mode: none\n")) {
            HttpResponse<String> direct = query(c, AGENT, "SELECT msisdn, region FROM calls");
            assertEquals(200, direct.statusCode(), "stamped with zero masked: " + direct.body());
            assertTrue(direct.body().contains(A), "the Space masks nothing for anyone (D-AK14): " + direct.body());
        }
    }

    @Test
    void theAgentBudgetIs429PastItsBoundOnEachDoorPerClientAndSubject(@TempDir Path root) throws Exception {
        System.setProperty(BUDGET, "3");
        System.setProperty(REFILL, "0.01");   // no refill within the test
        try (Ctx c = open(root, null)) {
            for (int i = 0; i < 3; i++) assertEquals(200, query(c, AGENT, "SELECT region FROM calls").statusCode(), "direct #" + i);
            HttpResponse<String> past = query(c, AGENT, "SELECT region FROM calls");
            assertEquals(429, past.statusCode(), past.body());
            assertTrue(past.body().contains("agent data budget"), past.body());
            assertTrue(past.headers().firstValue("Retry-After").isPresent(), "429 says when to retry");
            JsonNode sameBudget = tool(c, AGENT, "SELECT region FROM calls");
            assertTrue(text(sameBudget).startsWith("HTTP 429"), "the MCP door spends the same budget: " + sameBudget);

            // another agent client of the same person has its own budget, and the MCP replay spends it
            for (int i = 0; i < 3; i++) {
                JsonNode ok = tool(c, AGENT2, "SELECT region FROM calls");
                assertFalse(ok.path("isError").asBoolean(), "MCP #" + i + ": " + ok);
            }
            JsonNode mcpPast = tool(c, AGENT2, "SELECT region FROM calls");
            assertTrue(mcpPast.path("isError").asBoolean() && text(mcpPast).startsWith("HTTP 429"), mcpPast.toString());

            // the person's own budget is untouched: its full standard burst of 20 still answers
            for (int i = 0; i < 20; i++)
                assertEquals(200, query(c, PERSON, "SELECT region FROM calls").statusCode(), "the person's request #" + i);
        }
    }

    @Test
    void noRequestValueReachesAnAuditRowOrALogLine(@TempDir Path root) throws Exception {
        String secret = "ZQXS0SECRET4417";
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
        try (Ctx c = open(root, null)) {
            String sql = "SELECT region, '" + secret + "' AS tag FROM calls";
            assertEquals(200, query(c, AGENT, sql).statusCode());
            assertFalse(tool(c, AGENT, sql).path("isError").asBoolean());
            AnswerMasking.ignoreStampsForTest(true);   // the withheld answer's log line names the route, never the request
            assertEquals(500, query(c, AGENT, sql).statusCode());
            AnswerMasking.ignoreStampsForTest(false);

            StringBuilder rows = new StringBuilder();
            for (String type : List.of("AUDIT", "ACCESS_DENIED")) {
                HttpResponse<String> audit = client.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + c.port + "/api/v1/spaces/s1/audit/search?type=" + type + "&limit=500"))
                        .header("Authorization", ADMIN).GET().build(), BodyHandlers.ofString());
                assertEquals(200, audit.statusCode(), audit.body());
                rows.append(audit.body());
            }
            assertFalse(rows.toString().contains(secret), "no audit row holds a body value: " + rows);
            assertTrue(rows.toString().contains("mcp.tool.called") && rows.toString().contains("query_dataset"),
                    "the MCP call leaves its own row: " + rows);
        } finally {
            rootLogger.detachAppender(logs);
        }
        for (ILoggingEvent e : logs.list)
            assertFalse(e.getFormattedMessage().contains(secret), "no log line holds a body value: " + e.getFormattedMessage());
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("without a masking stamp")),
                "the withheld answer is logged");
    }
}

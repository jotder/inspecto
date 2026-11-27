package com.gamma.mcp;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.control.ControlApi;
import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.EntityTypes;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGENT-KIT-1 W6 group E over real HTTP, on both doors (direct REST and the MCP {@code preview_score} /
 * {@code check_screening} replays), for a person's agent and a machine agent: {@code POST /risk-scores/preview} and
 * {@code POST /screening/check} answer masked (D-AK13: the key as the Space token, a masked list's entry as its token);
 * each call spends its weight in the agent budget - 5 for a score preview, 2 for a check - and 429 past it on each door,
 * never the person's own (D-AK17); no submitted name, identifier or key reaches an audit row or a log line, and each MCP
 * call leaves its {@code mcp.tool.called} row (D-AK19). Each route's masked-column and unstamped-500 proof is in its own
 * module's test ({@code AgentDataRoutesParityTest}).
 */
class ControlApiAgentAnalystPreviewsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The raw entity key, a screened name, its identifier and the caller's subject key: none may reach a row or a log. */
    private static final String KEY = "447700900123", NAME = "Zarkov Quillfeather", IDENT = "ID-ZQ-77821", SUBJECT = "ZQKEY9931";
    /** The list member as stored (the default normaliser folds case). */
    private static final String MEMBER = "zarkov quillfeather";
    private static final String PERSON = "Bearer person", AGENT = "Bearer agent", AGENT2 = "Bearer agent2", AGENT3 = "Bearer agent3", AGENT4 = "Bearer agent4",
            MACHINE = "Bearer machine", ADMIN = "Bearer admin";
    private static final String BUDGET = "control.rateLimit.agent.capacity", REFILL = "control.rateLimit.agent.refillPerSecond";
    private static final Set<String> CAPS = Set.of("canWorkIncidents");
    private final HttpClient client = HttpClient.newHttpClient();
    private String inheritedWriteRoot;

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
        }
    }

    @BeforeEach
    void arm() {
        inheritedWriteRoot = System.getProperty("assist.write.root");
        System.clearProperty("assist.write.root");
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if (ADMIN.equals(h)) {
                Roles.Def def = Roles.effective(ex).get("admin");
                ComponentAccess.heldRoles(ex, Set.of("admin"));
                return Optional.of(new Subject("admin-1", def.capabilities(), def.dataScopes()));
            }
            return switch (h) {
                case PERSON -> Optional.of(new Subject("analyst-1", CAPS));
                case AGENT -> Optional.of(new Subject("analyst-1", CAPS, null, Map.of(), null, null, null, "claude-code"));
                case AGENT2 -> Optional.of(new Subject("analyst-1", CAPS, null, Map.of(), null, null, null, "cursor"));
                case AGENT3 -> Optional.of(new Subject("analyst-1", CAPS, null, Map.of(), null, null, null, "codex"));
                case AGENT4 -> Optional.of(new Subject("analyst-1", CAPS, null, Map.of(), null, null, null, "gemini"));
                case MACHINE -> Optional.of(new Subject("client:bot", CAPS, null, Map.of(), null, null, "svc", "bot"));
                default -> Optional.empty();
            };
        });
    }

    @AfterEach
    void disarm() {
        AnswerMasking.ignoreStampsForTest(false);
        Authenticators.forTest(null);
        System.clearProperty(BUDGET);
        System.clearProperty(REFILL);
        if (inheritedWriteRoot != null) System.setProperty("assist.write.root", inheritedWriteRoot);
    }

    /** Space s1 (masking on): store {@code topups} keyed by a classified msisdn, Risk Score model {@code subs}, list {@code watch}. */
    private Ctx open(Path root) throws Exception {
        Path base = root.resolve("s1");
        Path config = base.resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Path store = Files.createDirectories(base.resolve("data").resolve("topups"));
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("e_previews_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('" + KEY + "','FAILED',10.0,'t1')) AS v(msisdn, status, amount, topup_id)) TO '"
                    + store.resolve("data.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        ComponentStore registry = new ComponentStore(config.resolve("registry"));
        registry.write("dataset", "topups", Map.of("physicalRef", "topups",
                "columns", List.of(Map.of("name", "msisdn", "classification", "msisdn"),
                        Map.of("name", "topup_id", "classification", "PII"), Map.of("name", "status"))));
        registry.write("risk-score", "subs", Map.of("entityType", "subscriber", "highThreshold", 50,
                "factors", List.of(Map.of("id", "failed", "dataset", "topups", "key", "msisdn", "measure", "count",
                        "filters", List.of(Map.of("field", "status", "op", "=", "value", "FAILED")),
                        "weight", 30, "cap", 45, "evidence", List.of("topup_id")))));
        Roles.write(config, Map.of(), List.of(), List.of("bot"));   // the Space admits the machine agent client 'bot'
        EntityFactLog log = new EntityFactLog(config);
        log.append(log.read(), "analyst-1", "test", "list.created", "watch",
                Map.of("title", "watch", "purpose", "block", "entityType", "subscriber", "normaliser", "default"));
        log.append(log.read(), "analyst-1", "test", "list.member.added", "watch",
                Map.of("keys", List.of(EntityTypes.normalise("default", NAME))));
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private static String previewBody() throws Exception {
        return JSON.writeValueAsString(Map.of("model", "subs", "entityKey", KEY));
    }

    private static Map<String, Object> checkBody() {
        return Map.of("lists", List.of("watch"), "maxMatches", 50,
                "subjects", List.of(Map.of("key", SUBJECT, "name", NAME, "identifier", IDENT)));
    }

    private HttpResponse<String> post(Ctx c, String auth, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/s1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> preview(Ctx c, String auth) throws Exception {
        return post(c, auth, "/risk-scores/preview", previewBody());
    }

    private HttpResponse<String> check(Ctx c, String auth) throws Exception {
        return post(c, auth, "/screening/check", JSON.writeValueAsString(checkBody()));
    }

    /** An MCP tool call; its {@code result} (an {@code isError} tool result when the replay failed). */
    private JsonNode tool(Ctx c, String auth, String name, Map<String, Object> arguments) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments);
        params.put("_meta", Map.of(McpRoutes.META_VERSION, McpRoutes.MODERN,
                McpRoutes.META_CLIENT, Map.of("name", "TestAgent", "version", "1.0")));
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/s1/mcp"))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .header("MCP-Protocol-Version", McpRoutes.MODERN).header("Mcp-Method", "tools/call").header("Mcp-Name", name)
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", params)))).build(), BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        JsonNode result = JSON.readTree(res.body()).get("result");
        assertNotNull(result, res.body());
        return result;
    }

    private JsonNode previewTool(Ctx c, String auth) throws Exception {
        return tool(c, auth, "preview_score", Map.of("kind", "risk", "body", Map.of("model", "subs", "entityKey", KEY)));
    }

    /**
     * The Anomaly Score preview against a model this Space does not hold: the route answers 404 after the gate let the
     * agent in and charged its weight (its masked answer is proven over the corpus in {@code ControlApiAnomalyScoreTest}).
     */
    private HttpResponse<String> anomaly(Ctx c, String auth) throws Exception {
        return post(c, auth, "/anomaly-scores/preview", JSON.writeValueAsString(Map.of("model", "usage", "entityKey", KEY)));
    }

    private JsonNode anomalyTool(Ctx c, String auth) throws Exception {
        return tool(c, auth, "preview_score", Map.of("kind", "anomaly", "body", Map.of("model", "usage", "entityKey", KEY)));
    }

    private JsonNode checkTool(Ctx c, String auth) throws Exception {
        return tool(c, auth, "check_screening", Map.of("body", checkBody()));
    }

    private static String text(JsonNode toolResult) {
        return toolResult.at("/content/0/text").asText();
    }

    /** The answer holds the token and neither the raw key nor the stored member. */
    private static void assertMasked(String what, String body) {
        assertFalse(body.contains(KEY), what + " leaked the raw key: " + body);
        assertFalse(body.contains(MEMBER), what + " leaked the list member: " + body);
        assertTrue(body.contains("masked:"), what + " carries the token: " + body);
    }

    @Test
    void aPersonsAgentAndAMachineGetMaskedAnswersOnBothDoors(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            for (String auth : List.of(AGENT, MACHINE)) {
                HttpResponse<String> direct = preview(c, auth);
                assertEquals(200, direct.statusCode(), auth + ": " + direct.body());
                assertMasked(auth + " preview", direct.body());
                assertTrue(direct.body().contains("\"found\":true"), "the raw key sent is scored (D-AK15): " + direct.body());

                JsonNode viaMcp = previewTool(c, auth);
                assertFalse(viaMcp.path("isError").asBoolean(), viaMcp.toString());
                assertMasked(auth + " preview_score", viaMcp.toString());

                HttpResponse<String> checked = check(c, auth);
                assertEquals(200, checked.statusCode(), auth + ": " + checked.body());
                assertMasked(auth + " check", checked.body());

                JsonNode checkedViaMcp = checkTool(c, auth);
                assertFalse(checkedViaMcp.path("isError").asBoolean(), checkedViaMcp.toString());
                assertMasked(auth + " check_screening", checkedViaMcp.toString());

                HttpResponse<String> anomalyDirect = anomaly(c, auth);   // admitted on both doors: the route's own 404
                assertEquals(404, anomalyDirect.statusCode(), auth + ": " + anomalyDirect.body());
                assertFalse(anomalyDirect.body().contains(KEY), anomalyDirect.body());
                JsonNode anomalyViaMcp = anomalyTool(c, auth);
                assertTrue(text(anomalyViaMcp).startsWith("HTTP 404"), auth + " preview_score anomaly: " + anomalyViaMcp);
                assertFalse(anomalyViaMcp.toString().contains(KEY), anomalyViaMcp.toString());
            }
        }
    }

    @Test
    void eachCallSpendsItsWeightOnBothDoorsAndNeverThePersonsBudget(@TempDir Path root) throws Exception {
        System.setProperty(BUDGET, "5");
        System.setProperty(REFILL, "0.01");   // no refill within the test
        try (Ctx c = open(root)) {
            assertEquals(200, preview(c, AGENT).statusCode(), "a score preview weighs 5: the whole burst");
            HttpResponse<String> past = check(c, AGENT);
            assertEquals(429, past.statusCode(), "nothing left for a check's 2: " + past.body());
            assertTrue(past.body().contains("agent data budget"), past.body());
            assertTrue(past.headers().firstValue("Retry-After").isPresent(), "429 says when to retry");
            assertTrue(text(previewTool(c, AGENT)).startsWith("HTTP 429"), "the MCP door spends the same budget");

            // another agent client of the same person: a check weighs 2, so two fit in 5 and a third does not
            for (int i = 0; i < 2; i++) assertFalse(checkTool(c, AGENT2).path("isError").asBoolean(), "MCP check #" + i);
            JsonNode third = checkTool(c, AGENT2);
            assertTrue(third.path("isError").asBoolean() && text(third).startsWith("HTTP 429"), third.toString());

            assertEquals(200, check(c, MACHINE).statusCode(), "a machine agent has its own budget");

            // a third client: the anomaly preview weighs 5 too, charged before the route answers
            assertEquals(404, anomaly(c, AGENT3).statusCode(), "the whole burst spent on the anomaly preview");
            assertEquals(429, anomaly(c, AGENT3).statusCode(), "nothing left for a second one");
            assertTrue(text(anomalyTool(c, AGENT3)).startsWith("HTTP 429"), "nor through preview_score kind anomaly");

            // D-AK24: a check costs 2 per started 10 subjects - 11 subjects spend 4 of 5, so a 1-subject check (2) is refused
            List<Map<String, Object>> eleven = new java.util.ArrayList<>();
            for (int i = 0; i < 11; i++) eleven.add(Map.of("name", NAME));
            assertEquals(200, post(c, AGENT4, "/screening/check", JSON.writeValueAsString(
                    Map.of("lists", List.of("watch"), "subjects", eleven))).statusCode(), "11 subjects cost 4");
            JsonNode after = checkTool(c, AGENT4);
            assertTrue(after.path("isError").asBoolean() && text(after).startsWith("HTTP 429"), "1 token left: " + after);
            for (int i = 0; i < 3; i++)
                assertEquals(200, preview(c, PERSON).statusCode(), "the person's own previews spend no agent budget #" + i);
        }
    }

    @Test
    void noSubmittedNameIdentifierOrKeyReachesAnAuditRowOrALogLine(@TempDir Path root) throws Exception {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
        List<String> secrets = List.of(KEY, NAME, MEMBER, "Quillfeather", IDENT, SUBJECT);
        try (Ctx c = open(root)) {
            for (String auth : List.of(AGENT, MACHINE)) {
                assertEquals(200, preview(c, auth).statusCode());
                assertEquals(200, check(c, auth).statusCode());
                assertFalse(previewTool(c, auth).path("isError").asBoolean());
                assertFalse(checkTool(c, auth).path("isError").asBoolean());
                assertEquals(404, anomaly(c, auth).statusCode());
                anomalyTool(c, auth);
            }
            Map<String, Object> personBody = new LinkedHashMap<>(checkBody());
            personBody.put("maxMatches", 20);   // a person keeps the 1..20 range; 50 is an agent's clamp only
            assertEquals(200, post(c, PERSON, "/screening/check", JSON.writeValueAsString(personBody)).statusCode());
            AnswerMasking.ignoreStampsForTest(true);   // the withheld answer's log line names the route, never the request
            assertEquals(500, check(c, AGENT).statusCode());
            assertEquals(500, preview(c, AGENT).statusCode());
            AnswerMasking.ignoreStampsForTest(false);

            StringBuilder rows = new StringBuilder();
            for (String type : List.of("AUDIT", "ACCESS_DENIED")) {
                HttpResponse<String> audit = client.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + c.port + "/api/v1/spaces/s1/audit/search?type=" + type + "&limit=500"))
                        .header("Authorization", ADMIN).GET().build(), BodyHandlers.ofString());
                assertEquals(200, audit.statusCode(), audit.body());
                rows.append(audit.body());
            }
            for (String s : secrets) assertFalse(rows.toString().contains(s), "an audit row holds '" + s + "': " + rows);
            assertTrue(rows.toString().contains("mcp.tool.called") && rows.toString().contains("check_screening")
                    && rows.toString().contains("preview_score"), "each MCP call leaves its own row: " + rows);
        } finally {
            rootLogger.detachAppender(logs);
        }
        for (ILoggingEvent e : logs.list)
            for (String s : secrets)
                assertFalse(e.getFormattedMessage().contains(s), "a log line holds '" + s + "': " + e.getFormattedMessage());
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("without a masking stamp")),
                "the withheld answer is logged");
    }
}

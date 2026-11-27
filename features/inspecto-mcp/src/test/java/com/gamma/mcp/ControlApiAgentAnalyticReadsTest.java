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
 * AGENT-KIT-1 W6 group D, change D1 of the signed design ({@code 2026-11-26-agent-data-answers-masking-design.md}) over
 * real HTTP: the analytic reads {@code POST /bi/query} (MCP Tool {@code query_measure}) and
 * {@code POST /queries/([^/]+)/run} ({@code run_saved_query}), for a person's agent and a machine agent, on both doors.
 * A masked column comes back as the Space token; with every stamp a no-op the answer is withheld as 500
 * {@code masking not proven} while a person is unchanged (D-AK13); a saved query that names no Dataset runs no masking
 * step and is withheld from an agent; the existing value-oracle vets still refuse a filter or sort on a masked column
 * (D-AK15 adds no raw path there); a {@code none} Space answers raw (D-AK14); the agent budget is charged on both doors
 * and answers 429 past it, the person's own buckets untouched (D-AK17); no request value reaches an audit row or a log
 * line and the MCP call leaves its {@code mcp.tool.called} row (D-AK19).
 */
class ControlApiAgentAnalyticReadsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "441700900101", B = "441700900202";
    private static final String PERSON = "Bearer person", AGENT = "Bearer agent", MACHINE = "Bearer machine", ADMIN = "Bearer admin";
    private static final String BUDGET = "control.rateLimit.agent.capacity", REFILL = "control.rateLimit.agent.refillPerSecond";
    /** The Measure query a Widget grouped by the masked key would run. */
    private static final Map<String, Object> BY_MSISDN = Map.of("dataset", "calls_ds",
            "measures", List.of(Map.of("id", "n", "agg", "count")), "groupBy", List.of("msisdn"));
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
            Set<String> machineCaps = new LinkedHashSet<>(agentCaps);
            machineCaps.retainAll(Roles.AGENT_MACHINE_CAPABILITIES);
            return switch (h) {
                case PERSON -> Optional.of(new Subject("analyst-1", def.capabilities(), def.dataScopes()));
                case AGENT -> Optional.of(new Subject("analyst-1", agentCaps, def.dataScopes(), Map.of(), null, null, null, "claude-code"));
                case MACHINE -> Optional.of(new Subject("client:bot", machineCaps, def.dataScopes(), Map.of(), null, null, "svc", "bot"));
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

    /**
     * Space s1: the Parquet store {@code calls} behind the Dataset {@code calls_ds} (msisdn classified), the saved query
     * {@code calls_q} over it, and {@code bare_q}, a saved query that names no Dataset.
     */
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
        File db = DuckDbUtil.tempDbFile("d1seam_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('" + A + "','EU',10.0),('" + B + "','US',7.0)) "
                    + "t(msisdn,region,amount)) TO '" + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        ComponentStore reg = new ComponentStore(config.resolve("registry"));
        reg.write("dataset", "calls_ds", Map.of("physicalRef", "calls",
                "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "region"),
                        Map.of("name", "amount"))));
        reg.write("query", "calls_q", Map.of("type", "sql", "datasetId", "calls_ds",
                "text", "SELECT msisdn, region, amount FROM calls_ds"));
        reg.write("query", "bare_q", Map.of("type", "sql", "text", "SELECT 'x' AS tag"));
        reg.write("query", "alias_q", Map.of("type", "sql", "datasetId", "calls_ds",   // the key under a clean name
                "text", "SELECT amount, msisdn AS region FROM calls_ds"));
        if (laSettings != null) Files.writeString(config.resolve("link-analysis.toon"), laSettings);
        Roles.write(config, Map.of(), List.of(), List.of("bot"));   // the Space admits the machine client
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private HttpResponse<String> post(Ctx c, String auth, String path, Map<String, Object> body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/s1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> measure(Ctx c, String auth, Map<String, Object> spec) throws Exception {
        return post(c, auth, "/bi/query", spec);
    }

    private HttpResponse<String> run(Ctx c, String auth, String query, Map<String, Object> body) throws Exception {
        return post(c, auth, "/queries/" + query + "/run", body);
    }

    /** An MCP {@code tools/call}; its {@code result} (an {@code isError} tool result when the replay failed). */
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

    private JsonNode measureTool(Ctx c, String auth, Map<String, Object> spec) throws Exception {
        return tool(c, auth, "query_measure", Map.of("body", spec));
    }

    private JsonNode runTool(Ctx c, String auth, String query) throws Exception {
        return tool(c, auth, "run_saved_query", Map.of("query", query));
    }

    private static String text(JsonNode toolResult) {
        return toolResult.at("/content/0/text").asText();
    }

    private static void assertNoRaw(String what, String body) {
        for (String raw : List.of(A, B)) assertFalse(body.contains(raw), what + " leaked " + raw + ": " + body);
    }

    private static void assertMasked(String what, String body) {
        assertNoRaw(what, body);
        assertTrue(body.contains("masked:"), what + " carries the Space token: " + body);
    }

    @Test
    void aPersonsAgentAndAMachineAgentGetTheMaskedColumnAsATokenOnBothDoors(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            for (String who : List.of(AGENT, MACHINE)) {
                HttpResponse<String> bi = measure(c, who, BY_MSISDN);
                assertEquals(200, bi.statusCode(), who + " /bi/query: " + bi.body());
                assertMasked(who + " /bi/query", bi.body());
                HttpResponse<String> q = run(c, who, "calls_q", Map.of());
                assertEquals(200, q.statusCode(), who + " /queries/calls_q/run: " + q.body());
                assertMasked(who + " /queries/calls_q/run", q.body());
                assertTrue(q.body().contains("EU"), "an unclassified column stays as it is: " + q.body());

                JsonNode biTool = measureTool(c, who, BY_MSISDN);
                assertFalse(biTool.path("isError").asBoolean(), biTool.toString());
                assertMasked(who + " query_measure", biTool.toString());
                JsonNode qTool = runTool(c, who, "calls_q");
                assertFalse(qTool.path("isError").asBoolean(), qTool.toString());
                assertMasked(who + " run_saved_query", qTool.toString());
            }
        }
    }

    @Test
    void anUnstampedAnswerIsWithheldOnBothDoorsAndAPersonIsUnchanged(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            AnswerMasking.ignoreStampsForTest(true);   // the routes' masking step no longer proves itself
            for (HttpResponse<String> r : List.of(measure(c, AGENT, BY_MSISDN), run(c, AGENT, "calls_q", Map.of()),
                    measure(c, MACHINE, BY_MSISDN), run(c, MACHINE, "calls_q", Map.of()))) {
                assertEquals(500, r.statusCode(), r.body());
                assertTrue(r.body().contains(ControlApi.MASKING_NOT_PROVEN), r.body());
                assertNoRaw("unstamped", r.body());
                assertFalse(r.body().contains("masked:") || r.body().contains("EU"), "no row leaves: " + r.body());
            }
            for (JsonNode t : List.of(measureTool(c, AGENT, BY_MSISDN), runTool(c, AGENT, "calls_q"))) {
                assertTrue(t.path("isError").asBoolean() && text(t).startsWith("HTTP 500"), t.toString());
                assertNoRaw("MCP unstamped", t.toString());
                assertFalse(t.toString().contains("masked:") || t.toString().contains("EU"), "no row leaves: " + t);
            }
            HttpResponse<String> bi = measure(c, PERSON, BY_MSISDN), q = run(c, PERSON, "calls_q", Map.of());
            assertEquals(200, bi.statusCode(), "a person's request does not pass the seam: " + bi.body());
            assertEquals(200, q.statusCode(), q.body());
            assertMasked("a person, as before", bi.body() + q.body());
        }
    }

    /** A saved query that names no Dataset has no masking step to run, so it can never prove its answer (D-AK13). */
    @Test
    void aSavedQueryThatNamesNoDatasetIsWithheldFromAnAgent(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            HttpResponse<String> agent = run(c, AGENT, "bare_q", Map.of());
            assertEquals(500, agent.statusCode(), agent.body());
            assertTrue(agent.body().contains(ControlApi.MASKING_NOT_PROVEN), agent.body());
            JsonNode viaMcp = runTool(c, MACHINE, "bare_q");
            assertTrue(viaMcp.path("isError").asBoolean() && text(viaMcp).startsWith("HTTP 500"), viaMcp.toString());
            HttpResponse<String> person = run(c, PERSON, "bare_q", Map.of());
            assertEquals(200, person.statusCode(), "a person is unchanged: " + person.body());
        }
    }

    /** The value-oracle vets refuse an agent as they refuse a person who cannot reveal (design §3.2: no new raw path). */
    @Test
    void aFilterOrSortOnAMaskedColumnIsStillRefused(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            HttpResponse<String> filter = measure(c, AGENT, Map.of("dataset", "calls_ds",
                    "measures", List.of(Map.of("id", "n", "agg", "count")),
                    "filters", List.of(Map.of("field", "msisdn", "op", "=", "value", A))));
            assertEquals(422, filter.statusCode(), filter.body());
            HttpResponse<String> max = measure(c, AGENT, Map.of("dataset", "calls_ds",
                    "measures", List.of(Map.of("id", "m", "agg", "max", "field", "msisdn"))));
            assertEquals(422, max.statusCode(), max.body());
            assertNoRaw("refused max", max.body());
            HttpResponse<String> sort = run(c, AGENT, "calls_q", Map.of("sort", List.of(Map.of("field", "msisdn"))));
            assertEquals(422, sort.statusCode(), sort.body());
        }
    }

    /** Security review F1: a caller's projection reorders the author's columns; the masking must not lose the key. */
    @Test
    void aProjectionThatReordersTheColumnsStillMasksTheKey(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            Map<String, Object> reorder = Map.of("projection", List.of("region", "amount"));
            for (String who : List.of(AGENT, MACHINE)) {
                HttpResponse<String> q = run(c, who, "alias_q", reorder);
                assertEquals(200, q.statusCode(), who + ": " + q.body());
                assertMasked(who + " projected alias_q", q.body());
                assertTrue(q.body().indexOf("\"region\"") < q.body().indexOf("\"amount\""), "the projection's order: " + q.body());
            }
            JsonNode viaMcp = tool(c, AGENT, "run_saved_query", Map.of("query", "alias_q", "body", reorder));
            assertFalse(viaMcp.path("isError").asBoolean(), text(viaMcp));
            assertMasked("run_saved_query projected", text(viaMcp));
            assertEquals(422, run(c, AGENT, "alias_q", Map.of("projection", List.of("nope"))).statusCode());
        }
    }

    @Test
    void aSpaceWithMaskingModeNoneAnswersAnAgentRaw(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "masking_mode: none\n")) {
            HttpResponse<String> bi = measure(c, AGENT, BY_MSISDN), q = run(c, AGENT, "calls_q", Map.of());
            assertEquals(200, bi.statusCode(), "stamped with zero masked: " + bi.body());
            assertEquals(200, q.statusCode(), q.body());
            assertTrue(bi.body().contains(A) && q.body().contains(A), "the Space masks nothing for anyone (D-AK14)");
        }
    }

    @Test
    void theAgentBudgetIsChargedOnBothDoorsAnd429PastItsBound(@TempDir Path root) throws Exception {
        // 20: the heaviest agent call weighs 20 (a lookup test that builds its index), and RateLimiter.agent raises a
        // lower burst to it
        System.setProperty(BUDGET, "20");
        System.setProperty(REFILL, "0.01");   // no refill within the test
        try (Ctx c = open(root, null)) {
            for (int i = 0; i < 8; i++) {
                assertEquals(200, measure(c, AGENT, BY_MSISDN).statusCode(), "direct /bi/query spends 1 #" + i);
                assertEquals(200, run(c, AGENT, "calls_q", Map.of()).statusCode(), "direct /queries/{id}/run spends 1 #" + i);
            }
            for (int i = 0; i < 2; i++) {
                assertFalse(measureTool(c, AGENT, BY_MSISDN).path("isError").asBoolean(), "query_measure spends 1 #" + i);
                assertFalse(runTool(c, AGENT, "calls_q").path("isError").asBoolean(), "run_saved_query spends 1 #" + i);
            }
            HttpResponse<String> past = run(c, AGENT, "calls_q", Map.of());
            assertEquals(429, past.statusCode(), "the budget of 20 is spent across both routes and both doors: " + past.body());
            assertTrue(past.body().contains("agent data budget"), past.body());
            assertTrue(past.headers().firstValue("Retry-After").isPresent(), "429 says when to retry");
            assertEquals(429, measure(c, AGENT, BY_MSISDN).statusCode());
            assertTrue(text(measureTool(c, AGENT, BY_MSISDN)).startsWith("HTTP 429"), "the MCP door spends the same budget");
            assertTrue(text(runTool(c, AGENT, "calls_q")).startsWith("HTTP 429"));

            // the machine agent's budget is its own, and the person's own requests are untouched
            assertEquals(200, run(c, MACHINE, "calls_q", Map.of()).statusCode(), "another client and subject");
            for (int i = 0; i < 10; i++) {
                assertEquals(200, run(c, PERSON, "calls_q", Map.of()).statusCode(), "the person's run #" + i);
                assertEquals(200, measure(c, PERSON, BY_MSISDN).statusCode(), "the person's Measure query #" + i);
            }
        }
    }

    @Test
    void noRequestValueReachesAnAuditRowOrALogLine(@TempDir Path root) throws Exception {
        String secret = "ZQXD1SECRET4417";
        Map<String, Object> spec = Map.of("dataset", "calls_ds", "measures", List.of(Map.of("id", "n", "agg", "count")),
                "groupBy", List.of("region"), "filters", List.of(Map.of("field", "region", "op", "=", "value", secret)));
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
        try (Ctx c = open(root, null)) {
            assertEquals(200, measure(c, AGENT, spec).statusCode());
            assertFalse(measureTool(c, AGENT, spec).path("isError").asBoolean());
            Map<String, Object> runBody = Map.of("projection", List.of("region"), "limit", 7);
            assertFalse(tool(c, AGENT, "run_saved_query", Map.of("query", "calls_q", "body", runBody)).path("isError").asBoolean());
            AnswerMasking.ignoreStampsForTest(true);   // the withheld answer's log line names the route, never the request
            assertEquals(500, measure(c, AGENT, spec).statusCode());
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
            assertTrue(rows.toString().contains("mcp.tool.called") && rows.toString().contains("query_measure")
                    && rows.toString().contains("run_saved_query"), "each MCP call leaves its own row: " + rows);
        } finally {
            rootLogger.detachAppender(logs);
        }
        for (ILoggingEvent e : logs.list)
            assertFalse(e.getFormattedMessage().contains(secret), "no log line holds a body value: " + e.getFormattedMessage());
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("without a masking stamp")),
                "the withheld answer is logged");
    }
}

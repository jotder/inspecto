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
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.AnswerMasking;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGENT-LOOKUP-PREVIEW-1, the last change of the signed design ({@code 2026-11-26-agent-data-answers-masking-design.md})
 * over real HTTP: {@code POST /lookups/test} (MCP Tool {@code test_lookup}) for a person's agent and a machine agent, on
 * both doors. Over a by-name Reference the classified matched key, mapped column and fuzzy candidates come back as tokens,
 * a raw value asked included (D-AK15), and at most five candidates (D-AK16: the 6th is cut); with every stamp a no-op the
 * answer is withheld as 500 {@code masking not proven} while a person is unchanged (D-AK13). Over a {@code path:}
 * Reference a person's agent gets the raw answer and a machine agent 403 before any index is built (D-AK25). A plain test
 * weighs 1 and one that builds the index 20 in the agent budget, which is the agent's own (D-AK17); no submitted value
 * reaches a log line (D-AK19). The people refusals hold ({@code ControlApiLookupTestAccessTest}).
 */
class ControlApiAgentLookupTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PERSON = "Bearer person", AGENT = "Bearer agent", AGENT2 = "Bearer agent2",
            AGENT3 = "Bearer agent3", MACHINE = "Bearer machine", READER = "Bearer reader";
    private static final String BUDGET = "control.rateLimit.agent.capacity", REFILL = "control.rateLimit.agent.refillPerSecond";

    /** Seven subscribers whose keys differ only in the last digit, so one fuzzy value matches all of them. */
    private static final List<String> KEYS = List.of("249910000001", "249910000002", "249910000003", "249910000004",
            "249910000005", "249910000006", "249910000007");
    private static final String OWNER_OF_FIRST = "447700900123";

    private static final String BY_NAME = """
            output_store: enriched_calls
            steps[2]:
              - join:
                  reference: reference/region_dim
                  key: msisdn
                  match: exact
                  on: ID
                  columns[2]: owner_msisdn, region
                  details: true
              - join:
                  reference: reference/region_dim
                  key: msisdn
                  match: fuzzy
                  method: jaro_winkler
                  threshold: 0.5
                  top_k: 10
                  on: AMT
                  columns: region
            """;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final String PRIOR_ROOTS = System.getProperty("assist.safety.roots");

    @BeforeEach
    void arm() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.INVESTIGATIONS));   // masking on
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            Roles.Def def = Roles.effective(ex).get("developer");
            ComponentAccess.heldRoles(ex, Set.of("developer"));
            Set<String> agentCaps = new LinkedHashSet<>(def.capabilities());
            agentCaps.removeAll(Roles.AGENT_NEVER_CAPABILITIES);   // an agent never reveals
            Set<String> machineCaps = new LinkedHashSet<>(agentCaps);
            machineCaps.retainAll(Roles.AGENT_MACHINE_CAPABILITIES);
            return switch (h) {
                case PERSON -> Optional.of(new Subject("dev-1", def.capabilities(), def.dataScopes()));
                case AGENT -> Optional.of(new Subject("dev-1", agentCaps, def.dataScopes(), Map.of(), null, null, null, "claude-code"));
                case AGENT2 -> Optional.of(new Subject("dev-1", agentCaps, def.dataScopes(), Map.of(), null, null, null, "cursor"));
                case AGENT3 -> Optional.of(new Subject("dev-1", agentCaps, def.dataScopes(), Map.of(), null, null, null, "codex"));
                case MACHINE -> Optional.of(new Subject("client:bot", machineCaps, def.dataScopes(), Map.of(), null, null, "svc", "bot"));
                case READER -> Optional.of(new Subject("viewer-1", Set.of(), def.dataScopes(), Map.of(), null, null, null, "claude-code"));
                default -> Optional.empty();
            };
        });
        com.gamma.control.AgentGateProbe.clearLookups();
    }

    @AfterEach
    void disarm() {
        AnswerMasking.ignoreStampsForTest(false);
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        System.clearProperty(BUDGET);
        System.clearProperty(REFILL);
        if (PRIOR_ROOTS != null) System.setProperty("assist.safety.roots", PRIOR_ROOTS);
        else System.clearProperty("assist.safety.roots");
        com.gamma.control.AgentGateProbe.clearLookups();
    }

    private record Ctx(CollectorService svc, ControlApi api, String pipeline, String exact, String fuzzy) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /**
     * {@code REGION_DIM} ({@code produces: reference}) holding msisdn, owner_msisdn, region; the registry Dataset
     * {@code regions} over its store classifies msisdn and owner_msisdn as MSISDN; the Space admits the machine client.
     */
    private Ctx open(Path dir, String steps) throws Exception {
        System.setProperty("assist.safety.roots", dir.toString());
        Path root = dir.resolve("space");
        Path region = TestConfigs.csv(dir.resolve("region"), PipelineConfigBatchTest.miniSchema())
                .name("REGION_DIM").format("PARQUET").write();
        Files.writeString(region, "produces: reference\n" + Files.readString(region).replace("active: true", "active: false"));
        Path db = Files.createDirectories(dir.resolve("region").resolve("db"));
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < KEYS.size(); i++)
            values.append(i == 0 ? "" : ",").append("('").append(KEYS.get(i)).append("','")
                    .append(i == 0 ? OWNER_OF_FIRST : "44770090000" + i).append("','R").append(i).append("')");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:"); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") AS t(msisdn, owner_msisdn, region)) TO '"
                    + db.resolve("part0.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Files.writeString(toon, Files.readString(toon).replace("active: true", "active: false") + steps);
        new ComponentStore(root.resolve("registry")).write("dataset", "regions", Map.of("physicalRef", "region_dim",
                "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"),
                        Map.of("name", "owner_msisdn", "classification", "MSISDN"), Map.of("name", "region"))));
        Files.createDirectories(root);
        Roles.write(root, Map.of(), List.of(), List.of("bot"));   // the Space admits the machine client
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon, region), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            String name = svc.loadedPipelines().stream().map(p -> p.identity().pipelineName())
                    .filter(n -> !n.equalsIgnoreCase("REGION_DIM")).findFirst().orElseThrow();
            List<String> stepIds = new java.util.ArrayList<>();
            data(get(api, PERSON, "/lookups")).get("lookups").forEach(r -> stepIds.add(r.get("step").asText()));
            return new Ctx(svc, api, name, stepIds.get(0), stepIds.size() > 1 ? stepIds.get(1) : null);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** A Space whose one keyed lookup reads a {@code path:} Reference (a CSV under the safety roots: no classification). */
    private Ctx openPath(Path dir) throws Exception {
        Path ref = Files.createDirectories(dir.resolve("refdata")).resolve("dial.csv");
        Files.writeString(ref, "dial_digit_key,provider_desc\n249,Sudan\n44,UK\n");
        return open(dir, """
                output_store: enriched_calls
                steps[1]:
                  - join:
                      reference: %s
                      key: dial_digit_key
                      match: exact
                      on: ID
                      columns: provider_desc
                """.formatted(ref.toString().replace('\\', '/')));
    }

    private HttpResponse<String> get(ControlApi api, String auth, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1" + path))
                .header("Authorization", auth).GET().build(), BodyHandlers.ofString());
    }

    private static Map<String, Object> body(Ctx c, String step, String value, boolean refresh) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("pipeline", c.pipeline, "step", step, "value", value));
        if (refresh) b.put("refresh", true);
        return b;
    }

    private HttpResponse<String> test(Ctx c, String auth, Map<String, Object> body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.api.port() + "/api/v1/lookups/test"))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;   // the v1 envelope's data
    }

    /** An MCP {@code tools/call} of {@code test_lookup}; its {@code result} (an {@code isError} result when the replay failed). */
    private JsonNode tool(Ctx c, String auth, Map<String, Object> body) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "test_lookup");
        params.put("arguments", Map.of("body", body));
        params.put("_meta", Map.of(McpRoutes.META_VERSION, McpRoutes.MODERN,
                McpRoutes.META_CLIENT, Map.of("name", "TestAgent", "version", "1.0")));
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.api.port() + "/api/v1/mcp"))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .header("MCP-Protocol-Version", McpRoutes.MODERN).header("Mcp-Method", "tools/call").header("Mcp-Name", "test_lookup")
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

    /** The tool's answer: the replayed route's JSON (its v1 data). */
    private static JsonNode toolData(JsonNode toolResult) throws Exception {
        assertFalse(toolResult.path("isError").asBoolean(), toolResult.toString());
        JsonNode n = JSON.readTree(text(toolResult));
        return n.has("data") ? n.get("data") : n;
    }

    private static void assertNoRaw(String what, String body) {
        for (String raw : KEYS) assertFalse(body.contains(raw), what + " leaked key " + raw + ": " + body);
        assertFalse(body.contains(OWNER_OF_FIRST), what + " leaked the owner: " + body);
    }

    /** The answer without the caller's own value echoed back in {@code compared} (D-AK15: the value is the caller's). */
    private static String withoutEcho(JsonNode a) {
        return a.toString().replace("\"compared\":\"" + a.path("compared").asText() + "\"", "");
    }

    @Test
    void aMaskedKeyMappingAndCandidateComeBackAsTokensOnBothDoorsAndTheSixthCandidateIsCut(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, BY_NAME)) {
            for (String who : List.of(AGENT, MACHINE)) {
                // a raw value the agent knows (D-AK15): the answer names the matched key and the mapped columns masked
                List<JsonNode> exact = List.of(data(test(c, who, body(c, c.exact, KEYS.get(0), false))),
                        toolData(tool(c, who, body(c, c.exact, KEYS.get(0), false))));
                for (JsonNode a : exact) {
                    assertTrue(a.get("hit").asBoolean(), who + ": " + a);
                    assertNoRaw(who, withoutEcho(a));
                    assertTrue(a.get("matchedKey").asText().startsWith("masked:"), who + ": " + a);
                    JsonNode cols = a.get("mappings").get(0).get("columns");
                    assertTrue(cols.get("ID_owner_msisdn").asText().startsWith("masked:"), who + ": " + a);
                    assertTrue(cols.get("ID_matched_key").asText().startsWith("masked:"), who + ": " + a);
                    assertEquals("R0", cols.get("ID_region").asText(), "an unclassified column stays raw: " + a);
                }
                // seven keys match the fuzzy value: five candidates come back, each masked, and the 6th is cut (D-AK16)
                List<JsonNode> fuzzy = List.of(data(test(c, who, body(c, c.fuzzy, "24991000000", false))),
                        toolData(tool(c, who, body(c, c.fuzzy, "24991000000", false))));
                for (JsonNode f : fuzzy) {
                    assertNoRaw(who, f.toString());
                    assertEquals(5, f.get("candidates").size(), who + ": seven match, five are served: " + f);
                    for (JsonNode cand : f.get("candidates"))
                        assertTrue(cand.get("matchedKey").asText().startsWith("masked:"), who + ": " + f);
                }
            }
        }
    }

    @Test
    void anAnswerWithoutAMaskingStampIsWithheldWhileThePersonIsUnchanged(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, BY_NAME)) {
            AnswerMasking.ignoreStampsForTest(true);   // the route's masking step acts as if it were missing
            for (String who : List.of(AGENT, MACHINE)) {
                HttpResponse<String> direct = test(c, who, body(c, c.exact, KEYS.get(0), false));
                assertEquals(500, direct.statusCode(), who + ": " + direct.body());
                assertTrue(direct.body().contains(ControlApi.MASKING_NOT_PROVEN), direct.body());
                assertNoRaw(who, direct.body());
                JsonNode viaMcp = tool(c, who, body(c, c.fuzzy, "24991000000", false));
                assertTrue(viaMcp.path("isError").asBoolean() && text(viaMcp).startsWith("HTTP 500"), viaMcp.toString());
                assertNoRaw(who, viaMcp.toString());
            }
            JsonNode person = data(test(c, PERSON, body(c, c.exact, KEYS.get(0), false)));
            assertTrue(person.get("hit").asBoolean(), "a person's answer needs no stamp: " + person);
        }
    }

    @Test
    void aPathReferenceAnswersRawToAPersonsAgentAndIsRefusedToAMachineBeforeAnyBuild(@TempDir Path dir) throws Exception {
        try (Ctx c = openPath(dir)) {
            // D-AK25: a machine agent is refused before the index is built
            HttpResponse<String> machine = test(c, MACHINE, body(c, c.exact, "44", false));
            assertEquals(403, machine.statusCode(), machine.body());
            assertFalse(machine.body().contains("UK"), machine.body());
            JsonNode machineMcp = tool(c, MACHINE, body(c, c.exact, "44", false));
            assertTrue(machineMcp.path("isError").asBoolean() && text(machineMcp).startsWith("HTTP 403"), machineMcp.toString());
            assertEquals("cold", data(get(c.api, PERSON, "/lookups")).get("lookups").get(0).get("index").get("state").asText(),
                    "nothing was built for the machine");

            // a person's agent holding canAuthorWorkbench gets it raw, stamped as unmasked by decision, on both doors
            for (JsonNode a : List.of(data(test(c, AGENT, body(c, c.exact, "44", false))),
                    toolData(tool(c, AGENT, body(c, c.exact, "44", false))))) {
                assertEquals("44", a.get("matchedKey").asText(), a.toString());
                assertEquals("UK", a.get("mappings").get(0).get("columns").get("ID_provider_desc").asText(), a.toString());
            }
            AnswerMasking.ignoreStampsForTest(true);   // that stamp is the proof too: without it the answer is withheld
            HttpResponse<String> unstamped = test(c, AGENT, body(c, c.exact, "44", false));
            assertEquals(500, unstamped.statusCode(), unstamped.body());
            assertTrue(unstamped.body().contains(ControlApi.MASKING_NOT_PROVEN), unstamped.body());
        }
    }

    @Test
    void theRouteGateStillDecidesForAnAgent(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, BY_NAME)) {
            HttpResponse<String> reader = test(c, READER, body(c, c.exact, KEYS.get(0), false));
            assertEquals(403, reader.statusCode(), "an agent without canAuthorWorkbench: " + reader.body());
            assertNoRaw("reader", reader.body());
            JsonNode viaMcp = tool(c, READER, body(c, c.exact, KEYS.get(0), false));
            assertTrue(viaMcp.path("isError").asBoolean() && text(viaMcp).startsWith("HTTP 403"), viaMcp.toString());
        }
    }

    @Test
    void aBuildSpendsTwentyAndAPlainTestOneOfTheAgentsOwnBudgetOnBothDoors(@TempDir Path dir) throws Exception {
        System.setProperty(BUDGET, "20");     // the heaviest agent call: a lookup test that builds its index
        System.setProperty(REFILL, "0.01");   // no refill within the test
        try (Ctx c = open(dir, BY_NAME)) {
            // the cold index is built by AGENT's first call: 1 + 19 = the whole burst, so even a plain test is then refused
            assertEquals("first use", data(test(c, AGENT, body(c, c.exact, KEYS.get(0), false))).get("index").get("reloaded").asText());
            HttpResponse<String> past = test(c, AGENT, body(c, c.exact, KEYS.get(0), false));
            assertEquals(429, past.statusCode(), past.body());
            assertTrue(past.body().contains("agent data budget"), past.body());
            assertTrue(past.headers().firstValue("Retry-After").isPresent(), "429 says when to retry");
            assertTrue(text(tool(c, AGENT, body(c, c.exact, KEYS.get(0), false))).startsWith("HTTP 429"),
                    "the MCP door spends the same budget");

            // another client of the same person: refresh: true builds through the MCP door, 20, and nothing is left
            assertEquals("reload asked", toolData(tool(c, AGENT2, body(c, c.exact, KEYS.get(0), true))).get("index")
                    .get("reloaded").asText());
            HttpResponse<String> agent2Past = test(c, AGENT2, body(c, c.exact, KEYS.get(0), false));
            assertEquals(429, agent2Past.statusCode(), agent2Past.body());
            assertTrue(agent2Past.body().contains("agent data budget"), agent2Past.body());

            // a third client on the warm index: twenty plain tests fit in twenty, the 21st is the agent budget's 429
            for (int i = 0; i < 20; i++)
                assertNull(data(test(c, AGENT3, body(c, c.exact, KEYS.get(0), false))).get("index").get("reloaded").textValue(),
                        "plain test #" + i + " builds nothing");
            HttpResponse<String> agent3Past = test(c, AGENT3, body(c, c.exact, KEYS.get(0), false));
            assertEquals(429, agent3Past.statusCode(), agent3Past.body());
            assertTrue(agent3Past.body().contains("agent data budget"), agent3Past.body());

            // the person's own budgets (the host's and the route's per-caller ones) are untouched by the agents' calls
            for (int i = 0; i < 3; i++)
                assertTrue(data(test(c, PERSON, body(c, c.exact, KEYS.get(0), false))).get("hit").asBoolean(), "person #" + i);
        }
    }

    @Test
    void noSubmittedValueReachesALogLine(@TempDir Path dir) throws Exception {
        String secret = "249919998877";   // a raw value the agent asks for (D-AK15): a miss, so its answer holds no key
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
        try (Ctx c = open(dir, BY_NAME)) {
            assertFalse(data(test(c, AGENT, body(c, c.exact, secret, false))).get("hit").asBoolean());
            assertFalse(toolData(tool(c, AGENT, body(c, c.exact, secret, false))).get("hit").asBoolean());
        } finally {
            rootLogger.detachAppender(logs);
        }
        for (ILoggingEvent e : logs.list)
            assertFalse(e.getFormattedMessage().contains(secret), "a log line holds the value: " + e.getFormattedMessage());
    }
}

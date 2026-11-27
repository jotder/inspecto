package com.gamma.mcp;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGENT-KIT-1 W6 group B over real HTTP, on both doors (direct REST and the MCP replay of {@code simulate_decision_rule},
 * {@code simulate_rule_template} and {@code preview_enrichment}): an agent's Rule Template simulation is masked and stamped
 * (D-AK13); the enrichment preview is raw by decision for a builder's agent and refused to a machine (D-AK22); with every
 * stamp a no-op each simulator's answer is
 * withheld as 500 {@code masking not proven} while a person's is unchanged; each call spends its weight in the agent budget
 * (D-AK17), on both doors, and never the person's; an agent's Decision Rule
 * simulate answers without writing the rule file, a person's still stamps it (D-AK20); and a Rule Template over a Dataset
 * shared away from the caller is 404, as a missing one ({@code RULE-TEMPLATE-SIMULATE-SHARING-1}).
 *
 * <p>The data routes this class proves ({@code AgentDataRoutesParityTest}): /decision-rules/([^/]+)/simulate,
 * /rule-templates/([^/]+)/simulate and /enrichment/preview (raw by decision for a builder, D-AK22).
 */
class ControlApiAgentSimulatorsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "441700900101", B = "441700900202";
    /**
     * PERSON = dev-1 itself; AGENT = dev-1 through an agent client; OTHER = another developer (not the Dataset's owner);
     * BOT = a machine agent holding the developer's rights; READER = a person's agent without canAuthorWorkbench.
     */
    private static final String PERSON = "Bearer person", AGENT = "Bearer agent", OTHER = "Bearer other", BOT = "Bearer bot",
            READER = "Bearer reader";
    private static final String ROOTS = "assist.safety.roots";
    private static final String BUDGET = "control.rateLimit.agent.capacity", REFILL = "control.rateLimit.agent.refillPerSecond";
    private static final String RULE = "{\"kind\":\"group\",\"op\":\"AND\",\"items\":["
            + "{\"kind\":\"condition\",\"field\":\"cost\",\"operator\":\">\",\"value\":\"5000\"}]}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config) implements AutoCloseable {
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
            Roles.Def def = Roles.effective(ex).get("developer");
            ComponentAccess.heldRoles(ex, Set.of("developer"));
            Set<String> agentCaps = new LinkedHashSet<>(def.capabilities());
            agentCaps.removeAll(Roles.AGENT_NEVER_CAPABILITIES);
            return switch (h) {
                case PERSON -> Optional.of(new Subject("dev-1", def.capabilities(), def.dataScopes()));
                case AGENT -> Optional.of(new Subject("dev-1", agentCaps, def.dataScopes(), Map.of(), null, null, null, "claude-code"));
                case OTHER -> Optional.of(new Subject("dev-2", def.capabilities(), def.dataScopes()));
                case BOT -> Optional.of(new Subject("client:ops-bot", agentCaps, def.dataScopes(), Map.of(), null, null, "svc-ops", "ops-bot"));
                case READER -> Optional.of(new Subject("viewer-1", Set.of(), def.dataScopes(), Map.of(), null, null, null, "claude-code"));
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
        System.clearProperty(ROOTS);
    }

    /**
     * Space s1: the Parquet store {@code calls}, its msisdn classified through the Dataset {@code calls_ds} (owned by dev-1,
     * shared with nobody); the Rule Template {@code big} over it; the Decision Rule {@code high_cost}.
     */
    private Ctx open(Path root) throws Exception {
        return open(root, false);
    }

    /**
     * As {@link #open(Path)}; {@code withReference} also loads {@code region_dim} ({@code produces: reference}, its PARQUET
     * store {@code s1/region/db} holding A EU and B US) and the Dataset {@code regions_ds} over it, owned by dev-2 and
     * shared with nobody: dev-1 and dev-1's agent cannot view it.
     */
    private Ctx open(Path root, boolean withReference) throws Exception {
        Path base = root.resolve("s1");
        Path config = base.resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        if (withReference) {
            Path region = TestConfigs.csv(base.resolve("region"), PipelineConfigBatchTest.miniSchema())
                    .name("REGION_DIM").format("PARQUET").write();
            Files.writeString(config.resolve("region_dim_pipeline.toon"),
                    "produces: reference\n" + Files.readString(region).replace("active: true", "active: false"));
            Files.delete(region);
            // FEEDS routes into two stores; the second (s1/feeds/sms) is named only by sinks[] and its route branch:
            // under none of its dirs (s1/feeds/main/...), and no registry Dataset reads it
            String fwd = base.toString().replace("\\", "/");
            Path feeds = TestConfigs.csv(base.resolve("feeds").resolve("main"), PipelineConfigBatchTest.miniSchema())
                    .name("FEEDS").format("PARQUET").write();
            Files.writeString(config.resolve("feeds_pipeline.toon"), Files.readString(feeds).replace("active: true", "active: false")
                    + "\nsinks[2]{database,format}:\n  \"" + fwd + "/feeds/main/db\",PARQUET\n  \"" + fwd + "/feeds/sms\",PARQUET\n"
                    + "\nroute:\n  mode: case\n  default: sms\n  branches[2]{key,where,database}:\n"
                    + "    main,\"1 = 1\",\"" + fwd + "/feeds/main/db\"\n    sms,\"1 = 0\",\"" + fwd + "/feeds/sms\"\n");
            Files.delete(feeds);
            String part = Files.createDirectories(base.resolve("region").resolve("db")).resolve("part0.parquet")
                    .toString().replace("\\", "/");
            String sms = Files.createDirectories(base.resolve("feeds").resolve("sms")).resolve("part0.parquet")
                    .toString().replace("\\", "/");
            String main = Files.createDirectories(base.resolve("feeds").resolve("main").resolve("db")).resolve("part0.parquet")
                    .toString().replace("\\", "/");
            DuckDbUtil.loadDriver();
            File seed = DuckDbUtil.tempDbFile("regionseed_");
            try (Connection conn = DuckDbUtil.openConnection(seed); Statement st = conn.createStatement()) {
                for (String to : List.of(part, sms, main))
                    st.execute("COPY (SELECT * FROM (VALUES ('" + A + "','EU'),('" + B + "','US')) t(msisdn,region)) TO '"
                            + to + "' (FORMAT PARQUET)");
            } finally {
                DuckDbUtil.deleteTempDb(seed);
            }
            new ComponentStore(config.resolve("registry")).write("dataset", "regions_ds", Map.of("physicalRef", "region_dim",
                    "owner", "dev-2", "shares", List.of(), "columns", List.of(Map.of("name", "msisdn"), Map.of("name", "region"))));
        }
        Path partition = Files.createDirectories(base.resolve("data").resolve("calls").resolve("dt=2026"));
        String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("simseed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('" + A + "','EU',10.0),('" + B + "','US',7.0)) "
                    + "t(msisdn,region,amount)) TO '" + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        ComponentStore reg = new ComponentStore(config.resolve("registry"));
        reg.write("dataset", "calls_ds", Map.of("physicalRef", "calls", "owner", "dev-1", "shares", List.of(),
                "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "region"))));
        reg.write("rule-template", "big", Map.of("id", "big", "name", "big", "source", "calls_ds",
                "paramSql", "SELECT msisdn, region, amount FROM calls_ds WHERE amount > :floor",
                "params", List.of(Map.of("name", "floor", "field", "amount", "operator", "gt", "value", "0"))));
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("name", "high_cost");
        rule.put("targetType", "pipeline");
        rule.put("target", "orders");
        rule.put("consequences", List.of(Map.of("action", "quarantine", "destination", "too costly")));
        rule.put("when", JSON.readValue(RULE, Map.class));
        reg.write("decision-rule", "high_cost", rule);
        Roles.write(config, Map.of(), List.of(), List.of("ops-bot"));   // the Space admits the machine client
        System.setProperty(ROOTS, root.toString());                    // an enrichment path Reference may read under root
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config);
    }

    private HttpResponse<String> post(Ctx c, String auth, String path, Object body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/s1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), BodyHandlers.ofString());
    }

    /** An MCP tool call; its {@code result} (an {@code isError} tool result when the replay failed). */
    private JsonNode tool(Ctx c, String auth, String name, Map<String, Object> args) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", args);
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

    private static String text(JsonNode toolResult) {
        return toolResult.at("/content/0/text").asText();
    }

    private static void assertNoRaw(String what, String body) {
        for (String raw : List.of(A, B)) assertFalse(body.contains(raw), what + " leaked " + raw + ": " + body);
    }

    private static Map<String, Object> sample() {
        return Map.of("sampleRows", List.of(Map.of("cost", 9200), Map.of("cost", 50), Map.of("cost", 7000)));
    }

    /** The Decision Rule's file bytes: what "nothing written" is measured by. */
    private static byte[] ruleFile(Ctx c) throws Exception {
        try (Stream<Path> files = Files.walk(c.config.resolve("registry"))) {
            Path f = files.filter(p -> p.getFileName().toString().startsWith("high_cost")).findFirst().orElseThrow();
            return Files.readAllBytes(f);
        }
    }

    @Test
    void anAgentsRuleTemplateSimulationIsMaskedOnBothDoors(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> direct = post(c, AGENT, "/rule-templates/big/simulate", Map.of());
            assertEquals(200, direct.statusCode(), direct.body());
            assertNoRaw("direct", direct.body());
            assertTrue(direct.body().contains("masked:") && direct.body().contains("EU"), direct.body());

            JsonNode viaMcp = tool(c, AGENT, "simulate_rule_template", Map.of("template", "big"));
            assertFalse(viaMcp.path("isError").asBoolean(), viaMcp.toString());
            assertNoRaw("MCP", viaMcp.toString());
            assertTrue(viaMcp.toString().contains("masked:"), viaMcp.toString());
        }
    }

    @Test
    void anUnstampedSimulatorAnswerIsWithheldOnBothDoorsAndAPersonIsUnchanged(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            AnswerMasking.ignoreStampsForTest(true);   // the routes' masking steps no longer prove themselves
            for (String path : List.of("/rule-templates/big/simulate", "/decision-rules/high_cost/simulate")) {
                HttpResponse<String> direct = post(c, AGENT, path, sample());
                assertEquals(500, direct.statusCode(), path + ": " + direct.body());
                assertTrue(direct.body().contains(ControlApi.MASKING_NOT_PROVEN), direct.body());
                assertNoRaw(path, direct.body());
                assertFalse(direct.body().contains("EU") || direct.body().contains("matched"), "no answer leaves: " + direct.body());
                assertEquals(200, post(c, PERSON, path, sample()).statusCode(), "a person is unchanged: " + path);
            }
            for (Map.Entry<String, Map<String, Object>> t : Map.of(
                    "simulate_rule_template", Map.<String, Object>of("template", "big"),
                    "simulate_decision_rule", Map.<String, Object>of("rule", "high_cost", "body", sample())).entrySet()) {
                JsonNode viaMcp = tool(c, AGENT, t.getKey(), t.getValue());
                assertTrue(viaMcp.path("isError").asBoolean() && text(viaMcp).startsWith("HTTP 500"), t.getKey() + ": " + viaMcp);
                assertNoRaw(t.getKey(), viaMcp.toString());
            }
        }
    }

    /** D-AK20: the agent's answer is the person's, but the rule file stays byte-identical on both doors. */
    @Test
    void anAgentsDecisionRuleSimulationWritesNothingAndAPersonsStillStamps(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] before = ruleFile(c);
            HttpResponse<String> direct = post(c, AGENT, "/decision-rules/high_cost/simulate", sample());
            assertEquals(200, direct.statusCode(), direct.body());
            JsonNode sim = JSON.readTree(direct.body()).at("/data/lastSimulation").isMissingNode()
                    ? JSON.readTree(direct.body()).get("lastSimulation") : JSON.readTree(direct.body()).at("/data/lastSimulation");
            assertEquals(2, sim.get("matched").asInt(), direct.body());
            assertEquals(3, sim.get("total").asInt(), direct.body());
            JsonNode viaMcp = tool(c, AGENT, "simulate_decision_rule", Map.of("rule", "high_cost", "body", sample()));
            assertFalse(viaMcp.path("isError").asBoolean(), viaMcp.toString());
            assertTrue(text(viaMcp).contains("\"matched\""), viaMcp.toString());
            assertArrayEquals(before, ruleFile(c), "an agent's simulate leaves the rule file byte-identical");

            assertEquals(200, post(c, PERSON, "/decision-rules/high_cost/simulate", sample()).statusCode());
            assertTrue(new String(ruleFile(c)).contains("lastSimulation"), "a person's simulate still stamps the file");
        }
    }

    /**
     * D-AK17: each simulator spends its weight at the door, on both doors — Decision Rule and Rule Template 1, the enrichment
     * preview 2; past the budget 429, the person untouched.
     */
    @Test
    void eachSimulatorSpendsItsWeightInTheAgentBudget(@TempDir Path root) throws Exception {
        System.setProperty(BUDGET, "20");     // the floor: the heaviest agent call (a lookup build) weighs 20
        System.setProperty(REFILL, "0.01");   // no refill within the test
        Map<String, Object> preview = enrichment(root);
        try (Ctx c = open(root)) {
            for (int i = 0; i < 4; i++) {
                assertEquals(200, post(c, AGENT, "/decision-rules/high_cost/simulate", sample()).statusCode());   // -1
                assertEquals(200, post(c, AGENT, "/rule-templates/big/simulate", Map.of()).statusCode());          // -1
                assertEquals(200, post(c, AGENT, "/enrichment/preview", preview).statusCode());                    // -2
            }                                                                                                       // 20 → 4
            assertEquals(200, post(c, AGENT, "/enrichment/preview", preview).statusCode());                        // 4 → 2
            assertEquals(200, post(c, AGENT, "/rule-templates/big/simulate", Map.of()).statusCode());              // 2 → 1
            JsonNode last = tool(c, AGENT, "simulate_decision_rule", Map.of("rule", "high_cost", "body", sample()));
            assertFalse(last.path("isError").asBoolean(), "the MCP door spends the last unit: " + last);       // 1 → 0
            HttpResponse<String> past = post(c, AGENT, "/decision-rules/high_cost/simulate", sample());
            assertEquals(429, past.statusCode(), past.body());
            assertTrue(past.headers().firstValue("Retry-After").isPresent(), "429 says when to retry");
            JsonNode viaMcp = tool(c, AGENT, "simulate_rule_template", Map.of("template", "big"));
            assertTrue(text(viaMcp).startsWith("HTTP 429"), "the MCP door spends the same budget: " + viaMcp);
            assertEquals(200, post(c, PERSON, "/decision-rules/high_cost/simulate", sample()).statusCode(),
                    "the person's own budget is untouched");
        }
    }

    /** An enrichment draft joining the caller's sample to a path Reference (a CSV inside the safety roots) holding A and B. */
    private static Map<String, Object> enrichment(Path root) throws Exception {
        Path dim = Files.createDirectories(root.resolve("refdata")).resolve("dim.csv");   // a data dir, not the Space root's parent
        Files.writeString(dim, "msisdn,region\n" + A + ",EU\n" + B + ",US\n");
        return draft(Map.of("path", dim.toString().replace("\\", "/"), "format", "CSV"));
    }

    /** An enrichment draft joining the caller's sample (region EU) to the Reference {@code dim} ({@code path:} or {@code ref:}). */
    private static Map<String, Object> draft(Map<String, Object> dim) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("name", "PREVIEW");
        cfg.put("input", Map.of("database", "unused", "format", "PARQUET", "partitions", List.of("day")));
        cfg.put("output", Map.of("database", "unused", "format", "PARQUET", "partitions", List.of("day")));
        cfg.put("references", Map.of("dim", dim));
        cfg.put("transform", "SELECT i.region, d.msisdn FROM input i JOIN dim d ON i.region = d.region");
        return Map.of("config", cfg, "sampleRows", List.of(Map.of("region", "EU")));
    }

    /** A {@code path:} Reference to {@code file}, written first as a CSV holding A EU and B US (PARQUET when it exists). */
    private static Map<String, Object> pathTo(Path file) throws Exception {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "msisdn,region\n" + A + ",EU\n" + B + ",US\n");
        }
        String format = file.toString().endsWith(".parquet") || Files.isDirectory(file) ? "PARQUET" : "CSV";
        return Map.of("path", file.toString().replace("\\", "/"), "format", format);
    }

    /**
     * ENRICHMENT-PREVIEW-PATH-REACH-1 (operator, 2026-11-27): a path Reference into a data folder is refused 422 to the
     * person and the person's agent on both doors, and 403 to a machine as before; each probe holds A, so each would answer
     * it without the guard. A Dataset's store (no Pipeline produces it: the registry branch), a Pipeline's rejected rows
     * ({@code region_dim}'s {@code errors} folder: the Pipeline branch). (A backup archive and the restricted quarantine
     * need no rule: DuckDB refuses a {@code .zip}, and the sealed read's allowlist a restricted store, {@code PathJail}.)
     */
    @Test
    void aPathReferenceIntoADataStoreIsRefusedToEveryCaller(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            for (Path probe : List.of(root.resolve("s1/data/calls/dt=2026/data.parquet"),
                    root.resolve("s1/region/errors/rejected.csv")))
                assertRefusedToEveryCaller(c, probe);
            // a path outside every data folder keeps today's answer (raw for a builder, D-AK22)
            HttpResponse<String> outside = post(c, PERSON, "/enrichment/preview", enrichment(root));
            assertEquals(200, outside.statusCode(), outside.body());
        }
    }

    /**
     * A folder path: DuckDB reads every file below it, so {@code s1/feeds/main} (holding FEEDS's store {@code db}) is
     * refused. (A folder directly under the Space root is refused by the seal already.)
     */
    @Test
    void aFolderPathAboveAStoreIsRefusedToEveryCaller(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            assertRefusedToEveryCaller(c, root.resolve("s1/feeds/main"));
        }
    }

    /** A store a Pipeline names only in {@code sinks[]} ({@code s1/feeds/sms}): not under its dirs, no registry Dataset. */
    @Test
    void aSinkStoreIsRefusedToEveryCaller(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            assertRefusedToEveryCaller(c, root.resolve("s1/feeds/sms/part0.parquet"));
        }
    }

    /** 422 to the person and the person's agent on both doors, 403 to a machine; no answer holds a value. */
    private void assertRefusedToEveryCaller(Ctx c, Path probe) throws Exception {
        Map<String, Object> body = draft(pathTo(probe));
        HttpResponse<String> person = post(c, PERSON, "/enrichment/preview", body);
        assertEquals(422, person.statusCode(), probe + ": " + person.body());
        assertTrue(person.body().contains("may not name a file in a data folder"), person.body());
        assertNoRaw("person " + probe, person.body());
        HttpResponse<String> agent = post(c, AGENT, "/enrichment/preview", body);
        assertEquals(422, agent.statusCode(), probe + ": " + agent.body());
        assertNoRaw("agent " + probe, agent.body());
        JsonNode viaMcp = tool(c, AGENT, "preview_enrichment", Map.of("body", body));
        assertTrue(viaMcp.path("isError").asBoolean() && text(viaMcp).startsWith("HTTP 422"), viaMcp.toString());
        assertNoRaw("agent MCP " + probe, viaMcp.toString());
        HttpResponse<String> bot = post(c, BOT, "/enrichment/preview", body);
        assertEquals(403, bot.statusCode(), bot.body());
        assertNoRaw("machine " + probe, bot.body());
    }

    /**
     * ENRICHMENT-PREVIEW-SHARING-1: a by-name Reference whose Dataset is shared away from the caller is as absent as a
     * Pipeline that is not loaded, for the person and the person's agent on both doors (a machine is refused 403 as
     * before); the Dataset's owner previews it.
     */
    @Test
    void aByNameReferenceWhoseDatasetIsSharedAwayIsAsAbsentAsAnUnloadedOne(@TempDir Path root) throws Exception {
        Map<String, Object> body = draft(Map.of("ref", "region_dim"));
        try (Ctx c = open(root, true)) {
            HttpResponse<String> owner = post(c, OTHER, "/enrichment/preview", body);
            assertEquals(200, owner.statusCode(), owner.body());
            assertTrue(owner.body().contains(A), "the owner reads it: " + owner.body());

            String unloaded = post(c, PERSON, "/enrichment/preview", draft(Map.of("ref", "no_such_dim"))).body();
            assertTrue(unloaded.contains("no such pipeline is loaded"), unloaded);
            HttpResponse<String> person = post(c, PERSON, "/enrichment/preview", body);
            assertEquals(422, person.statusCode(), person.body());
            assertTrue(person.body().contains("binds ref: 'region_dim' but no such pipeline is loaded"),
                    "the same refusal as a Pipeline that is not loaded: " + person.body());
            assertNoRaw("person", person.body());
            HttpResponse<String> agent = post(c, AGENT, "/enrichment/preview", body);
            assertEquals(422, agent.statusCode(), agent.body());
            assertNoRaw("agent", agent.body());
            JsonNode viaMcp = tool(c, AGENT, "preview_enrichment", Map.of("body", body));
            assertTrue(viaMcp.path("isError").asBoolean() && text(viaMcp).startsWith("HTTP 422"), viaMcp.toString());
            assertNoRaw("agent MCP", viaMcp.toString());
            HttpResponse<String> bot = post(c, BOT, "/enrichment/preview", body);
            assertEquals(403, bot.statusCode(), bot.body());
            assertNoRaw("machine", bot.body());
        }
    }

    /**
     * D-AK22 (operator, 2026-11-26): the enrichment preview is RAW for a builder and the builder's agent, on both doors (the
     * agent sends those values to its model provider); a machine agent is refused 403 (person-only); a person's agent
     * without canAuthorWorkbench keeps the route's own 403; and the agent's answer still passes the seam's stamp check.
     */
    @Test
    void theEnrichmentPreviewIsRawForABuildersAgentAndRefusedToAMachine(@TempDir Path root) throws Exception {
        Map<String, Object> body = enrichment(root);
        try (Ctx c = open(root)) {
            HttpResponse<String> agent = post(c, AGENT, "/enrichment/preview", body);
            assertEquals(200, agent.statusCode(), agent.body());
            assertTrue(agent.body().contains(A) && !agent.body().contains(B), "raw by decision, the joined row only: " + agent.body());
            JsonNode viaMcp = tool(c, AGENT, "preview_enrichment", Map.of("body", body));
            assertFalse(viaMcp.path("isError").asBoolean(), viaMcp.toString());
            assertTrue(text(viaMcp).contains(A), viaMcp.toString());
            assertEquals(200, post(c, PERSON, "/enrichment/preview", body).statusCode(), "the builder, as today");

            HttpResponse<String> bot = post(c, BOT, "/enrichment/preview", body);
            assertEquals(403, bot.statusCode(), bot.body());
            assertTrue(bot.body().contains("nor preview an enrichment"), bot.body());
            assertNoRaw("machine", bot.body());
            JsonNode botMcp = tool(c, BOT, "preview_enrichment", Map.of("body", body));
            assertTrue(botMcp.path("isError").asBoolean() && text(botMcp).startsWith("HTTP 403"), "the MCP door too: " + botMcp);
            assertNoRaw("machine MCP", botMcp.toString());
            HttpResponse<String> reader = post(c, READER, "/enrichment/preview", body);
            assertEquals(403, reader.statusCode(), "no canAuthorWorkbench, today's refusal: " + reader.body());
            assertNoRaw("reader", reader.body());

            AnswerMasking.ignoreStampsForTest(true);   // even raw by decision, the answer must be stamped as such
            HttpResponse<String> unstamped = post(c, AGENT, "/enrichment/preview", body);
            assertEquals(500, unstamped.statusCode(), unstamped.body());
            assertTrue(unstamped.body().contains(ControlApi.MASKING_NOT_PROVEN), unstamped.body());
            assertNoRaw("unstamped", unstamped.body());
        }
    }

    /** Design §4's cost bound: an agent's simulator body is at most 256 KiB on the direct door too (413); a person's is not capped. */
    @Test
    void anAgentsSimulatorBodyIsCappedAt256KiB(@TempDir Path root) throws Exception {
        // just over the cap: sample rows, then one pad field
        String big = "{\"sampleRows\":[{\"cost\":9200}],\"pad\":\"" + "x".repeat(256 * 1024) + "\"}";
        try (Ctx c = open(root)) {
            HttpResponse<String> agent = chunked(c, AGENT, "/decision-rules/high_cost/simulate", big);
            assertEquals(413, agent.statusCode(), agent.body());
            assertEquals(200, chunked(c, PERSON, "/decision-rules/high_cost/simulate", big).statusCode(), "a person is unchanged");
        }
    }

    /**
     * ⚠ Chunked, so the server reads up to the cap + 1 and the few bytes left fit its drain: a declared body refused unread
     * races the client, which can lose the 413 to a reset (as {@code ControlApiAgentPreviewsTest}).
     */
    private HttpResponse<String> chunked(Ctx c, String auth, String path, String body) throws Exception {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/s1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .POST(BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(bytes))).build(),
                BodyHandlers.ofString());
    }

    /** RULE-TEMPLATE-SIMULATE-SHARING-1: a source Dataset the caller cannot view is a 404, as a missing one. */
    @Test
    void aRuleTemplateOverASharedAwayDatasetIs404(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> other = post(c, OTHER, "/rule-templates/big/simulate", Map.of());
            assertEquals(404, other.statusCode(), other.body());
            assertTrue(other.body().contains("unknown dataset 'calls_ds'"), "the same text as a missing Dataset: " + other.body());
            assertNoRaw("shared away", other.body());
            assertEquals(200, post(c, PERSON, "/rule-templates/big/simulate", Map.of()).statusCode(), "the owner still simulates");
            // the template itself shared away: 404 as a missing one, so its holes and defaults are not echoed
            new ComponentStore(c.config.resolve("registry")).write("rule-template", "mine", Map.of("id", "mine", "name", "mine",
                    "owner", "dev-1", "shares", List.of(), "source", "calls_ds",
                    "paramSql", "SELECT region FROM calls_ds WHERE amount > :floor",
                    "params", List.of(Map.of("name", "floor", "field", "amount", "operator", "gt", "value", "0"))));
            HttpResponse<String> hidden = post(c, OTHER, "/rule-templates/mine/simulate", Map.of());
            assertEquals(404, hidden.statusCode(), hidden.body());
            assertTrue(hidden.body().contains("unknown rule template 'mine'"), hidden.body());
        }
    }
}

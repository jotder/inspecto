package com.gamma.mcp;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * AGENT-KIT-1 W6 group D, change D2 of the signed design ({@code 2026-11-26-agent-data-answers-masking-design.md}) over
 * real HTTP: the reconciliation reads {@code POST /recon/columns}, {@code /recon/run}, {@code /recon/breaks} and
 * {@code /recon/rows} (MCP Tool {@code reconcile_preview}), for a person's agent and a machine agent, on both doors.
 * The classified key comes back as the Space token on every side, the raw key an agent asks for included (D-AK15); with
 * every stamp a no-op the answer is withheld as 500 {@code masking not proven} while a person is unchanged (D-AK13); the
 * answer cache shared across callers never carries one caller's raw values to an agent, nor an agent's copy to anyone;
 * an inline (ad-hoc) Reconciliation is allowed (D-AK16) within a person's bounds (D-AK18) and only over Datasets the agent
 * may view; a {@code none} Space answers raw (D-AK14); the agent budget is charged by weight on both doors and answers 429
 * past it (D-AK17); no request value reaches an audit row or a log line (D-AK19).
 *
 * <p>Data: {@code subs_a_ds} and {@code subs_b_ds} classify {@code msisdn}; m2 breaks on {@code active_flag}, m3 is only
 * in A, m5 only in B. {@code hidden_ds} is shared with no one.
 */
class ControlApiAgentReconReadsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> RAW_KEYS = List.of("m1", "m2", "m3", "m4", "m5");
    private static final String PERSON = "Bearer person", AGENT = "Bearer agent", MACHINE = "Bearer machine",
            ADMIN = "Bearer admin", ADMIN_AGENT = "Bearer admin-agent";
    private static final String BUDGET = "control.rateLimit.agent.capacity", REFILL = "control.rateLimit.agent.refillPerSecond";
    /** An inline (ad-hoc) Reconciliation over the two classified Datasets. */
    private static final Map<String, Object> SUBS = Map.of("datasets", List.of("subs_a_ds", "subs_b_ds"),
            "keyColumns", List.of("msisdn"),
            "compareColumns", List.of(Map.of("column", "active_flag", "toleranceType", "exact")));
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
            String role = ADMIN.equals(h) || ADMIN_AGENT.equals(h) ? "admin" : "operations";
            Roles.Def def = Roles.effective(ex).get(role);
            ComponentAccess.heldRoles(ex, Set.of(role));
            Set<String> agentCaps = new LinkedHashSet<>(def.capabilities());
            agentCaps.removeAll(Roles.AGENT_NEVER_CAPABILITIES);   // an agent never reveals
            Set<String> machineCaps = new LinkedHashSet<>(agentCaps);
            machineCaps.retainAll(Roles.AGENT_MACHINE_CAPABILITIES);
            return switch (h) {
                case PERSON -> Optional.of(new Subject("analyst-1", def.capabilities(), def.dataScopes()));
                case AGENT -> Optional.of(new Subject("analyst-1", agentCaps, def.dataScopes(), Map.of(), null, null, null, "claude-code"));
                case MACHINE -> Optional.of(new Subject("client:bot", machineCaps, def.dataScopes(), Map.of(), null, null, "svc", "bot"));
                case ADMIN -> Optional.of(new Subject("admin-1", def.capabilities(), def.dataScopes()));
                case ADMIN_AGENT -> Optional.of(new Subject("admin-1", agentCaps, def.dataScopes(), Map.of(), null, null, null, "claude-code"));
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

    private Ctx open(Path root, String laSettings) throws Exception {
        Path config = root.resolve("s1").resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(root.resolve("s1").resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Path data = root.resolve("s1").resolve("data");
        seedStore(data, "subs_a", "VALUES ('m1',1,199.0),('m2',1,149.0),('m3',1,99.0),('m4',0,50.0)");
        seedStore(data, "subs_b", "VALUES ('m1',1,199.0),('m2',0,149.0),('m4',0,50.0),('m5',1,30.0)");
        seedStore(data, "num_b", "VALUES (1,1,199.0),(2,0,149.0)");   // the key typed as an integer
        ComponentStore store = new ComponentStore(config.resolve("registry"));
        store.write("dataset", "subs_a_ds", dataset("subs_a"));
        store.write("dataset", "subs_b_ds", dataset("subs_b"));
        Map<String, Object> hidden = new LinkedHashMap<>(dataset("subs_b"));
        hidden.put("owner", "owner");
        hidden.put("shares", List.of());   // shared with no one
        store.write("dataset", "hidden_ds", hidden);
        store.write("reconciliation", "subs_recon", SUBS);
        store.write("dataset", "num_b_ds", Map.of("dateField", "event_date", "physicalRef", "num_b", "columns", List.of(
                Map.of("name", "msisdn"), Map.of("name", "active_flag"), Map.of("name", "fee"), Map.of("name", "event_date"))));
        if (laSettings != null) Files.writeString(config.resolve("link-analysis.toon"), laSettings);
        Roles.write(config, Map.of(), List.of(), List.of("bot"));   // the Space admits the machine client
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private static Map<String, Object> dataset(String store) {
        return Map.of("dateField", "event_date", "physicalRef", store, "columns", List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "active_flag"), Map.of("name", "fee"),
                Map.of("name", "event_date")));
    }

    private static void seedStore(Path dataDir, String name, String values) throws Exception {
        Path partition = Files.createDirectories(dataDir.resolve(name).resolve("dt=2026"));
        String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("d2seam_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT *, DATE '2026-09-26' AS event_date FROM (" + values
                    + ") t(msisdn, active_flag, fee)) TO '" + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** The four reads (route → body) over {@code config}; rows asks for {@code key}, a raw value (D-AK15). */
    private static Map<String, Map<String, Object>> reads(Map<String, Object> config, String key) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        m.put("/recon/columns", Map.of("datasets", config.get("datasets")));
        m.put("/recon/run", Map.of("config", config));
        m.put("/recon/breaks", Map.of("config", config));
        m.put("/recon/rows", Map.of("config", config, "key", Map.of("msisdn", key)));
        return m;
    }

    private static String step(String route) {
        return route.substring("/recon/".length());
    }

    private HttpResponse<String> post(Ctx c, String auth, String path, Map<String, Object> body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/s1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String auth, String path, Map<String, Object> body) throws Exception {
        HttpResponse<String> r = post(c, auth, path, body);
        assertEquals(200, r.statusCode(), auth + " " + path + " -> " + r.body());
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;   // the v1 envelope's data
    }

    /** An MCP {@code tools/call} of {@code reconcile_preview}; its {@code result} (an {@code isError} result when the replay failed). */
    private JsonNode tool(Ctx c, String auth, String route, Map<String, Object> body) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "reconcile_preview");
        params.put("arguments", Map.of("step", step(route), "body", body));
        params.put("_meta", Map.of(McpRoutes.META_VERSION, McpRoutes.MODERN,
                McpRoutes.META_CLIENT, Map.of("name", "TestAgent", "version", "1.0")));
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/s1/mcp"))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .header("MCP-Protocol-Version", McpRoutes.MODERN).header("Mcp-Method", "tools/call")
                .header("Mcp-Name", "reconcile_preview")
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
        for (String k : RAW_KEYS) assertFalse(body.contains("\"" + k + "\"") || body.contains("\\\"" + k + "\\\""),
                what + " leaked raw key " + k + ": " + body);
    }

    /** Masked: no raw key, and (but for the column inventory, which carries names only) the Space token. */
    private static void assertMasked(String route, String what, String body) {
        assertNoRaw(what, body);
        if (!"/recon/columns".equals(route)) assertTrue(body.contains("masked:"), what + " carries the Space token: " + body);
    }

    /** The answer without its timing / cache statistics, which differ run to run. */
    private static JsonNode stable(JsonNode n) {
        JsonNode copy = n.deepCopy();
        if (copy instanceof ObjectNode o) o.remove("statistics");
        return copy;
    }

    @Test
    void aPersonsAgentAndAMachineAgentGetTheMaskedKeyOnEveryReadOnBothDoors(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            for (String who : List.of(AGENT, MACHINE)) {
                for (Map.Entry<String, Map<String, Object>> read : reads(SUBS, "m2").entrySet()) {
                    JsonNode direct = ok(c, who, read.getKey(), read.getValue());
                    assertMasked(read.getKey(), who + " " + read.getKey(), direct.toString());
                    JsonNode viaMcp = tool(c, who, read.getKey(), read.getValue());
                    assertFalse(viaMcp.path("isError").asBoolean(), viaMcp.toString());
                    assertMasked(read.getKey(), who + " reconcile_preview " + step(read.getKey()), text(viaMcp));
                }
                // the raw key asked for (D-AK15) is echoed as the token the Break list names it by, on both sides' rows
                JsonNode breaks = ok(c, who, "/recon/breaks", Map.of("config", SUBS));
                String token = breaks.at("/value_break/rows/0/key/msisdn").asText();
                assertTrue(token.startsWith("masked:"), breaks.toString());
                JsonNode rows = ok(c, who, "/recon/rows", Map.of("config", SUBS, "key", Map.of("msisdn", "m2")));
                assertEquals(token, rows.at("/key/msisdn").asText(), "one key, one token");
                for (String side : List.of("a", "b")) {
                    assertEquals(token, rows.at("/" + side + "/rows/0/msisdn").asText(), side);
                    assertEquals(149.0, rows.at("/" + side + "/rows/0/fee").asDouble(), "an unclassified column stays raw");
                }
                // a saved Reconciliation by id answers the same way
                assertMasked("/recon/run", who + " saved", ok(c, who, "/recon/run", Map.of("id", "subs_recon")).toString());
            }
        }
    }

    @Test
    void anUnstampedAnswerIsWithheldOnBothDoorsAndAPersonIsUnchanged(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            AnswerMasking.ignoreStampsForTest(true);   // the routes' masking step no longer proves itself
            for (Map.Entry<String, Map<String, Object>> read : reads(SUBS, "m2").entrySet()) {
                for (String who : List.of(AGENT, MACHINE)) {
                    HttpResponse<String> r = post(c, who, read.getKey(), read.getValue());
                    assertEquals(500, r.statusCode(), who + " " + read.getKey() + ": " + r.body());
                    assertTrue(r.body().contains(ControlApi.MASKING_NOT_PROVEN), r.body());
                    assertNoRaw("unstamped", r.body());
                    assertFalse(r.body().contains("masked:") || r.body().contains("active_flag"), "no row leaves: " + r.body());
                }
                JsonNode t = tool(c, AGENT, read.getKey(), read.getValue());
                assertTrue(t.path("isError").asBoolean() && text(t).startsWith("HTTP 500"), t.toString());
                assertFalse(t.toString().contains("masked:") || t.toString().contains("active_flag"), "no row leaves: " + t);
                HttpResponse<String> person = post(c, PERSON, read.getKey(), read.getValue());
                assertEquals(200, person.statusCode(), "a person's request does not pass the seam: " + person.body());
                assertMasked(read.getKey(), "a person, as before", person.body());
            }
        }
    }

    /**
     * The answer cache is keyed by config, day and arguments, never by caller (design §2): the masking runs on a copy after
     * the cache read, and the stamp with it, so a revealer's raw answer read back from the cache reaches an agent masked —
     * the agent of that very revealer included — and an agent's masked copy never reaches the revealer.
     */
    @Test
    void aCachedAnswerNeverCarriesOneCallersRawValuesToAnotherCaller(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            for (Map.Entry<String, Map<String, Object>> read : reads(SUBS, "m2").entrySet()) {
                if ("/recon/columns".equals(read.getKey())) continue;   // names only, not cached
                JsonNode raw = ok(c, ADMIN, read.getKey(), read.getValue());
                assertTrue(raw.toString().contains("\"m2\""), read.getKey() + " the revealer reads raw: " + raw);
                for (String who : List.of(ADMIN_AGENT, AGENT, MACHINE)) {
                    JsonNode masked = ok(c, who, read.getKey(), read.getValue());
                    assertMasked(read.getKey(), who + " " + read.getKey() + " after a revealer's read", masked.toString());
                    if ("/recon/run".equals(read.getKey()))
                        assertTrue(masked.at("/statistics/cached").asBoolean(), "the agent's page came from the cache");
                    JsonNode viaMcp = tool(c, who, read.getKey(), read.getValue());
                    assertMasked(read.getKey(), who + " reconcile_preview after a revealer's read", text(viaMcp));
                }
                JsonNode again = ok(c, ADMIN, read.getKey(), read.getValue());
                assertEquals(stable(raw), stable(again), read.getKey() + ": the agents' masked copies never reach the cache");
            }
            // the other order: an agent fills the cache (a sampled day is its own entry), then a person without reveal and
            // the revealer read it
            Map<String, Object> sampled = Map.of("config", SUBS, "sample", 4);
            assertMasked("/recon/run", "agent first", ok(c, AGENT, "/recon/run", sampled).toString());
            JsonNode person = ok(c, PERSON, "/recon/run", sampled);
            assertTrue(person.at("/statistics/cached").asBoolean(), person.toString());
            assertMasked("/recon/run", "a person without reveal after the agent", person.toString());
            JsonNode revealer = ok(c, ADMIN, "/recon/run", sampled);
            assertTrue(revealer.at("/statistics/cached").asBoolean() && !revealer.toString().contains("masked:"),
                    "the revealer reads the cached answer raw: " + revealer);
        }
    }

    /**
     * D-AK16 (looser): an inline Reconciliation is allowed, but every side must be a Dataset the agent may view, and the
     * caps are a person's (D-AK18). The value-oracle vets refuse an agent as a person without reveal.
     */
    @Test
    void anAdHocReconciliationIsBoundedAsAPersonsAndOnlyOverViewableDatasets(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            Map<String, Object> shared = new LinkedHashMap<>(SUBS);
            shared.put("datasets", List.of("subs_a_ds", "hidden_ds"));
            for (Map.Entry<String, Map<String, Object>> read : reads(shared, "m2").entrySet())
                for (String who : List.of(AGENT, MACHINE)) {
                    HttpResponse<String> r = post(c, who, read.getKey(), read.getValue());
                    assertEquals(404, r.statusCode(), who + " " + read.getKey() + ": " + r.body());
                    assertTrue(r.body().contains("unknown dataset 'hidden_ds'"), r.body());
                }
            // a person's bounds: a page is 1..200 rows, a sample at most 100000 keys, an offset never negative
            for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("config", SUBS, "limit", 201),
                    Map.of("config", SUBS, "limit", 0), Map.of("config", SUBS, "sample", 100_001),
                    Map.of("config", SUBS, "offset", -1))) {
                HttpResponse<String> r = post(c, AGENT, "/recon/run", bad);
                assertEquals(422, r.statusCode(), bad + ": " + r.body());
                assertTrue(text(tool(c, MACHINE, "/recon/run", bad)).startsWith("HTTP 422"), bad.toString());
            }
            assertEquals(200, post(c, AGENT, "/recon/run", Map.of("config", SUBS, "limit", 200)).statusCode(), "200 is the cap");
            // a Break list's limit is clamped to 5000, never refused
            assertEquals(200, post(c, AGENT, "/recon/breaks", Map.of("config", SUBS, "limit", 50_000)).statusCode());
            // a sum, or a side filter, over the masked key reads its values: refused
            Map<String, Object> sumOverKey = Map.of("datasets", List.of("subs_a_ds", "subs_b_ds"), "keyColumns", List.of("active_flag"),
                    "compareColumns", List.of(Map.of("column", "msisdn", "toleranceType", "exact")));
            Map<String, Object> filtered = new LinkedHashMap<>(SUBS);
            filtered.put("filters", Map.of("subs_b_ds", "msisdn LIKE 'm2%'"));
            for (Map<String, Object> config : List.of(sumOverKey, filtered)) {
                HttpResponse<String> r = post(c, AGENT, "/recon/breaks", Map.of("config", config));
                assertEquals(422, r.statusCode(), r.body());
                assertTrue(r.body().contains("canRevealLinkEntities"), r.body());
                assertNoRaw("refused", r.body());
            }
        }
    }

    /**
     * D2 security review: a classified VARCHAR key compared with another side's integer key fails in DuckDB with the stored
     * value quoted ({@code Could not convert string 'm1'}); a 4xx passes the seam unstamped, so the error text itself is
     * withheld while masking is in force, as {@code /bi/query} withholds it.
     */
    @Test
    void aFailedComparisonNeverQuotesAMaskedKeyInItsErrorText(@TempDir Path root) throws Exception {
        Map<String, Object> mixed = new LinkedHashMap<>(SUBS);
        mixed.put("datasets", List.of("subs_a_ds", "num_b_ds"));
        try (Ctx c = open(root, null)) {
            for (Map.Entry<String, Map<String, Object>> read : reads(mixed, "m2").entrySet())
                for (String who : List.of(AGENT, MACHINE, PERSON)) {
                    HttpResponse<String> r = post(c, who, read.getKey(), read.getValue());
                    assertNoRaw(who + " " + read.getKey(), r.body());
                    assertFalse(r.body().contains("'m1'") || r.body().contains("'m2'") || r.body().contains("'m3'")
                            || r.body().contains("'m4'"), who + " " + read.getKey() + " quoted a stored key: " + r.body());
                    if (r.statusCode() != 200) {
                        assertEquals(422, r.statusCode(), r.body());
                        // a run-time error's text is withheld; a compile-time one (Binder) quotes no stored value
                        assertTrue(r.body().contains("withheld") || r.body().contains("Binder Error"), r.body());
                    }
                }
            // the missing-row join compares the raw typed keys, so it fails at run time on a stored value
            Map<String, Object> missing = Map.of("config", mixed, "type", "missing_right");
            for (String who : List.of(AGENT, PERSON)) {
                HttpResponse<String> r = post(c, who, "/recon/breaks", missing);
                assertEquals(422, r.statusCode(), who + ": " + r.body());
                assertTrue(r.body().contains("withheld"), who + " gets the fixed sentence: " + r.body());
                assertNoRaw(who + " missing_right", r.body());
            }
            JsonNode viaMcp = tool(c, MACHINE, "/recon/breaks", missing);
            assertTrue(text(viaMcp).startsWith("HTTP 422") && text(viaMcp).contains("withheld"), viaMcp.toString());
        }
    }

    @Test
    void aSpaceWithMaskingModeNoneAnswersAnAgentRaw(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "masking_mode: none\n")) {
            for (Map.Entry<String, Map<String, Object>> read : reads(SUBS, "m2").entrySet()) {
                JsonNode r = ok(c, AGENT, read.getKey(), read.getValue());   // stamped with zero masked
                if (!"/recon/columns".equals(read.getKey()))
                    assertTrue(r.toString().contains("\"m2\""), read.getKey() + ": the Space masks nothing (D-AK14): " + r);
            }
        }
    }

    @Test
    void theAgentBudgetIsChargedByWeightOnBothDoorsAnd429PastItsBound(@TempDir Path root) throws Exception {
        System.setProperty(BUDGET, "13");     // run 5 + breaks 5 + rows 2 + columns 1
        System.setProperty(REFILL, "0.01");   // no refill within the test
        Map<String, Map<String, Object>> reads = reads(SUBS, "m2");
        try (Ctx c = open(root, null)) {
            assertEquals(200, post(c, AGENT, "/recon/run", reads.get("/recon/run")).statusCode(), "direct run spends 5");
            assertFalse(tool(c, AGENT, "/recon/breaks", reads.get("/recon/breaks")).path("isError").asBoolean(), "breaks spends 5");
            assertEquals(200, post(c, AGENT, "/recon/rows", reads.get("/recon/rows")).statusCode(), "rows spends 2");
            assertFalse(tool(c, AGENT, "/recon/columns", reads.get("/recon/columns")).path("isError").asBoolean(), "columns 1");
            HttpResponse<String> past = post(c, AGENT, "/recon/columns", reads.get("/recon/columns"));
            assertEquals(429, past.statusCode(), "the budget of 13 is spent across the four routes and both doors: " + past.body());
            assertTrue(past.body().contains("agent data budget"), past.body());
            assertTrue(past.headers().firstValue("Retry-After").isPresent(), "429 says when to retry");
            assertTrue(text(tool(c, AGENT, "/recon/run", reads.get("/recon/run"))).startsWith("HTTP 429"),
                    "the MCP door spends the same budget");

            // the machine agent's budget is its own, and the person's own requests are untouched
            assertEquals(200, post(c, MACHINE, "/recon/run", reads.get("/recon/run")).statusCode(), "another client and subject");
            for (int i = 0; i < 6; i++)
                assertEquals(200, post(c, PERSON, "/recon/run", reads.get("/recon/run")).statusCode(), "the person's run #" + i);
        }
    }

    @Test
    void noRequestValueReachesAnAuditRowOrALogLine(@TempDir Path root) throws Exception {
        String secret = "ZQXD2SECRET4417";   // a raw key the agent asks for (D-AK15): absent, so its answer is empty
        Map<String, Object> rowsBody = Map.of("config", SUBS, "key", Map.of("msisdn", secret));
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
        try (Ctx c = open(root, null)) {
            assertEquals(200, post(c, AGENT, "/recon/rows", rowsBody).statusCode());
            assertFalse(tool(c, AGENT, "/recon/rows", rowsBody).path("isError").asBoolean());
            AnswerMasking.ignoreStampsForTest(true);   // the withheld answer's log line names the route, never the request
            assertEquals(500, post(c, AGENT, "/recon/rows", rowsBody).statusCode());
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
            assertTrue(rows.toString().contains("mcp.tool.called") && rows.toString().contains("reconcile_preview"),
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

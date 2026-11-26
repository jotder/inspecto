package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.risk.RiskScoreEvaluator;
import com.gamma.risk.RiskScoreModel;
import com.gamma.risk.RiskScorer;
import com.gamma.service.SpaceManager;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-RISK-SCORE-1 over real HTTP with an ARMED Subject: {@code GET /risk-scores/{model}/{entityKey}} returns
 * the latest score with factors that recompute to it; the capability, data scopes and existence-hiding 404s
 * hold; and a {@code risk-score} component save refuses a factor its Dataset's Schema cannot back (422).
 */
class ControlApiRiskScoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private static final Set<String> CAPS = Set.of("canWorkIncidents", "canAuthorWorkbench");

    /** analyst: unscoped · fraud / billing: data-scoped · nocap: authenticated without canWorkIncidents. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer analyst".equals(auth)) return Optional.of(new Subject("ana", CAPS));
        if ("Bearer fraud".equals(auth)) return Optional.of(new Subject("fay", CAPS, Set.of("fraud")));
        if ("Bearer billing".equals(auth)) return Optional.of(new Subject("bo", CAPS, Set.of("billing")));
        if ("Bearer nocap".equals(auth)) return Optional.of(new Subject("nc", Set.of("canAuthorWorkbench")));
        if ("Bearer lead".equals(auth))
            return Optional.of(new Subject("lee", Set.of("canWorkIncidents", "canManageIncidents")));
        if ("Bearer revealer".equals(auth))
            return Optional.of(new Subject("rev", Set.of("canWorkIncidents", "canRevealLinkEntities")));
        if ("Bearer agent".equals(auth)) return Optional.of(agentFor("rev", Set.of("canWorkIncidents", "canRevealLinkEntities")));
        if ("Bearer agentauthor".equals(auth)) return Optional.of(agentFor("ana", CAPS));
        if ("Bearer machine".equals(auth)) return Optional.of(new Subject("client:bot", CAPS, null, Map.of(), null, null, "svc", "bot"));
        return Optional.empty();
    };

    /** The person's Subject through an MCP agent client, narrowed as {@code OidcAuthenticator} narrows it (AK-2a). */
    private static Subject agentFor(String id, Set<String> personCaps) {
        Set<String> kept = new java.util.HashSet<>(personCaps);
        kept.removeAll(com.gamma.access.Roles.AGENT_NEVER_CAPABILITIES);
        return new Subject(id, Set.copyOf(kept), null, Map.of(), null, null, null, "claude-code");
    }

    @AfterEach
    void tearDown() { Authenticators.forTest(null); }

    /**
     * W3 (AK-2 widening plan §3, check before building {@code get_risk_score}): an agent acting for a person who may
     * reveal reads the key masked, with the same token the person's screen shows a caller without reveal, and reads the
     * score again by that token. Falsified: an agent Subject that kept {@code canRevealLinkEntities} reads the raw key.
     */
    @Test
    void anAgentReadsTheKeyMaskedEvenForAPersonWhoMayReveal(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            score(c, "subs", model(null));
            assertEquals("m1", V1Body.of(send(c.port, "GET", "/spaces/s1/risk-scores/subs/m1", null, "revealer").body())
                    .get("entityKey").asText(), "precondition: the person sees the raw key");
            HttpResponse<String> r = send(c.port, "GET", "/spaces/s1/risk-scores/subs/m1", null, "agent");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode agent = V1Body.of(r.body());
            assertTrue(agent.get("keyMasked").asBoolean(), r.body());
            String token = agent.get("entityKey").asText();
            assertEquals(V1Body.of(send(c.port, "GET", "/spaces/s1/risk-scores/subs/m1", null, "analyst").body())
                    .get("entityKey").asText(), token, "the token a person without reveal sees");
            assertFalse(r.body().contains("\"m1\""), "the raw key is nowhere in the body: " + r.body());
            HttpResponse<String> byAlias = send(c.port, "GET", "/spaces/s1/risk-scores/subs/" + token, null, "agent");
            assertEquals(200, byAlias.statusCode(), byAlias.body());
            assertEquals(token, V1Body.of(byAlias.body()).get("entityKey").asText(), "read again by the alias, still masked");
        }
    }

    /**
     * AGENT-GET-DATA-READS-1 (D-AK13): "GET /risk-scores/([^/]+)/([^/]+)" is an agent data route. The agent's answer is
     * stamped by {@code EntityKeyMasking.put}; with every stamp a no-op the agent gets 500 with no key while a person
     * still gets the score. Falsified: removing the stamp in {@code EntityKeyMasking.put}.
     */
    @Test
    void anAgentsRiskScoreIsProvenMaskedOrWithheld(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            score(c, "subs", model(null));
            String path = "/spaces/s1/risk-scores/subs/m1";
            assertEquals(200, send(c.port, "GET", path, null, "agent").statusCode());
            com.gamma.util.AnswerMasking.ignoreStampsForTest(true);
            try {
                HttpResponse<String> agent = send(c.port, "GET", path, null, "agent");
                assertEquals(500, agent.statusCode(), agent.body());
                assertTrue(agent.body().contains(ControlApi.MASKING_NOT_PROVEN), agent.body());
                assertFalse(agent.body().contains("\"m1\"") || agent.body().contains("masked:"), agent.body());
                assertEquals(200, send(c.port, "GET", path, null, "revealer").statusCode(), "a person is unchanged");
            } finally {
                com.gamma.util.AnswerMasking.ignoreStampsForTest(false);
            }
        }
    }

    /**
     * AGENT-KIT-1 W6 group E (D-AK13, D-AK15): "POST /risk-scores/preview" is an agent data route. The agent sends the raw
     * key (allowed); the answer names it only as the Space token and carries the PII evidence masked. With every stamp a
     * no-op the agent gets 500 with no key or evidence while a person still gets the preview. Falsified: removing the
     * stamp in {@code EntityKeyMasking.put}.
     */
    @Test
    void anAgentsScorePreviewIsProvenMaskedOrWithheld(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            ComponentStore store = new ComponentStore(c.config.resolve("registry"));
            store.write("dataset", "topups", Map.of("physicalRef", "topups",
                    "columns", List.of(Map.of("name", "msisdn", "classification", "msisdn"),
                            Map.of("name", "topup_id", "classification", "PII"), Map.of("name", "status"))));
            store.write("risk-score", "subs", model(null));
            String path = "/spaces/s1/risk-scores/preview", body = "{\"model\":\"subs\",\"entityKey\":\"m1\"}";
            HttpResponse<String> r = send(c.port, "POST", path, body, "agent");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = V1Body.of(r.body());
            assertTrue(d.get("found").asBoolean(), "the raw key an agent sends is scored (D-AK15): " + r.body());
            assertTrue(d.get("keyMasked").asBoolean());
            assertEquals(com.gamma.mask.EvidenceMasker.forSpace(c.config).tokenFor("m1"), d.get("entityKey").asText());
            assertTrue(d.at("/factors/0/evidence/0/topup_id").asText().startsWith("masked:"), r.body());
            assertFalse(r.body().contains("\"m1\"") || r.body().contains("\"t1\""), "no raw value leaves: " + r.body());
            com.gamma.util.AnswerMasking.ignoreStampsForTest(true);
            try {
                HttpResponse<String> agent = send(c.port, "POST", path, body, "agent");
                assertEquals(500, agent.statusCode(), agent.body());
                assertTrue(agent.body().contains(ControlApi.MASKING_NOT_PROVEN), agent.body());
                assertFalse(agent.body().contains("m1") || agent.body().contains("masked:") || agent.body().contains("t1"),
                        agent.body());
                assertEquals(200, send(c.port, "POST", path, body, "revealer").statusCode(), "a person is unchanged");
            } finally {
                com.gamma.util.AnswerMasking.ignoreStampsForTest(false);
            }
        }
    }

    /**
     * D-AK23 (operator 2026-11-26): a DRAFT preview is raw for a builder. A person's agent holding canAuthorWorkbench gets
     * the same factor values the person gets (a draft's measure or filter over a classified column is not masked:
     * RISK-SCORE-MEASURE-UNMASKED-1, accepted for builders); a machine agent's draft is 403. Falsified: removing the machine
     * refusal in {@code RiskScoreRoutes.draftForBuilder}.
     */
    @Test
    void aDraftPreviewIsRawForABuildersAgentAndRefusedToAMachine(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            com.gamma.access.Roles.write(c.config, Map.of(), List.of(), List.of("bot"));   // the Space admits client 'bot'
            new ComponentStore(c.config.resolve("registry")).write("dataset", "topups", Map.of("physicalRef", "topups",
                    "columns", List.of(Map.of("name", "msisdn", "classification", "msisdn"), Map.of("name", "status"))));
            String draft = JSON.writeValueAsString(Map.of("entityKey", "m1", "content", model(null)));
            HttpResponse<String> person = send(c.port, "POST", "/spaces/s1/risk-scores/preview", draft, "analyst");
            assertEquals(200, person.statusCode(), person.body());
            HttpResponse<String> agent = send(c.port, "POST", "/spaces/s1/risk-scores/preview", draft, "agentauthor");
            assertEquals(200, agent.statusCode(), agent.body());
            assertEquals(V1Body.of(person.body()).get("factors"), V1Body.of(agent.body()).get("factors"),
                    "a builder's agent gets the draft's factor values as the person does");
            assertTrue(V1Body.of(agent.body()).get("keyMasked").asBoolean(), "the key still leaves masked: " + agent.body());

            HttpResponse<String> machine = send(c.port, "POST", "/spaces/s1/risk-scores/preview", draft, "machine");
            assertEquals(403, machine.statusCode(), machine.body());
            assertTrue(machine.body().contains("saved model only"), machine.body());
            assertFalse(machine.body().contains("m1"), machine.body());
        }
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config, Path data) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    private Ctx open(Path root) throws Exception {
        Path base = root.resolve("s1");
        Path config = base.resolve("config");
        Path data = base.resolve("data");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        DuckDbUtil.loadDriver();
        Files.createDirectories(data.resolve("topups"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('m1','FAILED',10.0,'t1')) AS v(msisdn, status, amount, topup_id)) TO '"
                    + data.resolve("topups").resolve("data.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        new ComponentStore(config.resolve("registry")).write("dataset", "topups", Map.of("physicalRef", "topups"));
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config, data);
    }

    private static Map<String, Object> model(String scope) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("entityType", "subscriber");
        m.put("highThreshold", 50);
        if (scope != null) m.put("dataScope", scope);
        m.put("factors", List.of(
                Map.of("id", "failed", "dataset", "topups", "key", "msisdn", "measure", "count",
                        "filters", List.of(Map.of("field", "status", "op", "=", "value", "FAILED")),
                        "weight", 30, "cap", 45, "evidence", List.of("topup_id")),
                Map.of("id", "spend", "dataset", "topups", "key", "msisdn", "measure", "sum(amount)", "weight", 1)));
        return m;
    }

    /** Write the scores Dataset with the production writer: two runs, the later one scoring m1 higher. */
    private static void score(Ctx c, String modelId, Map<String, Object> content) throws Exception {
        new ComponentStore(c.config.resolve("registry")).write("risk-score", modelId, content);
        RiskScoreModel model = RiskScoreModel.fromMap(modelId, content);
        String v = RiskScoreEvaluator.version(content);
        RiskScoreEvaluator.write(c.data, model, v, "r1", Instant.parse("2026-09-01T00:00:00Z"),
                List.of(RiskScorer.score(model, "m1", Map.of("failed", 1.0, "spend", 10.0), Map.of())));
        RiskScoreEvaluator.write(c.data, model, v, "r2", Instant.parse("2026-09-02T00:00:00Z"),
                List.of(RiskScorer.score(model, "m1", Map.of("failed", 2.0, "spend", 10.0), Map.of()),
                        RiskScorer.score(model, "m2", Map.of("spend", 3.0), Map.of())));
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (who != null) b.header("Authorization", "Bearer " + who);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void theLatestScoreComesBackWithFactorsThatRecomputeToIt(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            score(c, "subs", model(null));
            HttpResponse<String> r = send(c.port, "GET", "/spaces/s1/risk-scores/subs/m1", null, "analyst");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = V1Body.of(r.body());
            assertEquals("r2", d.get("runId").asText(), "the latest run, not the first");
            assertEquals(55.0, d.get("score").asDouble(), 1e-9, "min(30×2, 45) + 1×10");
            assertTrue(d.get("high").asBoolean());
            assertEquals(50.0, d.get("highThreshold").asDouble());
            assertEquals("subscriber", d.get("entityType").asText());
            List<Map<String, Object>> factors = JSON.convertValue(d.get("factors"), new TypeReference<>() {});
            assertEquals(2, factors.size());
            assertEquals(true, factors.get(0).get("capped"));
            assertEquals(d.get("score").asDouble(), RiskScorer.recompute(factors), 1e-9, "reproducible over the wire");

            JsonNode m2 = V1Body.of(send(c.port, "GET", "/spaces/s1/risk-scores/subs/m2", null, "analyst").body());
            assertEquals(true, JSON.convertValue(m2.get("factors").get(0), Map.class).get("missing"),
                    "a missing indicator is flagged in the factors");
        }
    }

    @Test
    void unknownModelOrEntityIsA404(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            score(c, "subs", model(null));
            assertEquals(404, send(c.port, "GET", "/spaces/s1/risk-scores/nope/m1", null, "analyst").statusCode());
            assertEquals(404, send(c.port, "GET", "/spaces/s1/risk-scores/subs/m9", null, "analyst").statusCode());
            assertEquals(404, send(c.port, "GET", "/spaces/s1/risk-scores/subs/m1'%20OR%20'1'='1", null, "analyst")
                    .statusCode(), "the key is bound, never spliced");
        }
    }

    @Test
    void aStoredModelThatNoLongerParsesIsA404Not500(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            score(c, "subs", model(null));
            Map<String, Object> broken = new java.util.LinkedHashMap<>(model(null));
            broken.put("highThreshold", "very high");   // written behind the gate, e.g. a hand edit
            new ComponentStore(c.config.resolve("registry")).write("risk-score", "subs", broken);
            HttpResponse<String> r = send(c.port, "GET", "/spaces/s1/risk-scores/subs/m1", null, "analyst");
            assertEquals(404, r.statusCode(), r.body());
        }
    }

    @Test
    void withoutTheCapabilityItIs403(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            score(c, "subs", model(null));
            assertEquals(403, send(c.port, "GET", "/spaces/s1/risk-scores/subs/m1", null, "nocap").statusCode());
        }
    }

    @Test
    void aScopedModelIsHiddenFromACallerOutsideItsScope(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            score(c, "fraud_subs", model("fraud"));
            HttpResponse<String> out = send(c.port, "GET", "/spaces/s1/risk-scores/fraud_subs/m1", null, "billing");
            assertEquals(404, out.statusCode(), "out of scope reads as absence: " + out.body());
            assertEquals(200, send(c.port, "GET", "/spaces/s1/risk-scores/fraud_subs/m1", null, "fraud").statusCode());
            assertEquals(200, send(c.port, "GET", "/spaces/s1/risk-scores/fraud_subs/m1", null, "analyst").statusCode(),
                    "an unscoped caller sees every model");

            score(c, "open_subs", model(null));
            assertEquals(404, send(c.port, "GET", "/spaces/s1/risk-scores/open_subs/m1", null, "billing").statusCode(),
                    "a data-scoped caller cannot read an UNscoped model");
            assertEquals(200, send(c.port, "GET", "/spaces/s1/risk-scores/open_subs/m1", null, "analyst").statusCode(),
                    "an unscoped caller can");
        }
    }

    @Test
    void aSaveNamingAColumnTheSchemaLacksIs422(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, Object> bad = new java.util.LinkedHashMap<>(model(null));
            bad.put("factors", List.of(Map.of("id", "x", "dataset", "topups", "key", "imsi", "measure", "count", "weight", 1)));
            HttpResponse<String> r = send(c.port, "POST", "/spaces/s1/components/risk-score",
                    JSON.writeValueAsString(withId("bad", bad)), "analyst");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("imsi"), "names the missing column: " + r.body());

            Map<String, Object> noDs = new java.util.LinkedHashMap<>(model(null));
            noDs.put("factors", List.of(Map.of("id", "x", "dataset", "ghost", "key", "msisdn", "measure", "count", "weight", 1)));
            assertEquals(422, send(c.port, "POST", "/spaces/s1/components/risk-score",
                    JSON.writeValueAsString(withId("ghost", noDs)), "analyst").statusCode(), "unknown Dataset fails closed");

            Map<String, Object> word = new java.util.LinkedHashMap<>(model(null));
            word.put("factors", List.of(Map.of("id", "x", "dataset", "topups", "key", "msisdn", "measure", "count", "weight", "heavy")));
            assertEquals(422, send(c.port, "POST", "/spaces/s1/components/risk-score",
                    JSON.writeValueAsString(withId("word", word)), "analyst").statusCode(), "a non-numeric weight");

            HttpResponse<String> ok = send(c.port, "POST", "/spaces/s1/components/risk-score",
                    JSON.writeValueAsString(withId("good", model(null))), "analyst");
            assertTrue(ok.statusCode() < 300, ok.body());
            assertTrue(new ComponentStore(c.config.resolve("registry")).exists("risk-score", "good"));
            assertFalse(new ComponentStore(c.config.resolve("registry")).exists("risk-score", "bad"), "nothing stored");
        }
    }

    @Test
    void aSaveWhoseOutputWouldOverwriteAnotherStoreIs422AndDestroysNothing(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Path foreign = Files.createDirectories(c.data.resolve("risk_scores_clash"));
            Path precious = Files.writeString(foreign.resolve("data.parquet"), "not ours");
            HttpResponse<String> r = send(c.port, "POST", "/spaces/s1/components/risk-score",
                    JSON.writeValueAsString(withId("clash", model(null))), "analyst");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("not this model's output"), r.body());
            assertTrue(Files.exists(precious));

            new ComponentStore(c.config.resolve("registry")).write("dataset", "risk_scores_named",
                    Map.of("physicalRef", "topups"));
            assertEquals(422, send(c.port, "POST", "/spaces/s1/components/risk-score",
                    JSON.writeValueAsString(withId("named", model(null))), "analyst").statusCode(),
                    "a Dataset of the derived id over another store");

            // Any Dataset whose physicalRef IS the derived store, whatever its own id, would be overwritten.
            new ComponentStore(c.config.resolve("registry")).write("dataset", "innocent_name",
                    Map.of("physicalRef", "risk_scores_aimed2"));
            HttpResponse<String> byRef = send(c.port, "POST", "/spaces/s1/components/risk-score",
                    JSON.writeValueAsString(withId("aimed2", model(null))), "analyst");
            assertEquals(422, byRef.statusCode(), byRef.body());
            assertTrue(byRef.body().contains("innocent_name"), byRef.body());

            Map<String, Object> authored = new java.util.LinkedHashMap<>(model(null));
            authored.put("scoresDataset", "topups");
            HttpResponse<String> a = send(c.port, "POST", "/spaces/s1/components/risk-score",
                    JSON.writeValueAsString(withId("aimed", authored)), "analyst");
            assertEquals(422, a.statusCode(), "the output name is not authorable: " + a.body());
        }
    }

    @Test
    void aMaskedSpaceReadsTheScoreByTheLinkAnalysisAliasAndNeverAnswersTheRawKey(@TempDir Path root) throws Exception {
        // LA-SUSPICION-FACTORS-MASKED-1: the Studio holds a masked node by its Space alias, not the raw key.
        try (Ctx c = open(root)) {
            ComponentStore store = new ComponentStore(c.config.resolve("registry"));
            store.write("dataset", "topups", Map.of("physicalRef", "topups",
                    "columns", List.of(Map.of("name", "msisdn"), Map.of("name", "topup_id"), Map.of("name", "status"))));
            Map<String, Object> m = model(null);
            store.write("risk-score", "subs", m);
            RiskScoreModel model = RiskScoreModel.fromMap("subs", m);
            var run = com.gamma.risk.RiskScoreEvaluator.evaluate(model, id -> com.gamma.query.DatasetRelation.relationSql(
                    store.get("dataset", id).orElseThrow().content(), c.data, null),
                    com.gamma.mask.EvidenceMasker.of(store, c.config, model.datasetIds()));
            RiskScoreEvaluator.write(c.data, model, "v", "r1", Instant.now(), run.scored());
            String alias = com.gamma.entitystore.MaskTokens.token(com.gamma.entitystore.MaskTokens.spaceKey(c.config), "m1");
            String path = "/spaces/s1/risk-scores/subs/" + java.net.URLEncoder.encode(alias, java.nio.charset.StandardCharsets.UTF_8);

            HttpResponse<String> r = send(c.port, "GET", path, null, "analyst");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = V1Body.of(r.body());
            assertEquals(alias, d.get("entityKey").asText(), "the alias asked is echoed: " + r.body());
            assertTrue(d.get("keyMasked").asBoolean());
            assertTrue(d.get("factors").isArray() && d.get("factors").size() > 0, r.body());
            assertFalse(r.body().contains("\"m1\""), "the raw key leaked: " + r.body());

            JsonNode rev = V1Body.of(send(c.port, "GET", path, null, "revealer").body());
            assertEquals("m1", rev.get("entityKey").asText(), "canRevealLinkEntities sees the raw key");

            // An alias of no scored key is the same 404 as any unknown key, naming only the alias.
            String other = com.gamma.entitystore.MaskTokens.token(com.gamma.entitystore.MaskTokens.spaceKey(c.config), "nobody");
            HttpResponse<String> miss = send(c.port, "GET", "/spaces/s1/risk-scores/subs/"
                    + java.net.URLEncoder.encode(other, java.nio.charset.StandardCharsets.UTF_8), null, "analyst");
            assertEquals(404, miss.statusCode(), miss.body());
        }
    }

    @Test
    void anInvestigationAliasResolvesUnderItsOwnKeyOnlyForACallerWhoCanReadThatInvestigation(@TempDir Path root) throws Exception {
        // LA-SUSPICION-FACTORS-INVESTIGATION-1: an Investigation masks with its OWN key, not the Space key, so the Studio
        // names the Investigation and the route resolves the alias after the Investigation's read gate.
        try (Ctx c = open(root)) {
            score(c, "subs", model(null));
            Files.writeString(c.config.resolve("link-analysis.toon"), "masking_mode: all\n");
            new com.gamma.pipeline.ViewStore(c.config.resolve("views")).write(new com.gamma.pipeline.ViewDefinition("calls_view",
                    "flow-x", List.of(), "SELECT * FROM (VALUES ('m1','m2'),('m2','m3')) AS t(caller,callee)", "2026-10-31T00:00:00Z"));
            new ComponentStore(c.config.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            HttpResponse<String> made = send(c.port, "POST", "/spaces/s1/inv/investigations",
                    "{\"purpose\":\"test\",\"id\":\"inv-a\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}", "lead");
            assertEquals(200, made.statusCode(), made.body());
            HttpResponse<String> seeded = send(c.port, "POST", "/spaces/s1/inv/investigations/inv-a/ops",
                    "{\"op\":\"seed\",\"ids\":[\"m1\"]}", "lead");
            assertEquals(200, seeded.statusCode(), seeded.body());
            assertFalse(seeded.body().contains("\"m1\""), "the Investigation masks m1: " + seeded.body());
            java.util.regex.Matcher t = java.util.regex.Pattern.compile("masked:[0-9a-f]{16}").matcher(seeded.body());
            assertTrue(t.find(), seeded.body());
            String alias = t.group();
            String spaceAlias = com.gamma.entitystore.MaskTokens.token(com.gamma.entitystore.MaskTokens.spaceKey(c.config), "m1");
            assertNotEquals(spaceAlias, alias, "the Investigation's own key, not the Space key");
            String path = "/spaces/s1/risk-scores/subs/" + java.net.URLEncoder.encode(alias, java.nio.charset.StandardCharsets.UTF_8);

            assertEquals(404, send(c.port, "GET", path, null, "lead").statusCode(),
                    "without the Investigation the alias is read under the Space key and resolves nothing");
            HttpResponse<String> r = send(c.port, "GET", path + "?investigation=inv-a", null, "lead");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = V1Body.of(r.body());
            assertEquals(55.0, d.get("score").asDouble(), 1e-9, "m1's latest score");
            assertEquals(alias, d.get("entityKey").asText(), "the Investigation's alias is echoed: " + r.body());
            assertTrue(d.get("keyMasked").asBoolean());
            assertFalse(r.body().contains("\"m1\""), "the raw key leaked: " + r.body());

            // A caller who cannot read the Investigation (not a member) gets its 404, never the score.
            HttpResponse<String> outsider = send(c.port, "GET", path + "?investigation=inv-a", null, "analyst");
            assertEquals(404, outsider.statusCode(), outsider.body());
            assertFalse(outsider.body().contains("\"score\""), outsider.body());
            assertEquals(404, send(c.port, "GET", path + "?investigation=nope", null, "lead").statusCode(),
                    "an unknown Investigation is a 404");
        }
    }

    @Test
    void evidenceIsMaskedAtWriteTimeTheKeyOnReadAndNoRouteServesTheMaskKey(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // msisdn is classified MSISDN, topup_id PII; status is not classified.
            ComponentStore store = new ComponentStore(c.config.resolve("registry"));
            store.write("dataset", "topups", Map.of("physicalRef", "topups",
                    "columns", List.of(Map.of("name", "msisdn", "classification", "msisdn"),
                            Map.of("name", "topup_id", "classification", "PII"),
                            Map.of("name", "status"))));
            Map<String, Object> m = model(null);
            store.write("risk-score", "subs", m);
            RiskScoreModel model = RiskScoreModel.fromMap("subs", m);
            var run = com.gamma.risk.RiskScoreEvaluator.evaluate(model, id -> com.gamma.query.DatasetRelation.relationSql(
                    store.get("dataset", id).orElseThrow().content(), c.data, null),
                    com.gamma.mask.EvidenceMasker.of(store, c.config, model.datasetIds()));
            RiskScoreEvaluator.write(c.data, model, "v", "r1", Instant.now(), run.scored());

            HttpResponse<String> r = send(c.port, "GET", "/spaces/s1/risk-scores/subs/m1", null, "analyst");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = V1Body.of(r.body());
            // D-P8 mask on read: without canRevealLinkEntities the key is the Space token — the same token masked
            // evidence carries for that value — and the raw key appears nowhere in the body.
            String token = com.gamma.mask.EvidenceMasker.forSpace(c.config).tokenFor("m1");
            assertEquals(token, d.get("entityKey").asText(), "the entity key is masked for analyst: " + r.body());
            assertTrue(d.get("keyMasked").asBoolean());
            assertFalse(r.body().contains("\"m1\""), "the raw key leaked: " + r.body());
            JsonNode rev = V1Body.of(send(c.port, "GET", "/spaces/s1/risk-scores/subs/m1", null, "revealer").body());
            assertEquals("m1", rev.get("entityKey").asText(), "canRevealLinkEntities sees the raw key");
            assertFalse(rev.get("keyMasked").asBoolean());
            assertTrue(rev.get("factors").get(0).get("evidence").get(0).get("topup_id").asText().startsWith("masked:"),
                    "unmasking the key does not unmask write-time evidence");
            assertFalse(r.body().contains("\"t1\""), "the PII evidence value was never stored: " + r.body());
            assertTrue(d.get("factors").get(0).get("evidence").get(0).get("topup_id").asText().startsWith("masked:"));
            List<Map<String, Object>> factors = JSON.convertValue(d.get("factors"), new TypeReference<>() {});
            assertEquals(d.get("score").asDouble(), RiskScorer.recompute(factors), 1e-9, "masking touches no number");

            // The key sits in <config>.secrets/ — no route serves it, by any spelling that could reach it.
            Path key = c.config.resolveSibling("config.secrets").resolve(".risk-score-mask.key");
            assertTrue(Files.isRegularFile(key), "created beside the config root");
            String hex = Files.readString(key).trim();
            for (String path : List.of("/spaces/s1/config/risk-score/..%2F..%2Fconfig.secrets%2F.risk-score-mask.key",
                    "/spaces/s1/db/table?store=..%2Fconfig.secrets", "/spaces/s1/db/table?store=.risk-score-mask.key",
                    "/spaces/s1/export", "/spaces/s1/db/catalog")) {
                HttpResponse<String> probe = send(c.port, "GET", path, null, "analyst");
                assertFalse(probe.body().contains(hex), path + " served the mask key");
                assertFalse(probe.body().contains(".risk-score-mask.key") && probe.statusCode() == 200
                        && path.contains("catalog"), path + " lists the key file");
            }
        }
    }

    /**
     * MASKING-TWO-VOCABULARIES-1: evidence classified WALLET (a class the masked default Entity Type {@code wallet} claims, not
     * one of the four SENSITIVE classes) is masked at write time too, so neither an analyst nor a revealer reads it raw.
     */
    @Test
    void evidenceClassedWithAMaskedEntityTypesClassIsMaskedAtWriteTime(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            ComponentStore store = new ComponentStore(c.config.resolve("registry"));
            store.write("dataset", "topups", Map.of("physicalRef", "topups",
                    "columns", List.of(Map.of("name", "msisdn"), Map.of("name", "topup_id", "classification", "WALLET"),
                            Map.of("name", "status"))));
            Map<String, Object> m = model(null);
            store.write("risk-score", "subs", m);
            RiskScoreModel model = RiskScoreModel.fromMap("subs", m);
            var run = com.gamma.risk.RiskScoreEvaluator.evaluate(model, id -> com.gamma.query.DatasetRelation.relationSql(
                    store.get("dataset", id).orElseThrow().content(), c.data, null),
                    com.gamma.mask.EvidenceMasker.of(store, c.config, model.datasetIds()));
            RiskScoreEvaluator.write(c.data, model, "v", "r1", Instant.now(), run.scored());

            String token = com.gamma.mask.EvidenceMasker.forSpace(c.config).tokenFor("t1");
            for (String who : List.of("analyst", "revealer")) {
                HttpResponse<String> r = send(c.port, "GET", "/spaces/s1/risk-scores/subs/m1", null, who);
                assertEquals(200, r.statusCode(), r.body());
                assertEquals(token, V1Body.of(r.body()).get("factors").get(0).get("evidence").get(0).get("topup_id").asText(),
                        who + ": the WALLET evidence is the Space token: " + r.body());
                assertFalse(r.body().contains("\"t1\""), who + ": the WALLET evidence value was never stored: " + r.body());
            }
        }
    }

    @Test
    void aBundleCannotPlantAModelWhoseEvidenceColumnTheSchemaLacks(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, Object> bad = new java.util.LinkedHashMap<>(model(null));
            bad.put("factors", List.of(Map.of("id", "x", "dataset", "topups", "key", "msisdn", "measure", "count",
                    "weight", 1, "evidence", List.of("imsi"))));
            String bundle = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"exportedAt\":\"2026-07-18T00:00:00Z\","
                    + "\"sourceSpace\":null,\"items\":[{\"kind\":\"risk-score\",\"id\":\"planted\",\"content\":"
                    + JSON.writeValueAsString(bad) + "}]}";
            HttpResponse<String> r = send(c.port, "POST", "/spaces/s1/bundle/import", bundle, "analyst");
            assertFalse(new ComponentStore(c.config.resolve("registry")).exists("risk-score", "planted"),
                    "the bulk writer runs the Schema column check too: " + r.body());
            assertTrue(r.body().contains("imsi"), "and names the column: " + r.body());
        }
    }

    @Test
    void theRiskScoresPrefixIsReservedForDatasetsAndSinks(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            ComponentStore store = new ComponentStore(c.config.resolve("registry"));
            for (String[] body : new String[][]{
                    {"dataset", "{\"id\":\"risk_scores_x\",\"physicalRef\":\"topups\"}"},
                    {"dataset", "{\"id\":\"harmless\",\"physicalRef\":\"RISK_SCORES_subs\"}"},
                    {"dataset", "{\"id\":\"harmless2\",\"physicalRef\":\"./a/../risk_scores_subs/x\"}"},
                    {"dataset", "{\"id\":\"risk_scores_ghost_latest\",\"physicalRef\":\"risk_scores_ghost_latest\"}"},
                    {"sink", "{\"id\":\"risk_scores_sink\",\"format\":\"PARQUET\"}"}}) {
                HttpResponse<String> r = send(c.port, "POST", "/spaces/s1/components/" + body[0], body[1], "analyst");
                assertEquals(422, r.statusCode(), body[1] + " -> " + r.body());
                assertTrue(r.body().contains("reserved prefix"), r.body());
            }
            assertFalse(store.exists("dataset", "harmless"));

            // The documented exception: id = physicalRef = risk_scores_<model>_latest over a SAVED model.
            assertTrue(send(c.port, "POST", "/spaces/s1/components/risk-score",
                    JSON.writeValueAsString(withId("subs", model(null))), "analyst").statusCode() < 300);
            HttpResponse<String> ok = send(c.port, "POST", "/spaces/s1/components/dataset",
                    "{\"id\":\"risk_scores_subs_latest\",\"physicalRef\":\"risk_scores_subs_latest\"}", "analyst");
            assertTrue(ok.statusCode() < 300, ok.body());
            assertTrue(send(c.port, "POST", "/spaces/s1/components/dataset",
                    "{\"id\":\"plain\",\"physicalRef\":\"topups\"}", "analyst").statusCode() < 300, "others unaffected");
        }
    }

    private static Map<String, Object> withId(String id, Map<String, Object> m) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(m);
        out.put("id", id);
        return out;
    }
}

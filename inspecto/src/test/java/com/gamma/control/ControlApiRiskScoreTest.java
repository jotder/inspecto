package com.gamma.control;

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
        return Optional.empty();
    };

    @AfterEach
    void tearDown() { Authenticators.forTest(null); }

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

            Map<String, Object> authored = new java.util.LinkedHashMap<>(model(null));
            authored.put("scoresDataset", "topups");
            HttpResponse<String> a = send(c.port, "POST", "/spaces/s1/components/risk-score",
                    JSON.writeValueAsString(withId("aimed", authored)), "analyst");
            assertEquals(422, a.statusCode(), "the output name is not authorable: " + a.body());
        }
    }

    @Test
    void evidenceIsMaskedAtWriteTimeTheKeyIsRawAndNoRouteServesTheMaskKey(@TempDir Path root) throws Exception {
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
                    com.gamma.risk.EvidenceMasker.of(store, c.config, model));
            RiskScoreEvaluator.write(c.data, model, "v", "r1", Instant.now(), run.scored());

            HttpResponse<String> r = send(c.port, "GET", "/spaces/s1/risk-scores/subs/m1", null, "analyst");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = V1Body.of(r.body());
            assertEquals("m1", d.get("entityKey").asText(), "the entity key is raw, like every Alert key (D-P8)");
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

    private static Map<String, Object> withId(String id, Map<String, Object> m) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(m);
        out.put("id", id);
        return out;
    }
}

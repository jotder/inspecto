package com.gamma.predictive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetRelation;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /predictions/{kind}/{model}/{entityKey}} over real HTTP with an ARMED Subject, over stores written by the
 * production writers: 401 without a credential, 403 without {@code canWorkIncidents}, 404 for an unknown kind, model
 * or key and a not-yet-run model (indistinguishable), 200 with the latest row, its parsed explanation (which
 * recomputes) and the run history; the key masked unless the caller holds {@code canRevealLinkEntities}.
 */
class ControlApiPredictionReadTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer analyst".equals(auth)) return Optional.of(new Subject("ana", Set.of("canWorkIncidents")));
        if ("Bearer revealer".equals(auth))
            return Optional.of(new Subject("rev", Set.of("canWorkIncidents", "canRevealLinkEntities")));
        if ("Bearer nocap".equals(auth)) return Optional.of(new Subject("nc", Set.of("canAuthorWorkbench")));
        return Optional.empty();
    };

    @AfterEach
    void tearDown() { Authenticators.forTest(null); }

    private interface Planter { void plant(Path config, Path data) throws Exception; }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config, Path data) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }

        ComponentStore store() { return new ComponentStore(config.resolve("registry")); }

        Map<String, Object> content(String kind, String id) { return store().get(kind, id).orElseThrow().content(); }

        String relation(String dataset) {
            return DatasetRelation.relationSql(store().get("dataset", dataset).map(ComponentRegistry.Component::content)
                    .orElseThrow(), data, null);
        }
    }

    private Ctx open(Path root, Planter planter) throws Exception {
        Path base = root.resolve("s1");
        Path config = base.resolve("config");
        Path data = base.resolve("data");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        planter.plant(config, data);
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config, data);
    }

    /** Two churn runs: r1 a day earlier, r2 at {@link ChurnCorpus#AS_OF}. */
    private static void churn(Ctx c) throws Exception {
        Map<String, Object> content = c.content(ChurnModel.KIND, ChurnCorpus.MODEL);
        ChurnModel model = ChurnModel.fromMap(ChurnCorpus.MODEL, content);
        int run = 0;
        for (LocalDate asOf : List.of(ChurnCorpus.AS_OF.minusDays(1), ChurnCorpus.AS_OF))
            ChurnEvaluator.write(c.data, model, ChurnEvaluator.version(content), "r" + (++run),
                    Instant.parse("2026-07-0" + run + "T00:00:00Z"), asOf,
                    ChurnEvaluator.evaluate(model, asOf, c.relation(model.dataset())));
    }

    private static void segment(Ctx c) throws Exception {
        Map<String, Object> content = c.content(SegmentationModel.KIND, RfmCorpus.MODEL);
        SegmentationModel model = SegmentationModel.fromMap(RfmCorpus.MODEL, content);
        SegmentEvaluator.write(c.data, model, SegmentEvaluator.version(content), "r1",
                Instant.parse("2026-07-01T00:00:00Z"), RfmCorpus.AS_OF,
                SegmentEvaluator.evaluate(model, RfmCorpus.AS_OF, c.relation(model.dataset())));
    }

    /** Two forecast runs of {@code demand}; the read must return only the later one. */
    private static void forecast(Ctx c) throws Exception {
        Map<String, Object> content = c.content(ForecastModel.KIND, "demand");
        ForecastModel model = ForecastModel.fromMap("demand", content);
        int run = 0;
        for (LocalDate asOf : List.of(ForecastCorpus.AS_OF.minusDays(1), ForecastCorpus.AS_OF))
            ForecastEvaluator.write(c.data, model, ForecastEvaluator.version(content), "r" + (++run),
                    Instant.parse("2026-07-0" + run + "T00:00:00Z"), asOf,
                    ForecastEvaluator.evaluate(model, asOf, c.relation(model.dataset())));
    }

    private HttpResponse<String> get(int port, String path, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/spaces/s1" + path));
        if (who != null) b.header("Authorization", "Bearer " + who);
        return client.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;
    }

    private static Map<String, Object> map(JsonNode n) {
        return JSON.convertValue(n, new TypeReference<>() {});
    }

    @Test
    void theGatesFailClosed(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, ChurnCorpus::plant)) {
            assertEquals(404, get(c.port, "/predictions/churn-model/churn/steady", "analyst").statusCode(),
                    "a model that has not run yet has no prediction");
            churn(c);
            String ok = "/predictions/churn-model/churn/steady";
            assertEquals(401, get(c.port, ok, null).statusCode());
            assertEquals(403, get(c.port, ok, "nocap").statusCode(), "canWorkIncidents is the read gate");
            assertEquals(200, get(c.port, ok, "analyst").statusCode());
            for (String p : List.of("/predictions/anomaly-model/churn/steady", "/predictions/churn-model/nope/steady",
                    "/predictions/churn-model/churn/nobody", "/predictions/forecast-model/churn/steady",
                    "/predictions/churn-model/bad..id/steady")) {
                HttpResponse<String> r = get(c.port, p, "analyst");
                assertEquals(404, r.statusCode(), p + " " + r.body());
            }
        }
    }

    @Test
    void aChurnScoreComesBackWithItsExplanationHistoryAndTheKeyMasked(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, ChurnCorpus::plant)) {
            churn(c);
            JsonNode d = data(get(c.port, "/predictions/churn-model/churn/lapsing", "revealer"));
            assertEquals("lapsing", d.get("entityKey").asText());
            assertFalse(d.get("keyMasked").asBoolean());
            assertEquals("churn-model", d.get("kind").asText());
            assertEquals("customer", d.get("entityType").asText());
            assertEquals("r2", d.get("runId").asText(), "the latest run");
            assertEquals("elevated", d.get("band").asText());
            ChurnModel model = ChurnModel.fromMap(ChurnCorpus.MODEL, c.content(ChurnModel.KIND, ChurnCorpus.MODEL));
            assertEquals(d.get("score").asDouble(),
                    ChurnScorer.recompute(model, map(d.get("explanation"))).score(), 1e-9,
                    "the served explanation recomputes to the score");
            assertEquals(2, d.get("history").size());
            assertEquals("r2", d.get("history").get(0).get("runId").asText(), "newest first");

            JsonNode insufficient = data(get(c.port, "/predictions/churn-model/churn/newbie", "revealer"));
            assertTrue(insufficient.get("score").isNull(), "insufficient is NULL, never 0");

            JsonNode masked = data(get(c.port, "/predictions/churn-model/churn/lapsing", "analyst"));
            assertTrue(masked.get("keyMasked").asBoolean());
            assertTrue(masked.get("entityKey").asText().startsWith("masked:"), masked.toString());
            assertFalse(masked.toString().contains("\"lapsing\""), "the raw key is nowhere in the body: " + masked);
        }
    }

    @Test
    void aCustomerSegmentComesBackWithItsScores(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, RfmCorpus::plant)) {
            segment(c);
            JsonNode d = data(get(c.port, "/predictions/segmentation-model/rfm/lapsed", "revealer"));
            assertEquals("At risk", d.get("segment").asText());
            assertTrue(d.has("recencyScore") && d.has("frequencyScore") && d.has("monetaryScore"), d.toString());
            assertTrue(d.get("explanation").has("recency"), "the explanation is parsed, not a string");
            assertEquals("At risk", d.get("history").get(0).get("segment").asText());
        }
    }

    @Test
    void aForecastReturnsEveryStepOfTheLatestRunOnly(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, ForecastCorpus::plant)) {
            forecast(c);
            JsonNode d = data(get(c.port, "/predictions/forecast-model/demand/trend", "revealer"));
            assertEquals("r2", d.get("runId").asText());
            JsonNode points = d.get("points");
            assertEquals(10, points.size(), "the horizon, from the latest run only");
            for (int i = 0; i < points.size(); i++) {
                JsonNode p = points.get(i);
                assertEquals(i + 1, p.get("step").asInt(), "step order");
                assertEquals("ok", p.get("status").asText());
                Forecaster.Recomputed again = Forecaster.recompute(p.get("step").asInt(), map(p.get("explanation")));
                assertEquals(p.get("forecast").asDouble(), again.forecast(), 1e-9);
                assertTrue(p.get("lower").asDouble() <= p.get("forecast").asDouble());
                assertTrue(p.has("backtestMae") && p.has("bucket"), p.toString());
            }
            JsonNode s = data(get(c.port, "/predictions/forecast-model/demand/short", "revealer")).get("points");
            assertEquals(1, s.size(), "an insufficient series is one row");
            assertEquals("insufficient", s.get(0).get("status").asText());
            assertTrue(s.get(0).get("forecast").isNull());
        }
    }
}

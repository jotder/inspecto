package com.gamma.anomaly;

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
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ANOMALY-DETECTION-1 S4 over real HTTP with an ARMED Subject (a test with no Subject would make
 * {@code withCapability} a no-op): {@code GET /anomaly-scores/{model}/{entityKey}} returns the latest score with its
 * explanation and the recent runs; {@code POST /anomaly-scores/preview} scores one entity and writes nothing. Every
 * gate: the capability, unsaved content needing {@code canAuthorWorkbench}, data scopes and the existence-hiding
 * 404s, the body shape, and D-P8 key masking unless {@code canRevealLinkEntities}.
 */
class ControlApiAnomalyScoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private static final Set<String> CAPS = Set.of("canWorkIncidents", "canAuthorWorkbench");

    /** analyst: unscoped, no reveal · fraud / billing: data-scoped · nocap: no canWorkIncidents · revealer: reads only. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer analyst".equals(auth)) return Optional.of(new Subject("ana", CAPS));
        if ("Bearer fraud".equals(auth)) return Optional.of(new Subject("fay", CAPS, Set.of("fraud")));
        if ("Bearer billing".equals(auth)) return Optional.of(new Subject("bo", CAPS, Set.of("billing")));
        if ("Bearer nocap".equals(auth)) return Optional.of(new Subject("nc", Set.of("canAuthorWorkbench")));
        if ("Bearer revealer".equals(auth))
            return Optional.of(new Subject("rev", Set.of("canWorkIncidents", "canRevealLinkEntities")));
        return Optional.empty();
    };

    @AfterEach
    void tearDown() { Authenticators.forTest(null); }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config, Path data) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    /** One Space {@code s1} over the golden corpus, with the model saved as {@code usage} (optionally data-scoped). */
    private Ctx open(Path root, String scope) throws Exception {
        Path base = root.resolve("s1");
        Path config = base.resolve("config");
        Path data = base.resolve("data");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Map<String, Object> model = new LinkedHashMap<>(AnomalyCorpus.plant(config, data));
        if (scope != null) {
            model.put("dataScope", scope);
            new ComponentStore(config.resolve("registry")).write(AnomalyModel.KIND, AnomalyCorpus.MODEL, model);
        }
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config, data);
    }

    /** Two production-writer runs: the earlier scores 2026-09-29 (all normal), the later 2026-09-30 (spike high). */
    private static void score(Ctx c) throws Exception {
        ComponentStore store = new ComponentStore(c.config.resolve("registry"));
        Map<String, Object> content = store.get(AnomalyModel.KIND, AnomalyCorpus.MODEL).orElseThrow().content();
        AnomalyModel model = AnomalyModel.fromMap(AnomalyCorpus.MODEL, content);
        String v = AnomalyScoreEvaluator.version(content);
        int run = 0;
        for (LocalDate asOf : List.of(LocalDate.parse("2026-09-30"), AnomalyCorpus.AS_OF)) {
            AnomalyScoreEvaluator.Run r = AnomalyScoreEvaluator.evaluate(model, asOf, id -> DatasetRelation.relationSql(
                    store.get("dataset", id).map(ComponentRegistry.Component::content).orElseThrow(), c.data, null));
            AnomalyScoreEvaluator.write(c.data, model, v, "r" + (++run), Instant.parse("2026-10-0" + run + "T00:00:00Z"), r);
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (who != null) b.header("Authorization", "Bearer " + who);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;
    }

    private static long parquetFiles(Path data) throws Exception {
        if (!Files.isDirectory(data)) return 0;
        try (Stream<Path> s = Files.walk(data)) {
            return s.filter(p -> p.toString().endsWith(".parquet")).count();
        }
    }

    @Test
    void theLatestScoreComesBackWithItsExplanationAndRecentRunsAndTheKeyMasked(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            score(c);
            HttpResponse<String> r = send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "revealer");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = data(r);
            assertEquals("spike", d.get("entityKey").asText(), "a caller with canRevealLinkEntities sees the raw key");
            assertFalse(d.get("keyMasked").asBoolean());
            assertEquals("r2", d.get("runId").asText(), "the latest run, not the first");
            assertEquals("high", d.get("band").asText());
            assertTrue(d.get("score").asDouble() >= d.get("highThreshold").asDouble());
            assertEquals("subscriber", d.get("entityType").asText());
            assertEquals(2, d.get("features").size());
            double[] re = AnomalyScorer.recompute(JSON.convertValue(d.get("features"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}), 10, 3);
            assertEquals(d.get("score").asDouble(), re[1], 1e-9, "the served explanation recomputes to the score");
            JsonNode history = d.get("history");
            assertEquals(2, history.size(), "one point per run, for the sparkline");
            assertEquals("r2", history.get(0).get("runId").asText(), "newest first");
            assertEquals("normal", history.get(1).get("band").asText(), "the earlier day was normal");

            JsonNode masked = data(send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "analyst"));
            assertTrue(masked.get("keyMasked").asBoolean(), "D-P8: no canRevealLinkEntities, masked on read");
            assertTrue(masked.get("entityKey").asText().startsWith("masked:"), masked.toString());
            assertFalse(masked.toString().contains("\"spike\""), "the raw key is nowhere in the body: " + masked);
        }
    }

    @Test
    void theCapabilityScopesAndExistenceHidingHold(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "fraud")) {
            score(c);
            assertEquals(403, send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "nocap").statusCode());
            assertEquals(200, send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "fraud").statusCode(),
                    "a caller holding the model's scope reads it");
            HttpResponse<String> billing = send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "billing");
            HttpResponse<String> unknownModel = send(c.port, "GET", "/spaces/s1/anomaly-scores/nope/spike", null, "fraud");
            HttpResponse<String> unknownKey = send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/nobody", null, "fraud");
            for (HttpResponse<String> r : List.of(billing, unknownModel, unknownKey))
                assertEquals(404, r.statusCode(), r.body());
            assertEquals(200, send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "analyst").statusCode(),
                    "an unscoped caller reads a scoped model");
        }
    }

    @Test
    void aNotYetScoredModelIs404(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            assertEquals(404, send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "analyst").statusCode());
        }
    }

    @Test
    void thePreviewScoresOneEntityAndWritesNothing(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            long before = parquetFiles(c.data);
            HttpResponse<String> r = send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\",\"entityKey\":\"spike\",\"asOf\":\"2026-10-01\"}", "revealer");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = data(r);
            assertTrue(d.get("found").asBoolean());
            assertTrue(d.get("saved").asBoolean());
            assertEquals("high", d.get("band").asText());
            assertEquals("2026-09-30", d.get("periodStart").asText());
            assertEquals(2, d.get("features").size());
            assertEquals("spike", d.get("entityKey").asText());
            assertEquals(before, parquetFiles(c.data), "no scores Dataset is written");
            assertFalse(Files.exists(c.data.resolve("anomaly_scores_usage")));

            JsonNode masked = data(send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\",\"entityKey\":\"spike\",\"asOf\":\"2026-10-01\"}", "analyst"));
            assertTrue(masked.get("keyMasked").asBoolean());
            assertTrue(masked.get("entityKey").asText().startsWith("masked:"));

            JsonNode none = data(send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\",\"entityKey\":\"nobody\",\"asOf\":\"2026-10-01\"}", "analyst"));
            assertFalse(none.get("found").asBoolean(), "an entity no feature names is not found");
            assertTrue(none.get("score").isNull());
        }
    }

    @Test
    void thePreviewGatesFailClosed(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            String content = JSON.writeValueAsString(Map.of("content", AnomalyCorpus.model(), "entityKey", "spike",
                    "asOf", "2026-10-01"));
            assertEquals(403, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\",\"entityKey\":\"spike\"}", "nocap").statusCode());
            assertEquals(403, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview", content, "revealer").statusCode(),
                    "unsaved content needs canAuthorWorkbench");
            HttpResponse<String> unsaved = send(c.port, "POST", "/spaces/s1/anomaly-scores/preview", content, "analyst");
            assertEquals(200, unsaved.statusCode(), unsaved.body());
            assertFalse(data(unsaved).get("saved").asBoolean());
            assertEquals("high", data(unsaved).get("band").asText());

            Map<String, Object> bad = new LinkedHashMap<>(AnomalyCorpus.model());
            bad.put("window", 500);
            assertEquals(422, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    JSON.writeValueAsString(Map.of("content", bad, "entityKey", "spike")), "analyst").statusCode());
            Map<String, Object> scoped = new LinkedHashMap<>(AnomalyCorpus.model());
            scoped.put("dataScope", "fraud");
            assertEquals(403, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    JSON.writeValueAsString(Map.of("content", scoped, "entityKey", "spike")), "billing").statusCode(),
                    "a data-scoped caller previews only content carrying its scope");
            assertEquals(400, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\",\"content\":{},\"entityKey\":\"spike\"}", "analyst").statusCode());
            assertEquals(400, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\"}", "analyst").statusCode());
            assertEquals(400, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\",\"entityKey\":\"spike\",\"asOf\":\"yesterday\"}", "analyst").statusCode());
            assertEquals(404, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"nope\",\"entityKey\":\"spike\"}", "analyst").statusCode());
        }
    }
}

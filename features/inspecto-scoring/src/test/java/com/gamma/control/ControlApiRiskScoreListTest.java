package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.risk.RiskScoreEvaluator;
import com.gamma.risk.RiskScoreModel;
import com.gamma.risk.RiskScorer;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /risk-score-lists/{model}} over real HTTP with an ARMED Subject (MFS review D4): the latest run's scores,
 * highest first, filtered by the model's declared {@code segments} (evidence columns), capped by the Space's list
 * limit; the capability, data scope and existence-hiding 404s hold.
 */
class ControlApiRiskScoreListTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private static final Set<String> CAPS = Set.of("canWorkIncidents", "canAuthorWorkbench", "canRevealLinkEntities");

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer analyst".equals(auth)) return Optional.of(new Subject("ana", CAPS));
        if ("Bearer fraud".equals(auth)) return Optional.of(new Subject("fay", CAPS, Set.of("fraud")));
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
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config, data);
    }

    private static Map<String, Object> model(String scope) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityType", "subscriber");
        m.put("highThreshold", 50);
        if (scope != null) m.put("dataScope", scope);
        m.put("segments", List.of("band", "mix"));
        m.put("factors", List.of(
                Map.of("id", "activity", "dataset", "topups", "key", "msisdn", "measure", "count", "weight", 10,
                        "evidence", List.of("band", "mix"))));
        return m;
    }

    private static Map<String, List<Map<String, Object>>> evidence(String band, String mix) {
        return Map.of("activity", List.of(Map.of("band", band, "mix", mix)));
    }

    /** m1 = 60 (band 2, both), m2 = 20 (band 1, out only), m3 = 40 (band 1, both), m4 = 10 (no evidence). */
    private static void score(Ctx c, String modelId, Map<String, Object> content) throws Exception {
        new ComponentStore(c.config.resolve("registry")).write("risk-score", modelId, content);
        RiskScoreModel model = RiskScoreModel.fromMap(modelId, content);
        String v = RiskScoreEvaluator.version(content);
        RiskScoreEvaluator.write(c.data, model, v, "r1", Instant.parse("2026-09-02T00:00:00Z"), List.of(
                RiskScorer.score(model, "m1", Map.of("activity", 6.0), evidence("2", "both")),
                RiskScorer.score(model, "m2", Map.of("activity", 2.0), evidence("1", "out")),
                RiskScorer.score(model, "m3", Map.of("activity", 4.0), evidence("1", "both")),
                RiskScorer.score(model, "m4", Map.of("activity", 1.0), null)));
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (who != null) b.header("Authorization", "Bearer " + who);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static List<String> keys(JsonNode d) {
        List<String> out = new ArrayList<>();
        d.get("rows").forEach(r -> out.add(r.get("entityKey").asText()));
        return out;
    }

    private JsonNode get(Ctx c, String query) throws Exception {
        HttpResponse<String> r = send(c.port, "GET", "/spaces/s1/risk-score-lists/subs" + query, null, "analyst");
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    @Test
    void listsTheLatestRunHighestFirstWithTheSegmentValuesFoundThere(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            score(c, "subs", model(null));
            JsonNode d = get(c, "");
            assertEquals(List.of("m1", "m3", "m2", "m4"), keys(d), "highest score first");
            assertEquals(60.0, d.get("rows").get(0).get("score").asDouble(), 1e-9);
            assertTrue(d.get("rows").get(0).get("high").asBoolean());
            assertEquals("2", d.get("rows").get(0).get("segments").get("band").asText());
            assertFalse(d.get("truncated").asBoolean());
            JsonNode segs = d.get("segments");
            assertEquals("band", segs.get(0).get("column").asText());
            assertEquals(List.of("1", "2"), List.of(segs.get(0).get("values").get(0).asText(), segs.get(0).get("values").get(1).asText()));
            assertEquals(2, segs.get(1).get("values").size(), "both, out");
        }
    }

    @Test
    void aSegmentFiltersAndTwoSegmentsAreAnded(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            score(c, "subs", model(null));
            assertEquals(List.of("m3", "m2"), keys(get(c, "?band=1")));
            assertEquals(List.of("m3"), keys(get(c, "?band=1&mix=both")));
            assertEquals(List.of(), keys(get(c, "?band=9")), "no entity has that value");
            assertEquals(4, keys(get(c, "?nosuch=1")).size(), "a name that is not a declared segment filters nothing");
        }
    }

    @Test
    void theRowCapIsTheSpacesListLimitAndALargerRequestIsClampedToIt(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            score(c, "subs", model(null));
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/s1/settings/list-limit", "{\"rows\":2}", "analyst");
            assertEquals(200, put.statusCode(), put.body());
            JsonNode d = get(c, "");
            assertEquals(List.of("m1", "m3"), keys(d));
            assertTrue(d.get("truncated").asBoolean());
            assertEquals(2, d.get("limit").asInt());
            assertEquals(2, get(c, "?limit=500").get("rows").size(), "a request cannot exceed the Space's limit");
            assertEquals(1, get(c, "?limit=1").get("rows").size());
        }
    }

    @Test
    void aModelWithNoRunYetListsNothing(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            new ComponentStore(c.config.resolve("registry")).write("risk-score", "subs", model(null));
            JsonNode d = get(c, "");
            assertEquals(0, d.get("rows").size());
            assertEquals(0, d.get("segments").get(0).get("values").size());
        }
    }

    @Test
    void theRouteIsGatedAndHidesWhatTheCallerMayNotSee(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            score(c, "subs", model(null));
            assertEquals(401, send(c.port, "GET", "/spaces/s1/risk-score-lists/subs", null, null).statusCode());
            HttpResponse<String> denied = send(c.port, "GET", "/spaces/s1/risk-score-lists/subs", null, "nocap");
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains("canWorkIncidents"), denied.body());
            assertEquals(404, send(c.port, "GET", "/spaces/s1/risk-score-lists/nosuch", null, "analyst").statusCode());
            assertEquals(404, send(c.port, "GET", "/spaces/s1/risk-score-lists/subs", null, "fraud").statusCode(),
                    "a data-scoped caller reaches only a model carrying a scope it holds");
            score(c, "scoped", model("fraud"));
            assertEquals(200, send(c.port, "GET", "/spaces/s1/risk-score-lists/scoped", null, "fraud").statusCode());
            assertEquals(200, send(c.port, "GET", "/spaces/s1/risk-score-lists/scoped", null, "analyst").statusCode());
        }
    }
}

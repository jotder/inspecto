package com.gamma.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.control.BiSpecRunner;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /insights/explain} with {@code depth: 2} over real HTTP under an ARMED authenticator
 * (BI-EXPLAIN-DEPTH2-1): the pairs stored by the {@code insight.scan} Job for a Profile with {@code pairs: true} come
 * back beside the depth-1 drivers, only to a caller who may view the Profile's Insights Dataset; otherwise, or when
 * none were computed, {@code pairs} is null and {@code pairsNote} says how to get them. Data as
 * {@link PairExplainGoldenTest}: region × plan, Jan → Feb 2026, 400 → 500.
 */
class ControlApiExplainDepth2Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUM = "\"measure\":{\"agg\":\"sum\",\"field\":\"amount\"}";

    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of("developer"));
        String user = a.substring(7);
        return Optional.of(new Subject(user, "author".equals(user) ? Set.of("canAuthorWorkbench") : Set.of()));
    };

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void restore() {
        Authenticators.forTest(null);
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root, Path data) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path base = dir.resolve("s1"), root = base.resolve("config"), data = base.resolve("data");
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(root, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, root.resolve("etl_pipeline.toon"));
        new ViewStore(root.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES"
                        + " ('N', 'p1', TIMESTAMP '2026-01-10 09:00:00', 100.0),"
                        + " ('S', 'p1', TIMESTAMP '2026-01-11 09:00:00', 200.0),"
                        + " ('E', 'p2', TIMESTAMP '2026-01-12 09:00:00', 100.0),"
                        + " ('N', 'p1', TIMESTAMP '2026-02-10 09:00:00', 150.0),"
                        + " ('S', 'p2', TIMESTAMP '2026-02-11 09:00:00', 190.0),"
                        + " ('E', 'p2', TIMESTAMP '2026-02-12 09:00:00', 160.0)"
                        + ") AS t(region, plan, ts, amount)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(root.resolve("registry")).write("dataset", "sales_ds", Map.of("view", "sales_view", "columns",
                List.of(Map.of("name", "region"), Map.of("name", "plan"), Map.of("name", "ts"), Map.of("name", "amount"))));
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(dir);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), root, data);
    }

    /** Saves Profile {@code id} (pairs on), stores what its Job would for Feb 2026, and registers its Insights Dataset. */
    private static void seed(Ctx c, String id, Map<String, Object> envelope, List<Map<String, Object>> rows) throws Exception {
        ComponentStore store = new ComponentStore(c.root().resolve("registry"));
        Map<String, Object> content = PairExplainGoldenTest.profile(200);
        store.write(InsightProfile.KIND, id, content);
        InsightProfile p = InsightProfile.parse(id, content);
        InsightScan.Buckets b = InsightScan.buckets("month", PairExplainGoldenTest.AS_OF, p.lookback());
        Instant at = Instant.parse("2026-03-02T06:00:00Z");
        Map<String, Object> entry = PairExplain.compute(p, b, body -> new BiSpecRunner.Run(rows, false, null), at);
        PairExplain.write(InsightStore.claim(c.data(), id), entry, at);
        InsightScanJobType.register(store, id, envelope);
    }

    private HttpResponse<String> explain(Ctx c, String user, String rest) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1/spaces/s1/insights/explain"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"dataset\":\"sales_ds\",\"field\":\"ts\",\"grain\":\"month\","
                        + "\"at\":\"2026-02-20\",\"lookback\":8," + SUM + ",\"dimensions\":[\"region\",\"plan\"]" + rest + "}"));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void depthTwoIsGatedAsDepthOne(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            seed(c, "paired", Map.of(), PairExplainGoldenTest.janFeb());
            assertEquals(401, explain(c, null, ",\"depth\":2").statusCode());
            assertEquals(200, explain(c, "viewer", ",\"depth\":2").statusCode());
            assertEquals(200, explain(c, "author", ",\"depth\":2").statusCode());
        }
    }

    @Test
    void storedPairsComeBackBesideTheDepthOneDrivers(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            seed(c, "paired", Map.of(), PairExplainGoldenTest.janFeb());
            JsonNode d = data(explain(c, "author", ",\"depth\":2"));
            assertEquals(100, d.get("delta").asDouble(), 1e-9);
            assertEquals(2, d.get("drivers").size(), "depth 1 is unchanged");
            JsonNode pairs = d.get("pairs");
            assertEquals("paired", pairs.get("profile").asText());
            assertEquals("2026-02", pairs.get("current").asText());
            assertFalse(pairs.get("stale").asBoolean(), "the stored totals equal the live ones");
            JsonNode pair = pairs.get("drivers").get(0);
            assertEquals("region × plan", pair.get("dimension").asText());
            assertEquals("S × p2", pair.get("cover").get(0).asText());
            double sum = 0;
            for (JsonNode e : pair.get("elements")) sum += e.get("contribution").asDouble();
            assertEquals(100, sum, 1e-9);

            JsonNode one = data(explain(c, "author", ""));
            assertFalse(one.has("pairs"), "depth 1 (the default) reads no pairs");
        }
    }

    @Test
    void restatedDataMarksThePairsStale(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            List<Map<String, Object>> old = new java.util.ArrayList<>(PairExplainGoldenTest.janFeb());
            old.set(3, PairExplainGoldenTest.row("N", "p1", "2026-02", 140));
            seed(c, "paired", Map.of(), old);
            assertTrue(data(explain(c, "author", ",\"depth\":2")).get("pairs").get("stale").asBoolean());
        }
    }

    @Test
    void pairsOfAProfileTheCallerMayNotReadAreIndistinguishableFromNone(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode none = data(explain(c, "author", ",\"depth\":2"));
            assertTrue(none.get("pairs").isNull());
            String note = none.get("pairsNote").asText();
            assertTrue(note.contains("pairs: true") && note.contains("insight.scan"), note);

            seed(c, "secret", Map.of("owner", "someone-else", "shares", List.of()), PairExplainGoldenTest.janFeb());
            JsonNode hidden = data(explain(c, "author", ",\"depth\":2"));
            assertTrue(hidden.get("pairs").isNull(), "a Profile the caller may not read is not shown: " + hidden.get("pairs"));
            assertEquals(note, hidden.get("pairsNote").asText(), "and not hinted at");
        }
    }

    @Test
    void otherFiltersOrADepthOtherThanOneOrTwoOrARatioFindNoPairs(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            seed(c, "paired", Map.of(), PairExplainGoldenTest.janFeb());
            JsonNode filtered = data(explain(c, "author",
                    ",\"depth\":2,\"filters\":[{\"field\":\"region\",\"op\":\"!=\",\"value\":\"N\"}]"));
            assertTrue(filtered.get("pairs").isNull(), "pairs of other filters never answer this request");
            assertEquals(422, explain(c, "author", ",\"depth\":3").statusCode());
            HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1/spaces/s1/insights/explain"))
                    .header("Authorization", "Bearer author")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"dataset\":\"sales_ds\",\"field\":\"ts\",\"grain\":\"month\","
                            + "\"at\":\"2026-02-20\",\"lookback\":8,\"depth\":2,\"dimensions\":[\"region\"],"
                            + "\"ratio\":{\"num\":{\"agg\":\"sum\",\"field\":\"amount\"},\"den\":{\"agg\":\"count\"}}}")).build();
            HttpResponse<String> ratio = client.send(r, HttpResponse.BodyHandlers.ofString());
            assertEquals(422, ratio.statusCode());
            assertTrue(ratio.body().contains("depth 2 explains a sum or count"), ratio.body());
        }
    }
}

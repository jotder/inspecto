package com.gamma.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /insights/explain} over real HTTP and DuckDB under an ARMED authenticator (BI-INSIGHTS-1 P2): the
 * capability gate (401 / 403 / 200), hand-computed drivers (I-4), an exact mix + rate split (I-5), moved-with (I-6),
 * masking of a classified dimension (a revealer sees the raw key, so the alias is what protects it), and the refusals.
 *
 * <p>{@code sales_ds}: region, plan, msisdn (classified MSISDN), ts, amount, cost (= 2 × amount). Jan → Feb 2026:
 * region N 100 → 150, S 200 → 190, E 100 → 160 (Δ 100); plan p1 300 → 150, p2 100 → 350. 2025 has one N / p1 row a
 * month, amount m², the history the moved-with series needs.
 */
class ControlApiExplainTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String K1 = "441700900101", K2 = "441700900202";
    private static final double EPS = 1e-9;
    private static final String ROUTE = "/insights/explain";

    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of("developer"));
        String user = a.substring(7);
        Set<String> caps = switch (user) {
            case "author" -> Set.of("canAuthorWorkbench");
            case "revealer" -> Set.of("canAuthorWorkbench", "canRevealLinkEntities");
            default -> Set.of();
        };
        return Optional.of(new Subject(user, caps));
    };

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void standardEdition() {   // masking by default is Standard
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.INVESTIGATIONS));
    }

    @AfterEach
    void restore() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path root) throws Exception {
        Authenticators.forTest(FAKE);
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        Ctx c;
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            c = new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
        new ViewStore(root.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES"
                        + " ('N', 'p1', '" + K1 + "', TIMESTAMP '2026-01-10 09:00:00', 100.0, 200.0),"
                        + " ('S', 'p1', '" + K2 + "', TIMESTAMP '2026-01-11 09:00:00', 200.0, 400.0),"
                        + " ('E', 'p2', '" + K1 + "', TIMESTAMP '2026-01-12 09:00:00', 100.0, 200.0),"
                        + " ('N', 'p1', '" + K1 + "', TIMESTAMP '2026-02-10 09:00:00', 150.0, 300.0),"
                        + " ('S', 'p2', '" + K2 + "', TIMESTAMP '2026-02-11 09:00:00', 190.0, 380.0),"
                        + " ('E', 'p2', '" + K1 + "', TIMESTAMP '2026-02-12 09:00:00', 160.0, 320.0)"
                        + ") AS t(region, plan, msisdn, ts, amount, cost)"
                        + " UNION ALL SELECT 'N', 'p1', '" + K1 + "', CAST('2025-' || lpad(CAST(m AS VARCHAR), 2, '0') || '-15 09:00:00' AS TIMESTAMP),"
                        + " CAST(m * m AS DOUBLE), CAST(2 * m * m AS DOUBLE) FROM range(1, 13) AS r(m)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(root.resolve("registry")).write("dataset", "sales_ds", Map.of("view", "sales_view", "columns", List.of(
                Map.of("name", "region"), Map.of("name", "plan"), Map.of("name", "msisdn", "classification", "MSISDN"),
                Map.of("name", "ts"), Map.of("name", "amount"), Map.of("name", "cost"))));
        return c;
    }

    private HttpResponse<String> explain(Ctx c, String user, String rest) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + ROUTE))
                .POST(BodyPublishers.ofString("{\"dataset\":\"sales_ds\",\"field\":\"ts\",\"grain\":\"month\",\"at\":\"2026-02-20\","
                        + "\"lookback\":12," + rest + "}"));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static final String SUM = "\"measure\":{\"agg\":\"sum\",\"field\":\"amount\"}";

    @Test
    void theRouteIsGatedOnCanAuthorWorkbench(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String body = SUM + ",\"dimensions\":[\"region\"]";
            assertEquals(401, explain(c, null, body).statusCode());
            assertEquals(403, explain(c, "viewer", body).statusCode());
            assertEquals(200, explain(c, "author", body).statusCode());
        }
    }

    @Test
    void driversAreHandComputedAndRanked(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(explain(c, "author", SUM + ",\"dimensions\":[\"region\",\"plan\"]"));
            assertEquals("sum_amount", d.get("measure").asText());
            assertEquals("additive", d.get("kind").asText());
            assertEquals("2026-01", d.get("previous").asText());
            assertEquals("2026-02", d.get("current").asText());
            assertEquals(400, d.get("before").asDouble(), EPS);
            assertEquals(500, d.get("after").asDouble(), EPS);
            assertEquals(100, d.get("delta").asDouble(), EPS);
            assertEquals(25, d.get("pct").asDouble(), EPS);
            JsonNode drivers = d.get("drivers");
            // plan's make-up moved most (shares .75/.25 → .3/.7): it ranks first, one value (p2, +250) explains it
            assertEquals("plan", drivers.get(0).get("dimension").asText());
            assertEquals(List.of("p2"), texts(drivers.get(0).get("cover")));
            assertEquals(2.5, drivers.get(0).get("elements").get(0).get("share").asDouble(), EPS);
            JsonNode region = drivers.get(1);
            assertEquals("region", region.get("dimension").asText());
            assertTrue(region.get("explains").asBoolean());
            assertEquals(List.of("E", "N"), texts(region.get("cover")));
            assertEquals(1.1, region.get("covered").asDouble(), EPS);
            assertEquals(0.0073961745371377, region.get("surprise").asDouble(), 1e-12);
            assertEquals(60, region.get("elements").get(0).get("contribution").asDouble(), EPS);
            assertTrue(d.get("refused").isEmpty());
        }
    }

    @Test
    void aRatioSplitsExactlyIntoMixAndRate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // amount per row by plan: Jan p1 2 rows r 150, p2 1 row r 100 (R 400/3); Feb p1 1 row r 150, p2 2 rows r 175 (R 500/3)
            // mix = (1/3 − 2/3)·150 + (2/3 − 1/3)·100 = −50/3; rate = 1/3·0 + 2/3·75 = 50
            JsonNode d = data(explain(c, "author", "\"ratio\":{\"num\":{\"agg\":\"sum\",\"field\":\"amount\"},\"den\":{\"agg\":\"count\"}},"
                    + "\"dimensions\":[\"plan\"]"));
            assertEquals("ratio", d.get("kind").asText());
            assertEquals("sum_amount_per_count", d.get("measure").asText());
            assertEquals(400.0 / 3, d.get("before").asDouble(), EPS);
            assertEquals(500.0 / 3, d.get("after").asDouble(), EPS);
            JsonNode mr = d.get("mixRate").get(0);
            assertEquals(-50.0 / 3, mr.get("mix").asDouble(), EPS);
            assertEquals(50, mr.get("rate").asDouble(), EPS);
            assertEquals(d.get("delta").asDouble(), mr.get("mix").asDouble() + mr.get("rate").asDouble(), EPS);
            // the drivers' contributions (N_c ÷ D) add up to ΔR too
            double sum = 0;
            for (JsonNode e : d.get("drivers").get(0).get("elements")) sum += e.get("contribution").asDouble();
            assertEquals(d.get("delta").asDouble(), sum, EPS);
        }
    }

    @Test
    void movedWithCorrelatesChangesAndNeverClaimsACause(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(explain(c, "author", SUM + ",\"dimensions\":[\"region\"],"
                    + "\"related\":[{\"agg\":\"sum\",\"field\":\"cost\"},{\"agg\":\"count\"}]"));
            JsonNode cost = d.get("movedWith").get(0);
            assertEquals("sum_cost", cost.get("measure").asText());
            assertEquals(11, cost.get("pairs").asInt());   // 2025-03 … 2026-02: 12 buckets, 11 changes
            assertEquals(1.0, cost.get("r").asDouble(), EPS);
            assertTrue(cost.get("movedWith").asBoolean());
            assertEquals(800, cost.get("before").asDouble(), EPS);
            assertEquals(1000, cost.get("after").asDouble(), EPS);
            String note = d.get("movedWithNote").asText();
            assertTrue(note.contains("does not say one causes the other"), note);
            assertFalse(note.toLowerCase().contains("because"), note);
        }
    }

    @Test
    void aClassifiedDimensionReachesAViewerOnlyAsItsAlias(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String body = SUM + ",\"dimensions\":[\"msisdn\"]";
            HttpResponse<String> masked = explain(c, "author", body);
            assertEquals(200, masked.statusCode(), masked.body());
            for (String raw : List.of(K1, K2)) assertFalse(masked.body().contains(raw), "raw key leaked: " + masked.body());
            assertNotNull(JSON.readTree(masked.body()).get("data").get("masking"));
            // the probe: a revealer sees the raw keys, so without the mask the author would too
            HttpResponse<String> raw = explain(c, "revealer", body);
            assertTrue(raw.body().contains(K1), raw.body());
        }
    }

    @Test
    void aNonAdditiveAggregateIsRefusedInWords(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            for (String agg : List.of("median", "countDistinct", "p90", "avg")) {
                HttpResponse<String> r = explain(c, "author", "\"measure\":{\"agg\":\"" + agg + "\",\"field\":\"amount\"},"
                        + "\"dimensions\":[\"region\"]");
                assertEquals(422, r.statusCode(), agg + ": " + r.body());
                assertTrue(r.body().contains("cannot be explained by slices"), r.body());
            }
        }
    }

    @Test
    void noEarlierBucketMeansNoChangeToExplain(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + ROUTE))
                    .header("Authorization", "Bearer author")
                    .POST(BodyPublishers.ofString("{\"dataset\":\"sales_ds\",\"field\":\"ts\",\"grain\":\"month\",\"at\":\"2025-01-20\","
                            + SUM + ",\"dimensions\":[\"region\"]}")).build(), BodyHandlers.ofString());
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("no change to explain"), r.body());
        }
    }

    @Test
    void anUnknownDatasetIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + ROUTE))
                    .header("Authorization", "Bearer author")
                    .POST(BodyPublishers.ofString("{\"dataset\":\"nope\",\"field\":\"ts\",\"grain\":\"month\",\"at\":\"2026-02-01\","
                            + SUM + ",\"dimensions\":[\"region\"]}")).build(), BodyHandlers.ofString());
            assertEquals(404, r.statusCode(), r.body());
        }
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}

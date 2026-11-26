package com.gamma.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.etl.TestConfigs;
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
 * The Insight feed, feedback and replay preview over real HTTP under an ARMED authenticator (BI-INSIGHTS-1 P3): the
 * gates (401 / 403 / 200), the Insights Dataset's visibility (404, indistinguishable from none), feedback laid over the
 * feed, and the replay counts over a view-backed Dataset in DuckDB.
 *
 * <p>{@code sales_ds}: daily 2026-01-01 … 2026-03-01, regions N / S / E at about 100 a day, N = 400 on 2026-03-01.
 */
class ControlApiInsightsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    /** One Space {@code s1} under the test's folder: config (registry, views) and its own data root. */
    private Ctx open(Path cfg, Path dir) throws Exception {
        Path base = dir.resolve("s1"), root = base.resolve("config"), data = base.resolve("data");
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(root, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, root.resolve("etl_pipeline.toon"));
        new ViewStore(root.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT r.region, CAST(DATE '2026-03-01' - CAST(d AS INTEGER) AS TIMESTAMP) AS ts, "
                        + "CASE WHEN d = 0 AND r.region = 'N' THEN 400.0 ELSE 100.0 + ((d * 7 + ascii(r.region)) % 5) END AS amount "
                        + "FROM range(0, 60) AS t(d), (VALUES ('N'), ('S'), ('E')) AS r(region)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(root.resolve("registry")).write("dataset", "sales_ds", Map.of("view", "sales_view", "columns",
                List.of(Map.of("name", "region"), Map.of("name", "ts"), Map.of("name", "amount"))));
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(dir);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), root, data);
    }

    /** Stores one run of Profile {@code id} (fake data) and registers its Insights Dataset with {@code envelope}. */
    private static void seed(Ctx c, String id, Map<String, Object> envelope) throws Exception {
        InsightProfile p = InsightProfile.parse(id, InsightScanTest.profile());
        InsightScan.Buckets b = InsightScan.buckets("day", InsightScanTest.AS_OF, p.lookback());
        InsightScan.Fetched f = InsightScan.fetch(p, b, InsightScanTest.fake(b, InsightScanTest.regions(56, 400)));
        Path dir = InsightStore.claim(c.data(), id);
        InsightScanJobType.store(dir, p, f, "r1", Instant.parse("2026-03-02T06:00:00Z"));
        InsightScanJobType.register(new ComponentStore(c.root().resolve("registry")), id, envelope);
    }

    private HttpResponse<String> send(Ctx c, String user, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1/spaces/s1" + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void theFeedShowsTheLatestRunToWhoeverMayViewTheInsightsDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, "daily", Map.of());
            seed(c, "secret", Map.of("owner", "someone-else", "shares", List.of()));

            assertEquals(401, send(c, null, "GET", "/insights?profile=daily", null).statusCode());
            JsonNode d = data(send(c, "viewer", "GET", "/insights?profile=daily", null));
            assertEquals(1, d.get("runs").asInt());
            assertTrue(d.get("insights").size() > 0);
            JsonNode first = d.get("insights").get(0);
            assertTrue(first.get("explanation").has("sentence"), "the explanation is JSON, not a string");
            double prev = Double.MAX_VALUE;
            for (JsonNode i : d.get("insights")) {
                assertTrue(i.get("score").asDouble() <= prev, "best score first");
                prev = i.get("score").asDouble();
            }
            assertEquals(404, send(c, "viewer", "GET", "/insights?profile=secret", null).statusCode(),
                    "an Insights Dataset the caller may not view reads as absent");
            assertEquals(404, send(c, "viewer", "GET", "/insights?profile=nope", null).statusCode());
            assertEquals(422, send(c, "viewer", "GET", "/insights?profile=daily&band=purple", null).statusCode());
        }
    }

    @Test
    void feedbackIsGatedRecordedAndLaidOverTheFeed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, "daily", Map.of());
            seed(c, "secret", Map.of("owner", "someone-else", "shares", List.of()));
            String id = data(send(c, "viewer", "GET", "/insights?profile=daily", null)).get("insights").get(0)
                    .get("insight_id").asText();
            String path = "/insights/daily/" + id + "/feedback", body = "{\"status\":\"not_useful\",\"note\":\"known\"}";
            assertEquals(401, send(c, null, "POST", path, body).statusCode());
            String secretId = String.valueOf(InsightStore.read(c.data().resolve("insights_secret")).get(0).get("insight_id"));
            assertEquals(404, send(c, "viewer", "POST", "/insights/secret/" + secretId + "/feedback", body).statusCode(),
                    "a Profile whose Insights Dataset the caller cannot view is the 404 of none");
            JsonNode fb = data(send(c, "viewer", "POST", path, body));   // operator 2026-11-26: any viewer
            assertEquals("viewer", fb.get("actor").asText());
            fb = data(send(c, "author", "POST", path, body));
            assertEquals("author", fb.get("actor").asText());
            assertEquals(422, send(c, "author", "POST", path, "{\"status\":\"meh\"}").statusCode());
            assertEquals(404, send(c, "author", "POST", "/insights/daily/no-such/feedback", body).statusCode());

            JsonNode shown = data(send(c, "viewer", "GET", "/insights?profile=daily", null)).get("insights");
            for (JsonNode i : shown)
                if (i.get("insight_id").asText().equals(id)) {
                    assertEquals("not_useful", i.get("status").asText());
                    assertEquals("known", i.get("feedback").get("note").asText());
                }
        }
    }

    @Test
    void theReplayCountsInsightsPerPeriodAsTheCallerAndSavesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String body = "{\"asOf\":\"2026-03-02\",\"periods\":4,\"profile\":{\"dataset\":\"sales_ds\","
                    + "\"measure\":{\"agg\":\"sum\",\"field\":\"amount\"},\"field\":\"ts\",\"grain\":\"day\","
                    + "\"dimensions\":[\"region\"],\"types\":[\"anomaly\"],\"season\":\"none\",\"lookback\":28}}";
            assertEquals(401, send(c, null, "POST", "/insights/preview", body).statusCode());
            assertEquals(403, send(c, "viewer", "POST", "/insights/preview", body).statusCode());
            JsonNode d = data(send(c, "author", "POST", "/insights/preview", body));
            assertEquals(4, d.get("perPeriod").size());
            assertEquals("2026-03-01", d.get("perPeriod").get(3).get("period").asText());
            assertEquals(0, d.get("perPeriod").get(0).get("count").asInt(), "nothing unusual before the spike");
            assertTrue(d.get("perPeriod").get(3).get("count").asInt() >= 1);
            boolean north = false;
            for (JsonNode i : d.get("insights"))
                north |= "anomaly|region|N".equals(i.get("insight_key").asText()) && "2026-03-01".equals(i.get("asOf").asText());
            assertTrue(north, d.toString());
            assertFalse(java.nio.file.Files.exists(c.data().resolve("insights_preview")), "nothing saved");

            assertEquals(422, send(c, "author", "POST", "/insights/preview",
                    body.replace("\"sum\"", "\"median\"")).statusCode());
            assertEquals(404, send(c, "author", "POST", "/insights/preview",
                    body.replace("sales_ds", "nope")).statusCode());
        }
    }
}

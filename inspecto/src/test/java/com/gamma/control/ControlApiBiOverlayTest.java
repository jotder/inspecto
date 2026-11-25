package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.access.ComponentAccess;
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
 * BI-INSIGHTS-1 I-11 / I-7 over real HTTP and DuckDB, under an ARMED authenticator: {@code POST /bi/query} with
 * {@code overlay} returns the forecast band (on new rows after the last day), the anomaly scores and a summary with the
 * backtest, all computed from the Widget's own masked result — a classified series key stays an alias in the rows AND the
 * summary for a viewer (a revealer sees it raw, which proves the probe would leak without the mask).
 */
class ControlApiBiOverlayTest {

    private static final String A = "441700900101", B = "441700900202";

    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of("developer"));
        String user = a.substring(7);
        return Optional.of(new Subject(user, "revealer".equals(user) ? Set.of("canRevealLinkEntities") : Set.of()));
    };

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void standardEdition() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.INVESTIGATIONS));
    }

    @AfterEach
    void restore() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /**
     * {@code usage_ds}: msisdn (classified MSISDN), ts, amount — 63 days from Monday 2026-01-05 for each of A and B, weekdays
     * 100, weekends 40 (B a thousand higher), and one spike of 400 on A's day 30.
     */
    private Ctx open(Path cfg, Path root) throws Exception {
        Authenticators.forTest(FAKE);
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        Ctx c;
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            c = new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
        new ViewStore(root.resolve("views")).write(new ViewDefinition("usage_view", "flow-x", List.of(),
                "SELECT k.msisdn, TIMESTAMP '2026-01-05 09:00:00' + i * INTERVAL 1 DAY AS ts,"
                        + " CASE WHEN k.msisdn = '" + A + "' AND i = 30 THEN 400.0"
                        + " ELSE (CASE WHEN i % 7 >= 5 THEN 40.0 ELSE 100.0 END) + (i % 3)"
                        + " + (CASE WHEN k.msisdn = '" + B + "' THEN 1000.0 ELSE 0.0 END) END AS amount"
                        + " FROM range(63) AS r(i) CROSS JOIN (VALUES ('" + A + "'), ('" + B + "')) AS k(msisdn)",
                "2026-07-08T00:00:00Z"));
        ComponentStore reg = new ComponentStore(root.resolve("registry"));
        reg.write("dataset", "usage_ds", Map.of("view", "usage_view", "columns", List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "ts"), Map.of("name", "amount"))));
        return c;
    }

    private HttpResponse<String> bi(Ctx c, String user, String rest) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/bi/query"))
                .POST(BodyPublishers.ofString("{\"dataset\":\"usage_ds\"," + rest + "}"));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static final String DAILY_BY_KEY = "\"measures\":[{\"agg\":\"sum\",\"field\":\"amount\"}],"
            + "\"groupBy\":[\"ts\",\"msisdn\"],\"grains\":{\"ts\":\"day\"},"
            + "\"overlay\":{\"forecast\":{\"horizon\":7,\"level\":0.8},\"anomaly\":{}}";

    @Test
    void aForecastBandAndAnomalyScoresComeMarkedAndTheKeyStaysAnAlias(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(401, bi(c, null, DAILY_BY_KEY).statusCode(), "an armed deployment refuses an anonymous call");

            HttpResponse<String> viewer = bi(c, "viewer", DAILY_BY_KEY);
            assertEquals(200, viewer.statusCode(), viewer.body());
            for (String raw : List.of(A, B)) assertFalse(viewer.body().contains(raw), "raw key leaked: " + viewer.body());
            JsonNode v = V1Body.of(viewer.body());
            JsonNode cols = v.get("resultSet").get("columns");
            assertEquals(List.of("ts", "msisdn", "sum_amount", "sum_amount__fc", "sum_amount__fc_lo", "sum_amount__fc_hi",
                    "sum_amount__expected", "sum_amount__z"), names(cols));
            assertEquals("string", cols.get(1).get("type").asText(), "the series key is masked");
            assertEquals("forecast", cols.get(4).get("overlay").get("overlay").asText());
            assertEquals("lower", cols.get(4).get("overlay").get("part").asText());
            assertEquals("anomaly", cols.get(7).get("overlay").get("overlay").asText());
            assertEquals("sum_amount", cols.get(7).get("overlay").get("of").asText());

            JsonNode rows = v.get("rows");
            assertEquals(63 * 2 + 7 * 2, rows.size(), "seven forecast days per series after the history");
            JsonNode last = rows.get(rows.size() - 1);
            assertEquals("2026-03-15", last.get("ts").asText());
            assertTrue(last.get("sum_amount").isNull(), "a forecast day has no actual");
            assertTrue(last.get("sum_amount__fc_lo").asDouble() < last.get("sum_amount__fc").asDouble());
            assertTrue(last.get("sum_amount__fc").asDouble() < last.get("sum_amount__fc_hi").asDouble());

            JsonNode spike = null;
            for (JsonNode r : rows) if (r.get("sum_amount").asDouble() == 400.0) spike = r;
            assertNotNull(spike, rows.toString());
            assertEquals("2026-02-04", spike.get("ts").asText());
            assertTrue(spike.get("sum_amount__z").asDouble() > 8, spike.toString());

            JsonNode summary = v.get("overlays");
            assertEquals(4, summary.size(), summary.toString());   // forecast + anomaly, per series
            JsonNode fc = summary.get(0);
            assertEquals("forecast", fc.get("overlay").asText());
            assertEquals(7, fc.get("period").asInt());
            assertEquals("detected", fc.get("periodSource").asText());
            assertTrue(fc.get("backtest").get("origins").asInt() > 0, fc.toString());
            assertTrue(fc.get("backtest").has("coverage"), fc.toString());
            assertEquals("weekday", summary.get(1).get("seasonality").asText());
            assertFalse(fc.get("series").get("msisdn").asText().isBlank(), "the summary names the series by its alias");

            // The same body for a caller who may reveal names the raw key: the viewer's alias is the mask, not an accident.
            HttpResponse<String> revealer = bi(c, "revealer", DAILY_BY_KEY);
            assertEquals(200, revealer.statusCode(), revealer.body());
            assertTrue(V1Body.of(revealer.body()).get("overlays").toString().contains("44170090"), revealer.body());
        }
    }

    @Test
    void anAggregateThatReturnsARawKeyIsRefusedWithAnOverlayToo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String leak = "\"measures\":[{\"agg\":\"max\",\"field\":\"msisdn\"}],\"groupBy\":[\"ts\"],"
                    + "\"grains\":{\"ts\":\"day\"},\"overlay\":{\"anomaly\":{}}";
            HttpResponse<String> viewer = bi(c, "viewer", leak);
            assertEquals(422, viewer.statusCode(), viewer.body());
            for (String raw : List.of(A, B)) assertFalse(viewer.body().contains(raw), viewer.body());
            // falsified: a revealer runs it — and a key is no number, so it gets no overlay rather than a score
            HttpResponse<String> revealer = bi(c, "revealer", leak);
            assertEquals(200, revealer.statusCode(), revealer.body());
            JsonNode s = V1Body.of(revealer.body()).get("overlays").get(0);
            assertTrue(s.get("reason").asText().contains("not a number"), s.toString());
        }
    }

    @Test
    void anOverlayNeedsATimeGrainAndTwoSeasons(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(422, bi(c, "viewer", "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"msisdn\"],"
                    + "\"overlay\":{\"forecast\":{}}").statusCode(), "a forecast needs a time grain");
            assertEquals(422, bi(c, "viewer", "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"ts\"],\"grains\":{\"ts\":\"day\"},"
                    + "\"overlay\":{\"soon\":{}}").statusCode());

            // Nine weeks at a week grain: far from two 52-week seasons — no forecast and the reason, never a band.
            HttpResponse<String> weekly = bi(c, "viewer", "\"measures\":[{\"agg\":\"sum\",\"field\":\"amount\"}],"
                    + "\"groupBy\":[\"ts\"],\"grains\":{\"ts\":\"week\"},\"overlay\":{\"forecast\":{}}");
            assertEquals(200, weekly.statusCode(), weekly.body());
            JsonNode w = V1Body.of(weekly.body());
            assertTrue(w.get("overlays").get(0).get("reason").asText().contains("two seasons"), w.get("overlays").toString());
            for (JsonNode r : w.get("rows")) assertTrue(r.get("sum_amount__fc").isNull(), r.toString());
        }
    }

    private static List<String> names(JsonNode cols) {
        List<String> out = new ArrayList<>();
        cols.forEach(n -> out.add(n.get("name").asText()));
        return out;
    }
}

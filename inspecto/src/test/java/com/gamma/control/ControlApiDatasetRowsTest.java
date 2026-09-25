package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP + real-DuckDB tests for {@code GET /datasets/{id}/rows} — one page of a Dataset's RELATION
 * (view/physicalRef + calculated columns), which is what lets the Widget Builder type a calculated column
 * and count its cardinality — plus the end-to-end proof that {@code POST /bi/query} groups and aggregates
 * on a calculated column. Every gate: 503 write root unset · 404 unknown Dataset · 422 a calculated
 * expression the {@code ExpressionGuard} refuses · the {@code limit} bound with {@code truncated}.
 *
 * <p>The fixture is the IPL {@code matches} shape that motivated the widened scalar whitelist: a text
 * {@code DATE} ({@code 'March 22,2025'}) and a {@code 'Ground, City'} {@code VENUE}.
 */
class ControlApiDatasetRowsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** The derived fields a builder models on {@code matches}, exercising the widened whitelist. */
    private static final List<Map<String, Object>> CALCULATED = List.of(
            Map.of("name", "match_date", "expr", "cast(strptime(DATE, '%B %d,%Y') AS date)"),
            Map.of("name", "month", "expr", "monthname(strptime(DATE, '%B %d,%Y'))"),
            Map.of("name", "venue_name", "expr", "split_part(VENUE, ',', 1)"),
            Map.of("name", "city", "expr", "trim(split_part(VENUE, ',', 2))"),
            Map.of("name", "toss_outcome", "expr",
                    "CASE WHEN TOSS_WINNER = MATCH_WINNER THEN 'Toss winner won' ELSE 'Toss winner lost' END"),
            Map.of("name", "win_type", "expr", "CASE WHEN WB_RUNS IS NOT NULL THEN 'Batting first' "
                    + "WHEN WB_WICKETS IS NOT NULL THEN 'Chasing' ELSE 'No result' END"),
            Map.of("name", "match_runs", "expr", "FIRST_INGS_SCORE + SECOND_INGS_SCORE"));

    private void seedMatches(Ctx c, List<Map<String, Object>> calculated) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("matches_view", "pipeline-x", List.of(),
                "SELECT * FROM (VALUES "
                        + "(1, 'March 22,2025', 'Eden Gardens, Kolkata', 'KKR', 'RCB', 'RCB', 'RCB', 174, 177, NULL, 7), "
                        + "(2, 'March 23,2025', 'Wankhede Stadium, Mumbai', 'MI', 'CSK', 'CSK', 'CSK', 155, 158, NULL, 4), "
                        + "(3, 'April 2,2025', 'Eden Gardens, Kolkata', 'KKR', 'SRH', 'SRH', 'KKR', 200, 120, 80, NULL)"
                        + ") AS t(MATCH_ID, DATE, VENUE, TEAM1, TEAM2, TOSS_WINNER, MATCH_WINNER, "
                        + "FIRST_INGS_SCORE, SECOND_INGS_SCORE, WB_RUNS, WB_WICKETS)",
                "2026-09-25T00:00:00Z"));
        Map<String, Object> ds = new HashMap<>();
        ds.put("view", "matches_view");
        ds.put("calculated", calculated);
        new ComponentStore(c.root.resolve("registry")).write("dataset", "matches", ds);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .GET().build(), BodyHandlers.ofString());
    }

    private static JsonNode column(JsonNode data, String name) {
        for (JsonNode c : data.get("columns")) if (c.get("name").asText().equals(name)) return c;
        fail("no served column '" + name + "' in " + data.get("columns"));
        return null;
    }

    @Test
    void servesTheRelationWithCalculatedColumnsTypedAndCounted(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedMatches(c, CALCULATED);
            HttpResponse<String> r = get(c.port, "/datasets/matches/rows");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = V1Body.of(r.body());
            assertEquals(3, data.get("rows").size());
            assertFalse(data.get("statistics").get("truncated").asBoolean());

            // the served types are what drive the Widget Builder's roles: date → temporal, number → measure
            assertEquals("date", column(data, "match_date").get("type").asText());
            assertEquals("number", column(data, "match_runs").get("type").asText());
            JsonNode city = column(data, "city");
            assertEquals("string", city.get("type").asText());
            assertEquals("dimension", city.get("role").asText());
            assertEquals(2, city.get("cardinality").asInt(), "Kolkata + Mumbai");

            JsonNode first = data.get("rows").get(0);
            assertEquals("2025-03-22", first.get("match_date").asText());
            assertEquals("March", first.get("month").asText());
            assertEquals("Eden Gardens", first.get("venue_name").asText());
            assertEquals("Kolkata", first.get("city").asText());
            assertEquals("Toss winner won", first.get("toss_outcome").asText());
            assertEquals("Chasing", first.get("win_type").asText());
            assertEquals(351, first.get("match_runs").asInt());
        }
    }

    @Test
    void biQueryGroupsAndAggregatesOnCalculatedColumns(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedMatches(c, CALCULATED);
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + c.port + "/api/v1/bi/query"))
                    .POST(BodyPublishers.ofString("""
                            {"dataset":"matches",
                             "measures":[{"agg":"sum","field":"match_runs"},{"agg":"count"}],
                             "groupBy":["city"],
                             "orderBy":[{"field":"city","dir":"asc"}]}""")).build(), BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = V1Body.of(r.body()).get("rows");
            assertEquals(2, rows.size());
            assertEquals("Kolkata", rows.get(0).get("city").asText());
            assertEquals(351 + 320, rows.get(0).get("sum_match_runs").asInt(), "sum over a calculated measure");
            assertEquals(2, rows.get(0).get("count").asInt());
            assertEquals("Mumbai", rows.get(1).get("city").asText());
        }
    }

    /** Every newly whitelisted scalar must also EXECUTE in DuckDB — a name the guard admits but the engine
     *  cannot bind would green-light an expression that then fails on every read. */
    @Test
    void everyNewlyAllowedScalarRunsInDuckDb(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedMatches(c, List.of(
                    Map.of("name", "a", "expr", "try_strptime(DATE, '%B %d,%Y')"),
                    Map.of("name", "b", "expr", "strftime(strptime(DATE, '%B %d,%Y'), '%Y-%m')"),
                    Map.of("name", "c", "expr", "date_trunc('month', strptime(DATE, '%B %d,%Y'))"),
                    Map.of("name", "d", "expr", "date_part('year', strptime(DATE, '%B %d,%Y')) "
                            + "+ datepart('month', strptime(DATE, '%B %d,%Y'))"),
                    Map.of("name", "e", "expr", "year(strptime(DATE, '%B %d,%Y')) + month(strptime(DATE, '%B %d,%Y')) "
                            + "+ day(strptime(DATE, '%B %d,%Y')) + week(strptime(DATE, '%B %d,%Y')) "
                            + "+ quarter(strptime(DATE, '%B %d,%Y'))"),
                    Map.of("name", "f", "expr", "dayname(strptime(DATE, '%B %d,%Y'))"),
                    Map.of("name", "g", "expr", "starts_with(VENUE, 'Eden') OR ends_with(VENUE, 'Mumbai') "
                            + "OR contains(VENUE, 'Stadium')"),
                    Map.of("name", "h", "expr", "regexp_matches(VENUE, 'Gardens')"),
                    Map.of("name", "i", "expr", "regexp_extract(VENUE, ', (.*)$', 1)"),
                    Map.of("name", "j", "expr", "regexp_replace(VENUE, '[aeiou]', '', 'g')"),
                    Map.of("name", "k", "expr", "left(TEAM1, 2) || right(TEAM2, 2)"),
                    Map.of("name", "l", "expr", "lpad(cast(MATCH_ID AS varchar), 4, '0') || rpad(TEAM1, 5, '_')"),
                    Map.of("name", "m", "expr", "if(WB_RUNS IS NOT NULL, 'Batting first', 'Chasing')")));
            HttpResponse<String> r = get(c.port, "/datasets/matches/rows");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode first = V1Body.of(r.body()).get("rows").get(0);
            assertEquals("2025-03", first.get("b").asText());
            assertEquals(2025 + 3, first.get("d").asInt());
            assertEquals("Saturday", first.get("f").asText());
            assertTrue(first.get("g").asBoolean());
            assertEquals("Kolkata", first.get("i").asText());
            assertEquals("KKCB", first.get("k").asText());
            assertEquals("0001KKR__", first.get("l").asText());
            assertEquals("Chasing", first.get("m").asText());
        }
    }

    @Test
    void limitBoundsThePageAndSaysItWasTruncated(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedMatches(c, CALCULATED);
            HttpResponse<String> r = get(c.port, "/datasets/matches/rows?limit=2");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = V1Body.of(r.body());
            assertEquals(2, data.get("rows").size());
            assertTrue(data.get("statistics").get("truncated").asBoolean());
        }
    }

    @Test
    void writesDisabledIs503(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, get(c.port, "/datasets/matches/rows").statusCode());
        }
    }

    @Test
    void unknownDatasetIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = get(c.port, "/datasets/nope/rows");
            assertEquals(404, r.statusCode(), r.body());
        }
    }

    @Test
    void aCalculatedExpressionTheGuardRefusesIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedMatches(c, List.of(Map.of("name", "leak", "expr", "getenv('HOME')")));
            HttpResponse<String> r = get(c.port, "/datasets/matches/rows");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("getenv"), r.body());
        }
    }
}

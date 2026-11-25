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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BI-INSIGHTS-1 I-1 over real HTTP and DuckDB, under an ARMED authenticator: {@code POST /bi/query} with {@code compare}
 * returns the comparison columns marked as overlays, and masking still holds on the same statement — a classified key is
 * aliased for a viewer (a revealer sees it raw, which proves the probe would leak without the mask), and an aggregate that
 * returns a key's raw values is refused with or without a comparison.
 */
class ControlApiBiCompareTest {

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
    void standardEdition() {   // masking by default is Standard; this module's own classpath is Basic
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

    /** {@code sales_ds}: msisdn (classified MSISDN), at, amount. A has Jan, Feb, Apr 2026 (no March); B has Feb only. */
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
        new ViewStore(root.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('" + A + "', TIMESTAMP '2026-01-05 10:00:00', 10.0),"
                        + " ('" + A + "', TIMESTAMP '2026-02-07 10:00:00', 20.0), ('" + A + "', TIMESTAMP '2026-04-01 10:00:00', 30.0),"
                        + " ('" + B + "', TIMESTAMP '2026-02-02 10:00:00', 5.0)) AS t(msisdn, ts, amount)", "2026-07-08T00:00:00Z"));
        ComponentStore reg = new ComponentStore(root.resolve("registry"));
        reg.write("dataset", "sales_ds", Map.of("view", "sales_view", "columns", List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "ts"), Map.of("name", "amount"))));
        reg.write("query", "sales_q", Map.of("type", "sql", "datasetId", "sales_ds",
                "text", "SELECT ts, amount FROM sales_ds WHERE amount > 6"));
        return c;
    }

    private HttpResponse<String> bi(Ctx c, String user, String rest) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/bi/query"))
                .POST(BodyPublishers.ofString("{\"dataset\":\"sales_ds\"," + rest + "}"));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static final String BY_KEY_AND_MONTH = "\"measures\":[{\"agg\":\"sum\",\"field\":\"amount\"}],"
            + "\"groupBy\":[\"msisdn\",\"ts\"],\"grains\":{\"ts\":\"month\"},\"compare\":{\"kind\":\"previous\"}";

    @Test
    void aComparisonIsMarkedAndAViewerStillSeesOnlyTheAlias(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(401, bi(c, null, BY_KEY_AND_MONTH).statusCode(), "an armed deployment refuses an anonymous call");

            HttpResponse<String> viewer = bi(c, "viewer", BY_KEY_AND_MONTH);
            assertEquals(200, viewer.statusCode(), viewer.body());
            for (String raw : List.of(A, B)) assertFalse(viewer.body().contains(raw), "raw key leaked: " + viewer.body());
            JsonNode v = V1Body.of(viewer.body());
            JsonNode cols = v.get("resultSet").get("columns");
            assertEquals(List.of("msisdn", "ts", "sum_amount", "sum_amount__cmp", "sum_amount__delta", "sum_amount__pct"),
                    names(cols));
            assertTrue(cols.get(2).path("overlay").isMissingNode(), "the Measure itself is no overlay");
            JsonNode pct = cols.get(5).get("overlay");
            assertEquals("compare", pct.get("overlay").asText());
            assertEquals("previous", pct.get("kind").asText());
            assertEquals("sum_amount", pct.get("of").asText());
            assertEquals("pct", pct.get("part").asText());
            assertEquals("string", cols.get(0).get("type").asText(), "the key column is masked");

            // A: Jan 10, Feb 20 (prev 10, +100 %), Apr 30 (no March: absent, never 0 and never February's 20). The masked key
            // orders the rows by the Measure (stripCosmeticOrder), so they are found by month and value.
            JsonNode rows = v.get("rows");
            assertEquals(4, rows.size(), rows.toString());
            assertTrue(row(rows, "2026-01", 10).get("sum_amount__cmp").isNull());
            assertEquals(10.0, row(rows, "2026-02", 20).get("sum_amount__cmp").asDouble());
            assertEquals(100.0, row(rows, "2026-02", 20).get("sum_amount__pct").asDouble(), 1e-9);
            assertTrue(row(rows, "2026-04", 30).get("sum_amount__cmp").isNull(), rows.toString());
            assertTrue(row(rows, "2026-04", 30).get("sum_amount__pct").isNull());
            assertTrue(row(rows, "2026-02", 5).get("sum_amount__cmp").isNull(), "B has no January: A's is not B's");

            // The same body for a caller who may reveal returns the raw key: the viewer's alias is the mask, not an accident.
            HttpResponse<String> revealer = bi(c, "revealer", BY_KEY_AND_MONTH);
            assertEquals(200, revealer.statusCode(), revealer.body());
            assertTrue(revealer.body().contains(A), revealer.body());
        }
    }

    @Test
    void anAggregateThatReturnsARawKeyIsRefusedWithAComparisonToo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String leak = "\"measures\":[{\"agg\":\"max\",\"field\":\"msisdn\"}],\"groupBy\":[\"ts\"],"
                    + "\"grains\":{\"ts\":\"month\"},\"compare\":{\"kind\":\"previous\"}";
            HttpResponse<String> viewer = bi(c, "viewer", leak);
            assertEquals(422, viewer.statusCode(), viewer.body());
            assertTrue(viewer.body().contains(BiResultMasking.REVEAL), viewer.body());
            for (String raw : List.of(A, B)) assertFalse(viewer.body().contains(raw), viewer.body());
            // falsified: the same probe for a revealer runs and carries the raw key in both the value and its comparison
            HttpResponse<String> revealer = bi(c, "revealer", leak);
            assertEquals(200, revealer.statusCode(), revealer.body());
            assertTrue(V1Body.of(revealer.body()).get("rows").get(1).get("max_msisdn__cmp").asText().contains("4417009001"));
        }
    }

    @Test
    void aComparisonRunsOverABoundQueryAndRefusesNonsense(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> bound = bi(c, "viewer", "\"query\":\"sales_q\",\"measures\":[{\"agg\":\"sum\",\"field\":\"amount\"}],"
                    + "\"groupBy\":[\"ts\"],\"grains\":{\"ts\":\"month\"},\"compare\":{\"kind\":\"previous\"}");
            assertEquals(200, bound.statusCode(), bound.body());
            JsonNode b = V1Body.of(bound.body());
            assertTrue(b.get("sql").asText().startsWith("WITH \"__bound_query\" AS ("), b.get("sql").asText());
            JsonNode feb = b.get("rows").get(1);
            assertEquals("2026-02", feb.get("ts").asText());
            assertEquals(20.0, feb.get("sum_amount").asDouble(), "B's 5.0 is filtered out by the bound query");
            assertEquals(10.0, feb.get("sum_amount__cmp").asDouble());

            HttpResponse<String> target = bi(c, "viewer", "\"measures\":[{\"agg\":\"sum\",\"field\":\"amount\"}],"
                    + "\"compare\":{\"kind\":\"target\",\"target\":50}");
            assertEquals(200, target.statusCode(), target.body());
            JsonNode t = V1Body.of(target.body()).get("rows").get(0);
            assertEquals(65.0, t.get("sum_amount").asDouble());
            assertEquals(15.0, t.get("sum_amount__delta").asDouble());
            assertEquals(30.0, t.get("sum_amount__pct").asDouble(), 1e-9);

            assertEquals(422, bi(c, "viewer", "\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"msisdn\"],"
                    + "\"compare\":{\"kind\":\"previous\"}").statusCode(), "previous needs a time grain");
            assertEquals(422, bi(c, "viewer", "\"measures\":[{\"agg\":\"count\"}],\"compare\":{\"kind\":\"soon\"}").statusCode());
        }
    }

    private static JsonNode row(JsonNode rows, String month, double amount) {
        for (JsonNode r : rows)
            if (month.equals(r.get("ts").asText()) && r.get("sum_amount").asDouble() == amount) return r;
        throw new AssertionError("no row " + month + "/" + amount + " in " + rows);
    }

    private static List<String> names(JsonNode cols) {
        List<String> out = new java.util.ArrayList<>();
        cols.forEach(n -> out.add(n.get("name").asText()));
        return out;
    }
}

package com.gamma.query;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Windowed and ratio Measures (ANALYTICS-CORE-1 C1, D-AN12): the compiled text and bound parameters, the refusals, and the
 * golden corpus of {@code measure-grammar.contract.json} RUN on DuckDB — the same figures {@code query-spec.spec.ts} holds
 * the offline twin to.
 */
class MeasureWindowTest {

    private static final double EPS = 1e-12;

    private static MeasureCompiler.Spec parse(Map<String, Object> body) {
        return MeasureCompiler.parse(body, 500, 10_000);
    }

    private static Map<String, Object> window(Object days) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("field", "day");
        w.put("asOf", "2026-06-30");
        if (days != null) w.put("days", days);
        return w;
    }

    @Test
    void aWindowRestrictsRowsWithBoundDatesAndRecencyReadsTheAsOf() {
        MeasureCompiler.Spec spec = parse(Map.of("dataset", "tx", "window", window(30),
                "measures", List.of(Map.of("agg", "count"), Map.of("agg", "recency", "field", "day")),
                "groupBy", List.of("customer")));
        MeasureCompiler.Compiled c = MeasureCompiler.compile(spec);
        assertEquals("SELECT \"customer\", COUNT(*) AS \"count\", "
                + "DATE_DIFF('day', CAST(MAX(\"day\") AS DATE), CAST(? AS DATE)) AS \"recency_day\" FROM \"tx\" "
                + "WHERE CAST(\"day\" AS DATE) <= CAST(? AS DATE) AND CAST(\"day\" AS DATE) > CAST(? AS DATE) "
                + "GROUP BY \"customer\" ORDER BY \"customer\" ASC LIMIT 500", c.sql());
        assertEquals(List.of("2026-06-30", "2026-06-30", "2026-05-31"), c.params(),
                "recency's as-of first (the select list), then the window's two bounds - in text order");
        assertTrue(MeasureCompiler.render(spec).contains("DATE '2026-05-31'"), "the display text renders the dates");
    }

    @Test
    void anOpenWindowHasNoLowerBound() {
        MeasureCompiler.Compiled c = MeasureCompiler.compile(parse(Map.of("dataset", "tx", "window", window(null),
                "measures", List.of(Map.of("agg", "count")))));
        assertEquals(List.of("2026-06-30"), c.params());
        assertFalse(c.sql().contains(">"), c.sql());
    }

    @Test
    void aRatioIsADoubleDivisionWithAZeroDenominatorNull() {
        MeasureCompiler.Spec spec = parse(Map.of("dataset", "tx", "groupBy", List.of("customer"),
                "ratios", List.of(Map.of("num", Map.of("agg", "sum", "field", "refund"),
                        "den", Map.of("agg", "sum", "field", "amount"))),
                "orderBy", List.of(Map.of("field", "sum_refund_per_sum_amount", "dir", "desc"))));
        assertEquals("SELECT \"customer\", CAST(SUM(\"refund\") AS DOUBLE) / NULLIF(CAST(SUM(\"amount\") AS DOUBLE), 0) "
                + "AS \"sum_refund_per_sum_amount\" FROM \"tx\" GROUP BY \"customer\" "
                + "ORDER BY \"sum_refund_per_sum_amount\" DESC LIMIT 500", MeasureCompiler.render(spec));
        assertEquals("sum_refund_per_sum_amount", spec.ratios().get(0).id());
    }

    @Test
    void theNewShapesKeepTheirSeamsAndTheOldOnesAreUntouched() {
        MeasureCompiler.Spec spec = parse(Map.of("dataset", "tx", "window", window(7),
                "measures", List.of(Map.of("agg", "count")),
                "ratios", List.of(Map.of("num", Map.of("agg", "count"), "den", Map.of("agg", "countDistinct", "field", "customer")))));
        assertEquals(spec.window(), spec.withLimit(5).window());
        assertEquals(spec.ratios(), spec.withOrderBy(List.of()).ratios());
        MeasureCompiler.Spec moved = spec.withDataset("other");
        assertEquals("other", moved.dataset());
        assertEquals(spec.window(), moved.window());
        // a spec with neither is the same SQL as before the feature
        assertEquals("SELECT COUNT(*) AS \"count\" FROM \"tx\" LIMIT 500",
                MeasureCompiler.render(parse(Map.of("dataset", "tx", "measures", List.of(Map.of("agg", "count"))))));
    }

    @Test
    void refusalsFailClosed() {
        Map<String, Object> rec = Map.of("agg", "recency", "field", "day");
        // recency needs a window; a ratio cannot hold it; an unknown window key; a bad date; days out of range
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("dataset", "tx", "measures", List.of(rec))));
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("dataset", "tx", "window", window(30),
                "ratios", List.of(Map.of("num", rec, "den", Map.of("agg", "count"))))));
        Map<String, Object> extra = window(30);
        extra.put("zone", "UTC");
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("dataset", "tx", "window", extra,
                "measures", List.of(Map.of("agg", "count")))));
        Map<String, Object> bad = window(30);
        bad.put("asOf", "30/06/2026");
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("dataset", "tx", "window", bad,
                "measures", List.of(Map.of("agg", "count")))));
        for (Object days : new Object[] {0, -3, 3661, 2.5, "7"})
            assertThrows(IllegalArgumentException.class, () -> parse(Map.of("dataset", "tx", "window", window(days),
                    "measures", List.of(Map.of("agg", "count")))), String.valueOf(days));
        Map<String, Object> noAsOf = window(30);
        noAsOf.remove("asOf");
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("dataset", "tx", "window", noAsOf,
                "measures", List.of(Map.of("agg", "count")))));
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("dataset", "tx", "window",
                Map.of("field", "day; DROP", "asOf", "2026-06-30"), "measures", List.of(Map.of("agg", "count")))));
        // a ratio of unknown aggregates, or with an unknown key, or half a ratio
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("dataset", "tx",
                "ratios", List.of(Map.of("num", Map.of("agg", "p42", "field", "a"), "den", Map.of("agg", "count"))))));
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("dataset", "tx",
                "ratios", List.of(Map.of("num", Map.of("agg", "count"))))));
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("dataset", "tx",
                "ratios", List.of(Map.of("num", Map.of("agg", "count"), "den", Map.of("agg", "count"), "as", "x")))));
        // the shorthand surfaces still do not know recency
        assertFalse(MeasureCompiler.AGGS.contains("recency"));
        assertThrows(IllegalArgumentException.class,
                () -> MeasureCompiler.aggregateSql(new MeasureCompiler.Measure("recency", "day")));
    }

    // ── the golden corpus, on DuckDB ─────────────────────────────────────────────────────────────────────────────

    private static JsonNode corpus() throws Exception {
        return new ObjectMapper().readTree(MeasureGrammarContractTest.contractPath().toFile()).get("windowed");
    }

    private static String relation(JsonNode corpus) {
        List<String> rows = new ArrayList<>();
        for (JsonNode r : corpus.get("rows"))
            rows.add("('" + r.get(0).asText() + "', DATE '" + r.get(1).asText() + "', " + r.get(2).asText() + ", "
                    + r.get(3).asText() + ")");
        return "SELECT * FROM (VALUES " + String.join(", ", rows) + ") AS t(customer, day, amount, refund)";
    }

    private static Map<String, Map<String, Object>> run(JsonNode corpus, Object days) throws Exception {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("field", corpus.get("field").asText());
        w.put("asOf", corpus.get("asOf").asText());
        if (days != null) w.put("days", days);
        MeasureCompiler.Spec spec = parse(Map.of("dataset", "d", "window", w, "groupBy", List.of("customer"),
                "measures", List.of(Map.of("agg", "count"), Map.of("agg", "sum", "field", "amount"),
                        Map.of("agg", "recency", "field", "day")),
                "ratios", List.of(Map.of("num", Map.of("agg", "sum", "field", "refund"),
                        "den", Map.of("agg", "sum", "field", "amount")))));
        MeasureCompiler.Compiled sql = MeasureCompiler.compile(spec);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : QueryExecutor.run(new QueryExecutor.Request("d", relation(corpus), sql.sql(),
                500, 0, List.of(), List.of(), sql.params())).rows())
            out.put((String) row.get("customer"), row);
        return out;
    }

    private static void assertCorpus(JsonNode expected, Map<String, Map<String, Object>> got) {
        List<String> keys = new ArrayList<>();
        expected.fieldNames().forEachRemaining(keys::add);
        assertEquals(keys, new ArrayList<>(got.keySet()), "exactly the customers with a row in the window");
        for (String customer : keys)
            expected.get(customer).fields().forEachRemaining(e -> {
                Object v = got.get(customer).get(e.getKey());
                if (e.getValue().isNull()) assertNull(v, customer + " " + e.getKey());
                else assertEquals(e.getValue().asDouble(), ((Number) v).doubleValue(), EPS, customer + " " + e.getKey());
            });
    }

    @Test
    void theGoldenCorpusRunsToTheContractsFigures() throws Exception {
        JsonNode corpus = corpus();
        assertCorpus(corpus.get("expected").get("days30"), run(corpus, 30));
        assertCorpus(corpus.get("expected").get("open"), run(corpus, null));
    }
}

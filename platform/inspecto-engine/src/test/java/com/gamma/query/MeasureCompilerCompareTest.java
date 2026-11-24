package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BI-INSIGHTS-1 I-1 — {@code compare} on a Measure spec: previous period, same period last year, a target and the
 * average, computed in the Widget's own statement and run here through the real sandbox. The honesty rule under test: a
 * comparison with no value (no earlier bucket, a zero baseline) is NULL, never 0, and never a neighbouring bucket.
 */
class MeasureCompilerCompareTest {

    /** Region N has Jan, Mar, Apr 2026 and Jan 2025 (Feb 2026 missing); region S has Feb 2026 only. */
    private static final String RELATION = """
            SELECT * FROM (VALUES
              ('N', TIMESTAMP '2025-01-10 08:00:00', 4),
              ('N', TIMESTAMP '2026-01-05 09:00:00', 10),
              ('N', TIMESTAMP '2026-01-20 10:00:00', 5),
              ('N', TIMESTAMP '2026-03-02 11:00:00', 7),
              ('N', TIMESTAMP '2026-04-15 12:00:00', 14),
              ('S', TIMESTAMP '2026-02-01 00:00:00', 3)
            ) AS t(region, ts, amount)""";

    private static Map<String, Object> body(Map<String, Object> compare, List<String> groupBy) {
        Map<String, Object> b = new HashMap<>();
        b.put("dataset", "sales");
        b.put("measures", List.of(Map.of("agg", "sum", "field", "amount")));
        b.put("groupBy", groupBy);
        b.put("grains", Map.of("ts", "month"));
        b.put("compare", compare);
        return b;
    }

    private static List<Map<String, Object>> run(Map<String, Object> body) throws Exception {
        MeasureCompiler.Spec spec = MeasureCompiler.parse(body, 500, 10_000);
        MeasureCompiler.Compiled sql = MeasureCompiler.compile(spec);
        return QueryExecutor.run(new QueryExecutor.Request("sales", RELATION, sql.sql(), 500, 0,
                List.of(), List.of(), sql.params())).rows();
    }

    private static String line(Map<String, Object> r) {
        return r.get("region") + "|" + r.get("ts") + "|" + r.get("sum_amount") + "|" + r.get("sum_amount__cmp") + "|"
                + r.get("sum_amount__delta") + "|" + r.get("sum_amount__pct");
    }

    @Test
    void previousMatchesTheBucketBeforePerSeriesAndAGapIsAbsentNotZero() throws Exception {
        List<String> got = run(body(Map.of("kind", "previous"), List.of("region", "ts"))).stream()
                .map(MeasureCompilerCompareTest::line).toList();
        assertEquals(List.of(
                "N|2025-01|4|null|null|null",
                "N|2026-01|15|null|null|null",
                "N|2026-03|7|null|null|null",          // no February row for N: absent, not January's 15, not S's 3
                "N|2026-04|14|7|7.0|100.0",
                "S|2026-02|3|null|null|null"), got);
    }

    @Test
    void lastYearLooksTwelveMonthsBack() throws Exception {
        List<Map<String, Object>> rows = run(body(Map.of("kind", "lastYear"), List.of("ts")));
        Map<String, Object> jan26 = rows.stream().filter(r -> "2026-01".equals(r.get("ts"))).findFirst().orElseThrow();
        assertEquals(4, ((Number) jan26.get("sum_amount__cmp")).intValue());
        assertEquals(11, ((Number) jan26.get("sum_amount__delta")).intValue());
        assertEquals(275.0, ((Number) jan26.get("sum_amount__pct")).doubleValue(), 1e-9);
        Map<String, Object> feb26 = rows.stream().filter(r -> "2026-02".equals(r.get("ts"))).findFirst().orElseThrow();
        assertNull(feb26.get("sum_amount__cmp"), "no February 2025: absent");
    }

    @Test
    void lastYearOnADayGrainLooksAtTheSameDate() throws Exception {
        Map<String, Object> b = body(Map.of("kind", "lastYear"), List.of("ts"));
        b.put("grains", Map.of("ts", "day"));
        List<Map<String, Object>> rows = run(b);
        Map<String, Object> d = rows.stream().filter(r -> "2026-01-05".equals(r.get("ts"))).findFirst().orElseThrow();
        assertNull(d.get("sum_amount__cmp"), "2025-01-05 holds nothing; the 2025-01-10 row is not 'the same day'");
    }

    @Test
    void targetIsAConstantAndAZeroTargetHasNoPercent() throws Exception {
        List<Map<String, Object>> rows = run(body(Map.of("kind", "target", "target", 10), List.of("ts")));
        Map<String, Object> jan26 = rows.stream().filter(r -> "2026-01".equals(r.get("ts"))).findFirst().orElseThrow();
        assertEquals(10.0, ((Number) jan26.get("sum_amount__cmp")).doubleValue());
        assertEquals(5.0, ((Number) jan26.get("sum_amount__delta")).doubleValue());
        assertEquals(50.0, ((Number) jan26.get("sum_amount__pct")).doubleValue(), 1e-9);
        List<Map<String, Object>> zero = run(body(Map.of("kind", "target", "target", 0), List.of("ts")));
        assertNull(zero.get(0).get("sum_amount__pct"), "a % of a zero baseline is undefined: absent, not 0 or infinity");
    }

    @Test
    void averageIsTheMeanAlongTheTimeAxisPerSeries() throws Exception {
        List<Map<String, Object>> rows = run(body(Map.of("kind", "average"), List.of("region", "ts")));
        for (Map<String, Object> r : rows) {
            double expected = "N".equals(r.get("region")) ? (4 + 15 + 7 + 14) / 4.0 : 3.0;
            assertEquals(expected, ((Number) r.get("sum_amount__cmp")).doubleValue(), 1e-9, line(r));
        }
    }

    @Test
    void aMeasureWithNoNumberIsComparedButHasNoChange() throws Exception {
        Map<String, Object> b = body(Map.of("kind", "previous"), List.of("ts"));
        b.put("measures", List.of(Map.of("agg", "max", "field", "region")));
        Map<String, Object> feb = run(b).stream().filter(r -> "2026-02".equals(r.get("ts"))).findFirst().orElseThrow();
        assertEquals("S", feb.get("max_region"));
        assertEquals("N", feb.get("max_region__cmp"), "the earlier value itself is still shown");
        assertNull(feb.get("max_region__delta"), "text has no change: NULL, not a failed statement");
        Map<String, Object> avg = body(Map.of("kind", "average"), List.of("ts"));
        avg.put("measures", List.of(Map.of("agg", "max", "field", "region")));
        assertNull(run(avg).get(0).get("max_region__cmp"));
    }

    @Test
    void theComparisonColumnsAreNamedAndDescribedFromTheSpec() {
        MeasureCompiler.Spec spec = MeasureCompiler.parse(body(Map.of("kind", "previous"), List.of("ts")), 5, 10);
        Map<String, MeasureCompiler.CompareColumn> cols = MeasureCompiler.compareColumns(spec);
        assertEquals(List.of("sum_amount__cmp", "sum_amount__delta", "sum_amount__pct"), List.copyOf(cols.keySet()));
        assertEquals(new MeasureCompiler.CompareColumn("previous", "sum_amount", "pct"), cols.get("sum_amount__pct"));
        List<ResultSetDescriptor.Column> marked = ResultSetDescriptor.markCompare(List.of(
                new ResultSetDescriptor.Column("sum_amount", "number", "measure", null),
                new ResultSetDescriptor.Column("sum_amount__cmp", "number", "measure", null)), cols);
        assertNull(marked.get(0).overlay());
        assertEquals(new ResultSetDescriptor.Overlay("compare", "previous", "sum_amount", "cmp"), marked.get(1).overlay());
    }

    @Test
    void aFilteredSpecStillBindsItsValuesAndRendersForDisplay() throws Exception {
        Map<String, Object> b = body(Map.of("kind", "previous"), List.of("ts"));
        b.put("filters", List.of(Map.of("field", "region", "op", "=", "value", "N")));
        MeasureCompiler.Compiled c = MeasureCompiler.compile(MeasureCompiler.parse(b, 5, 10));
        assertEquals(List.of("N"), c.params());
        assertTrue(c.sql().startsWith("WITH \"__cmp_base\" AS (SELECT"), c.sql());
        assertTrue(MeasureCompiler.render(MeasureCompiler.parse(b, 5, 10)).contains("'N'"));
        assertEquals(4, run(b).size());
        assertEquals("WITH \"q\" AS (SELECT 1), \"__cmp_base\" AS (x)",
                MeasureCompiler.withPrefix("WITH \"q\" AS (SELECT 1) ", "WITH \"__cmp_base\" AS (x)"));
        assertEquals("WITH \"q\" AS (SELECT 1) SELECT 2", MeasureCompiler.withPrefix("WITH \"q\" AS (SELECT 1) ", "SELECT 2"));
    }

    @Test
    void nonsenseIsRefused() {
        List<String> ts = List.of("ts");
        assertThrows(IllegalArgumentException.class, () -> MeasureCompiler.parse(body(Map.of("kind", "yesterday"), ts), 5, 10));
        assertThrows(IllegalArgumentException.class, () -> MeasureCompiler.parse(body(Map.of("kind", "target"), ts), 5, 10),
                "a target needs its number");
        assertThrows(IllegalArgumentException.class, () -> MeasureCompiler.parse(body(Map.of("kind", "previous", "target", 3), ts), 5, 10));
        assertThrows(IllegalArgumentException.class, () -> MeasureCompiler.parse(body(Map.of("kind", "previous", "x", 1), ts), 5, 10));
        Map<String, Object> noGrain = body(Map.of("kind", "previous"), List.of("region"));
        noGrain.remove("grains");
        assertThrows(IllegalArgumentException.class, () -> MeasureCompiler.parse(noGrain, 5, 10), "previous needs a time grain");
        assertThrows(IllegalArgumentException.class,
                () -> MeasureCompiler.parse(body(Map.of("kind", "previous", "field", "region"), List.of("region", "ts")), 5, 10),
                "the field must be the grained column");
        Map<String, Object> hidden = body(Map.of("kind", "previous"), ts);
        hidden.put("orderBy", List.of(Map.of("field", "amount", "agg", "max")));
        assertThrows(IllegalArgumentException.class, () -> MeasureCompiler.parse(hidden, 5, 10));
        Map<String, Object> noMeasure = body(Map.of("kind", "average"), ts);
        noMeasure.remove("measures");
        assertThrows(IllegalArgumentException.class, () -> MeasureCompiler.parse(noMeasure, 5, 10));
        assertThrows(IllegalArgumentException.class, () -> MeasureCompiler.parse(body(Map.of("kind", "previous"),
                List.of("ts", "sum_amount__cmp")), 5, 10), "a groupBy column may not take a compare column's name");
    }
}

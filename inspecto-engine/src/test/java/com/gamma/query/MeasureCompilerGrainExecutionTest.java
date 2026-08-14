package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The half of the time-grain fix {@link MeasureCompilerTest} cannot prove: that DuckDB actually
 * <em>runs</em> the bucketing expression and returns the same bucket keys the UI's offline
 * {@code bucketValue} produces. Compiling the right-looking string is not evidence — the whole defect
 * being fixed here was a widget that looked correct offline and grouped by the raw timestamp live.
 *
 * <p>Runs the compiled SQL through the real sandbox against an inline relation, so nothing but the
 * grain expression is under test.
 */
class MeasureCompilerGrainExecutionTest {

    /** Three events: two in the same week of June 2026, one in July. */
    private static final String RELATION = """
            SELECT * FROM (VALUES
              (TIMESTAMP '2026-06-24 09:01:30', 10),
              (TIMESTAMP '2026-06-28 23:59:00', 20),
              (TIMESTAMP '2026-07-02 00:00:00', 5)
            ) AS t(event_time, amount)""";

    private static List<Map<String, Object>> run(String grain) throws Exception {
        MeasureCompiler.Spec spec = MeasureCompiler.parse(Map.of(
                "dataset", "events",
                "measures", List.of(Map.of("agg", "sum", "field", "amount")),
                "groupBy", List.of("event_time"),
                "grains", Map.of("event_time", grain),
                "orderBy", List.of(Map.of("field", "event_time", "dir", "asc"))), 500, 10_000);
        String sql = MeasureCompiler.compile(spec);
        return QueryExecutor.run(new QueryExecutor.Request("events", RELATION, sql, 500, 0,
                List.of(), List.of())).rows();
    }

    private static String key(Map<String, Object> row) {
        return String.valueOf(row.get("event_time"));
    }

    private static int sum(Map<String, Object> row) {
        return ((Number) row.get("sum_amount")).intValue();
    }

    @Test
    void monthCollapsesToTheUisYyyyMmKey() throws Exception {
        List<Map<String, Object>> rows = run("month");

        assertEquals(2, rows.size(), () -> "three timestamps must fall into two months: " + rows);
        assertEquals("2026-06", key(rows.get(0)));
        assertEquals(30, sum(rows.get(0)));
        assertEquals("2026-07", key(rows.get(1)));
        assertEquals(5, sum(rows.get(1)));
    }

    @Test
    void weekCollapsesToItsMondayJustAsTheOfflineBucketDoes() throws Exception {
        List<Map<String, Object>> rows = run("week");

        // Wed 24 June and Sun 28 June share the week beginning Monday 22 June — the UI's bucketValue
        // ('2026-06-22') and DuckDB's Monday-based DATE_TRUNC must agree, or the same widget labels its
        // categories differently live and offline.
        assertEquals(2, rows.size(), () -> "the two June rows share a week: " + rows);
        assertEquals("2026-06-22", key(rows.get(0)));
        assertEquals(30, sum(rows.get(0)));
        assertEquals("2026-06-29", key(rows.get(1)));
    }

    @Test
    void dayKeepsEachDateApartAndUngroupedRawTimestampsWouldNot() throws Exception {
        List<Map<String, Object>> rows = run("day");

        assertEquals(3, rows.size(), () -> "three distinct dates: " + rows);
        assertEquals(List.of("2026-06-24", "2026-06-28", "2026-07-02"), rows.stream().map(
                MeasureCompilerGrainExecutionTest::key).toList());
    }
}

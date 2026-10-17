package com.gamma.expectation;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden figures of the {@code baseline} Expectation kind (ANALYTICS-CORE-1 M4 / M2): every profiled cell over parquet
 * fixtures (numbers, text, dates, NULLs, groups, an empty target) and the median the comparison judges against (odd and
 * even windows, trailing-days mode). Written against the hand-built profile SQL and private median BEFORE they moved
 * onto the Analytics Kernel ({@code ColumnProfiler}, {@code Stats.median} STANDARD), and kept green after.
 */
class BaselineEvaluatorGoldenTest {

    @TempDir
    static Path data;

    @BeforeAll
    static void seed() throws Exception {
        write(Files.createDirectories(data.resolve("t").resolve("p=0")), "SELECT region, CAST(amount AS DOUBLE) AS amount, "
                + "CAST(label AS VARCHAR) AS label, CAST(day AS DATE) AS day, CAST(n AS INTEGER) AS n FROM (VALUES "
                + "('EU', 10.0, '5', DATE '2024-01-01', 1),"
                + "('EU', NULL, 'x', DATE '2024-01-02', 2),"
                + "('US', 2.5, NULL, NULL, 3),"
                + "('US', 2.5, '7.5', DATE '2024-01-01', 3),"
                + "('APAC', NULL, NULL, NULL, NULL)) v(region, amount, label, day, n)");
        write(Files.createDirectories(data.resolve("empty").resolve("p=0")), "SELECT CAST(1.0 AS DOUBLE) AS amount, "
                + "CAST('a' AS VARCHAR) AS label WHERE false");
    }

    private static void write(Path dir, String select) throws Exception {
        String parquet = dir.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("baseline_golden_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (" + select + ") TO '" + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static Expectation baseline(String target, List<String> columns, List<String> groupBy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "b1");
        m.put("target", target);
        m.put("kind", "baseline");
        m.put("maxIncrease", 10);
        m.put("measures", List.of("row_count", "null_count", "null_rate", "distinct_count", "min", "max", "mean"));
        m.put("columns", columns);
        m.put("groupBy", groupBy);
        return Expectation.fromMap(m);
    }

    private static void col(Map<String, Double> m, String c, Double nulls, Double rate, Double distinct, Double min,
                            Double max, Double mean) {
        m.put(c + ".null_count", nulls);
        m.put(c + ".null_rate", rate);
        m.put(c + ".distinct_count", distinct);
        m.put(c + ".min", min);
        m.put(c + ".max", max);
        m.put(c + ".mean", mean);
    }

    @Test
    void aGroupedProfileOfNumbersTextDatesAndNulls() throws Exception {
        var profile = BaselineEvaluator.profile(baseline("t", List.of("amount", "label", "day", "n"), List.of("region")), data);

        Map<String, Map<String, Double>> want = new TreeMap<>();
        Map<String, Double> apac = new TreeMap<>();
        apac.put("row_count", 1.0);
        for (String c : List.of("amount", "label", "day", "n")) col(apac, c, 1.0, 1.0, 0.0, null, null, null);
        want.put("[\"APAC\"]", apac);

        Map<String, Double> eu = new TreeMap<>();
        eu.put("row_count", 2.0);
        col(eu, "amount", 1.0, 0.5, 1.0, 10.0, 10.0, 10.0);
        col(eu, "label", 0.0, 0.0, 2.0, 5.0, 5.0, 5.0);          // 'x' is no number: TRY_CAST drops it from min/max/mean
        col(eu, "day", 0.0, 0.0, 2.0, null, null, null);        // a date is no number
        col(eu, "n", 0.0, 0.0, 2.0, 1.0, 2.0, 1.5);
        want.put("[\"EU\"]", eu);

        Map<String, Double> us = new TreeMap<>();
        us.put("row_count", 2.0);
        col(us, "amount", 0.0, 0.0, 1.0, 2.5, 2.5, 2.5);
        col(us, "label", 1.0, 0.5, 1.0, 7.5, 7.5, 7.5);
        col(us, "day", 1.0, 0.5, 1.0, null, null, null);
        col(us, "n", 0.0, 0.0, 1.0, 3.0, 3.0, 3.0);
        want.put("[\"US\"]", us);

        assertEquals(want, profile);
    }

    @Test
    void anEmptyTargetProfilesZeroRowsAndNoRate() throws Exception {
        var profile = BaselineEvaluator.profile(baseline("empty", List.of("amount", "label"), List.of()), data);
        Map<String, Double> all = new TreeMap<>();
        all.put("row_count", 0.0);
        col(all, "amount", 0.0, null, 0.0, null, null, null);
        col(all, "label", 0.0, null, 0.0, null, null, null);
        assertEquals(Map.of(BaselineEvaluator.ALL, all), profile);
    }

    private static Map<String, Object> accepted(double rows) {
        return Map.of("groups", Map.of(BaselineEvaluator.ALL, Map.of("row_count", rows)));
    }

    @Test
    void theComparisonJudgesAgainstTheStandardMedianOfTheWindow() {
        var b = new Expectation.Baseline(7, List.of("row_count"), List.of(), 29.0, null, "absolute", List.of(), false);
        var now = Map.of(BaselineEvaluator.ALL, Map.of("row_count", 130.0));
        // even window: the two middle values (90, 110) are averaged
        var even = BaselineEvaluator.compare(b, now, List.of(accepted(80), accepted(120), accepted(90), accepted(110)));
        assertEquals(1, even.violations());
        assertEquals(100.0, even.findings().getFirst().baseline());
        // odd window: the middle value
        var odd = BaselineEvaluator.compare(b, now, List.of(accepted(95), accepted(10), accepted(500)));
        assertEquals(1, odd.violations());
        assertEquals(95.0, odd.findings().getFirst().baseline());
    }

    @Test
    void trailingDaysJudgeEachDayAgainstTheMedianOfTheDaysBefore() {
        var b = new Expectation.Baseline(7, List.of("row_count"), List.of(), null, 10.0, "percent", List.of("day"), false, 3, 2);
        Map<String, Map<String, Double>> days = new TreeMap<>();
        days.put("[\"2024-01-01\"]", Map.of("row_count", 100.0));
        days.put("[\"2024-01-02\"]", Map.of("row_count", 200.0));
        days.put("[\"2024-01-03\"]", Map.of("row_count", 50.0));   // prior 100, 200 -> median 150 -> below
        days.put("[\"2024-01-04\"]", Map.of("row_count", 60.0));   // prior 100, 200, 50 -> median 100 -> below
        var cmp = BaselineEvaluator.compareTrailing(b, days);
        assertEquals(2, cmp.violations());
        assertEquals(List.of(150.0, 100.0), cmp.findings().stream().map(BaselineEvaluator.Finding::baseline).toList());
    }
}

package com.gamma.analytics;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.ColumnProfiler.Bounds;
import com.gamma.analytics.ColumnProfiler.Distinct;
import com.gamma.analytics.ColumnProfiler.Spec;
import com.gamma.analytics.ColumnProfiler.Statistic;
import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The column profiler (ANALYTICS-CORE-1 M4) executed against DuckDB on fixtures: numbers, text, dates, NULLs, an
 * all-NULL column and an empty relation, both layouts, groups, quantile modes, top values and the histogram; the
 * numeric statistics agree with {@link Stats}.
 */
class ColumnProfilerTest {

    @TempDir
    static Path spill;
    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
        exec("CREATE TABLE f AS SELECT region, CAST(amount AS DOUBLE) AS amount, name, CAST(day AS DATE) AS day, "
                + "CAST(nothing AS INTEGER) AS nothing FROM (VALUES "
                + "('EU', 1.0, 'b', DATE '2024-03-01', NULL),"
                + "('EU', 2.0, 'a', DATE '2024-01-15', NULL),"
                + "('EU', NULL, NULL, NULL, NULL),"
                + "('US', 4.0, 'b', DATE '2023-12-31', NULL),"
                + "('US', 10.0, '7.5', DATE '2024-03-01', NULL)) t(region, amount, name, day, nothing)");
        exec("CREATE TABLE \"we\"\"ird\" AS SELECT * FROM f WHERE false");
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    private static void exec(String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** Every row as {@code a|b|c}, in the order the statement returns them. */
    private static List<String> rows(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) sb.append(i > 1 ? "|" : "").append(rs.getString(i));
                out.add(sb.toString());
            }
        }
        return out;
    }

    private static List<String> header(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql + " LIMIT 0")) {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) out.add(rs.getMetaData().getColumnLabel(i));
        }
        return out;
    }

    private static final List<String> ALL = List.of("amount", "name", "day", "nothing");

    @Test
    void rowPerColumnReportsEveryStatisticForEveryType() throws Exception {
        String sql = ColumnProfiler.rowPerColumn("f", Spec.of(ALL));
        assertEquals(List.of("column_name", "row_count", "null_count", "null_rate", "distinct_count", "min_value", "max_value",
                "mean", "stddev"), header(sql));
        assertEquals(List.of(
                // numbers: mean 17/4
                "amount|5|1|0.2|4|1.0|10.0|4.25",
                // text: bounds in text order; only '7.5' reads as a number
                "name|5|1|0.2|3|7.5|b|7.5",
                // dates: bounds as dates; no numeric reading at all
                "day|5|1|0.2|3|2023-12-31|2024-03-01|null",
                // all NULL: distinct 0, no bounds
                "nothing|5|5|1.0|0|null|null|null"), rows("SELECT * EXCLUDE (stddev) FROM (" + sql + ")"));
        assertEquals(List.of("amount", "null", "null", "null"), rows("SELECT CASE WHEN stddev IS NULL THEN 'null' ELSE column_name END "
                + "FROM (" + sql + ")"), "one number has no sample stddev");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT stddev FROM (" + sql + ") WHERE column_name = 'amount'")) {
            assertTrue(rs.next());
            assertEquals(Stats.stddevSample(new double[] {1, 2, 4, 10}), rs.getDouble(1), 1e-12);
        }
    }

    @Test
    void anEmptyRelationHasCountsButNoRateBoundsOrMoments() throws Exception {
        String sql = ColumnProfiler.rowPerColumn("\"we\"\"ird\"", Spec.of(List.of("amount", "name")));
        assertEquals(List.of("amount|0|0|null|0|null|null|null|null", "name|0|0|null|0|null|null|null|null"), rows(sql));
    }

    @Test
    void numericBoundsReadEveryColumnAsANumber() throws Exception {
        String sql = ColumnProfiler.rowPerColumn("f", Spec.of(ALL).withStatistics(Statistic.MIN, Statistic.MAX).withBounds(Bounds.NUMERIC));
        assertEquals(List.of("amount|1.0|10.0", "name|7.5|7.5", "day|null|null", "nothing|null|null"), rows(sql));
    }

    @Test
    void groupsComeFirstAsText() throws Exception {
        String sql = ColumnProfiler.rowPerColumn("f", Spec.of(List.of("amount")).withGroupBy(List.of("region"))
                .withStatistics(Statistic.ROW_COUNT, Statistic.NULL_COUNT)) + " ORDER BY group_0";
        assertEquals(List.of("group_0", "column_name", "row_count", "null_count"), header(sql));
        assertEquals(List.of("EU|amount|3|1", "US|amount|2|0"), rows(sql));
    }

    @Test
    void rowPerGroupReportsRowCountOnceAndEachColumnByPosition() throws Exception {
        Spec spec = Spec.of(List.of("amount", "name")).withGroupBy(List.of("region"))
                .withStatistics(Statistic.ROW_COUNT, Statistic.NULL_COUNT, Statistic.DISTINCT_COUNT, Statistic.MEAN)
                .withQuantiles(QuantileMode.CONTINUOUS, 0.5);
        String sql = ColumnProfiler.rowPerGroup("f AS __t", spec) + " ORDER BY group_0";
        assertEquals(List.of("group_0", "row_count", "null_count_0", "distinct_count_0", "mean_0", "p50_0",
                "null_count_1", "distinct_count_1", "mean_1", "p50_1"), header(sql));
        assertEquals(List.of("EU|3|1|2|1.5|1.5|1|2|null|null", "US|2|0|2|7.0|7.0|0|2|7.5|7.5"), rows(sql));
    }

    @Test
    void rowPerGroupWithoutGroupsIsOneRowEvenWhenEmpty() throws Exception {
        String sql = ColumnProfiler.rowPerGroup("\"we\"\"ird\"", Spec.of(List.of("amount"))
                .withStatistics(Statistic.ROW_COUNT, Statistic.NULL_COUNT, Statistic.MIN));
        assertEquals(List.of("0|0|null"), rows(sql));
    }

    @Test
    void approximateDistinctOverAnExpressionNeverExceedsTheNonNullRows() throws Exception {
        Spec spec = Spec.of(List.of("name")).withStatistics(Statistic.DISTINCT_COUNT)
                .withDistinct(Distinct.APPROXIMATE, c -> "NULLIF(upper(" + c + "), 'B')");
        assertEquals(List.of("name|2"), rows(ColumnProfiler.rowPerColumn("f", spec)), "'a' and '7.5'; 'b' is mapped away");
        Spec exact = spec.withDistinct(Distinct.EXACT, c -> "upper(" + c + ")");
        assertEquals(List.of("name|3"), rows(ColumnProfiler.rowPerColumn("f", exact)));
    }

    @Test
    void quantilesFollowTheirModeAndMatchTheKernel() throws Exception {
        double[] v = {1, 2, 4, 10};
        double[] fractions = {0, 0.25, 0.5, 0.9, 0.995, 1};
        for (QuantileMode mode : QuantileMode.values()) {
            String sql = ColumnProfiler.rowPerColumn("f", Spec.of(List.of("amount")).withQuantiles(mode, fractions).withStatistics());
            assertEquals(List.of("column_name", "p0", "p25", "p50", "p90", "p99_5", "p100"), header(sql));
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                assertTrue(rs.next());
                for (int i = 0; i < fractions.length; i++)
                    assertEquals(Stats.quantile(v, fractions[i], mode), rs.getDouble(i + 2), 1e-9, mode + " q=" + fractions[i]);
            }
        }
        assertEquals("quantile_disc(d, 0.5)", ColumnProfiler.quantile("d", 0.5, QuantileMode.DISCRETE));
        assertEquals("quantile_disc(d, 0.5)", ColumnProfiler.quantile("d", 0.5, QuantileMode.NEAREST_RANK));
        assertEquals("quantile_cont(d, 0.99)", ColumnProfiler.quantile("d", 0.99, QuantileMode.CONTINUOUS));
    }

    @Test
    void theMomentsMatchTheKernelOnARandomColumn() throws Exception {
        Random r = new Random(20261017);
        double[] v = r.doubles(500, -1000, 1000).map(x -> Math.rint(x * 1000) / 1000).toArray();
        StringBuilder values = new StringBuilder();
        for (double x : v) values.append(values.isEmpty() ? "" : ",").append("(").append(x).append(")");
        exec("CREATE OR REPLACE TABLE rnd AS SELECT CAST(x AS DOUBLE) AS x FROM (VALUES " + values + ") t(x)");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(ColumnProfiler.rowPerGroup("rnd",
                Spec.of(List.of("x")).withStatistics(Statistic.MEAN, Statistic.STDDEV)))) {
            assertTrue(rs.next());
            assertEquals(Stats.mean(v), rs.getDouble("mean_0"), 1e-9);
            assertEquals(Stats.stddevSample(v), rs.getDouble("stddev_0"), 1e-9);
        }
    }

    @Test
    void topValuesAreMostFrequentFirstThenByValue() throws Exception {
        assertEquals(List.of("b|2", "7.5|1"), rows(ColumnProfiler.topValues("f", "name", 2)));
        assertEquals(List.of("2024-03-01|2", "2023-12-31|1", "2024-01-15|1"), rows(ColumnProfiler.topValues("f", "day", 5)));
        assertEquals(List.of(), rows(ColumnProfiler.topValues("f", "nothing", 3)), "an all-NULL column has no values");
    }

    @Test
    void theHistogramHasEqualWidthBinsAndCountsTheMaximum() throws Exception {
        assertEquals(List.of("0|1.0|4.0|2", "1|4.0|7.0|1", "2|7.0|10.0|1"), rows(ColumnProfiler.histogram("f", "amount", 3)));
        assertEquals(List.of("0|7.5|7.5|1", "1|7.5|7.5|0"), rows(ColumnProfiler.histogram("f", "name", 2)),
                "one numeric value: everything in bin 0");
        assertEquals(List.of(), rows(ColumnProfiler.histogram("f", "day", 4)), "no numeric value: no bins");
        assertEquals(List.of(), rows(ColumnProfiler.histogram("\"we\"\"ird\"", "amount", 4)));
    }

    @Test
    void identifiersAreQuotedAndLiteralsEscaped() throws Exception {
        exec("CREATE OR REPLACE TABLE odd AS SELECT 1 AS \"it's \"\"x\"\"\"");
        assertEquals(List.of("it's \"x\"|1|0"), rows(ColumnProfiler.rowPerColumn("odd",
                Spec.of(List.of("it's \"x\"")).withStatistics(Statistic.ROW_COUNT, Statistic.NULL_COUNT))));
    }

    @Test
    void aSpecRefusesWhatHasNoAnswer() {
        assertThrows(IllegalArgumentException.class, () -> Spec.of(List.of()));
        assertThrows(IllegalArgumentException.class, () -> Spec.of(List.of("a")).withStatistics());
        assertThrows(IllegalArgumentException.class, () -> Spec.of(List.of("a")).withQuantiles(QuantileMode.CONTINUOUS, 1.5));
        assertThrows(IllegalArgumentException.class, () -> ColumnProfiler.topValues("f", "a", 0));
        assertThrows(IllegalArgumentException.class, () -> ColumnProfiler.histogram("f", "a", 0));
        assertEquals("p50", ColumnProfiler.quantileColumn(0.5));
        assertEquals("p99_5", ColumnProfiler.quantileColumn(0.995));
        assertEquals("p5", ColumnProfiler.quantileColumn(0.05));
    }
}

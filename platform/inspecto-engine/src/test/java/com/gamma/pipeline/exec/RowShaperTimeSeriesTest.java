package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code transform.timeseries.resample} and {@code transform.timeseries.shift} (ANALYTICS-CORE-1 M5) — {@link RowShaper}
 * over an embedded DuckDB input, with the session zone set to one no config names, so a bucket that leaned on DuckDB's
 * (host) zone would land elsewhere.
 */
class RowShaperTimeSeriesTest {

    private File db;
    private Connection conn;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rsts_");
        conn = DuckDbUtil.openConnection(db);
        exec("SET TimeZone = 'Pacific/Chatham'");
        exec("CREATE TABLE src AS SELECT * FROM (VALUES"
                + " ('N', TIMESTAMP '2026-01-05 10:00', 10.0), ('N', TIMESTAMP '2026-01-20 11:00', 5.0),"
                + " ('N', TIMESTAMP '2026-03-02 00:00', 7.0), ('S', TIMESTAMP '2026-02-14 09:00', 3.0)) t(region, ts, amt)");
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    private String run(String type, String input, Map<String, Object> cfg) throws SQLException {
        return RowShaper.shape(conn, PipelineNode.of("n", type, cfg), input, "n_" + Math.abs(cfg.hashCode())).stream()
                .filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void resampleThenShiftGivesMonthOverMonthWithGapsFilled() throws Exception {
        String monthly = run("transform.timeseries.resample", "src", cfg("time_column", "ts", "grain", "month",
                "group_by", List.of("region"), "measures", List.of("count", "sum(amt)", "max(amt)"),
                "bucket_column", "month", "to", "2026-03-31"));
        assertEquals(List.of(
                "N|2026-01-01 00:00:00|2|15.0|10.0", "N|2026-02-01 00:00:00|0|0.0|null", "N|2026-03-01 00:00:00|1|7.0|7.0",
                "S|2026-01-01 00:00:00|0|0.0|null", "S|2026-02-01 00:00:00|1|3.0|3.0", "S|2026-03-01 00:00:00|0|0.0|null"),
                rows("SELECT region, strftime(month, '%Y-%m-%d %H:%M:%S'), count, sum_amt, max_amt FROM "
                        + q(monthly) + " ORDER BY ALL"));
        String mom = run("transform.timeseries.shift", monthly, cfg("time_column", "month", "grain", "month",
                "group_by", List.of("region"), "columns", List.of("sum_amt")));
        assertEquals(List.of("N|2026-01-01|15.0|null", "N|2026-02-01|0.0|15.0", "N|2026-03-01|7.0|0.0",
                        "S|2026-01-01|0.0|null", "S|2026-02-01|3.0|0.0", "S|2026-03-01|0.0|3.0"),
                rows("SELECT region, strftime(month, '%Y-%m-%d'), sum_amt, sum_amt_prev FROM " + q(mom) + " ORDER BY ALL"));
    }

    @Test
    void aZonedColumnIsBucketedOnTheStatedZoneAndRefusedWithoutOne() throws Exception {
        exec("CREATE TABLE calls AS SELECT * FROM (VALUES (TIMESTAMPTZ '2026-10-24 21:30:00+00'),"
                + " (TIMESTAMPTZ '2026-10-24 22:30:00+00')) t(call_at)");
        String daily = run("transform.timeseries.resample", "calls", cfg("time_column", "call_at", "grain", "day",
                "timezone", "Europe/Berlin", "measures", List.of("count")));
        assertEquals(List.of("2026-10-24|1", "2026-10-25|1"),
                rows("SELECT strftime(bucket, '%Y-%m-%d'), count FROM " + q(daily) + " ORDER BY 1"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(
                "transform.timeseries.resample", "calls", cfg("time_column", "call_at", "grain", "day", "measures", List.of("count"))));
        assertTrue(e.getMessage().contains("set 'timezone'"), e.getMessage());
        IllegalArgumentException wall = assertThrows(IllegalArgumentException.class, () -> run(
                "transform.timeseries.resample", "src", cfg("time_column", "ts", "grain", "day", "timezone", "UTC",
                        "measures", List.of("count"))));
        assertTrue(wall.getMessage().contains("drop 'timezone'"), wall.getMessage());
    }

    @Test
    void refusalsNameTheProblem() throws Exception {
        assertRefused("transform.timeseries.resample", cfg("time_column", "nope", "grain", "day", "measures", List.of("count")),
                "column 'nope' is not in the inbound data");
        assertRefused("transform.timeseries.resample", cfg("time_column", "ts", "grain", "fortnight", "measures", List.of("count")),
                "'grain' 'fortnight' is not one of");
        assertRefused("transform.timeseries.resample", cfg("time_column", "ts", "grain", "day", "measures", List.of("mode(amt)")),
                "unknown aggregation 'mode'");
        assertRefused("transform.timeseries.resample", cfg("time_column", "amt", "grain", "day", "measures", List.of("count")),
                "must be a DATE or TIMESTAMP column");
        assertRefused("transform.timeseries.resample", cfg("time_column", "ts", "grain", "day", "measures", List.of("count"),
                "timezone", "+05:30"), "not a known region id");
        assertRefused("transform.timeseries.resample", cfg("time_column", "ts", "grain", "day", "measures", List.of("count"),
                "group_by", List.of("count")), "appears twice");
        assertRefused("transform.timeseries.shift", cfg("time_column", "ts", "grain", "month", "columns", List.of("amt"),
                "suffix", ""), "one row per key per month");
        assertRefused("transform.timeseries.shift", cfg("time_column", "ts", "grain", "day", "columns", List.of("amt"),
                "group_by", List.of("region"), "suffix", "x", "periods", 0), "'periods' must be a non-zero");
        exec("CREATE TABLE clash AS SELECT region, ts, amt, amt AS amt_prev FROM src");
        assertRefused("transform.timeseries.shift", cfg("time_column", "ts", "grain", "day", "columns", List.of("amt"),
                "group_by", List.of("region")), "collides with an inbound column", "clash");
        assertRefused("transform.timeseries.shift", cfg("time_column", "ts", "grain", "day", "columns", List.of("amt"),
                "window", "1"), "does not take 'window'");
    }

    /**
     * {@code transform.timeseries.forecast} (ANALYTICS-CORE-1 S5): a resample's daily sums forecast per region, the
     * numbers the kernel's JVM form gives, the horizon labelled on the grain and typed as the input.
     */
    @Test
    void forecastAfterResampleGivesTheKernelsNumbersPerKey() throws Exception {
        exec("CREATE TABLE sales AS SELECT r AS region, TIMESTAMP '2026-01-05 09:00' + INTERVAL (d) DAY AS ts,"
                + " CAST(100 + 10 * (r = 'S')::INTEGER + d + 5 * (d % 7) AS DOUBLE) AS amt FROM range(21) t(d),"
                + " (VALUES ('N'), ('S')) k(r) WHERE NOT (r = 'N' AND d = 9)");   // N has an empty day: 0 after resample
        String daily = run("transform.timeseries.resample", "sales", cfg("time_column", "ts", "grain", "day",
                "group_by", List.of("region"), "measures", List.of("sum(amt)"), "bucket_column", "day"));
        String out = run("transform.timeseries.forecast", daily, cfg("time_column", "day", "grain", "day",
                "value_column", "sum_amt", "group_by", List.of("region"), "period", 7, "horizon", 3,
                "levels", List.of(0.8, 0.95)));
        assertEquals(List.of("region", "day", "horizon", "actual", "forecast", "level", "lower", "upper"),
                rows("SELECT column_name FROM (DESCRIBE " + q(out) + ")"));
        assertEquals(List.of("TIMESTAMP"), rows("SELECT DISTINCT typeof(day) FROM " + q(out)));
        for (String region : List.of("N", "S")) {
            List<String> ys = rows("SELECT sum_amt FROM " + q(daily) + " WHERE region = '" + region + "' ORDER BY day");
            double[] y = ys.stream().mapToDouble(Double::parseDouble).toArray();
            com.gamma.analytics.forecast.Forecaster.Result jvm = com.gamma.analytics.forecast.Forecaster.fit(y,
                    TimeSeriesStep.parseForecast(cfg("time_column", "day", "grain", "day", "value_column", "sum_amt",
                            "period", 7, "horizon", 3, "levels", List.of(0.8, 0.95))).model());
            List<String> fut = rows("SELECT strftime(day, '%Y-%m-%d'), forecast, upper FROM " + q(out) + " WHERE region = '"
                    + region + "' AND horizon > 0 AND level = 0.95 ORDER BY day");
            assertEquals(3, fut.size());
            assertTrue(fut.get(0).startsWith("2026-01-26|"), fut.toString());
            for (int h = 0; h < 3; h++) {
                String[] c = fut.get(h).split("\\|");
                assertEquals(jvm.ahead()[h], Double.parseDouble(c[1]), 1e-9 * Math.abs(jvm.ahead()[h]));
                assertEquals(jvm.upperAhead(0.95)[h], Double.parseDouble(c[2]), 1e-9 * Math.abs(jvm.upperAhead(0.95)[h]));
            }
        }
        assertEquals(List.of("96"), rows("SELECT count(*) FROM " + q(out)));   // (21 + 3) days x 2 regions x 2 levels
    }

    @Test
    void forecastRefusesAnIrregularOrShortSeriesBeforeForecasting() throws Exception {
        Map<String, Object> base = cfg("time_column", "ts", "grain", "day", "value_column", "amt",
                "group_by", List.of("region"), "period", 2, "horizon", 1);
        assertRefused("transform.timeseries.forecast", base, "not the start of a day");
        exec("CREATE TABLE gappy AS SELECT * FROM (VALUES ('N', DATE '2026-01-01', 1.0), ('N', DATE '2026-01-03', 2.0))"
                + " t(region, ts, amt)");
        assertRefused("transform.timeseries.forecast", base, "missing or repeated day", "gappy");
        exec("CREATE TABLE short AS SELECT * FROM (VALUES ('N', DATE '2026-01-01', 1.0), ('N', DATE '2026-01-02', 2.0),"
                + " ('N', DATE '2026-01-03', 2.0)) t(region, ts, amt)");
        assertRefused("transform.timeseries.forecast", base, "at least 4 (two seasons)", "short");
        exec("CREATE TABLE holes AS SELECT region, ts, CASE WHEN ts = DATE '2026-01-02' THEN NULL ELSE amt END AS amt"
                + " FROM short");
        assertRefused("transform.timeseries.forecast", base, "empty 'amt'", "holes");
        assertRefused("transform.timeseries.forecast", cfg("time_column", "ts", "grain", "day", "value_column", "region",
                "period", 2, "horizon", 1), "must be a number", "short");
        assertRefused("transform.timeseries.forecast", cfg("time_column", "ts", "grain", "day", "value_column", "amt",
                "period", 1, "horizon", 1), "'period' must be between 2");
        assertRefused("transform.timeseries.forecast", cfg("time_column", "ts", "grain", "day", "value_column", "amt",
                "period", 2, "horizon", 1, "group_by", List.of("forecast")), "appears twice");
        assertRefused("transform.timeseries.forecast", cfg("time_column", "ts", "grain", "day", "value_column", "amt",
                "period", 2, "horizon", 1, "levels", List.of(1.5)), "between 0 and 1");
    }

    @Test
    void aResampleOverTooManyBucketsFailsTheRun() throws Exception {
        SQLException e = assertThrows(SQLException.class, () -> run("transform.timeseries.resample", "src",
                cfg("time_column", "ts", "grain", "second", "measures", List.of("count"))));
        assertTrue(e.getMessage().contains("more than 100000 second buckets"), e.getMessage());
    }

    private void assertRefused(String type, Map<String, Object> cfg, String fragment) {
        assertRefused(type, cfg, fragment, "src");
    }

    private void assertRefused(String type, Map<String, Object> cfg, String fragment, String input) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(type, input, cfg));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
        assertTrue(e.getMessage().startsWith("node 'n': transform.timeseries."), e.getMessage());
    }

    private static String q(String t) {
        return com.gamma.util.SqlIdent.q(t);
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private List<String> rows(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= n; i++) cells.add(String.valueOf(rs.getObject(i)));
                out.add(String.join("|", cells));
            }
        }
        return out;
    }
}

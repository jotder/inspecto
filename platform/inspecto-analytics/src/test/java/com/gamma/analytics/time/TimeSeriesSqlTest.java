package com.gamma.analytics.time;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.time.TimeSeriesSql.Fill;
import com.gamma.analytics.time.TimeSeriesSql.Measure;
import com.gamma.analytics.time.TimeSeriesSql.Resample;
import com.gamma.analytics.time.TimeSeriesSql.Shift;
import com.gamma.analytics.time.TimeSeriesSql.Shifted;
import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link TimeSeriesSql}'s resample and shift, executed on DuckDB (ANALYTICS-CORE-1 M5). */
class TimeSeriesSqlTest {

    @TempDir
    static Path spill;
    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
        try (Statement st = conn.createStatement()) {
            st.execute("SET TimeZone = 'Pacific/Chatham'");   // the zoned forms must not depend on it
            st.execute("CREATE TABLE sales (region VARCHAR, ts TIMESTAMP, amt DOUBLE)");
            st.execute("INSERT INTO sales VALUES ('N', '2026-01-05 10:00', 10), ('N', '2026-01-20 11:00', 5),"
                    + " ('N', '2026-03-02 00:00', 7), ('S', '2026-02-14 09:00', 3), (NULL, '2026-01-01 00:00', 1),"
                    + " ('S', NULL, 99)");
            st.execute("CREATE TABLE calls (ts TIMESTAMPTZ)");
            // 2026-10-24 23:30 and 2026-10-25 00:30 in Berlin: one each side of local midnight, both on 24 Oct UTC.
            st.execute("INSERT INTO calls VALUES ('2026-10-24 21:30:00+00'), ('2026-10-24 22:30:00+00'),"
                    + " ('2026-10-27 10:00:00+00')");
        }
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    private static final List<Measure> COUNT_SUM_AVG = List.of(new Measure("COUNT(*)", "n", Fill.ZERO),
            new Measure("SUM(\"amt\")", "total", Fill.forAggregate("sum")),
            new Measure("AVG(\"amt\")", "mean", Fill.forAggregate("avg")));

    @Test
    void monthlyResampleFillsEveryMissingMonthPerKeyWithZeroOrNull() throws Exception {
        String sql = TimeSeriesSql.resample(new Resample("sales", "ts", TimeGrain.MONTH, null, List.of("region"),
                COUNT_SUM_AVG, "month", null, null, 1000));
        assertEquals(List.of(
                "N|2026-01-01 00:00:00|2|15.0|7.5",
                "N|2026-02-01 00:00:00|0|0.0|null",
                "N|2026-03-01 00:00:00|1|7.0|7.0",
                "S|2026-01-01 00:00:00|0|0.0|null",
                "S|2026-02-01 00:00:00|1|3.0|3.0",
                "S|2026-03-01 00:00:00|0|0.0|null",
                "null|2026-01-01 00:00:00|1|1.0|1.0",
                "null|2026-02-01 00:00:00|0|0.0|null",
                "null|2026-03-01 00:00:00|0|0.0|null"), rows(sql),
                "the series spans the data (Jan..Mar) for every key, a NULL key included; the NULL-time row is left out");
    }

    @Test
    void explicitBoundsAreTruncatedAndWidenTheSeries() throws Exception {
        String sql = TimeSeriesSql.resample(new Resample("sales", "ts", TimeGrain.QUARTER, null, List.of(),
                List.of(COUNT_SUM_AVG.get(0)), "q", LocalDateTime.parse("2025-11-30T12:00"),
                LocalDateTime.parse("2026-05-01T00:00"), 1000));
        assertEquals(List.of("2025-10-01 00:00:00|0", "2026-01-01 00:00:00|5", "2026-04-01 00:00:00|0"), rows(sql));
    }

    @Test
    void weeklyBucketsStartOnMonday() throws Exception {
        String sql = TimeSeriesSql.resample(new Resample("sales", "ts", TimeGrain.WEEK, null, List.of(),
                List.of(COUNT_SUM_AVG.get(0)), "wk", null, LocalDateTime.parse("2026-01-19T00:00"), 1000));
        assertEquals(List.of("2025-12-29 00:00:00|1", "2026-01-05 00:00:00|1", "2026-01-12 00:00:00|0",
                "2026-01-19 00:00:00|1"), rows(sql), "2026-01-01 is a Thursday: its week starts Monday 2025-12-29");
    }

    @Test
    void aZonedResampleBucketsByTheStatedZoneWallClock() throws Exception {
        String sql = TimeSeriesSql.resample(new Resample("calls", "ts", TimeGrain.DAY, ZoneId.of("Europe/Berlin"),
                List.of(), List.of(new Measure("COUNT(*)", "n", Fill.ZERO)), "day", null, null, 1000));
        assertEquals(List.of("2026-10-24 00:00:00|1", "2026-10-25 00:00:00|1", "2026-10-26 00:00:00|0",
                "2026-10-27 00:00:00|1"), rows(sql));
        String utc = TimeSeriesSql.resample(new Resample("calls", "ts", TimeGrain.DAY, ZoneId.of("UTC"),
                List.of(), List.of(new Measure("COUNT(*)", "n", Fill.ZERO)), "day", null, null, 1000));
        assertEquals("2026-10-24 00:00:00|2", rows(utc).get(0), "in UTC both calls are on the 24th");
    }

    @Test
    void tooManyBucketsFailsTheRunInsteadOfExploding() {
        String sql = TimeSeriesSql.resample(new Resample("sales", "ts", TimeGrain.MINUTE, null, List.of(),
                List.of(COUNT_SUM_AVG.get(0)), "m", null, null, 10_000));
        SQLException e = assertThrows(SQLException.class, () -> rows(sql));
        assertTrue(e.getMessage().contains("more than 10000 minute buckets"), e.getMessage());
    }

    @Test
    void noRowsAndNoBoundsIsAnEmptySeries() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE empty_sales AS SELECT * FROM sales WHERE false");
        }
        assertEquals(List.of(), rows(TimeSeriesSql.resample(new Resample("empty_sales", "ts", TimeGrain.DAY, null,
                List.of("region"), COUNT_SUM_AVG, "d", null, null, 1000))));
    }

    @Test
    void shiftMatchesByBucketNotByRowSoAGapGivesNull() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE monthly AS SELECT * FROM (VALUES"
                    + " ('N', TIMESTAMP '2026-01-01', 15.0), ('N', TIMESTAMP '2026-03-01', 7.0),"
                    + " ('N', TIMESTAMP '2026-04-01', 8.0), ('S', TIMESTAMP '2026-02-01', 3.0)) v(region, month, total)");
        }
        Shift lag = new Shift("monthly", "month", TimeGrain.MONTH, null, List.of("region"), 1,
                List.of(new Shifted("total", "total_prev")));
        assertEquals("0", rows(TimeSeriesSql.duplicateBucketsSql(lag)).get(0));
        assertEquals(List.of(
                "N|2026-01-01 00:00:00|15.0|null",
                "N|2026-03-01 00:00:00|7.0|null",
                "N|2026-04-01 00:00:00|8.0|7.0",
                "S|2026-02-01 00:00:00|3.0|null"), rows(TimeSeriesSql.shift(lag)),
                "March has no February row, so its previous is NULL, not January's 15");
        Shift lead = new Shift("monthly", "month", TimeGrain.MONTH, null, List.of("region"), -2,
                List.of(new Shifted("total", "total_in_2")));
        assertEquals("N|2026-01-01 00:00:00|15.0|7.0", rows(TimeSeriesSql.shift(lead)).get(0));
        Shift yoy = new Shift("monthly", "month", TimeGrain.YEAR, null, List.of(), 1, List.of(new Shifted("total", "ly")));
        assertNotEquals("0", rows(TimeSeriesSql.duplicateBucketsSql(yoy)).get(0),
                "four rows share the year bucket: the shift must be refused by its caller");
    }

    @Test
    void aZonedShiftComparesBucketsOnTheZoneWallClock() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE daily AS SELECT * FROM (VALUES"
                    + " (TIMESTAMPTZ '2026-10-24 22:00:00+00', 1), (TIMESTAMPTZ '2026-10-25 23:00:00+00', 2)) v(day, n)");
        }
        // Berlin midnights: 25 Oct starts at 22:00Z (CEST), 26 Oct at 23:00Z (CET) — consecutive days, 25 hours apart.
        Shift s = new Shift("daily", "day", TimeGrain.DAY, ZoneId.of("Europe/Berlin"), List.of(), 1,
                List.of(new Shifted("n", "n_prev")));
        List<String> out = rows(TimeSeriesSql.shift(s));
        assertTrue(out.get(1).endsWith("|2|1"), out.toString());
    }

    @Test
    void identifiersAreQuotedAndRecordsRefuseNonsense() {
        String sql = TimeSeriesSql.resample(new Resample("in\"put", "t\"s", TimeGrain.DAY, null, List.of("k\"ey"),
                List.of(new Measure("COUNT(*)", "n\"x", Fill.ZERO)), "b\"k", null, null, 10));
        assertTrue(sql.contains("\"in\"\"put\"") && sql.contains("\"t\"\"s\"") && sql.contains("\"k\"\"ey\"")
                && sql.contains("\"n\"\"x\"") && sql.contains("\"b\"\"k\""), sql);
        assertThrows(IllegalArgumentException.class, () -> new Resample("r", "t", TimeGrain.DAY, null, List.of(),
                List.of(), "b", null, null, 10));
        assertThrows(IllegalArgumentException.class, () -> new Resample("r", "t", TimeGrain.DAY, null, List.of(),
                COUNT_SUM_AVG, "b", LocalDateTime.parse("2026-02-01T00:00"), LocalDateTime.parse("2026-01-01T00:00"), 10));
        assertThrows(IllegalArgumentException.class, () -> new Shift("r", "t", TimeGrain.DAY, null, List.of(), 0,
                List.of(new Shifted("a", "b"))));
        assertEquals(Fill.ZERO, Fill.forAggregate("countDistinct"));
        assertEquals(Fill.NULL, Fill.forAggregate("max"));
    }

    private static final java.time.format.DateTimeFormatter WALL =
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private static List<String> rows(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    Object o = rs.getObject(i);
                    cells.add(o == null ? "null" : o instanceof java.sql.Timestamp ts
                            ? WALL.format(ts.toLocalDateTime()) : String.valueOf(o));
                }
                out.add(String.join("|", cells));
            }
        }
        return out;
    }
}

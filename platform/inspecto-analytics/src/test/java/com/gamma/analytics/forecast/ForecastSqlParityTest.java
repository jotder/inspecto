package com.gamma.analytics.forecast;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.time.TimeGrain;
import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ForecastSql} against {@link Forecaster}: the same series through DuckDB and the JVM give the same rows —
 * every forecast, band and label — within 1e-9 × max(1, |value|) (ANALYTICS-CORE-1 S5).
 */
class ForecastSqlParityTest {

    @TempDir
    static Path spill;
    private static Connection conn;

    /** Three series keyed by region, one of them the NULL key, of different lengths; seeded noise. */
    private static final Map<String, double[]> SERIES = new LinkedHashMap<>();

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
        Random rnd = new Random(20261017);
        int[] lengths = {60, 23, 14};
        String[] keys = {"N", "S", null};
        for (int s = 0; s < keys.length; s++) {
            double[] y = new double[lengths[s]];
            for (int i = 0; i < y.length; i++)
                y[i] = 500 * (s + 1) + 3.5 * i + new double[]{-40, -10, 0, 15, 30, 60, -55}[i % 7] + 12 * rnd.nextGaussian();
            SERIES.put(keys[s], y);
        }
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE daily (region VARCHAR, ds DATE, amount DECIMAL(18, 6))");
            st.execute("CREATE TABLE monthly (m TIMESTAMP, amount DOUBLE)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO daily VALUES (?, ?, ?)")) {
            for (Map.Entry<String, double[]> e : SERIES.entrySet())
                for (int i = 0; i < e.getValue().length; i++) {
                    ps.setString(1, e.getKey());
                    ps.setObject(2, LocalDate.of(2026, 1, 5).plusDays(i));
                    ps.setBigDecimal(3, java.math.BigDecimal.valueOf(e.getValue()[i]).setScale(6, java.math.RoundingMode.HALF_EVEN));
                    ps.executeUpdate();
                }
        }
        // DECIMAL(18, 6) is what the table holds: read it back so both forms see the same doubles.
        try (Statement st = conn.createStatement()) {
            for (String k : new ArrayList<>(SERIES.keySet())) {
                double[] y = SERIES.get(k);
                try (ResultSet rs = st.executeQuery("SELECT CAST(amount AS DOUBLE) FROM daily WHERE region "
                        + (k == null ? "IS NULL" : "= '" + k + "'") + " ORDER BY ds")) {
                    for (int i = 0; rs.next(); i++) y[i] = rs.getDouble(1);
                }
            }
            for (int i = 0; i < 36; i++)
                st.execute("INSERT INTO monthly VALUES (TIMESTAMP '2024-01-01' + INTERVAL (" + i + ") MONTH, "
                        + (1000 + 10 * i + 80 * Math.sin(i * Math.PI / 6)) + ")");
        }
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    private static ForecastSql.Spec daily(Forecaster.Spec model) {
        return new ForecastSql.Spec("daily", "ds", "DATE", TimeGrain.DAY, List.of("region"), "amount", model);
    }

    @Test
    void holtWintersPerKeyAgreesWithTheJvmKernel() throws Exception {
        Forecaster.Spec model = new Forecaster.Spec(ForecastMethod.HOLT_WINTERS, 7, 0.3, 0.05, 0.2, 10,
                List.of(0.8, 0.95));
        assertParity(daily(model), model);
    }

    @Test
    void seasonalNaivePerKeyAgreesWithTheJvmKernel() throws Exception {
        Forecaster.Spec model = new Forecaster.Spec(ForecastMethod.SEASONAL_NAIVE, 7, 0, 0, 0, 16, List.of(0.95));
        assertParity(daily(model), model);
    }

    @Test
    void aSeriesWithNoKeyIsLabelledOnItsGrainAndKeepsItsType() throws Exception {
        Forecaster.Spec model = new Forecaster.Spec(ForecastMethod.HOLT_WINTERS, 12, 0.25, 0.02, 0.15, 3,
                List.of(0.95));
        ForecastSql.Spec spec = new ForecastSql.Spec("monthly", "m", "TIMESTAMP", TimeGrain.MONTH, List.of(), "amount", model);
        List<Object[]> rows = query(ForecastSql.select(spec));
        assertEquals(36 + 3, rows.size());
        assertTrue(rows.get(36)[0].toString().replace('T', ' ').startsWith("2027-01-01 00:00"), String.valueOf(rows.get(36)[0]));
        assertTrue(rows.get(38)[0].toString().replace('T', ' ').startsWith("2027-03-01 00:00"), String.valueOf(rows.get(38)[0]));
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT typeof(m) FROM (" + ForecastSql.select(spec) + ") LIMIT 1")) {
            rs.next();
            assertEquals("TIMESTAMP", rs.getString(1));
        }
        double[] y = new double[36];
        for (int i = 0; i < 36; i++) y[i] = ((Number) rows.get(i)[2]).doubleValue();
        compare(Forecaster.fit(y, model).rows(), rows.stream().map(r -> shift(r, 1)).toList(), "monthly");
    }

    @Test
    void theCheckCountsEveryIrregularity() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE bad (k VARCHAR, ds TIMESTAMP, v DOUBLE)");
            st.execute("INSERT INTO bad VALUES ('gap', '2026-01-01', 1), ('gap', '2026-01-03', 2),"
                    + " ('dup', '2026-01-01', 1), ('dup', '2026-01-01', 2), ('dup', '2026-01-02', 3),"
                    + " ('ok', '2026-01-01', 1), ('ok', '2026-01-02', NULL), ('ok', '2026-01-03 10:00', 4),"
                    + " ('ok', NULL, 5)");
        }
        Forecaster.Spec model = new Forecaster.Spec(ForecastMethod.SEASONAL_NAIVE, 2, 0, 0, 0, 1, List.of(0.95));
        Object[] r = query(ForecastSql.checkSql(new ForecastSql.Spec("bad", "ds", "TIMESTAMP", TimeGrain.DAY,
                List.of("k"), "v", model))).getFirst();
        // series, off_grain, null_time, null_value, irregular_series, min_points, max_points
        assertEquals(List.of(3L, 1L, 1L, 1L, 3L, 2L, 4L), java.util.Arrays.stream(r).map(o -> ((Number) o).longValue()).toList());
        Object[] good = query(ForecastSql.checkSql(daily(model))).getFirst();
        assertEquals(List.of(3L, 0L, 0L, 0L, 0L, 14L, 60L), java.util.Arrays.stream(good).map(o -> ((Number) o).longValue()).toList());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private static void assertParity(ForecastSql.Spec spec, Forecaster.Spec model) throws Exception {
        List<Object[]> rows = query(ForecastSql.select(spec));
        int at = 0;
        // ORDER BY region sorts NULLS LAST: N, S, then the NULL key.
        for (String key : new String[]{"N", "S", null}) {
            List<ForecastOutput.Row> jvm = Forecaster.fit(SERIES.get(key), model).rows();
            List<Object[]> sql = rows.subList(at, at + jvm.size());
            for (Object[] r : sql) assertEquals(key, r[0]);
            compare(jvm, sql.stream().map(r -> shift(r, 2)).toList(), String.valueOf(key));
            LocalDate last = LocalDate.of(2026, 1, 5).plusDays(SERIES.get(key).length - 1);
            assertEquals(last.plusDays(model.horizon()).toString(), sql.getLast()[1].toString());
            at += jvm.size();
        }
        assertEquals(rows.size(), at);
    }

    /** horizon, actual, forecast, level, lower, upper — dropping the key/time columns in front. */
    private static Object[] shift(Object[] r, int lead) {
        return java.util.Arrays.copyOfRange(r, lead, r.length);
    }

    private static void compare(List<ForecastOutput.Row> jvm, List<Object[]> sql, String what) {
        assertEquals(jvm.size(), sql.size(), what);
        for (int i = 0; i < jvm.size(); i++) {
            ForecastOutput.Row j = jvm.get(i);
            Object[] s = sql.get(i);
            String at = what + " row " + i;
            assertEquals(j.horizon(), ((Number) s[0]).intValue(), at);
            close(j.actual(), s[1], at + " actual");
            close(j.forecast(), s[2], at + " forecast");
            close(j.level(), s[3], at + " level");
            close(j.lower(), s[4], at + " lower");
            close(j.upper(), s[5], at + " upper");
        }
    }

    private static void close(Double expected, Object actual, String at) {
        if (expected == null) {
            assertNull(actual, at);
            return;
        }
        assertNotNull(actual, at);
        double a = ((Number) actual).doubleValue();
        assertEquals(expected, a, 1e-9 * Math.max(1, Math.abs(expected)), at);
    }

    private static List<Object[]> query(String sql) throws SQLException {
        List<Object[]> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int i = 0; i < n; i++) row[i] = rs.getObject(i + 1);
                out.add(row);
            }
        }
        return out;
    }
}

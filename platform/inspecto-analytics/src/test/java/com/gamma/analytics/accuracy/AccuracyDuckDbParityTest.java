package com.gamma.analytics.accuracy;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JVM ↔ DuckDB parity for the accuracy measures: {@link AccuracySql} against {@link Accuracy} on the same pairs. */
class AccuracyDuckDbParityTest {

    @TempDir
    static Path spill;
    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    private static final String SQL_BAND = AccuracySql.summary(new AccuracySql.Spec("pairs", "a", "p", "lo", "hi", List.of()));
    private static final String SQL_POINT = AccuracySql.summary(new AccuracySql.Spec("pairs", "a", "p", null, null, List.of()));

    @Test
    void everyMeasureMatchesTheJvm() throws Exception {
        Random r = new Random(20261017);
        for (int n = 1; n <= 60; n++) {
            double[] a = new double[n], p = new double[n], lo = new double[n], hi = new double[n];
            for (int i = 0; i < n; i++) {
                a[i] = r.nextInt(5) == 0 ? 0 : Math.rint(r.nextGaussian() * 1000) / 10;   // some zero actuals
                p[i] = r.nextInt(7) == 0 ? 0 : a[i] + Math.rint(r.nextGaussian() * 200) / 10;
                double w = Math.abs(r.nextGaussian()) * 15;
                lo[i] = p[i] - w;
                hi[i] = p[i] + w;
            }
            load(a, p, lo, hi);
            assertRow(SQL_BAND, true, Accuracy.summary(a, p, lo, hi), "n=" + n);
            assertRow(SQL_POINT, false, Accuracy.summary(a, p), "n=" + n + " point");
        }
    }

    @Test
    void allZeroActualsGiveNullMape() throws Exception {
        double[] a = {0, 0, 0}, p = {1, 0, -2};
        load(a, p, p, p);
        Accuracy.Summary s = Accuracy.summary(a, p);
        assertTrue(s.mape().isEmpty());
        assertRow(SQL_POINT, false, s, "all zero");
    }

    @Test
    void groupsAreMeasuredSeparatelyAndNullPairsAreSkipped() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE g (k VARCHAR, a DOUBLE, p DOUBLE)");
            st.execute("INSERT INTO g VALUES ('x', 10, 12), ('x', 0, 1), ('x', NULL, 3), ('y', 20, 15), ('y', 5, NULL), ('y', 5, 5)");
        }
        String sql = AccuracySql.summary(new AccuracySql.Spec("g", "a", "p", null, null, List.of("k")));
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals("x", rs.getString("k"));
            check(rs, Accuracy.summary(new double[] {10, 0}, new double[] {12, 1}), "x");
            assertTrue(rs.next());
            assertEquals("y", rs.getString("k"));
            check(rs, Accuracy.summary(new double[] {20, 5}, new double[] {15, 5}), "y");
            assertFalse(rs.next());
        }
    }

    @Test
    void anInvertedBandFailsTheStatementAsTheJvmThrows() throws Exception {
        double[] a = {1, 2}, p = {1, 2}, lo = {0, 3}, hi = {2, 2.5};
        load(a, p, lo, hi);
        assertThrows(IllegalArgumentException.class, () -> Accuracy.summary(a, p, lo, hi));
        try (Statement st = conn.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> {
                try (ResultSet rs = st.executeQuery(SQL_BAND)) {
                    rs.next();
                }
            });
            assertTrue(e.getMessage().contains("band lower > upper"), e.getMessage());
        }
    }

    private static void load(double[] a, double[] p, double[] lo, double[] hi) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE pairs (a DOUBLE, p DOUBLE, lo DOUBLE, hi DOUBLE)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO pairs VALUES (?, ?, ?, ?)")) {
            for (int i = 0; i < a.length; i++) {
                ps.setDouble(1, a[i]);
                ps.setDouble(2, p[i]);
                ps.setDouble(3, lo[i]);
                ps.setDouble(4, hi[i]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void assertRow(String sql, boolean band, Accuracy.Summary s, String tag) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            check(rs, s, tag);
            if (band) {
                near(rs.getDouble("coverage"), s.coverage().getAsDouble(), tag + " coverage");
                near(rs.getDouble("mean_interval_width"), s.meanWidth().getAsDouble(), tag + " width");
            }
        }
    }

    private static void check(ResultSet rs, Accuracy.Summary s, String tag) throws Exception {
        assertEquals(s.n(), rs.getLong("n"), tag + " n");
        near(rs.getDouble("mae"), s.mae(), tag + " mae");
        near(rs.getDouble("rmse"), s.rmse(), tag + " rmse");
        double mape = rs.getDouble("mape");
        boolean sqlHasMape = !rs.wasNull();
        assertEquals(s.mape().isPresent(), sqlHasMape, tag + " mape presence");
        if (sqlHasMape) near(mape, s.mape().getAsDouble(), tag + " mape");
        assertEquals(s.mapeZeroActuals(), rs.getLong("mape_zero_actuals"), tag + " zero actuals");
        near(rs.getDouble("smape"), s.smape(), tag + " smape");
        near(rs.getDouble("bias"), s.bias(), tag + " bias");
    }

    private static void near(double sql, double jvm, String tag) {
        assertEquals(jvm, sql, 1e-9 * Math.max(1, Math.abs(jvm)), tag);
    }
}

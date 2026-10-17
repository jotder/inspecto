package com.gamma.analytics.distribution;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.QuantileMode;
import com.gamma.analytics.Stats;
import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OutlierSql} (DuckDB) against the JVM definitions it is built on ({@link Stats#tukeyFences(double[], double,
 * QuantileMode)}, {@link Stats#robustZ(double, double, double, double)}), row by row: groups (a NULL key included),
 * missing values (NULL and NaN), a group with no value, planted outliers, both quantile modes, several k and thresholds.
 */
class OutlierDuckDbParityTest {

    @TempDir
    static Path spill;
    private static Connection conn;
    private static final List<Object[]> ROWS = new ArrayList<>();   // {id, group, value (Double, NaN, or null)}

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
        Random r = new Random(20261017);
        int id = 0;
        for (String g : new String[] {"a", "b", "c", null}) {
            int n = g == null ? 7 : 40 + r.nextInt(30);
            for (int i = 0; i < n; i++) {
                double v = Math.rint((100 + r.nextGaussian() * 15) * 100) / 100;
                if (i % 13 == 5) v = v * 4;                       // planted high
                if (i % 17 == 3) v = -v;                          // planted low
                if ("c".equals(g)) v = Math.rint(v / 10) * 10;    // heavy ties
                Double cell = i % 11 == 7 ? null : (i % 19 == 9 ? Double.NaN : v);
                ROWS.add(new Object[] {id++, g, cell});
            }
        }
        ROWS.add(new Object[] {id++, "empty", null});
        ROWS.add(new Object[] {id++, "empty", Double.NaN});
        ROWS.add(new Object[] {id++, "one", 5.0});
        ROWS.add(new Object[] {id, "flat", 3.0});
        ROWS.add(new Object[] {++id, "flat", 3.0});
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t(id INTEGER, g VARCHAR, x DOUBLE)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?, ?, ?)")) {
            for (Object[] row : ROWS) {
                ps.setInt(1, (Integer) row[0]);
                ps.setString(2, (String) row[1]);
                if (row[2] == null) ps.setNull(3, java.sql.Types.DOUBLE);
                else ps.setDouble(3, (Double) row[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    @Test
    void iqrFencesAndFlagsMatchTheJvmPerGroupInBothQuantileModes() throws Exception {
        int flagged = 0;
        for (QuantileMode mode : new QuantileMode[] {QuantileMode.CONTINUOUS, QuantileMode.DISCRETE})
            for (double k : new double[] {0, 1.5, 3, 0.75}) {
                OutlierSql.Spec spec = new OutlierSql.Spec("x", List.of("g"), OutlierSql.Method.IQR, k, mode, 3.5, 1,
                        "is_outlier", "lo", "hi", "z");
                Map<String, double[]> groups = groupValues(true);
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT id, g, x, lo, hi, is_outlier FROM ("
                             + OutlierSql.select("t", spec) + ") ORDER BY id")) {
                    int seen = 0;
                    while (rs.next()) {
                        seen++;
                        String tag = mode + " k=" + k + " id=" + rs.getInt(1);
                        double[] values = groups.get(rs.getString(2));
                        double x = rs.getDouble(3);
                        boolean missing = rs.wasNull() || Double.isNaN(x);
                        double lo = rs.getDouble(4);
                        if (values.length == 0) {
                            assertTrue(rs.wasNull(), tag + " no value, no fence");
                            rs.getObject(6);
                            assertTrue(rs.wasNull(), tag + " no flag");
                            continue;
                        }
                        Stats.Fences f = Stats.tukeyFences(values, k, mode);
                        assertEquals(f.lower(), lo, 1e-9 * Math.max(1, Math.abs(lo)), tag + " lower");
                        assertEquals(f.upper(), rs.getDouble(5), 1e-9 * Math.max(1, Math.abs(f.upper())), tag + " upper");
                        boolean flag = rs.getBoolean(6);
                        if (missing) assertTrue(rs.wasNull(), tag + " a missing value has no flag");
                        else {
                            assertEquals(f.outside(x), flag, tag + " flag of " + x);
                            if (flag) flagged++;
                        }
                    }
                    assertEquals(ROWS.size(), seen, "every row kept");
                }
            }
        assertTrue(flagged > 20, "the planted outliers are flagged: " + flagged);
    }

    @Test
    void robustZScoresAndFlagsMatchTheJvmPerGroup() throws Exception {
        for (double unit : new double[] {1, 0.01})
            for (double threshold : new double[] {3.5, 2}) {
                OutlierSql.Spec spec = new OutlierSql.Spec("x", List.of("g"), OutlierSql.Method.ROBUST_Z, 1.5,
                        QuantileMode.CONTINUOUS, threshold, unit, "is_outlier", "lo", "hi", "z");
                Map<String, double[]> groups = groupValues(true);
                int flagged = 0;
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT id, g, x, z, is_outlier FROM (" + OutlierSql.select("t", spec)
                             + ") ORDER BY id")) {
                    while (rs.next()) {
                        String tag = "unit=" + unit + " t=" + threshold + " id=" + rs.getInt(1);
                        double[] values = groups.get(rs.getString(2));
                        double x = rs.getDouble(3);
                        boolean missing = rs.wasNull() || Double.isNaN(x);
                        double z = rs.getDouble(4);
                        if (missing || values.length == 0) {
                            assertTrue(rs.wasNull(), tag + " a missing value has no score");
                            rs.getObject(5);
                            assertTrue(rs.wasNull(), tag + " nor a flag");
                            continue;
                        }
                        double m = Stats.median(values);
                        double jvm = Stats.robustZ(x, m, Stats.scaledMad(values, m), Stats.robustZFloor(m, unit));
                        assertEquals(jvm, z, 1e-9 * Math.max(1, Math.abs(jvm)), tag + " z");
                        assertEquals(Math.abs(jvm) > threshold, rs.getBoolean(5), tag + " flag");
                        if (rs.getBoolean(5)) flagged++;
                    }
                }
                assertTrue(flagged > 5, "planted outliers flagged at unit " + unit + ": " + flagged);
            }
    }

    @Test
    void withoutGroupsTheWholeColumnIsOneGroupAndColumnsAreAdded() throws Exception {
        OutlierSql.Spec spec = new OutlierSql.Spec("x", List.of(), OutlierSql.Method.IQR, 1.5, QuantileMode.CONTINUOUS,
                3.5, 1, "flag", "fence_lo", "fence_hi", "unused");
        assertEquals(List.of("fence_lo", "fence_hi", "flag"), spec.addedColumns());
        double[] all = groupValues(false).get("*");
        Stats.Fences f = Stats.tukeyFences(all);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT fence_lo, fence_hi FROM (" + OutlierSql.select("t", spec) + ")")) {
            assertTrue(rs.next());
            assertEquals(f.lower(), rs.getDouble(1), 1e-9 * Math.max(1, Math.abs(f.lower())));
            assertEquals(f.upper(), rs.getDouble(2), 1e-9 * Math.max(1, Math.abs(f.upper())));
            assertFalse(rs.next(), "one pair of fences for the whole column");
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("DESCRIBE " + OutlierSql.select("t", spec))) {
            List<String> cols = new ArrayList<>();
            while (rs.next()) cols.add(rs.getString(1) + ":" + rs.getString(2));
            assertEquals(List.of("id:INTEGER", "g:VARCHAR", "x:DOUBLE", "fence_lo:DOUBLE", "fence_hi:DOUBLE",
                    "flag:BOOLEAN"), cols, "inbound columns kept as they were, scratch columns gone");
        }
    }

    @Test
    void theSpecRefusesWhatHasNoMeaning() {
        assertThrows(IllegalArgumentException.class, () -> new OutlierSql.Spec("x", List.of(), OutlierSql.Method.IQR,
                -1, QuantileMode.CONTINUOUS, 3.5, 1, "f", "l", "u", "s"));
        assertThrows(IllegalArgumentException.class, () -> new OutlierSql.Spec("x", List.of(), OutlierSql.Method.ROBUST_Z,
                1.5, QuantileMode.CONTINUOUS, 0, 1, "f", "l", "u", "s"));
        assertThrows(IllegalArgumentException.class, () -> new OutlierSql.Spec("x", List.of(), OutlierSql.Method.ROBUST_Z,
                1.5, QuantileMode.CONTINUOUS, 3.5, 0, "f", "l", "u", "s"));
    }

    /** Group key ("" for NULL) → its non-missing values; {@code grouped=false}: everything under "*". */
    private static Map<String, double[]> groupValues(boolean grouped) {
        Map<String, List<Double>> m = new LinkedHashMap<>();
        for (Object[] row : ROWS) {
            String key = grouped ? Objects.toString(row[1], null) : "*";
            List<Double> list = m.computeIfAbsent(key, x -> new ArrayList<>());
            if (row[2] != null && !Double.isNaN((Double) row[2])) list.add((Double) row[2]);
        }
        Map<String, double[]> out = new java.util.HashMap<>();
        m.forEach((k, v) -> out.put(k, v.stream().mapToDouble(Double::doubleValue).toArray()));
        return out;
    }
}

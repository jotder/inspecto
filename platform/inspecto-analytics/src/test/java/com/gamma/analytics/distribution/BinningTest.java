package com.gamma.analytics.distribution;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.ColumnProfiler;
import com.gamma.analytics.QuantileMode;
import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
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
 * {@link Binning}: hand figures for the JVM form, then the DuckDB form against it row by row (equal width over the
 * group's range and over a stated one with out-of-range values; quantile bins in both modes with heavy ties; NULL and
 * NaN; a NULL group key; a group of one value and a constant group), and the histogram's counts against the same bins.
 */
class BinningTest {

    @TempDir
    static Path spill;
    private static Connection conn;
    private static final List<Object[]> ROWS = new ArrayList<>();   // {id, group, value (Double, NaN, or null)}

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
        Random r = new Random(17);
        int id = 0;
        for (String g : new String[] {"a", "b", null}) {
            int n = 30 + r.nextInt(40);
            for (int i = 0; i < n; i++) {
                double v = "b".equals(g) ? r.nextInt(6) : Math.rint(r.nextGaussian() * 2000) / 100;   // b: heavy ties
                Double cell = i % 9 == 4 ? null : (i % 23 == 11 ? Double.NaN : v);
                ROWS.add(new Object[] {id++, g, cell});
            }
        }
        ROWS.add(new Object[] {id++, "one", 7.5});
        ROWS.add(new Object[] {id++, "flat", 2.0});
        ROWS.add(new Object[] {id++, "flat", 2.0});
        ROWS.add(new Object[] {id, "none", null});
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

    // ---- JVM hand figures ----------------------------------------------------------------------------------------

    @Test
    void equalWidthBinsAreHalfOpenWithTheLastClosed() {
        assertEquals(0, Binning.equalWidthIndex(0, 0, 10, 5));
        assertEquals(0, Binning.equalWidthIndex(1.99, 0, 10, 5));
        assertEquals(1, Binning.equalWidthIndex(2, 0, 10, 5), "an edge opens the next bin");
        assertEquals(4, Binning.equalWidthIndex(10, 0, 10, 5), "the maximum is in the last bin");
        assertEquals(-1, Binning.equalWidthIndex(10.01, 0, 10, 5), "above the stated range: no bin");
        assertEquals(-1, Binning.equalWidthIndex(-0.01, 0, 10, 5), "below: no bin");
        assertEquals(0, Binning.equalWidthIndex(3, 3, 3, 4), "every value equal: bin 0");
        assertArrayEquals(new double[] {0, 2, 4, 6, 8, 10}, Binning.equalWidthEdges(0, 10, 5), 0.0);
    }

    @Test
    void quantileBinsCountTheInteriorEdgesAtOrBelowTheValue() {
        double[] v = {1, 2, 3, 4, 5, 6, 7, 8};
        double[] e = Binning.quantileEdges(v, 4, QuantileMode.CONTINUOUS);   // h = 7q
        assertArrayEquals(new double[] {1, 2.75, 4.5, 6.25, 8}, e, 1e-12);
        assertEquals(0, Binning.quantileIndex(1, e));
        assertEquals(0, Binning.quantileIndex(2.7, e));
        assertEquals(1, Binning.quantileIndex(2.75, e), "a value equal to an edge goes to the higher bin");
        assertEquals(3, Binning.quantileIndex(8, e), "the maximum is in the last bin");
        double[] ties = {5, 5, 5, 5, 9};
        double[] te = Binning.quantileEdges(ties, 2, QuantileMode.CONTINUOUS);   // {5, 5, 9}
        assertEquals(1, Binning.quantileIndex(5, te), "ties share one bin (the higher), leaving bin 0 empty");
        assertEquals(1, Binning.quantileIndex(9, te));
        assertArrayEquals(new double[] {1, 2, 4, 6, 8}, Binning.quantileEdges(v, 4, QuantileMode.DISCRETE), 0.0);
    }

    @Test
    void theSpecRefusesWhatHasNoMeaning() {
        assertThrows(IllegalArgumentException.class, () -> spec(Binning.Method.EQUAL_WIDTH, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> spec(Binning.Method.EQUAL_WIDTH, Binning.MAX_BINS + 1, null, null));
        assertThrows(IllegalArgumentException.class, () -> spec(Binning.Method.QUANTILE, 4, 0.0, null), "range: equal width only");
        assertThrows(IllegalArgumentException.class, () -> spec(Binning.Method.EQUAL_WIDTH, 4, 5.0, 5.0));
    }

    // ---- DuckDB = JVM ---------------------------------------------------------------------------------------------

    @Test
    void equalWidthOverTheGroupRangeMatchesTheJvm() throws Exception {
        for (int bins : new int[] {1, 3, 10})
            check(spec(Binning.Method.EQUAL_WIDTH, bins, null, null));
    }

    @Test
    void equalWidthOverAStatedRangeLeavesOutOfRangeValuesWithoutABin() throws Exception {
        int outside = check(spec(Binning.Method.EQUAL_WIDTH, 4, -10.0, 10.0));
        assertTrue(outside > 5, "values outside [-10, 10] got no bin: " + outside);
        check(spec(Binning.Method.EQUAL_WIDTH, 4, 0.0, null));
        check(spec(Binning.Method.EQUAL_WIDTH, 4, null, 3.0));
    }

    @Test
    void quantileBinsMatchTheJvmInBothModes() throws Exception {
        for (QuantileMode mode : new QuantileMode[] {QuantileMode.CONTINUOUS, QuantileMode.DISCRETE})
            for (int bins : new int[] {1, 2, 3, 4, 10})
                check(new Binning.Spec("x", List.of("g"), Binning.Method.QUANTILE, bins, mode, null, null, "bin", "label"));
    }

    @Test
    void theHistogramCountsTheSameEqualWidthBins() throws Exception {
        Binning.Spec spec = new Binning.Spec("x", List.of(), Binning.Method.EQUAL_WIDTH, 7, QuantileMode.CONTINUOUS,
                null, null, "bin", "label");
        Map<Long, Long> fromBins = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT bin, count(*) FROM (" + Binning.select("t", spec)
                     + ") WHERE bin IS NOT NULL GROUP BY bin")) {
            while (rs.next()) fromBins.put(rs.getLong(1), rs.getLong(2));
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(ColumnProfiler.histogram("(SELECT nullif(x, CAST('NaN' AS DOUBLE)) AS x FROM t) h",
                     "x", 7))) {
            int rows = 0;
            while (rs.next()) {
                rows++;
                assertEquals(fromBins.getOrDefault(rs.getLong("bin"), 0L), rs.getLong("frequency"), "bin " + rs.getLong("bin"));
            }
            assertEquals(7, rows);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Binning.Spec spec(Binning.Method method, int bins, Double min, Double max) {
        return new Binning.Spec("x", List.of("g"), method, bins, QuantileMode.CONTINUOUS, min, max, "bin", "label");
    }

    /** Runs the spec and compares every row with the JVM form; returns how many present values got no bin. */
    private static int check(Binning.Spec spec) throws Exception {
        Map<String, double[]> groups = groupValues();
        int noBin = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, g, x, bin, label FROM (" + Binning.select("t", spec) + ") ORDER BY id")) {
            int seen = 0;
            while (rs.next()) {
                seen++;
                String tag = spec.method() + "/" + spec.quantileMode() + " bins=" + spec.bins() + " id=" + rs.getInt(1);
                double[] values = groups.get(rs.getString(2));
                double x = rs.getDouble(3);
                boolean missing = rs.wasNull() || Double.isNaN(x);
                long bin = rs.getLong(4);
                boolean noIndex = rs.wasNull();
                String label = rs.getString(5);
                if (missing) {
                    assertTrue(noIndex && label == null, tag + " a missing value has no bin");
                    continue;
                }
                double[] edges;
                int expected;
                if (spec.method() == Binning.Method.EQUAL_WIDTH) {
                    double lo = spec.rangeMin() != null ? spec.rangeMin() : java.util.Arrays.stream(values).min().orElseThrow();
                    double hi = spec.rangeMax() != null ? spec.rangeMax() : java.util.Arrays.stream(values).max().orElseThrow();
                    expected = Binning.equalWidthIndex(x, lo, hi, spec.bins());
                    edges = Binning.equalWidthEdges(lo, hi, spec.bins());
                } else {
                    edges = Binning.quantileEdges(values, spec.bins(), spec.quantileMode());
                    expected = Binning.quantileIndex(x, edges);
                }
                if (expected < 0) {
                    assertTrue(noIndex && label == null, tag + " out of range: no bin, no label");
                    noBin++;
                    continue;
                }
                assertFalse(noIndex, tag + " has a bin");
                assertEquals(expected, bin, tag + " bin of " + x);
                boolean last = expected == spec.bins() - 1;
                // the label's edges: DuckDB's quantile_cont may differ from the JVM's by an ulp, so compare as numbers
                assertTrue(label.startsWith("[") && label.endsWith(last ? "]" : ")"), tag + " label " + label);
                String[] parts = label.substring(1, label.length() - 1).split(", ");
                closeTo(edges[expected], Double.parseDouble(parts[0]), tag + " label lower");
                closeTo(edges[expected + 1], Double.parseDouble(parts[1]), tag + " label upper");
            }
            assertEquals(ROWS.size(), seen, "every row kept");
        }
        return noBin;
    }

    private static void closeTo(double jvm, double duck, String what) {
        assertEquals(jvm, duck, 1e-9 * Math.max(1, Math.abs(jvm)), what);
    }

    private static Map<String, double[]> groupValues() {
        Map<String, List<Double>> m = new LinkedHashMap<>();
        for (Object[] row : ROWS) {
            List<Double> list = m.computeIfAbsent(Objects.toString(row[1], null), x -> new ArrayList<>());
            if (row[2] != null && !Double.isNaN((Double) row[2])) list.add((Double) row[2]);
        }
        Map<String, double[]> out = new HashMap<>();
        m.forEach((k, v) -> out.put(k, v.stream().mapToDouble(Double::doubleValue).toArray()));
        return out;
    }
}

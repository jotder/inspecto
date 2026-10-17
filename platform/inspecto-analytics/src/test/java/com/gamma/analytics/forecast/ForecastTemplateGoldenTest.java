package com.gamma.analytics.forecast;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.util.DuckDbUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The golden test of the forecast kernel: the {@code business-assurance} Space Template's Holt-Winters SQL is the
 * reference. Its view ({@code ba_revenue_forecast_view.toon}) is read as shipped and run on DuckDB over its own
 * synthetic corpus (182 days of daily revenue, weekly season, a -400 spike planted on 2026-06-05).
 *
 * <p>The template adds three guards to plain Holt-Winters: a day outside its band updates the state with the forecast
 * instead of the actual (outlier replacement), three such days in a row reset the level (regime change), and a CUSUM
 * on the errors bends the trend (drift). The kernel is plain Holt-Winters, so:
 * <ol>
 *   <li>with the guards switched off (z and the CUSUM threshold set to 1e9 in the template's parameter row), the
 *       template's forecast equals the kernel's on every one of its 175 days, within 0.005 — the template rounds to two
 *       decimals;</li>
 *   <li>as shipped, they are equal on every day up to and including the first day the template flags (the spike), and
 *       differ after it, because from then on the template's state was fed its forecast instead of the spike.</li>
 * </ol>
 * The band is not compared: the template's sigma is the scaled MAD of the 7-day differences / √2 with a fixed z = 4;
 * the kernel's is the scaled MAD of its own one-step errors with z from the level ({@link PredictionInterval}).
 */
class ForecastTemplateGoldenTest {

    private static final Path VIEW = Path.of("..", "..", "spaces", "_templates", "business-assurance", "config", "views",
            "ba_revenue_forecast_view.toon").toAbsolutePath().normalize();
    /** The template's ROUND(f, 2), plus floating-point room. */
    private static final double TOLERANCE = 0.005 + 1e-9;
    private static final Forecaster.Spec TEMPLATE = new Forecaster.Spec(ForecastMethod.HOLT_WINTERS, 7,
            Forecaster.DEFAULT_ALPHA, Forecaster.DEFAULT_BETA, Forecaster.DEFAULT_GAMMA, 1, List.of(0.95));

    @TempDir
    static Path spill;
    private static Connection conn;
    private static String viewSql;
    private static double[] revenue;

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
        Matcher m = Pattern.compile("(?m)^derived_sql: \"(.*)\"\\s*$").matcher(Files.readString(VIEW, StandardCharsets.UTF_8));
        assertTrue(m.find(), "derived_sql in " + VIEW);
        viewSql = m.group(1);
        assertFalse(viewSql.contains("\\"), "no TOON escapes to undo");
        // The corpus: the view's own daily_revenue CTE, read on its own.
        String corpus = viewSql.substring(0, viewSql.indexOf(") SELECT * FROM (") + 1)
                + " SELECT revenue FROM daily_revenue ORDER BY ds";
        List<Double> ys = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(corpus)) {
            while (rs.next()) ys.add(rs.getDouble(1));
        }
        assertEquals(182, ys.size());
        revenue = ys.stream().mapToDouble(Double::doubleValue).toArray();
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    /** {@code [forecast, outside_band, regime_change, drift_change]} per template row; row i is day t = i + 8. */
    private static List<double[]> template(String sql) throws Exception {
        List<double[]> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next())
                out.add(new double[]{rs.getDouble("forecast"), rs.getInt("outside_band"), rs.getInt("regime_change"),
                        rs.getInt("drift_change")});
        }
        return out;
    }

    @Test
    void withItsGuardsOffTheTemplateIsTheKernelOnEveryDay() throws Exception {
        String off = viewSql.replace("4.0 AS z", "1e9 AS z").replace("8.0 AS cusum_h", "1e9 AS cusum_h");
        assertNotEquals(viewSql, off, "the parameter row was found");
        List<double[]> rows = template(off);
        double[] fitted = Forecaster.fit(revenue, TEMPLATE).fitted();
        assertEquals(182 - 7, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(0, rows.get(i)[1] + rows.get(i)[2] + rows.get(i)[3], "no guard fires on day " + (i + 8));
            assertEquals(rows.get(i)[0], fitted[i + 7], TOLERANCE, "day " + (i + 8));
        }
    }

    @Test
    void asShippedTheyAgreeUntilTheTemplatesOutlierGuardFirstFeedsItsForecast() throws Exception {
        List<double[]> rows = template(viewSql);
        double[] fitted = Forecaster.fit(revenue, TEMPLATE).fitted();
        int firstFlag = -1;
        for (int i = 0; i < rows.size() && firstFlag < 0; i++)
            if (rows.get(i)[1] + rows.get(i)[2] + rows.get(i)[3] > 0) firstFlag = i;
        assertEquals(151, firstFlag + 8, "the planted spike (2026-06-05) is the first flagged day");
        for (int i = 0; i <= firstFlag; i++)
            assertEquals(rows.get(i)[0], fitted[i + 7], TOLERANCE, "day " + (i + 8));
        assertTrue(Math.abs(rows.get(firstFlag + 1)[0] - fitted[firstFlag + 8]) > 1,
                "the day after the spike differs: the template updated on its forecast, plain Holt-Winters on the spike");
    }
}

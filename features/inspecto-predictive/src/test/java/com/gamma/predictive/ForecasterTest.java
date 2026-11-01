package com.gamma.predictive;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden answers for the forecast maths: synthetic series whose forecast, backtest error and interval are known in
 * closed form or computed by hand (exact binary fractions), the two-season sufficiency boundary, and rows that
 * recompute from their own explanation.
 */
class ForecasterTest {

    private static final LocalDate D0 = LocalDate.parse("2026-06-03");

    private static ForecastModel model(String method, int horizon, Integer interval) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityType", "product");
        m.put("dataset", "sales");
        m.put("key", "sku");
        m.put("time", "sold_at");
        m.put("measure", "count");
        m.put("grain", "day");
        m.put("method", method);
        m.put("horizon", horizon);
        if (interval != null) m.put("interval", interval);
        if ("holt-winters".equals(method)) {
            m.put("alpha", 0.5);
            m.put("beta", 0.3);
            m.put("gamma", 0.2);
        }
        return ForecastModel.fromMap("m", m);
    }

    private static Forecaster.Forecast run(ForecastModel model, double[] y) {
        return Forecaster.forecast(model, new Forecaster.Observed("s", D0, y));
    }

    private static void assertRecomputes(Forecaster.Forecast f) {
        for (Forecaster.Point p : f.points()) {
            Forecaster.Recomputed r = Forecaster.recompute(p.step(), p.explanation());
            assertEquals(p.forecast(), r.forecast(), 1e-9, "step " + p.step());
            assertEquals(p.lower(), r.lower(), 1e-9, "step " + p.step());
            assertEquals(p.upper(), r.upper(), 1e-9, "step " + p.step());
        }
    }

    @Test
    void seasonalNaiveRepeatsAnExactSeasonWithZeroErrorAndAZeroWidthInterval() {
        double[] season = {10, 20, 30, 40, 50, 60, 70};
        double[] y = new double[21];
        for (int t = 0; t < y.length; t++) y[t] = season[t % 7];
        Forecaster.Forecast f = run(model("seasonal-naive", 10, null), y);
        assertFalse(f.isInsufficient());
        assertEquals(10, f.points().size());
        for (Forecaster.Point p : f.points()) {
            double expected = season[(21 + p.step() - 1) % 7];
            assertEquals(expected, p.forecast(), 0.0, "step " + p.step());
            assertEquals(expected, p.lower(), 0.0);
            assertEquals(expected, p.upper(), 0.0);
            assertEquals(D0.plusDays(20 + p.step()), p.bucket(), "the bucket after the last history bucket");
        }
        assertEquals(7, f.backtest().holdout(), "min(horizon 10, season 7)");
        assertEquals(0.0, f.backtest().mae(), 0.0);
        assertNull(f.backtest().relMae(), "seasonal-naive's own hold-out MAE is 0: no ratio");
        assertRecomputes(f);
    }

    @Test
    void seasonalNaiveBacktestAndIntervalOnAHandComputedSeries() {
        // season 1: all 10; season 2: 11 9 11 9 11 9 10 -> hold-out errors 1 -1 1 -1 1 -1 0
        double[] y = {10, 10, 10, 10, 10, 10, 10, 11, 9, 11, 9, 11, 9, 10};
        Forecaster.Forecast f = run(model("seasonal-naive", 9, 80), y);
        Forecaster.Backtest bt = f.backtest();
        assertEquals(7, bt.holdout());
        assertEquals(6.0 / 7, bt.mae(), 1e-12);
        assertEquals(Math.sqrt(6.0 / 7), bt.rmse(), 1e-12);
        assertEquals(100 * (3 * 2.0 / 21 + 3 * 2.0 / 19) / 7, bt.smape(), 1e-9, "sMAPE in percent");
        assertEquals(1.0, bt.relMae(), 1e-12, "the method IS seasonal-naive");
        double sigma = Math.sqrt(6.0 / 7), z80 = 1.2815515655446004;
        double[] second = {11, 9, 11, 9, 11, 9, 10};
        for (Forecaster.Point p : f.points()) {
            double expected = second[(p.step() - 1) % 7];
            int seasonsAhead = p.step() <= 7 ? 1 : 2;
            assertEquals(expected, p.forecast(), 0.0);
            assertEquals(expected - z80 * sigma * Math.sqrt(seasonsAhead), p.lower(), 1e-12, "step " + p.step());
            assertEquals(expected + z80 * sigma * Math.sqrt(seasonsAhead), p.upper(), 1e-12, "step " + p.step());
        }
        assertRecomputes(f);
    }

    @Test
    void holtWintersReproducesTrendPlusSeasonExactlyAndBeatsSeasonalNaive() {
        double[] s = {-3, -2, -1, 0, 1, 2, 3};
        double[] y = new double[28];
        for (int t = 0; t < y.length; t++) y[t] = 100 + 2 * t + s[t % 7];
        Forecaster.Forecast hw = run(model("holt-winters", 14, null), y);
        for (Forecaster.Point p : hw.points()) {
            int t = 27 + p.step();
            assertEquals(100 + 2 * t + s[t % 7], p.forecast(), 1e-9, "step " + p.step());
            assertEquals(p.forecast(), p.lower(), 1e-9, "zero backtest error: zero width");
        }
        assertEquals(0.0, hw.backtest().mae(), 1e-9);
        assertEquals(0.0, hw.backtest().relMae(), 1e-9, "seasonal-naive misses the trend: 14 a bucket");
        assertRecomputes(hw);

        Forecaster.Forecast naive = run(model("seasonal-naive", 14, null), y);
        assertEquals(14.0, naive.backtest().mae(), 1e-12, "the trend over one season: 2 x 7");
        assertEquals(14.0, naive.backtest().rmse(), 1e-12);
        assertEquals(1.959963984540054 * 14 * Math.sqrt(2),
                naive.points().get(13).upper() - naive.points().get(13).forecast(), 1e-9, "step 14 is two seasons ahead");
    }

    @Test
    void holtWintersRecursionMatchesAHandComputation() {
        // m 2, y 2 4 3 7 5, α β γ 0.5: init L 3.5 T 1 S -0.5 0.5; t2 L 4 T 0.75 S0 -0.75; t3 L 5.625 T 1.1875
        // S1 0.9375; t4 L 6.28125 T 0.921875 S0 -1.015625 -> step 1 = 8.140625, step 2 = 7.109375
        Forecaster.Fit fit = Forecaster.holtWinters(new double[]{2, 4, 3, 7, 5}, 5, 2, 0.5, 0.5, 0.5);
        assertEquals(6.28125, fit.level(), 0.0);
        assertEquals(0.921875, fit.trend(), 0.0);
        assertArrayEquals(new double[]{0.9375, -1.015625}, fit.season(), 0.0);
        assertEquals(8.140625, fit.at(1), 0.0);
        assertEquals(7.109375, fit.at(2), 0.0);
        assertEquals(6.28125 + 3 * 0.921875 + 0.9375, fit.at(3), 0.0, "the season repeats past m");
    }

    @Test
    void underTwoSeasonsIsInsufficientNeverZero() {
        ForecastModel m = model("holt-winters", 5, null);
        Forecaster.Forecast f = run(m, new double[13]);
        assertTrue(f.isInsufficient());
        assertNull(f.backtest());
        assertTrue(f.points().isEmpty(), "no forecast row, never a 0");
        assertEquals(Map.of("insufficient", "history", "historyBuckets", 13, "minHistoryBuckets", 14), f.insufficient());
        assertFalse(run(m, new double[14]).isInsufficient(), "exactly two seasons is enough");
    }

    @Test
    void aRowWhoseNumbersDisagreeWithItsExplanationDoesNotRecompute() {
        double[] y = {10, 10, 10, 10, 10, 10, 10, 11, 9, 11, 9, 11, 9, 10};
        Forecaster.Point p = run(model("seasonal-naive", 3, null), y).points().get(0);
        Map<String, Object> tampered = new LinkedHashMap<>(p.explanation());
        tampered.put("season", 12.0);
        assertNotEquals(p.forecast(), Forecaster.recompute(p.step(), tampered).forecast(), 1e-9);
        tampered.remove("sigma");
        assertThrows(IllegalArgumentException.class, () -> Forecaster.recompute(p.step(), tampered));
    }
}

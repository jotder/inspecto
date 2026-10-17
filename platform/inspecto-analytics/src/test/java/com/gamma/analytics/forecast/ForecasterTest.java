package com.gamma.analytics.forecast;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link Forecaster} and {@link PredictionInterval}: hand-computed figures and the input rules (ANALYTICS-CORE-1 S5). */
class ForecasterTest {

    private static final double EPS = 1e-12;

    private static Forecaster.Spec hw(int period, double a, double b, double g, int horizon, List<Double> levels) {
        return new Forecaster.Spec(ForecastMethod.HOLT_WINTERS, period, a, b, g, horizon, levels);
    }

    private static Forecaster.Spec naive(int period, int horizon) {
        return new Forecaster.Spec(ForecastMethod.SEASONAL_NAIVE, period, 0, 0, 0, horizon, List.of(0.95));
    }

    @Test
    void holtWintersFollowsTheWrittenRecursionByHand() {
        // m = 2: L = 15, B = (19 - 15) / 2 = 2, S = [-5, 5]; then three updates with alpha = beta = gamma = 0.5.
        Forecaster.Result r = Forecaster.fit(new double[]{10, 20, 14, 24, 18}, hw(2, 0.5, 0.5, 0.5, 2, List.of(0.95)));
        assertTrue(Double.isNaN(r.fitted()[0]) && Double.isNaN(r.fitted()[1]), "the first season has no forecast");
        assertEquals(12.0, r.fitted()[2], EPS);
        assertEquals(25.5, r.fitted()[3], EPS);
        assertEquals(17.375, r.fitted()[4], EPS);
        assertEquals(29.09375, r.ahead()[0], EPS);   // L 22.1875 + B 2.28125 + S(2) 4.625
        assertEquals(22.40625, r.ahead()[1], EPS);   // L + 2B + S(1) -4.34375
        // errors 2, -1.5, 0.625: median 0.625, |dev| 1.375, 2.125, 0 -> MAD 1.375
        assertEquals(1.4826 * 1.375, r.sigma(), EPS);
    }

    @Test
    void seasonalNaiveRepeatsTheBucketOneSeasonEarlier() {
        Forecaster.Result r = Forecaster.fit(new double[]{10, 20, 13, 24, 18}, naive(2, 3));
        assertArrayEquals(new double[]{Double.NaN, Double.NaN, 10, 20, 13}, r.fitted(), EPS);
        assertArrayEquals(new double[]{24, 18, 24}, r.ahead(), EPS);
        assertEquals(1.4826, r.sigma(), EPS);    // errors 3, 4, 5 -> MAD 1
    }

    @Test
    void theIntervalWidensWithTheHorizonByEachMethodsVarianceFactor() {
        assertArrayEquals(new double[]{1, 1.5625, 3.125},
                PredictionInterval.varianceFactors(hw(2, 0.5, 0.5, 0.5, 3, List.of(0.95))), EPS);
        assertArrayEquals(new double[]{1, 1, 2, 2, 3}, PredictionInterval.varianceFactors(naive(2, 5)), EPS);
        assertEquals(1.959963984540054, PredictionInterval.z(0.95), 1e-8);
        assertEquals(1.2815515655446004, PredictionInterval.z(0.80), 1e-8);
        assertEquals(2.5758293035489004, PredictionInterval.z(0.99), 1e-8);
        assertEquals(-3.090232306167813, PredictionInterval.normalQuantile(0.001), 1e-8);
        assertEquals(0.0, PredictionInterval.normalQuantile(0.5), EPS);
    }

    @Test
    void rowsCarryEveryBucketAndTheHorizonOncePerLevel() {
        Forecaster.Result r = Forecaster.fit(new double[]{10, 20, 13, 24, 18}, new Forecaster.Spec(
                ForecastMethod.SEASONAL_NAIVE, 2, 0, 0, 0, 3, List.of(0.8, 0.95)));
        List<ForecastOutput.Row> rows = r.rows();
        assertEquals((5 + 3) * 2, rows.size());
        assertEquals(new ForecastOutput.Row(1, 0, 10.0, null, 0.8, null, null), rows.get(0));
        ForecastOutput.Row inSample = rows.get(5);   // bucket 3, level 0.95
        assertEquals(3, inSample.position());
        assertEquals(0.95, inSample.level());
        assertEquals(10 - 1.4826 * PredictionInterval.z(0.95), inSample.lower(), EPS);
        ForecastOutput.Row last = rows.get(15);      // bucket 8 = n + 3, level 0.95, v(3) = 2
        assertEquals(3, last.horizon());
        assertNull(last.actual());
        assertEquals(24.0, last.forecast());
        assertEquals(24 + PredictionInterval.z(0.95) * 1.4826 * Math.sqrt(2), last.upper(), EPS);
    }

    @Test
    void theHorizonBandMatchesTheRowsSoABacktestAdapterIsOneLine() {
        double[] y = {10, 20, 13, 24, 18, 27, 15, 30};
        Forecaster.Result r = Forecaster.fit(y, hw(2, 0.4, 0.1, 0.3, 1, List.of(0.8)).withHorizon(4));
        List<ForecastOutput.Row> future = r.rows().subList(y.length, y.length + 4);
        for (int h = 0; h < 4; h++) {
            assertEquals(future.get(h).lower(), r.lowerAhead(0.8)[h], EPS);
            assertEquals(future.get(h).upper(), r.upperAhead(0.8)[h], EPS);
            assertEquals(future.get(h).forecast(), r.ahead()[h], EPS);
        }
    }

    @Test
    void theSameInputGivesTheSameNumbersBitForBit() {
        double[] y = new double[60];
        for (int i = 0; i < y.length; i++) y[i] = 100 + i + 10 * Math.sin(i) + (i % 7) * 3;
        Forecaster.Spec spec = hw(7, 0.3, 0.1, 0.2, 14, List.of(0.95));
        assertEquals(Forecaster.fit(y, spec).rows(), Forecaster.fit(y.clone(), spec).rows());
    }

    @Test
    void inputRulesRefuseWhatCannotBeForecast() {
        Forecaster.Spec spec = hw(7, 0.25, 0.02, 0.15, 7, List.of(0.95));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Forecaster.fit(new double[13], spec));
        assertTrue(e.getMessage().contains("at least 14"), e.getMessage());
        double[] gap = new double[14];
        gap[5] = Double.NaN;
        assertThrows(IllegalArgumentException.class, () -> Forecaster.fit(gap, spec));
        assertThrows(IllegalArgumentException.class, () -> hw(1, 0.2, 0.2, 0.2, 1, List.of(0.95)));
        assertThrows(IllegalArgumentException.class, () -> hw(7, 1.2, 0.2, 0.2, 1, List.of(0.95)));
        assertThrows(IllegalArgumentException.class, () -> hw(7, 0.2, 0.2, 0.2, 0, List.of(0.95)));
        assertThrows(IllegalArgumentException.class, () -> hw(7, 0.2, 0.2, 0.2, 1, List.of(1.0)));
        assertThrows(IllegalArgumentException.class, () -> hw(7, 0.2, 0.2, 0.2, 1, List.of(0.95, 0.8)));
        assertThrows(IllegalArgumentException.class, () -> hw(7, 0.2, 0.2, 0.2, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> ForecastMethod.parse("arima"));
        assertEquals(ForecastMethod.HOLT_WINTERS, ForecastMethod.parse("Holt_Winters"));
    }
}

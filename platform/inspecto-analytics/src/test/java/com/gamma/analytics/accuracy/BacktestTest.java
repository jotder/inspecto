package com.gamma.analytics.accuracy;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.accuracy.Backtest.Forecast;
import com.gamma.analytics.accuracy.Backtest.ForecastFn;
import com.gamma.analytics.accuracy.Backtest.Spec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The rolling-origin backtest driver: origins, expanding window, per-step aggregates, caps and forecast checks. */
class BacktestTest {

    private static final double[] ONE_TO_TEN = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

    /** Naive: repeat the last observed value. */
    private static final ForecastFn NAIVE = (history, h) -> {
        double[] out = new double[h];
        Arrays.fill(out, history[history.length - 1]);
        return Forecast.of(out);
    };

    @Test
    void goldenNaiveBacktest() {
        // origins t = 5, 7 (t + 2 <= 10); t = 9 would need x[10]
        Backtest.Result r = Backtest.run(ONE_TO_TEN, new Spec(5, 2, 2), NAIVE);
        assertEquals(List.of(5, 7), r.origins().stream().map(Backtest.Origin::origin).toList());
        assertArrayEquals(new double[] {6, 7}, r.origins().get(0).actual());
        assertArrayEquals(new double[] {5, 5}, r.origins().get(0).forecast().point());
        assertArrayEquals(new double[] {8, 9}, r.origins().get(1).actual());
        // errors (p - a) are {-1, -2} at both origins
        assertEquals(4, r.overall().n());
        assertEquals(1.5, r.overall().mae(), 1e-12);
        assertEquals(-1.5, r.overall().bias(), 1e-12);
        assertEquals(Math.sqrt(2.5), r.overall().rmse(), 1e-12);
        assertEquals(1.0, r.byHorizon().get(0).mae(), 1e-12, "one step ahead");
        assertEquals(2.0, r.byHorizon().get(1).mae(), 1e-12, "two steps ahead");
        assertEquals(2, r.byHorizon().get(1).n());
        assertTrue(r.overall().coverage().isEmpty());
    }

    @Test
    void historyIsTheExpandingWindowBeforeTheOriginAndACopy() {
        double[] series = ONE_TO_TEN.clone();
        List<double[]> seen = new ArrayList<>();
        Backtest.run(series, new Spec(3, 1, 3), (history, h) -> {
            seen.add(history.clone());
            Arrays.fill(history, -99);   // must not reach the series
            return Forecast.of(0);
        });
        assertEquals(3, seen.size());   // t = 3, 6, 9
        assertArrayEquals(new double[] {1, 2, 3}, seen.get(0));
        assertArrayEquals(new double[] {1, 2, 3, 4, 5, 6}, seen.get(1));
        assertArrayEquals(Arrays.copyOf(ONE_TO_TEN, 9), seen.get(2));
        assertArrayEquals(ONE_TO_TEN, series, "the series is never modified");
    }

    @Test
    void bandsGiveCoverage() {
        ForecastFn banded = (history, h) -> {
            double last = history[history.length - 1];
            double[] p = new double[h], lo = new double[h], hi = new double[h];
            for (int i = 0; i < h; i++) {
                p[i] = last;
                lo[i] = last - 1.5;
                hi[i] = last + 1.5;
            }
            return new Forecast(p, lo, hi);
        };
        Backtest.Result r = Backtest.run(ONE_TO_TEN, new Spec(5, 2, 2), banded);
        // actual - last = 1 (inside) at step 1, 2 (outside) at step 2
        assertEquals(0.5, r.overall().coverage().getAsDouble(), 1e-12);
        assertEquals(1.0, r.byHorizon().get(0).coverage().getAsDouble(), 1e-12);
        assertEquals(0.0, r.byHorizon().get(1).coverage().getAsDouble(), 1e-12);
        assertEquals(3.0, r.overall().meanWidth().getAsDouble(), 1e-12);
    }

    @Test
    void deterministic() {
        double[] s = new java.util.Random(7).doubles(200).toArray();
        ForecastFn meanFn = (history, h) -> {
            double m = Arrays.stream(history).average().orElseThrow();
            double[] out = new double[h];
            Arrays.fill(out, m);
            return Forecast.of(out);
        };
        Backtest.Result a = Backtest.run(s, new Spec(20, 5, 7), meanFn);
        Backtest.Result b = Backtest.run(s, new Spec(20, 5, 7), meanFn);
        assertEquals(a.overall(), b.overall());
        assertEquals(a.byHorizon(), b.byHorizon());
        assertEquals(Backtest.originCount(200, new Spec(20, 5, 7)), a.origins().size());
    }

    @Test
    void capsFailClosed() {
        double[] s = new double[1001];
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Backtest.run(s, new Spec(1, 1, 1, 100), NAIVE));
        assertTrue(e.getMessage().contains("exceed maxOrigins"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> Backtest.run(new double[Backtest.MAX_SERIES_LENGTH + 1], new Spec(1, 1, 1), NAIVE));
        assertThrows(IllegalArgumentException.class, () -> new Spec(1, 1, 1, Backtest.MAX_ORIGINS_CEILING + 1));
        e = assertThrows(IllegalArgumentException.class,
                () -> Backtest.run(new double[90_000], new Spec(80_000, 2_000, 2, 5_000), NAIVE));
        assertTrue(e.getMessage().contains("scored pairs"), e.getMessage());
    }

    @Test
    void inputAndForecastChecks() {
        assertThrows(IllegalArgumentException.class, () -> Backtest.run(ONE_TO_TEN, new Spec(9, 2, 1), NAIVE), "no origin");
        assertThrows(IllegalArgumentException.class, () -> Backtest.run(new double[] {1, Double.NaN, 3}, new Spec(1, 1, 1), NAIVE));
        assertThrows(IllegalArgumentException.class, () -> new Spec(0, 1, 1));
        assertThrows(IllegalStateException.class, () -> Backtest.run(ONE_TO_TEN, new Spec(5, 2, 1), (h, n) -> Forecast.of(1)),
                "wrong length");
        assertThrows(IllegalStateException.class, () -> Backtest.run(ONE_TO_TEN, new Spec(5, 1, 1), (h, n) -> Forecast.of(Double.NaN)));
        assertThrows(IllegalStateException.class, () -> Backtest.run(ONE_TO_TEN, new Spec(5, 1, 1),
                (h, n) -> new Forecast(new double[] {1}, new double[] {2}, new double[] {1})), "lower > upper");
        assertThrows(IllegalStateException.class, () -> Backtest.run(ONE_TO_TEN, new Spec(5, 1, 1),
                (h, n) -> h.length == 5 ? new Forecast(new double[] {1}, new double[] {0}, new double[] {2}) : Forecast.of(1)),
                "band at one origin only");
    }
}

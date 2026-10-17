package com.gamma.analytics.forecast;

import com.gamma.analytics.Stats;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The forecast kernel (ANALYTICS-CORE-1 S5 (a)): seasonal naive and Holt-Winters additive over ONE regular series —
 * one value per bucket, no gap, no missing value (bucket and gap-fill it first with the M5 resample). Pure and
 * deterministic: the same values and spec give the same numbers bit for bit; nothing is fitted or searched.
 * {@link ForecastSql} is the DuckDB form of the same definitions, for Datasets; {@code ForecastSqlParityTest} proves
 * they agree. Positions below are 1-based: {@code y(1) … y(n)}, period {@code m}.
 *
 * <h2>Holt-Winters additive</h2>
 * Initialisation (the {@code business-assurance} Space Template's rule, kept so the two give the same numbers):
 * <pre>
 *   L(m) = mean(y(1..m))
 *   B(m) = (mean(y(m+1..2m)) − mean(y(1..m))) / m
 *   S(i) = y(i) − L(m)                       i = 1..m, one seasonal term per position k = (t − 1) mod m + 1
 * </pre>
 * Then for t = m+1 … n, with k the position of t:
 * <pre>
 *   ŷ(t) = L(t−1) + B(t−1) + S(k)                                   one-step-ahead forecast
 *   L(t) = α (y(t) − S(k)) + (1 − α)(L(t−1) + B(t−1))
 *   B(t) = β (L(t) − L(t−1)) + (1 − β) B(t−1)
 *   S(k) = γ (y(t) − L(t)) + (1 − γ) S(k)
 * </pre>
 * and from the last bucket {@code ŷ(n+h) = L(n) + h·B(n) + S((n + h − 1) mod m + 1)}. α, β, γ are declared, each in
 * [0, 1]; defaults {@link #DEFAULT_ALPHA}, {@link #DEFAULT_BETA}, {@link #DEFAULT_GAMMA} are the template's.
 *
 * <h2>Seasonal naive</h2>
 * {@code ŷ(t) = y(t − m)} for t &gt; m, and {@code ŷ(n+h) = y(n − m + (h − 1) mod m + 1)}.
 *
 * <h2>Both</h2>
 * The first season (t ≤ m) has no forecast. A series needs at least {@code 2m} values (Holt-Winters' trend needs two
 * seasons; one rule for both methods). The interval is {@link PredictionInterval}'s.
 */
public final class Forecaster {
    private Forecaster() {}

    /** The business-assurance template's smoothing weights. */
    public static final double DEFAULT_ALPHA = 0.25;
    public static final double DEFAULT_BETA = 0.02;
    public static final double DEFAULT_GAMMA = 0.15;

    /**
     * What to forecast: the method, the period (buckets per season, ≥ 2), the smoothing weights (ignored by seasonal
     * naive), the number of buckets ahead and the interval levels (each strictly between 0 and 1, ascending, distinct).
     */
    public record Spec(ForecastMethod method, int period, double alpha, double beta, double gamma, int horizon,
                       List<Double> levels) {
        public Spec {
            if (method == null) throw new IllegalArgumentException("forecast method is required");
            if (period < 2) throw new IllegalArgumentException("forecast period must be at least 2, got " + period);
            weight("alpha", alpha);
            weight("beta", beta);
            weight("gamma", gamma);
            if (horizon < 1) throw new IllegalArgumentException("forecast horizon must be at least 1, got " + horizon);
            levels = List.copyOf(levels);
            if (levels.isEmpty()) throw new IllegalArgumentException("forecast needs at least one interval level");
            double prev = 0;
            for (double l : levels) {
                PredictionInterval.z(l);
                if (l <= prev) throw new IllegalArgumentException("interval levels must be ascending and distinct: " + levels);
                prev = l;
            }
        }

        /** The smallest series this spec can forecast: two seasons. */
        public int minPoints() {
            return 2 * period;
        }

        private static void weight(String name, double w) {
            if (!(w >= 0 && w <= 1)) throw new IllegalArgumentException("forecast " + name + " must be between 0 and 1, got " + w);
        }
    }

    /**
     * A fitted series: {@code fitted[t − 1]} = ŷ(t) (NaN on the first season), {@code ahead[h − 1]} = ŷ(n + h), and
     * the interval's {@code sigma}.
     */
    public record Result(double[] actual, double[] fitted, double[] ahead, double sigma, Spec spec) {

        /** The output rows ({@link ForecastOutput}): every bucket of the history, then the horizon, each per level. */
        public List<ForecastOutput.Row> rows() {
            int n = actual.length;
            double[] v = PredictionInterval.varianceFactors(spec);
            List<ForecastOutput.Row> out = new ArrayList<>();
            for (int t = 1; t <= n + spec.horizon(); t++) {
                boolean future = t > n;
                int h = future ? t - n : 0;
                double f = future ? ahead[h - 1] : fitted[t - 1];
                for (double level : spec.levels()) {
                    if (Double.isNaN(f)) {
                        out.add(new ForecastOutput.Row(t, h, actual[t - 1], null, level, null, null));
                    } else {
                        double half = PredictionInterval.z(level) * sigma * Math.sqrt(future ? v[h - 1] : 1);
                        out.add(new ForecastOutput.Row(t, h, future ? null : actual[t - 1], f, level, f - half, f + half));
                    }
                }
            }
            return out;
        }
    }

    /** Fit {@code y} (oldest first) under {@code spec}. */
    public static Result fit(double[] y, Spec spec) {
        int n = y.length;
        int m = spec.period();
        if (n < spec.minPoints())
            throw new IllegalArgumentException("forecast needs at least " + spec.minPoints() + " values (two seasons of "
                    + m + "), got " + n);
        for (double v : y)
            if (!Double.isFinite(v)) throw new IllegalArgumentException("forecast of a missing or infinite value: fill gaps first");
        double[] fitted = new double[n];
        Arrays.fill(fitted, 0, m, Double.NaN);
        double[] ahead = new double[spec.horizon()];
        if (spec.method() == ForecastMethod.SEASONAL_NAIVE) {
            for (int t = m; t < n; t++) fitted[t] = y[t - m];
            for (int h = 1; h <= ahead.length; h++) ahead[h - 1] = y[n - m + (h - 1) % m];
        } else {
            double a = spec.alpha(), b = spec.beta(), g = spec.gamma();
            double level = Stats.mean(Arrays.copyOfRange(y, 0, m));
            double trend = (Stats.mean(Arrays.copyOfRange(y, m, 2 * m)) - level) / m;
            double[] season = new double[m];
            for (int i = 0; i < m; i++) season[i] = y[i] - level;
            for (int t = m; t < n; t++) {          // 0-based t is bucket t + 1
                int k = t % m;
                fitted[t] = level + trend + season[k];
                double next = a * (y[t] - season[k]) + (1 - a) * (level + trend);
                trend = b * (next - level) + (1 - b) * trend;
                season[k] = g * (y[t] - next) + (1 - g) * season[k];
                level = next;
            }
            for (int h = 1; h <= ahead.length; h++) ahead[h - 1] = level + h * trend + season[(n + h - 1) % m];
        }
        double[] errors = new double[n - m];
        for (int t = m; t < n; t++) errors[t - m] = y[t] - fitted[t];
        return new Result(y.clone(), fitted, ahead, Stats.scaledMad(errors), spec);
    }
}

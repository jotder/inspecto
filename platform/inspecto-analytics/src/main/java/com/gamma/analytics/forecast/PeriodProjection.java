package com.gamma.analytics.forecast;

import com.gamma.analytics.Stats;
import com.gamma.analytics.accuracy.Accuracy;
import com.gamma.analytics.accuracy.Backtest;
import com.gamma.analytics.time.SeasonalityDetector;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Where a period in progress lands (`BI-INSIGHTS-1` I-12): the period-to-date actual plus a forecast of the buckets
 * still to come, with an interval and the probability of meeting a goal. Pure and deterministic; the caller supplies
 * the bucketed series of an ADDITIVE Measure (a count or a sum — the parts of a period add up to the period).
 *
 * <pre>
 *   projected  = toDate + Σ ŷ(n + h), h = 1 … H        Holt-Winters additive ({@link Forecaster}), period m
 *   e(o)       = Σ ŷ − Σ y over the H buckets after each past origin o     rolling-origin {@link Backtest}
 *   sigma      = 1.4826 · median(|e(o)|)                                  scaled MAD about 0, so a biased model widens it
 *   lower/upper= projected ∓ z(level) · sigma                             {@link PredictionInterval#z}
 *   P(goal)    = Φ((projected − target) / sigma) for "at least", 1 − that for "at most", Φ(hi) − Φ(lo) for a band
 * </pre>
 *
 * The interval of a SUM of forecasts is not the sum of the per-bucket intervals (their errors are correlated), so it
 * is measured: how far the same projection of H buckets missed at every past origin. That makes the band honest about
 * this series rather than about the model's assumptions.
 *
 * <p>Fail closed — "no projection", with the reason, never a guess: fewer than two seasons of history
 * ({@link Forecaster.Spec#minPoints()}), or fewer than {@link #MIN_ORIGINS} past origins to measure the miss on.
 * The season is the one {@link SeasonalityDetector} finds among the candidates, else the caller's calendar default.
 */
public final class PeriodProjection {
    private PeriodProjection() {}

    /** The interval level reported. */
    public static final double LEVEL = 0.8;
    /** The fewest past projections the interval is measured on. */
    public static final int MIN_ORIGINS = 8;

    /** What "the target is met" means. */
    public enum Goal { AT_LEAST, AT_MOST, BETWEEN }

    /**
     * One projection. When {@code projected} is false only {@code reason} (and {@code toDate}) mean anything.
     *
     * @param remaining the forecast of the buckets still to come (0 for a complete period)
     * @param period    the season used, in buckets; {@code detected} says whether the series showed it
     * @param origins   past projections the interval was measured on
     * @param accuracy  those projections' accuracy (MAPE, coverage of the band at {@link #LEVEL}); null when none ran
     */
    public record Result(boolean projected, String reason, double toDate, double remaining, double value, double lower,
                         double upper, double sigma, int horizon, int history, int period, boolean detected, int origins,
                         Accuracy.Summary accuracy) {

        static Result none(String reason, double toDate, int horizon, int history) {
            return new Result(false, reason, toDate, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, horizon, history,
                    0, false, 0, null);
        }

        /** P(the landing meets the goal); empty when not projected. {@code lo} / {@code hi}: the target, or a band. */
        public OptionalDouble probability(Goal goal, double lo, double hi) {
            if (!projected) return OptionalDouble.empty();
            Objects.requireNonNull(goal, "goal");
            if (sigma == 0) {   // a series the backtest never missed: the landing is the projection
                boolean met = switch (goal) {
                    case AT_LEAST -> value >= lo;
                    case AT_MOST -> value <= hi;
                    case BETWEEN -> value >= lo && value <= hi;
                };
                return OptionalDouble.of(met ? 1 : 0);
            }
            double p = switch (goal) {
                case AT_LEAST -> 1 - PredictionInterval.normalCdf((lo - value) / sigma);
                case AT_MOST -> PredictionInterval.normalCdf((hi - value) / sigma);
                case BETWEEN -> PredictionInterval.normalCdf((hi - value) / sigma) - PredictionInterval.normalCdf((lo - value) / sigma);
            };
            return OptionalDouble.of(Math.max(0, Math.min(1, p)));
        }
    }

    /**
     * @param series     the Measure per bucket, oldest first, ending with the last bucket of the period to date; no gap
     * @param toDate     the period's value so far (the sum of its buckets in {@code series})
     * @param horizon    buckets left in the period (0 = complete: the landing is {@code toDate})
     * @param candidates season lengths to test ({@link SeasonalityDetector#candidatesFor})
     * @param fallback   the season when none is detected (the calendar's, e.g. 7 for days), ≥ 2
     */
    public static Result project(double[] series, double toDate, int horizon, int[] candidates, int fallback) {
        Objects.requireNonNull(series, "series");
        if (horizon < 0) throw new IllegalArgumentException("horizon must be >= 0: " + horizon);
        int n = series.length;
        if (horizon == 0)
            return new Result(true, null, toDate, 0, toDate, toDate, toDate, 0, 0, n, 0, false, 0, null);
        var detection = SeasonalityDetector.detect(series, candidates);
        boolean detected = detection.period().isPresent();
        int m = detected ? detection.period().getAsInt() : fallback;
        Forecaster.Spec spec = new Forecaster.Spec(ForecastMethod.HOLT_WINTERS, m, Forecaster.DEFAULT_ALPHA,
                Forecaster.DEFAULT_BETA, Forecaster.DEFAULT_GAMMA, horizon, List.of(LEVEL));
        if (n < spec.minPoints())
            return Result.none("needs two seasons of history (" + spec.minPoints() + " buckets of " + m + "), has " + n,
                    toDate, horizon, n);
        int count = Backtest.originCount(n, new Backtest.Spec(spec.minPoints(), horizon, 1));
        if (count < MIN_ORIGINS)
            return Result.none("needs " + MIN_ORIGINS + " past projections of " + horizon + " buckets to measure the interval on, "
                    + "the history holds " + count, toDate, horizon, n);
        int step = (count + Backtest.DEFAULT_MAX_ORIGINS - 1) / Backtest.DEFAULT_MAX_ORIGINS;   // stay under the cap
        Backtest.Result bt = Backtest.run(series, new Backtest.Spec(spec.minPoints(), horizon, step),
                (history, h) -> Backtest.Forecast.of(Forecaster.fit(history, spec.withHorizon(h)).ahead()));
        int k = bt.origins().size();
        double[] errors = new double[k], actual = new double[k], predicted = new double[k];
        for (int o = 0; o < k; o++) {
            Backtest.Origin origin = bt.origins().get(o);
            actual[o] = sum(origin.actual());
            predicted[o] = sum(origin.forecast().point());
            errors[o] = predicted[o] - actual[o];
        }
        double sigma = Stats.scaledMad(errors, 0);
        double half = PredictionInterval.z(LEVEL) * sigma;
        double[] lo = new double[k], hi = new double[k];
        for (int o = 0; o < k; o++) {
            lo[o] = predicted[o] - half;
            hi[o] = predicted[o] + half;
        }
        Accuracy.Summary accuracy = Accuracy.summary(actual, predicted, lo, hi);
        double remaining = sum(Forecaster.fit(series, spec).ahead());
        double value = toDate + remaining;
        return new Result(true, null, toDate, remaining, value, value - half, value + half, sigma, horizon, n, m, detected,
                k, accuracy);
    }

    private static double sum(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return s;
    }
}

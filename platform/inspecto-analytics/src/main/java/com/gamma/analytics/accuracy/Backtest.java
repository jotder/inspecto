package com.gamma.analytics.accuracy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Model-agnostic rolling-origin backtest (ANALYTICS-CORE-1 S5b; Tashman 2000, expanding window).
 *
 * <p>For a series {@code x[0..n-1]} and a {@link Spec}: the origins are {@code t = minTrain, minTrain + step, …} for as
 * long as {@code t + horizon ≤ n} — only origins whose whole horizon is observed, so every origin contributes exactly
 * {@code horizon} pairs and the per-step aggregates compare like with like. At each origin the {@link ForecastFn} gets a
 * COPY of {@code x[0..t-1]} (the expanding window — every observation before the origin, nothing after) and returns
 * {@code horizon} predictions; they are scored against {@code x[t..t+horizon-1]} with {@link Accuracy}.
 *
 * <p>Deterministic: origins are visited in order and nothing is random, so a deterministic {@code ForecastFn} gives the
 * same {@link Result} every run.
 *
 * <p>Caps fail closed (D-AD10): a series longer than {@link #MAX_SERIES_LENGTH} or a run that would need more than
 * {@link Spec#maxOrigins()} origins throws {@link IllegalArgumentException} — it never silently backtests a subset.
 * {@code maxOrigins} itself is capped at {@link #MAX_ORIGINS_CEILING}, and origins × horizon at {@link #MAX_PAIRS}.
 */
public final class Backtest {

    /** Longest series accepted. */
    public static final int MAX_SERIES_LENGTH = 100_000;
    /** Origins when the caller does not say. */
    public static final int DEFAULT_MAX_ORIGINS = 500;
    /** The most origins a caller may allow. */
    public static final int MAX_ORIGINS_CEILING = 5_000;
    /** The most scored pairs (origins × horizon) one run may hold. */
    public static final long MAX_PAIRS = 1_000_000;

    private Backtest() {}

    /**
     * Any forecaster: history (oldest first, a copy the function may keep or modify) → the next {@code horizon} values.
     * Must be deterministic for the backtest to be.
     */
    @FunctionalInterface
    public interface ForecastFn {
        Forecast forecast(double[] history, int horizon);
    }

    /**
     * One forecast: {@code horizon} point predictions and, optionally, a band ({@code lower} and {@code upper} both
     * {@code null}, or both {@code horizon} long with lower ≤ upper).
     */
    public record Forecast(double[] point, double[] lower, double[] upper) {
        public Forecast {
            Objects.requireNonNull(point, "point");
            if ((lower == null) != (upper == null)) throw new IllegalArgumentException("a band needs both lower and upper");
        }

        public static Forecast of(double... point) {
            return new Forecast(point, null, null);
        }

        public boolean hasBand() {
            return lower != null;
        }
    }

    /**
     * @param minTrain   observations before the first origin (≥ 1)
     * @param horizon    steps forecast at each origin (≥ 1)
     * @param step       distance between origins (≥ 1)
     * @param maxOrigins the run fails when it would need more origins than this (1 … {@link #MAX_ORIGINS_CEILING})
     */
    public record Spec(int minTrain, int horizon, int step, int maxOrigins) {
        public Spec {
            if (minTrain < 1) throw new IllegalArgumentException("minTrain must be >= 1: " + minTrain);
            if (horizon < 1) throw new IllegalArgumentException("horizon must be >= 1: " + horizon);
            if (step < 1) throw new IllegalArgumentException("step must be >= 1: " + step);
            if (maxOrigins < 1 || maxOrigins > MAX_ORIGINS_CEILING)
                throw new IllegalArgumentException("maxOrigins must be in 1.." + MAX_ORIGINS_CEILING + ": " + maxOrigins);
        }

        public Spec(int minTrain, int horizon, int step) {
            this(minTrain, horizon, step, DEFAULT_MAX_ORIGINS);
        }
    }

    /** One origin: {@code origin} = index of the first forecast value; the actuals, the forecast, and its measures. */
    public record Origin(int origin, double[] actual, Forecast forecast, Accuracy.Summary accuracy) {}

    /**
     * @param origins   every origin, in order
     * @param overall   the measures over all {@code origins × horizon} pairs
     * @param byHorizon element {@code h} = the measures over the {@code h+1}-step-ahead pairs of every origin
     */
    public record Result(List<Origin> origins, Accuracy.Summary overall, List<Accuracy.Summary> byHorizon) {}

    /** The origins a spec visits on a series of length {@code n} (0 when the series is too short for one). */
    public static int originCount(int n, Spec spec) {
        int last = n - spec.horizon();
        return last < spec.minTrain() ? 0 : (last - spec.minTrain()) / spec.step() + 1;
    }

    /**
     * Runs the backtest. The series must have no NaN (gap-fill it first) and room for at least one origin. A forecast
     * of the wrong length, with a NaN, with lower &gt; upper, or with a band at some origins but not others throws
     * {@link IllegalStateException} naming the origin.
     */
    public static Result run(double[] series, Spec spec, ForecastFn fn) {
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(fn, "fn");
        int n = series.length;
        if (n > MAX_SERIES_LENGTH) throw new IllegalArgumentException("backtest: series of " + n + " values exceeds the cap of " + MAX_SERIES_LENGTH);
        for (double v : series) if (Double.isNaN(v)) throw new IllegalArgumentException("backtest: NaN in series - gap-fill it first");
        int count = originCount(n, spec);
        if (count == 0) throw new IllegalArgumentException("backtest: series of " + n + " values has no origin for minTrain "
                + spec.minTrain() + " and horizon " + spec.horizon());
        if (count > spec.maxOrigins()) throw new IllegalArgumentException("backtest: " + count + " origins exceed maxOrigins "
                + spec.maxOrigins() + " - raise step or minTrain, or shorten the series");

        if ((long) count * spec.horizon() > MAX_PAIRS) throw new IllegalArgumentException("backtest: " + count + " origins x horizon "
                + spec.horizon() + " exceed the cap of " + MAX_PAIRS + " scored pairs");
        int h = spec.horizon(), pairs = count * h;
        double[] all = new double[pairs], allP = new double[pairs], allL = new double[pairs], allU = new double[pairs];
        double[][] stepA = new double[h][count], stepP = new double[h][count], stepL = new double[h][count], stepU = new double[h][count];
        List<Origin> origins = new ArrayList<>(count);
        Boolean banded = null;
        for (int o = 0; o < count; o++) {
            int t = spec.minTrain() + o * spec.step();
            Forecast f = fn.forecast(Arrays.copyOfRange(series, 0, t), h);
            if (f == null) throw new IllegalStateException("backtest: forecast at origin " + t + " is null");
            if (banded == null) banded = f.hasBand();
            else if (banded != f.hasBand()) throw new IllegalStateException("backtest: forecast at origin " + t + " has a band where others do not, or the reverse");
            check(f.point(), h, t, "point");
            double[] actual = Arrays.copyOfRange(series, t, t + h);
            Forecast kept = new Forecast(f.point().clone(), f.hasBand() ? f.lower().clone() : null, f.hasBand() ? f.upper().clone() : null);
            if (kept.hasBand()) {
                check(kept.lower(), h, t, "lower");
                check(kept.upper(), h, t, "upper");
                for (int i = 0; i < h; i++)
                    if (kept.lower()[i] > kept.upper()[i]) throw new IllegalStateException("backtest: forecast at origin " + t + " has lower > upper at step " + (i + 1));
            }
            for (int i = 0; i < h; i++) {
                int k = o * h + i;
                all[k] = actual[i];
                allP[k] = kept.point()[i];
                stepA[i][o] = actual[i];
                stepP[i][o] = kept.point()[i];
                if (kept.hasBand()) {
                    allL[k] = stepL[i][o] = kept.lower()[i];
                    allU[k] = stepU[i][o] = kept.upper()[i];
                }
            }
            origins.add(new Origin(t, actual, kept, kept.hasBand()
                    ? Accuracy.summary(actual, kept.point(), kept.lower(), kept.upper())
                    : Accuracy.summary(actual, kept.point())));
        }
        boolean band = banded;
        Accuracy.Summary overall = band ? Accuracy.summary(all, allP, allL, allU) : Accuracy.summary(all, allP);
        List<Accuracy.Summary> byHorizon = new ArrayList<>(h);
        for (int i = 0; i < h; i++)
            byHorizon.add(band ? Accuracy.summary(stepA[i], stepP[i], stepL[i], stepU[i]) : Accuracy.summary(stepA[i], stepP[i]));
        return new Result(List.copyOf(origins), overall, List.copyOf(byHorizon));
    }

    private static void check(double[] v, int h, int origin, String what) {
        if (v.length != h) throw new IllegalStateException("backtest: forecast at origin " + origin + " has " + v.length + " " + what + " values, expected " + h);
        for (double x : v) if (Double.isNaN(x)) throw new IllegalStateException("backtest: forecast at origin " + origin + " has a NaN " + what + " value");
    }
}

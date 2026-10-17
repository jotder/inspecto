package com.gamma.analytics.accuracy;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Forecast accuracy measures over (actual, predicted) pairs (ANALYTICS-CORE-1 S5b) — ONE written definition each, model
 * agnostic. {@link AccuracySql} is the DuckDB form of the same definitions; {@code AccuracyDuckDbParityTest} holds the
 * two together.
 *
 * <p>Error convention: <b>e = predicted − actual</b> (the forecast error). A positive {@link Summary#bias()} means the
 * model over-forecasts.
 *
 * <table>
 *   <caption>Definitions</caption>
 *   <tr><th>Measure</th><th>Definition</th></tr>
 *   <tr><td>MAE</td><td>Σ|e| / n</td></tr>
 *   <tr><td>RMSE</td><td>√(Σe² / n)</td></tr>
 *   <tr><td>MAPE (percent)</td><td>100 · Σ|e| / |a| / m over the m pairs whose actual is NOT zero; a zero actual is
 *       excluded and counted ({@link Summary#mapeZeroActuals()}), never divided by. Empty when every actual is zero.</td></tr>
 *   <tr><td>sMAPE (percent, 0–200)</td><td>100 / n · Σ 2|e| / (|a| + |p|) — the Makridakis (M3) / Hyndman–Koehler form
 *       with the MEAN of the absolute values as denominator, range [0, 200]; a pair with a = p = 0 contributes 0 (a
 *       perfect forecast) and is counted. Not the (|a| + |p|) form without the 2, whose range is [0, 100].</td></tr>
 *   <tr><td>Bias</td><td>Σe / n (mean error), positive = over-forecast</td></tr>
 *   <tr><td>Interval coverage</td><td>share of pairs with lower ≤ a ≤ upper (both ends inclusive)</td></tr>
 *   <tr><td>Mean interval width</td><td>Σ(upper − lower) / n</td></tr>
 * </table>
 *
 * <p>Input rules, as {@code Stats}: no pairs, a length mismatch, NaN anywhere, half a band (only one of lower and
 * upper), or a band with lower &gt; upper throws
 * {@link IllegalArgumentException}; the caller drops missing pairs first (the SQL form skips rows with a NULL in a used
 * column, so both count the same pairs). The arrays are never modified.
 */
public final class Accuracy {

    private Accuracy() {}

    /**
     * Every measure over one set of pairs.
     *
     * @param n               pairs measured
     * @param mape            empty when every actual is zero
     * @param mapeZeroActuals pairs left out of MAPE because their actual is zero
     * @param coverage        empty when no band was given
     * @param meanWidth       empty when no band was given
     */
    public record Summary(int n, double mae, double rmse, OptionalDouble mape, int mapeZeroActuals, double smape,
                          double bias, OptionalDouble coverage, OptionalDouble meanWidth) {}

    /** All point measures; coverage and width empty. */
    public static Summary summary(double[] actual, double[] predicted) {
        return summary(actual, predicted, null, null);
    }

    /** All measures; {@code lower} and {@code upper} both {@code null} (no band) or both the pairs' length. */
    public static Summary summary(double[] actual, double[] predicted, double[] lower, double[] upper) {
        check(actual, predicted);
        if ((lower == null) != (upper == null)) throw new IllegalArgumentException("a band needs both lower and upper");
        int n = actual.length;
        double abs = 0, sq = 0, err = 0, sym = 0, pct = 0;
        int zeros = 0;
        for (int i = 0; i < n; i++) {
            double a = actual[i], e = predicted[i] - a;
            abs += Math.abs(e);
            sq += e * e;
            err += e;
            double den = Math.abs(a) + Math.abs(predicted[i]);
            sym += den == 0 ? 0 : 200 * Math.abs(e) / den;
            if (a == 0) zeros++;
            else pct += Math.abs(e) / Math.abs(a);
        }
        OptionalDouble mape = zeros == n ? OptionalDouble.empty() : OptionalDouble.of(100 * pct / (n - zeros));
        OptionalDouble coverage = OptionalDouble.empty(), width = OptionalDouble.empty();
        if (lower != null || upper != null) {
            coverage = OptionalDouble.of(coverage(actual, lower, upper));
            width = OptionalDouble.of(meanIntervalWidth(lower, upper));
        }
        return new Summary(n, abs / n, Math.sqrt(sq / n), mape, zeros, sym / n, err / n, coverage, width);
    }

    /** Σ|predicted − actual| / n. */
    public static double mae(double[] actual, double[] predicted) {
        return summary(actual, predicted).mae();
    }

    /** √(Σ(predicted − actual)² / n). */
    public static double rmse(double[] actual, double[] predicted) {
        return summary(actual, predicted).rmse();
    }

    /** 100 · mean |e| / |a| over the non-zero actuals; empty when every actual is zero (see {@link Summary#mapeZeroActuals()}). */
    public static OptionalDouble mape(double[] actual, double[] predicted) {
        return summary(actual, predicted).mape();
    }

    /** 100 / n · Σ 2|e| / (|a| + |p|), range [0, 200]; a pair with a = p = 0 contributes 0. */
    public static double smape(double[] actual, double[] predicted) {
        return summary(actual, predicted).smape();
    }

    /** Σ(predicted − actual) / n — positive = over-forecast. */
    public static double bias(double[] actual, double[] predicted) {
        return summary(actual, predicted).bias();
    }

    /** Share of actuals with lower ≤ a ≤ upper. */
    public static double coverage(double[] actual, double[] lower, double[] upper) {
        check(actual, lower, upper);
        int in = 0;
        for (int i = 0; i < actual.length; i++) if (actual[i] >= lower[i] && actual[i] <= upper[i]) in++;
        return (double) in / actual.length;
    }

    /** Σ(upper − lower) / n. */
    public static double meanIntervalWidth(double[] lower, double[] upper) {
        check(lower, upper);
        double w = 0;
        for (int i = 0; i < lower.length; i++) {
            if (lower[i] > upper[i]) throw new IllegalArgumentException("band lower " + lower[i] + " > upper " + upper[i] + " at " + i);
            w += upper[i] - lower[i];
        }
        return w / lower.length;
    }

    private static void check(double[]... arrays) {
        for (double[] a : arrays) Objects.requireNonNull(a, "values");
        int n = arrays[0].length;
        if (n == 0) throw new IllegalArgumentException("accuracy of no pairs");
        for (double[] a : arrays) {
            if (a.length != n) throw new IllegalArgumentException("pair arrays differ in length: " + n + " vs " + a.length);
            for (double v : a) if (Double.isNaN(v)) throw new IllegalArgumentException("NaN in accuracy input - drop missing pairs first");
        }
        if (arrays.length == 3) meanIntervalWidth(arrays[1], arrays[2]);
    }
}

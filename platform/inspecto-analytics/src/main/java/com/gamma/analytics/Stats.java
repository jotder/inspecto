package com.gamma.analytics;

import java.util.Arrays;
import java.util.OptionalDouble;

/**
 * The canonical statistics of the Analytics Kernel (ANALYTICS-CORE-1 M1): ONE written definition each, so two modules
 * asking for "the median" or "the 95th percentile" of the same values get the same number. Pure, stateless, JDK only.
 * The definitions, and which DuckDB function each one matches, are in {@code docs/okf/product/module/analytics/spec.md};
 * {@code StatsDuckDbParityTest} proves the JVM and DuckDB forms agree.
 *
 * <h2>Input rules (every function)</h2>
 * <ul>
 *   <li><b>Empty input</b> throws {@link IllegalArgumentException} ({@code "<statistic> of no values"}). The kernel
 *       never invents a value for "no data": a caller that wants 0 or NaN for an empty set (a report row, a missing
 *       baseline) decides that itself, where the meaning is known. DuckDB returns NULL in the same case.</li>
 *   <li><b>NaN</b> anywhere in the input throws {@link IllegalArgumentException}. NaN is the JVM's stand-in for a
 *       missing value; SQL aggregates skip NULL, so the caller drops its missing values first (as every baseline
 *       caller already does) and the two forms agree on what was counted. ±Infinity is a value and follows IEEE
 *       arithmetic.</li>
 *   <li>A {@code null} array throws {@link NullPointerException}. The input array is never modified.</li>
 *   <li>A quantile fraction outside {@code [0, 1]} (or NaN) throws {@link IllegalArgumentException}.</li>
 * </ul>
 */
public final class Stats {

    /** Makes the MAD a consistent estimator of sigma for normally distributed data: 1 / Φ⁻¹(3/4) ≈ 1.4826. */
    public static final double MAD_SCALE = 1.4826;
    /** The robust-z floor's share of |median|: floor = max(unit, 5 % of |median|). */
    public static final double ROBUST_Z_FLOOR_SHARE = 0.05;
    /** Tukey's fence multiplier: a value more than 1.5 IQR outside the quartiles is an outlier. */
    public static final double TUKEY_K = 1.5;

    private Stats() {}

    // ---- location and spread -------------------------------------------------------------------------------------

    /** Arithmetic mean: Σx / n, summed left to right. DuckDB {@code avg}. */
    public static double mean(double[] values) {
        check(values, "mean");
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    /** Population variance: Σ(x − mean)² / n (two-pass). DuckDB {@code var_pop}. */
    public static double variancePopulation(double[] values) {
        check(values, "population variance");
        return sumSquaredDeviations(values) / values.length;
    }

    /**
     * Sample variance: Σ(x − mean)² / (n − 1) (two-pass, Bessel's correction). DuckDB {@code var_samp}.
     * Needs at least two values; one value throws {@link IllegalArgumentException} (DuckDB returns NULL).
     */
    public static double varianceSample(double[] values) {
        check(values, "sample variance");
        if (values.length < 2) throw new IllegalArgumentException("sample variance needs at least 2 values, got 1");
        return sumSquaredDeviations(values) / (values.length - 1);
    }

    /** Population standard deviation: √{@link #variancePopulation}. DuckDB {@code stddev_pop}. */
    public static double stddevPopulation(double[] values) {
        return Math.sqrt(variancePopulation(values));
    }

    /** Sample standard deviation: √{@link #varianceSample}. DuckDB {@code stddev_samp} (and {@code stddev}). */
    public static double stddevSample(double[] values) {
        return Math.sqrt(varianceSample(values));
    }

    // ---- median and quantiles ------------------------------------------------------------------------------------

    /** The {@link MedianMode#STANDARD} median. DuckDB {@code median}. */
    public static double median(double[] values) {
        return median(values, MedianMode.STANDARD);
    }

    /**
     * Median of the sorted values {@code v[0..n-1]}. Odd n: {@code v[n/2]} in every mode. Even n:
     * {@link MedianMode#STANDARD} = {@code (v[n/2-1] + v[n/2]) / 2}; {@link MedianMode#LOWER} = {@code v[n/2-1]}.
     */
    public static double median(double[] values, MedianMode mode) {
        double[] v = sorted(values, "median");
        int n = v.length;
        if (n % 2 == 1) return v[n / 2];
        return switch (mode) {
            case STANDARD -> (v[n / 2 - 1] + v[n / 2]) / 2.0;
            case LOWER -> v[n / 2 - 1];
        };
    }

    /** The {@link QuantileMode#CONTINUOUS} quantile (the canonical one, D-AN3). DuckDB {@code quantile_cont}. */
    public static double quantile(double[] values, double q) {
        return quantile(values, q, QuantileMode.CONTINUOUS);
    }

    /**
     * The {@code q}-quantile ({@code 0 <= q <= 1}) of the sorted values {@code v[0..n-1]}:
     * <ul>
     *   <li>{@link QuantileMode#CONTINUOUS}: {@code h = (n-1)q}; {@code v[⌊h⌋] + (h − ⌊h⌋)(v[⌈h⌉] − v[⌊h⌋])} — linear
     *       interpolation between the closest ranks (Hyndman–Fan type 7). DuckDB {@code quantile_cont}.</li>
     *   <li>{@link QuantileMode#DISCRETE}: {@code v[max(1, n − ⌊n − nq⌋) − 1]} — always an observed value, the
     *       smallest one whose empirical CDF reaches q (type 1), written exactly as DuckDB {@code quantile_disc}
     *       computes its index so the two agree bit for bit.</li>
     *   <li>{@link QuantileMode#NEAREST_RANK}: {@code v[min(max(⌈qn⌉, 1), n) − 1]} — the textbook nearest-rank
     *       percentile, written exactly as {@code ReportService.percentile} computes it (with {@code q = p / 100}).
     *       Mathematically the same rank as DISCRETE; kept as its own mode so a caller can pin its exact
     *       floating-point arithmetic.</li>
     * </ul>
     */
    public static double quantile(double[] values, double q, QuantileMode mode) {
        if (!(q >= 0 && q <= 1)) throw new IllegalArgumentException("quantile fraction must be in [0, 1], got " + q);
        double[] v = sorted(values, "quantile");
        int n = v.length;
        return switch (mode) {
            case CONTINUOUS -> {
                double h = (n - 1) * q;
                int lo = (int) Math.floor(h);
                int hi = (int) Math.ceil(h);
                yield lo == hi ? v[lo] : v[lo] + (h - lo) * (v[hi] - v[lo]);
            }
            case DISCRETE -> {
                long floored = (long) Math.floor(n - (double) n * q);
                yield v[(int) (Math.max(1L, n - floored) - 1)];
            }
            case NEAREST_RANK -> {
                int rank = (int) Math.ceil(q * n);
                yield v[Math.min(Math.max(rank, 1), n) - 1];
            }
        };
    }

    // ---- robust spread and the robust z --------------------------------------------------------------------------

    /** Raw median absolute deviation: median(|x − median(x)|), both medians {@link MedianMode#STANDARD}. DuckDB {@code mad}. */
    public static double mad(double[] values) {
        return mad(values, median(values));
    }

    /** Raw MAD about a given centre: median(|x − centre|) ({@link MedianMode#STANDARD}). */
    public static double mad(double[] values, double centre) {
        check(values, "mad");
        double[] dev = new double[values.length];
        for (int i = 0; i < values.length; i++) dev[i] = Math.abs(values[i] - centre);
        return median(dev);
    }

    /** Scaled MAD: {@link #MAD_SCALE} × {@link #mad(double[])} — the robust stand-in for the standard deviation. */
    public static double scaledMad(double[] values) {
        return MAD_SCALE * mad(values);
    }

    /** Scaled MAD about a given centre: {@link #MAD_SCALE} × {@link #mad(double[], double)}. */
    public static double scaledMad(double[] values, double centre) {
        return MAD_SCALE * mad(values, centre);
    }

    /**
     * The robust-z floor: {@code max(unit, 5 % of |median|)}. It keeps a near-constant history (scaled MAD ≈ 0) from
     * turning a tiny change into an enormous z; {@code unit} is the smallest change that matters for the value
     * (1 for counts).
     */
    public static double robustZFloor(double median, double unit) {
        return Math.max(unit, ROBUST_Z_FLOOR_SHARE * Math.abs(median));
    }

    /** Robust z: {@code (observed − median) / max(scaledMad, floor)}; take {@code floor} from {@link #robustZFloor}. */
    public static double robustZ(double observed, double median, double scaledMad, double floor) {
        return (observed - median) / Math.max(scaledMad, floor);
    }

    /**
     * Classic z: {@code (observed − mean) / stddev}. Which stddev is the caller's to state ({@link #stddevPopulation} or
     * {@link #stddevSample}); {@code stddev} must be finite and &gt; 0 — no spread has no z — and {@code mean} finite,
     * otherwise {@link IllegalArgumentException}. SQL form: {@link StatsSql#zScore}.
     */
    public static double zScore(double observed, double mean, double stddev) {
        checkZ(mean, stddev);
        return (observed - mean) / stddev;
    }

    static void checkZ(double mean, double stddev) {
        if (!Double.isFinite(mean) || !Double.isFinite(stddev) || !(stddev > 0))
            throw new IllegalArgumentException("z needs a finite mean and a finite stddev > 0, got mean " + mean
                    + ", stddev " + stddev);
    }

    /** Robust z of {@code observed} against {@code values}: median, scaled MAD and floor all from this class. */
    public static double robustZ(double observed, double[] values, double unit) {
        double m = median(values);
        return robustZ(observed, m, scaledMad(values, m), robustZFloor(m, unit));
    }

    // ---- quartile spread and outlier fences ----------------------------------------------------------------------

    /** Interquartile range: Q3 − Q1, both {@link QuantileMode#CONTINUOUS} (0.75 and 0.25 quantiles). */
    public static double iqr(double[] values) {
        return quantile(values, 0.75) - quantile(values, 0.25);
    }

    /** Tukey's fences with k = {@link #TUKEY_K}. */
    public static Fences tukeyFences(double[] values) {
        return tukeyFences(values, TUKEY_K);
    }

    /** Tukey's fences: {@code [Q1 − k·IQR, Q3 + k·IQR]}, quartiles {@link QuantileMode#CONTINUOUS}; k must be ≥ 0. */
    public static Fences tukeyFences(double[] values, double k) {
        return tukeyFences(values, k, QuantileMode.CONTINUOUS);
    }

    /**
     * Tukey's fences with the quartiles in an explicit {@link QuantileMode}: {@code Q1 − k·(Q3 − Q1)} and
     * {@code Q3 + k·(Q3 − Q1)}, written in that order so the SQL form ({@code OutlierSql}) computes the same doubles.
     */
    public static Fences tukeyFences(double[] values, double k, QuantileMode mode) {
        if (!(k >= 0)) throw new IllegalArgumentException("fence multiplier must be >= 0, got " + k);
        double q1 = quantile(values, 0.25, mode);
        double q3 = quantile(values, 0.75, mode);
        double iqr = q3 - q1;
        return new Fences(q1 - k * iqr, q3 + k * iqr);
    }

    /** Inclusive outlier fences: a value strictly below {@code lower} or strictly above {@code upper} is outside. */
    public record Fences(double lower, double upper) {
        public boolean outside(double value) {
            return value < lower || value > upper;
        }
    }

    // ---- correlation and simple linear regression ------------------------------------------------------------------

    /**
     * Pearson correlation of the pairs {@code (x[i], y[i])}: {@code Σ(x − x̄)(y − ȳ) / √(Σ(x − x̄)² · Σ(y − ȳ)²)}
     * (two-pass). <b>Empty when either side is constant</b> (one pair included) — no spread, no correlation; DuckDB
     * {@code corr} returns NaN there and the SQL form ({@code CorrelationSql}) turns it into NULL. Input rules as every
     * statistic here, plus equal lengths. DuckDB {@code corr(y, x)}.
     */
    public static OptionalDouble correlation(double[] x, double[] y) {
        checkPairs(x, y, "correlation");
        if (constant(x) || constant(y)) return OptionalDouble.empty();
        double mx = mean(x), my = mean(y);
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < x.length; i++) {
            double dx = x[i] - mx, dy = y[i] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        return OptionalDouble.of(sxy / Math.sqrt(sxx * syy));
    }

    /**
     * The ranks of {@code values}, 1-based, ascending; <b>ties take the average of the ranks they span</b> (three
     * equal values after two smaller ones all rank 4, not 3, 4, 5) — the tie rule Spearman's rho is defined with, and
     * the one SQL form below reproduces ({@code rank() + (ties − 1) / 2}). Every rank is a multiple of 0.5, so the
     * ranks are exact in a double. Input rules as every statistic here.
     */
    public static double[] ranks(double[] values) {
        check(values, "ranks");
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(values[a], values[b]));
        double[] out = new double[values.length];
        for (int i = 0; i < order.length; ) {
            int j = i;
            while (j + 1 < order.length && values[order[j + 1]] == values[order[i]]) j++;
            double rank = (i + j) / 2.0 + 1;   // positions i..j (0-based) hold ranks i+1..j+1
            for (int k = i; k <= j; k++) out[order[k]] = rank;
            i = j + 1;
        }
        return out;
    }

    /**
     * Spearman's rank correlation: the {@link #correlation} of the {@link #ranks} of each side (average ranks for
     * ties). Empty when either side is constant, as Pearson's. Input rules as {@link #correlation}.
     */
    public static OptionalDouble spearman(double[] x, double[] y) {
        checkPairs(x, y, "spearman correlation");
        return correlation(ranks(x), ranks(y));
    }

    /**
     * Ordinary least squares of {@code y} on {@code x} — DuckDB's {@code regr_*} family:
     * <ul>
     *   <li>{@code count} = n, the pairs ({@code regr_count});</li>
     *   <li>{@code slope} = {@code Σ(x − x̄)(y − ȳ) / Σ(x − x̄)²} ({@code regr_slope}); empty when x is constant;</li>
     *   <li>{@code intercept} = {@code ȳ − slope · x̄} ({@code regr_intercept}); empty with the slope;</li>
     *   <li>{@code r2} ({@code regr_r2}): empty when x is constant, <b>1 when y is constant</b> (a flat line fits it
     *       exactly — DuckDB's rule, kept), otherwise the squared {@link #correlation}.</li>
     * </ul>
     * Input rules as {@link #correlation}.
     */
    public static Regression linearRegression(double[] x, double[] y) {
        checkPairs(x, y, "linear regression");
        if (constant(x)) return new Regression(x.length, OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty());
        double mx = mean(x), my = mean(y);
        double sxy = 0, sxx = 0;
        for (int i = 0; i < x.length; i++) {
            double dx = x[i] - mx;
            sxy += dx * (y[i] - my);
            sxx += dx * dx;
        }
        double slope = sxy / sxx;
        double r2;
        if (constant(y)) r2 = 1.0;
        else {
            double r = correlation(x, y).getAsDouble();
            r2 = r * r;
        }
        return new Regression(x.length, OptionalDouble.of(slope), OptionalDouble.of(my - slope * mx), OptionalDouble.of(r2));
    }

    /** {@link #linearRegression}'s result; an empty member has no number (see there). */
    public record Regression(long count, OptionalDouble slope, OptionalDouble intercept, OptionalDouble r2) {}

    private static void checkPairs(double[] x, double[] y, String what) {
        check(x, what);
        check(y, what);
        if (x.length != y.length)
            throw new IllegalArgumentException(what + " needs pairs: " + x.length + " x values, " + y.length + " y values");
    }

    private static boolean constant(double[] v) {
        for (double d : v) if (d != v[0]) return false;
        return true;
    }

    // ---- ratio and rate ------------------------------------------------------------------------------------------

    /**
     * Ratio: {@code numerator / denominator}, or <b>empty when the denominator is zero</b> (±0.0) — there is no number
     * to state, and the result is never NaN. Both arguments must be finite (NaN or ±Infinity throws
     * {@link IllegalArgumentException}); a finite quotient too large for a double overflows to ±Infinity as in IEEE.
     * DuckDB {@code numerator / NULLIF(denominator, 0)} (NULL where this is empty).
     */
    public static OptionalDouble ratio(double numerator, double denominator) {
        if (!Double.isFinite(numerator) || !Double.isFinite(denominator))
            throw new IllegalArgumentException("ratio needs finite values, got " + numerator + " / " + denominator);
        return denominator == 0 ? OptionalDouble.empty() : OptionalDouble.of(numerator / denominator);
    }

    /**
     * Rate: the share {@code events / total} of a count, in {@code [0, 1]} — e.g. failed runs of all runs, null values
     * of all rows. <b>Empty when {@code total} is 0</b> (no trials, no rate; never NaN). {@code events} must be in
     * {@code [0, total]}, otherwise {@link IllegalArgumentException}. DuckDB {@code events::DOUBLE / NULLIF(total, 0)}.
     */
    public static OptionalDouble rate(long events, long total) {
        if (events < 0 || events > total)
            throw new IllegalArgumentException("rate needs 0 <= events <= total, got " + events + " of " + total);
        return total == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) events / total);
    }

    // ---- input rules ---------------------------------------------------------------------------------------------

    private static double sumSquaredDeviations(double[] values) {
        double m = mean(values);
        double ss = 0;
        for (double v : values) ss += (v - m) * (v - m);
        return ss;
    }

    private static double[] sorted(double[] values, String what) {
        check(values, what);
        double[] v = values.clone();
        Arrays.sort(v);
        return v;
    }

    private static void check(double[] values, String what) {
        if (values.length == 0) throw new IllegalArgumentException(what + " of no values");
        for (double v : values) {
            if (Double.isNaN(v)) throw new IllegalArgumentException(what + " of a NaN value: drop missing values first");
        }
    }
}

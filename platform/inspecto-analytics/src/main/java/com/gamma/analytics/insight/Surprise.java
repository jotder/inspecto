package com.gamma.analytics.insight;

import com.gamma.analytics.Stats;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three "what is unusual" tests of the Insight scan (BI-INSIGHTS-1 I-8), each over one slice's series of a Measure
 * (index = bucket, oldest first, {@code null} = no value in that bucket — never read as 0). Each returns a
 * {@link Finding} with a two-sided p-value, or {@code null} when the series cannot carry the test (too few points, no
 * spread). Pure; the p-values are what {@link FalseDiscovery} controls across slices.
 *
 * <ul>
 *   <li>{@link #spike} — robust z of the latest bucket against its history (the same season slot first, when the
 *       history holds enough of them): "an unusual value now".</li>
 *   <li>{@link #changepoint} — the largest CUSUM of the mean: "the level moved and stayed", reported only when the
 *       change lies within the last {@code maxAge} buckets.</li>
 *   <li>{@link #drift} — Mann-Kendall trend with Sen's slope: "a steady climb or slide".</li>
 * </ul>
 */
public final class Surprise {

    private Surprise() {}

    public static final String SPIKE = "anomaly", CHANGEPOINT = "trend-break", DRIFT = "drift";

    /**
     * One test's result. {@code observed} / {@code expected} are the levels compared (spike: the latest value and the
     * baseline median; changepoint: mean after and mean before; drift: Sen's line at the last and the first bucket);
     * {@code statistic} is the test's own (z, scaled CUSUM, Mann-Kendall Z); {@code at} is the bucket index the
     * finding dates from; {@code detail} holds the per-test numbers for the explanation.
     */
    public record Finding(String type, double observed, double expected, double statistic, double p, int at,
                          Map<String, Object> detail) {
        public double delta() { return observed - expected; }
    }

    // ── spike ──

    /**
     * Robust z of the last bucket. History = the earlier buckets in the same slot ({@code season} buckets apart) when at
     * least {@code minPoints} of them hold a value, else every earlier bucket; fewer than {@code minPoints} → {@code null}.
     * {@code unit} is the smallest change that matters (the robust-z floor, {@link Stats#robustZFloor}).
     */
    public static Finding spike(Double[] series, int season, int minPoints, double unit) {
        int n = series.length;
        if (n < 2 || series[n - 1] == null) return null;
        List<Double> hist = new ArrayList<>();
        boolean seasonal = false;
        if (season > 1) {
            for (int i = n - 1 - season; i >= 0; i -= season) if (series[i] != null) hist.add(series[i]);
            seasonal = hist.size() >= minPoints;
        }
        if (!seasonal) {
            hist.clear();
            for (int i = 0; i < n - 1; i++) if (series[i] != null) hist.add(series[i]);
        }
        if (hist.size() < Math.max(minPoints, 3)) return null;
        double[] h = hist.stream().mapToDouble(Double::doubleValue).toArray();
        double median = Stats.median(h), smad = Stats.scaledMad(h, median), floor = Stats.robustZFloor(median, unit);
        if (!(Math.max(smad, floor) > 0)) return null;
        double obs = series[n - 1], z = Stats.robustZ(obs, median, smad, floor);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("z", z);
        d.put("baselinePoints", h.length);
        d.put("seasonal", seasonal);
        return new Finding(SPIKE, obs, median, z, twoSided(z), n - 1, d);
    }

    // ── changepoint ──

    /**
     * The single most likely shift in the mean (CUSUM: the split {@code k} maximising |Σ_{i<k}(x_i − x̄)|, scaled by
     * σ√n with σ the robust spread of first differences ÷ √2, so a trend or a level shift inflates it less than plain
     * variance would). Both segments hold at least {@code minSegment} values. The p-value is the Kolmogorov
     * (Brownian-bridge) tail of the scaled maximum. {@code null} when the series is too short, has no spread, or the
     * change is older than the last {@code maxAge} buckets.
     */
    public static Finding changepoint(Double[] series, int minSegment, int maxAge, double unit) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < series.length; i++) if (series[i] != null) idx.add(i);
        int n = idx.size();
        if (minSegment < 2 || n < 2 * minSegment) return null;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = series[idx.get(i)];
        double[] diffs = new double[n - 1];
        for (int i = 1; i < n; i++) diffs[i - 1] = x[i] - x[i - 1];
        double mean = Stats.mean(x);
        double sigma = Math.max(Stats.scaledMad(diffs, Stats.median(diffs)) / Math.sqrt(2), Stats.robustZFloor(mean, unit));
        if (!(sigma > 0)) return null;
        double cum = 0, best = -1;
        int k = -1;
        for (int i = 0; i < n - minSegment; i++) {
            cum += x[i] - mean;
            int split = i + 1;   // first index of the after-segment
            if (split < minSegment) continue;
            if (Math.abs(cum) > best) {
                best = Math.abs(cum);
                k = split;
            }
        }
        if (k < 0) return null;
        int at = idx.get(k);
        if (series.length - at > maxAge) return null;
        double before = 0, after = 0;
        for (int i = 0; i < k; i++) before += x[i];
        for (int i = k; i < n; i++) after += x[i];
        before /= k;
        after /= n - k;
        double stat = best / (sigma * Math.sqrt(n));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("meanBefore", before);
        d.put("meanAfter", after);
        d.put("bucketsAfter", n - k);
        return new Finding(CHANGEPOINT, after, before, stat, kolmogorovTail(stat), at, d);
    }

    // ── drift ──

    /**
     * Mann-Kendall trend over the non-null buckets (at least {@code minPoints}), ties corrected; Sen's slope per bucket
     * and its line (intercept = median of x_i − slope·t_i). {@code null} for too few points or no variance.
     */
    public static Finding drift(Double[] series, int minPoints) {
        List<Integer> t = new ArrayList<>();
        for (int i = 0; i < series.length; i++) if (series[i] != null) t.add(i);
        int n = t.size();
        if (n < Math.max(minPoints, 4)) return null;
        long s = 0;
        double[] slopes = new double[n * (n - 1) / 2];
        int m = 0;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) {
                double a = series[t.get(i)], b = series[t.get(j)];
                s += Double.compare(b, a) > 0 ? 1 : Double.compare(b, a) < 0 ? -1 : 0;
                slopes[m++] = (b - a) / (t.get(j) - t.get(i));
            }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = series[t.get(i)];
        double var = n * (n - 1.0) * (2 * n + 5) / 18.0;
        double[] sorted = x.clone();
        java.util.Arrays.sort(sorted);
        for (int i = 0; i < n; ) {
            int j = i;
            while (j < n && sorted[j] == sorted[i]) j++;
            int tie = j - i;
            if (tie > 1) var -= tie * (tie - 1.0) * (2 * tie + 5) / 18.0;
            i = j;
        }
        if (!(var > 0)) return null;
        double z = s > 0 ? (s - 1) / Math.sqrt(var) : s < 0 ? (s + 1) / Math.sqrt(var) : 0;
        double slope = Stats.median(slopes);
        double[] resid = new double[n];
        for (int i = 0; i < n; i++) resid[i] = x[i] - slope * t.get(i);
        double intercept = Stats.median(resid);
        double first = intercept + slope * t.get(0), last = intercept + slope * t.get(n - 1);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("slopePerBucket", slope);
        d.put("kendallS", s);
        d.put("points", n);
        return new Finding(DRIFT, last, first, z, twoSided(z), t.get(n - 1), d);
    }

    // ── tails ──

    /** Two-sided normal p-value of {@code z}: {@code erfc(|z| ÷ √2)}. */
    public static double twoSided(double z) {
        return Math.min(1, erfc(Math.abs(z) / Math.sqrt(2)));
    }

    /**
     * Complementary error function, Chebyshev fit with fractional error below 1.2e-7 everywhere (Numerical Recipes
     * {@code erfcc}) — relative, so a tiny tail p stays accurate, which Benjamini-Hochberg needs.
     */
    static double erfc(double x) {
        double z = Math.abs(x), t = 1.0 / (1.0 + 0.5 * z);
        double ans = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? ans : 2 - ans;
    }

    /** P(sup|Brownian bridge| > x) = 2 Σ_{j≥1} (−1)^{j−1} e^{−2j²x²}, held to [0, 1]. */
    static double kolmogorovTail(double x) {
        if (x <= 0) return 1;
        if (x < 0.2) return 1;   // the series converges slowly there and the tail is ~1 anyway
        double sum = 0;
        for (int j = 1; j <= 100; j++) {
            double term = Math.exp(-2.0 * j * j * x * x);
            sum += (j % 2 == 1 ? term : -term);
            if (term < 1e-16) break;
        }
        return Math.max(0, Math.min(1, 2 * sum));
    }
}

package com.gamma.analytics.insight;

import java.util.Arrays;

/**
 * Concentration of a Measure across the values of one dimension (BI-INSIGHTS-1 I-9): how much of the whole the few
 * largest parts hold. Every function takes the parts' amounts (any order) and reads them as shares of their sum; the
 * parts must be non-negative (a share of a sum with negative parts is not a share) — a negative part is refused. An
 * empty or all-zero input has no concentration: each function returns {@code NaN}. Pure; JVM only (see plan §11.10 for
 * why there is no DuckDB form).
 */
public final class Concentration {

    private Concentration() {}

    /** The Insight Type wire name (GLOSSARY §7). */
    public static final String TYPE = "concentration";

    /** Share of the total held by the {@code n} largest parts (all of it when there are {@code n} or fewer). */
    public static double topShare(double[] parts, int n) {
        if (n < 1) throw new IllegalArgumentException("top-N needs n ≥ 1");
        double[] d = descending(parts);
        double total = sum(d);
        if (!(total > 0)) return Double.NaN;
        double top = 0;
        for (int i = 0; i < Math.min(n, d.length); i++) top += d[i];
        return top / total;
    }

    /**
     * The Pareto point: the fewest parts, largest first, whose shares reach {@code share} of the total (0.8 → the
     * "80/20" count). Divide by {@code parts.length} for the fraction of the parts it takes.
     */
    public static double paretoCount(double[] parts, double share) {
        if (!(share > 0 && share <= 1)) throw new IllegalArgumentException("the Pareto share must be in (0, 1]");
        double[] d = descending(parts);
        double total = sum(d);
        if (!(total > 0)) return Double.NaN;
        double cum = 0;
        for (int i = 0; i < d.length; i++) {
            cum += d[i];
            if (cum >= share * total - 1e-12 * total) return i + 1;
        }
        return d.length;
    }

    /** Herfindahl-Hirschman index Σ sᵢ² on shares in [0, 1]: 1 ÷ k for k equal parts, 1 for a single part. */
    public static double hhi(double[] parts) {
        double total = sum(check(parts));
        if (!(total > 0)) return Double.NaN;
        double h = 0;
        for (double p : parts) h += (p / total) * (p / total);
        return h;
    }

    /** Gini coefficient {@code 2 Σ i·x₍ᵢ₎ ÷ (k Σ x) − (k + 1) ÷ k} over the ascending parts (i from 1): 0 = all equal. */
    public static double gini(double[] parts) {
        double[] a = check(parts).clone();
        Arrays.sort(a);
        double total = sum(a);
        if (!(total > 0)) return Double.NaN;
        int k = a.length;
        double w = 0;
        for (int i = 0; i < k; i++) w += (i + 1) * a[i];
        return 2 * w / (k * total) - (k + 1.0) / k;
    }

    private static double[] descending(double[] parts) {
        double[] d = check(parts).clone();
        Arrays.sort(d);
        for (int i = 0, j = d.length - 1; i < j; i++, j--) { double t = d[i]; d[i] = d[j]; d[j] = t; }
        return d;
    }

    private static double[] check(double[] parts) {
        for (double p : parts)
            if (!(p >= 0)) throw new IllegalArgumentException("concentration needs non-negative, finite parts; got " + p);
        return parts;
    }

    private static double sum(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return s;
    }
}

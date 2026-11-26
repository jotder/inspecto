package com.gamma.analytics.insight;

/**
 * How far a distribution moved between two periods (BI-INSIGHTS-1 I-10). Both arguments are amounts over the SAME
 * ordered categories (index = category; a value a period lacks is 0), read as shares of their own sum; amounts must be
 * non-negative. Pure; JVM only.
 *
 * <ul>
 *   <li>{@link #psi} — Population Stability Index Σ (cᵢ − bᵢ)·ln(cᵢ ÷ bᵢ), shares floored at {@link #PSI_FLOOR}
 *       (a value new in one period would otherwise be infinite). Rule of thumb: &lt; 0.1 stable, 0.1–0.25 moderate,
 *       &gt; 0.25 large.</li>
 *   <li>{@link #ksStatistic} — two-sample Kolmogorov-Smirnov D, the largest gap between the two cumulative shares in
 *       the given order; {@link #ksPValue} its asymptotic two-sided p for sample sizes n₁, n₂ (independent units).</li>
 *   <li>{@link #totalVariation} — ½ Σ |cᵢ − bᵢ|: the share of the whole that sits on different values.</li>
 * </ul>
 */
public final class DistributionShift {

    private DistributionShift() {}

    /** The Insight Type wire name (GLOSSARY §7). */
    public static final String TYPE = "distribution-shift";

    public static final double PSI_FLOOR = 1e-4;

    public static double psi(double[] base, double[] current) {
        double[] b = shares(base, current.length), c = shares(current, base.length);
        if (b == null || c == null) return Double.NaN;
        double s = 0;
        for (int i = 0; i < b.length; i++) {
            double bi = Math.max(b[i], PSI_FLOOR), ci = Math.max(c[i], PSI_FLOOR);
            s += (ci - bi) * Math.log(ci / bi);
        }
        return s;
    }

    public static double ksStatistic(double[] base, double[] current) {
        double[] b = shares(base, current.length), c = shares(current, base.length);
        if (b == null || c == null) return Double.NaN;
        double cb = 0, cc = 0, d = 0;
        for (int i = 0; i < b.length; i++) {
            cb += b[i];
            cc += c[i];
            d = Math.max(d, Math.abs(cc - cb));
        }
        return d;
    }

    /**
     * P(D ≥ d) for samples of n₁ and n₂ independent units: the Kolmogorov tail at (√nₑ + 0.12 + 0.11 ÷ √nₑ)·d,
     * nₑ = n₁n₂ ÷ (n₁ + n₂) (Stephens' small-sample correction, Numerical Recipes {@code kstwo}).
     */
    public static double ksPValue(double d, double n1, double n2) {
        if (!(n1 > 0 && n2 > 0) || !(d >= 0)) return Double.NaN;
        double en = Math.sqrt(n1 * n2 / (n1 + n2));
        return Surprise.kolmogorovTail((en + 0.12 + 0.11 / en) * d);
    }

    public static double totalVariation(double[] base, double[] current) {
        double[] b = shares(base, current.length), c = shares(current, base.length);
        if (b == null || c == null) return Double.NaN;
        double s = 0;
        for (int i = 0; i < b.length; i++) s += Math.abs(c[i] - b[i]);
        return s / 2;
    }

    /** The amounts as shares of their sum; {@code null} when the sum is not positive. */
    static double[] shares(double[] v, int otherLength) {
        if (v.length != otherLength) throw new IllegalArgumentException("both periods need the same categories");
        double t = 0;
        for (double x : v) {
            if (!(x >= 0)) throw new IllegalArgumentException("a distribution needs non-negative, finite amounts; got " + x);
            t += x;
        }
        if (!(t > 0)) return null;
        double[] s = new double[v.length];
        for (int i = 0; i < v.length; i++) s[i] = v[i] / t;
        return s;
    }
}

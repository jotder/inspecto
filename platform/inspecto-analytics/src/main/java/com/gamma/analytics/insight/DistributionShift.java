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

    /**
     * P-value of {@code x} against reference values of the same statistic: the empirical rank (# refs ≥ x + 1) ÷ (n + 1)
     * while {@code x} lies within the references; beyond the largest, the upper tail of a scaled chi-square c·χ²(ν)
     * matched to the references' mean m and variance v (c = v ÷ 2m, ν = 2m² ÷ v, Satterthwaite) — PSI is a scaled
     * divergence, so a gross shift gets a p below the rank floor 1 ÷ (n + 1), never above it. NaN for fewer than 2 refs.
     */
    public static double referenceP(double x, double[] refs) {
        int n = refs.length;
        if (n < 2) return Double.NaN;
        int atLeast = 0;
        double m = 0, max = Double.NEGATIVE_INFINITY;
        for (double r : refs) { if (r >= x) atLeast++; m += r; max = Math.max(max, r); }
        double rank = (atLeast + 1.0) / (n + 1);
        if (x <= max) return rank;
        m /= n;
        double v = 0;
        for (double r : refs) v += (r - m) * (r - m);
        v /= n - 1;
        if (!(m > 0 && v > 0)) return rank;
        double c = v / (2 * m), df = 2 * m * m / v;
        return Math.min(rank, chiSquareTail(x / c, df));
    }

    /** P(χ²(df) &gt; x) = Q(df ÷ 2, x ÷ 2), the regularized upper incomplete gamma (Numerical Recipes {@code gammq}). */
    public static double chiSquareTail(double x, double df) {
        if (!(df > 0)) throw new IllegalArgumentException("df must be positive");
        if (x <= 0) return 1;
        double a = df / 2, y = x / 2, gln = logGamma(a);
        if (y < a + 1) {   // series for P, Q = 1 − P
            double ap = a, sum = 1 / a, del = sum;
            for (int i = 0; i < 1000 && Math.abs(del) > Math.abs(sum) * 1e-15; i++) { ap++; del *= y / ap; sum += del; }
            return Math.max(0, 1 - sum * Math.exp(-y + a * Math.log(y) - gln));
        }
        double b = y + 1 - a, cc = 1 / 1e-300, d = 1 / b, h = d;   // Lentz continued fraction for Q
        for (int i = 1; i < 1000; i++) {
            double an = -i * (i - a);
            b += 2;
            d = an * d + b; if (Math.abs(d) < 1e-300) d = 1e-300;
            cc = b + an / cc; if (Math.abs(cc) < 1e-300) cc = 1e-300;
            d = 1 / d;
            double del = d * cc;
            h *= del;
            if (Math.abs(del - 1) < 1e-15) break;
        }
        return Math.exp(-y + a * Math.log(y) - gln) * h;
    }

    /** ln Γ(x), Lanczos (Numerical Recipes {@code gammln}), x &gt; 0. */
    static double logGamma(double x) {
        double[] cof = {76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155,
                0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x, tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double c : cof) ser += c / ++y;
        return -tmp + Math.log(2.5066282746310005 * ser / x);
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

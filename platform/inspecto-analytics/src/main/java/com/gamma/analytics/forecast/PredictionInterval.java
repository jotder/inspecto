package com.gamma.analytics.forecast;

/**
 * The prediction interval of a forecast (ANALYTICS-CORE-1 S5 (e)): a normal approximation whose spread is estimated
 * robustly from the in-sample one-step-ahead errors.
 *
 * <pre>
 *   sigma          = 1.4826 · MAD(e)              e(t) = y(t) − ŷ(t), every in-sample bucket after the first season
 *   half-width(h)  = z(level) · sigma · sqrt(v(h))
 *   lower / upper  = ŷ ∓ half-width
 * </pre>
 *
 * {@code z(level)} is the standard normal quantile at {@code (1 + level) / 2} (1.96 for 0.95). {@code v(h)} grows the
 * band with the horizon, from each method's own error recursion: in-sample rows use {@code v(1) = 1}.
 * <ul>
 *   <li>Holt-Winters additive (the ETS(A,A,A) form of the recursion): {@code v(h) = 1 + Σ_{j=1}^{h−1} c_j²},
 *       {@code c_j = α(1 + jβ) + γ(1 − α)·[j mod period = 0]} — the smoothing weights a one-step error carries into
 *       the level, trend and seasonal term {@code j} buckets later.</li>
 *   <li>Seasonal naive: {@code v(h) = ⌊(h − 1) / period⌋ + 1} — each further season repeats an older value.</li>
 * </ul>
 * The scaled MAD ({@code Stats.scaledMad}) is used rather than the root mean square so that one planted spike in the
 * history does not widen every band; it is centred on the errors' median, the band on the point forecast.
 */
public final class PredictionInterval {
    private PredictionInterval() {}

    /** How the interval is computed, as recorded beside a forecast. */
    public static final String METHOD = "normal, sigma = 1.4826 x MAD of the in-sample one-step errors";

    /**
     * The standard normal quantile Φ⁻¹(p), 0 &lt; p &lt; 1 — P. J. Acklam's rational approximation, relative error
     * below 1.2e-9 (no refinement step: the JDK has no erf).
     */
    public static double normalQuantile(double p) {
        if (!(p > 0 && p < 1)) throw new IllegalArgumentException("normal quantile needs 0 < p < 1, got " + p);
        final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00};
        final double low = 0.02425;
        if (p < low) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - low) return -normalQuantile(1 - p);
        double q = p - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }

    /** {@code z(level)}: the two-sided multiplier of a central interval holding {@code level} (0 &lt; level &lt; 1). */
    public static double z(double level) {
        if (!(level > 0 && level < 1)) throw new IllegalArgumentException("interval level must be between 0 and 1, got " + level);
        return normalQuantile((1 + level) / 2);
    }

    /** {@code v(1) … v(horizon)} for {@code spec}, as the class note defines them. */
    public static double[] varianceFactors(Forecaster.Spec spec) {
        double[] v = new double[spec.horizon()];
        double sum = 0;
        for (int h = 1; h <= spec.horizon(); h++) {
            if (spec.method() == ForecastMethod.SEASONAL_NAIVE) {
                v[h - 1] = (h - 1) / spec.period() + 1;
            } else {
                v[h - 1] = 1 + sum;
                double cj = spec.alpha() * (1 + h * spec.beta())
                        + (h % spec.period() == 0 ? spec.gamma() * (1 - spec.alpha()) : 0);
                sum += cj * cj;
            }
        }
        return v;
    }
}

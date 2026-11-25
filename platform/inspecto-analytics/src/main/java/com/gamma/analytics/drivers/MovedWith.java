package com.gamma.analytics.drivers;

/**
 * "Moved with it" (BI-INSIGHTS-1 I-6): how closely another Measure's bucket-to-bucket CHANGES followed this Measure's
 * over the same aligned buckets — the Pearson correlation of the first differences. Differences, not levels: two
 * Measures that both trend upward correlate in level whatever their relation. A bucket where either value is missing
 * breaks the pairs touching it (a missing value is never 0). Correlation says the two moved together; it never says
 * one caused the other.
 */
public final class MovedWith {

    private MovedWith() {}

    /** The correlation of the changes and how many change pairs it rests on; {@code r} is {@code NaN} when undefined. */
    public record Result(double r, int pairs) {}

    /** {@code a} and {@code b} are aligned series (index = bucket), {@code null} = no value in that bucket. */
    public static Result ofChanges(Double[] a, Double[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("series are not aligned: " + a.length + " vs " + b.length);
        int n = 0;
        double[] x = new double[a.length], y = new double[a.length];
        for (int i = 1; i < a.length; i++) {
            if (a[i] == null || a[i - 1] == null || b[i] == null || b[i - 1] == null) continue;
            x[n] = a[i] - a[i - 1];
            y[n] = b[i] - b[i - 1];
            n++;
        }
        return new Result(pearson(x, y, n), n);
    }

    /** Pearson's r over the first {@code n} pairs; {@code NaN} for fewer than 3 pairs or a constant side. */
    public static double pearson(double[] x, double[] y, int n) {
        if (n < 3) return Double.NaN;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx, dy = y[i] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        if (sxx == 0 || syy == 0) return Double.NaN;
        return Math.max(-1, Math.min(1, sxy / Math.sqrt(sxx * syy)));
    }
}

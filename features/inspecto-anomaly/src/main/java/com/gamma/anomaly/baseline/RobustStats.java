package com.gamma.anomaly.baseline;

import java.util.Collection;

/** Robust centre and spread (design §4.2): median, MAD x 1.4826, and the robust z with its floor. Pure. */
public final class RobustStats {

    /** Makes the MAD consistent with sigma for a normal distribution. */
    public static final double MAD_SCALE = 1.4826;
    /** Floor share of |median| (design §4.2: max(1 unit, 5 % of |median|)). */
    public static final double FLOOR_SHARE = 0.05;

    private RobustStats() {}

    public static double median(Collection<Double> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("median of no values");
        double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = v.length;
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2.0;
    }

    /** Scaled MAD: 1.4826 x median(|x - median|). */
    public static double scaledMad(Collection<Double> values, double median) {
        return MAD_SCALE * median(values.stream().map(x -> Math.abs(x - median)).toList());
    }

    /** The floor: max(unit, 5 % of |median|). */
    public static double floor(double median, double unit) {
        return Math.max(unit, FLOOR_SHARE * Math.abs(median));
    }

    /** z = (observed - median) / max(scaledMad, floor). */
    public static double z(double observed, double median, double scaledMad, double floor) {
        return (observed - median) / Math.max(scaledMad, floor);
    }
}

package com.gamma.analytics.baseline;

import com.gamma.analytics.Stats;
import java.util.Collection;

/**
 * Robust centre and spread (design §4.2): median, MAD x 1.4826, and the robust z with its floor. Pure. Since
 * ANALYTICS-CORE-1 M6 every member delegates to the canonical definition in {@link Stats}; this class keeps the
 * {@code Collection<Double>} shape the baselines use. Empty input throws ("median of no values"); NaN input throws
 * (every caller drops missing values first).
 */
public final class RobustStats {

    /** Makes the MAD consistent with sigma for a normal distribution. */
    public static final double MAD_SCALE = Stats.MAD_SCALE;
    /** Floor share of |median| (design §4.2: max(1 unit, 5 % of |median|)). */
    public static final double FLOOR_SHARE = Stats.ROBUST_Z_FLOOR_SHARE;

    private RobustStats() {}

    /** {@link Stats#median(double[])}: the average of the middle pair for an even count. */
    public static double median(Collection<Double> values) {
        return Stats.median(toArray(values));
    }

    /** Scaled MAD: 1.4826 x median(|x - median|). */
    public static double scaledMad(Collection<Double> values, double median) {
        return Stats.scaledMad(toArray(values), median);
    }

    /** The floor: max(unit, 5 % of |median|). */
    public static double floor(double median, double unit) {
        return Stats.robustZFloor(median, unit);
    }

    /** z = (observed - median) / max(scaledMad, floor). */
    public static double z(double observed, double median, double scaledMad, double floor) {
        return Stats.robustZ(observed, median, scaledMad, floor);
    }

    private static double[] toArray(Collection<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }
}

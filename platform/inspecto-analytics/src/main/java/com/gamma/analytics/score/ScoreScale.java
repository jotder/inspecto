package com.gamma.analytics.score;

import java.util.Map;
import java.util.TreeMap;

/**
 * The 0-100 score scale and its bands, shared by every score kind (D-AD6: the same tile and Alert Rule shape for an
 * Anomaly Score and a Risk Score). How a kind reaches the scale is its own: a Risk Score sums capped contributions and
 * {@link #clamp clamps}; an Anomaly Score maps an unbounded deviation through {@link #saturating}.
 */
public final class ScoreScale {
    private ScoreScale() {}

    public static final double MIN = 0.0;
    public static final double MAX = 100.0;

    /** Band names, as the {@code band} column and the run's band counts carry them. */
    public static final String NORMAL = "normal";
    public static final String ELEVATED = "elevated";
    public static final String HIGH = "high";

    /** {@code s} held to {@code [0, 100]}. */
    public static double clamp(double s) {
        return Math.max(MIN, Math.min(MAX, s));
    }

    /** {@code 100 * (1 - exp(-raw / scale))}: 0 at raw 0, approaching 100; about 63 at {@code raw = scale}. */
    public static double saturating(double raw, double scale) {
        return MAX * (1.0 - Math.exp(-raw / scale));
    }

    /** Whether {@code score} reaches the high threshold. Inclusive: a score equal to it is high. */
    public static boolean isHigh(double score, double highThreshold) {
        return score >= highThreshold;
    }

    /** {@link #HIGH} at or above {@code high}, {@link #ELEVATED} at or above {@code elevated}, else {@link #NORMAL}. */
    public static String band(double score, double elevated, double high) {
        return score >= high ? HIGH : score >= elevated ? ELEVATED : NORMAL;
    }

    /** Entities per band, every band present (0 when none), in name order. */
    public static Map<String, Long> bandCounts(Iterable<String> bands) {
        Map<String, Long> out = new TreeMap<>();
        for (String b : new String[]{ELEVATED, HIGH, NORMAL}) out.put(b, 0L);
        for (String b : bands) out.merge(b, 1L, Long::sum);
        return out;
    }
}

package com.gamma.anomaly.baseline;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Self baseline of one entity and feature, with seasonality and its fallback chain (design §4.2, §15). The scored
 * bucket itself is always excluded, so an anomaly cannot sit in its own baseline. Named so S1's baseline interface
 * can adopt it at merge (operator, 2026-10-10).
 */
public record SeasonalBaseline(Seasonality seasonality, int minBaselinePoints) implements BaselineStatistic {

    public static final String KIND = "self";
    public static final int DEFAULT_MIN_POINTS = 7;

    public SeasonalBaseline {
        if (seasonality == null) throw new IllegalArgumentException("seasonality is required");
        if (minBaselinePoints < 1) throw new IllegalArgumentException("minBaselinePoints must be >= 1");
    }

    /**
     * @param history bucket start time to value, for one entity and one feature, within the baseline window
     *                (missing buckets are the caller's zero-inflation decision, §4.2)
     * @param scored  the scored bucket's start time (excluded from the baseline)
     */
    @Override public String kind() { return KIND; }

    @Override
    public Baseline compute(Map<LocalDateTime, Double> history, LocalDateTime scored) {
        String requested = seasonality.label(scored);
        int most = 0;
        for (Seasonality s : seasonality.fallbackChain()) {
            List<Double> slot = new ArrayList<>();
            history.forEach((t, v) -> {
                if (!t.equals(scored) && v != null && !v.isNaN() && s.sameSlot(t, scored)) slot.add(v);
            });
            most = Math.max(most, slot.size());
            if (slot.size() >= minBaselinePoints) {
                double m = RobustStats.median(slot);
                boolean fell = s != seasonality;
                String reason = fell
                        ? "%s has fewer than %d points; fell back to %s (%d points)"
                            .formatted(requested, minBaselinePoints, s.label(scored), slot.size())
                        : "%s, %d points".formatted(requested, slot.size());
                return new Baseline(KIND, m, RobustStats.scaledMad(slot, m), slot.size(), s.label(scored),
                        requested, fell, false, reason);
            }
        }
        return Baseline.insufficient(KIND, most, requested,
                "insufficient self history: %d points, %d needed".formatted(most, minBaselinePoints));
    }
}

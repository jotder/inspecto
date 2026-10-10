package com.gamma.anomaly.baseline;

/**
 * One computed baseline: the {@code baseline} / {@code peerBaseline} member of the explanation record (design §5).
 *
 * @param kind         {@code self} or {@code peer}
 * @param median       robust centre (NaN when insufficient)
 * @param scaledMad    MAD x 1.4826 (NaN when insufficient)
 * @param points       history points (self) or cohort size (peer) used; when insufficient, the most found
 * @param basis        self: the season label actually used; peer: the cohort key, or "population"; null if insufficient
 * @param requested    self: the season label requested; peer: the cohort key requested
 * @param fellBack     true when a coarser season (self) or the population (peer, opt-in) replaced the requested one
 * @param insufficient true when no baseline could be formed (too little history, or a D-AD11 small cohort)
 * @param reason       generated from the numbers, never free text
 */
public record Baseline(String kind, double median, double scaledMad, int points, String basis, String requested,
                       boolean fellBack, boolean insufficient, String reason) {

    public static Baseline insufficient(String kind, int points, String requested, String reason) {
        return new Baseline(kind, Double.NaN, Double.NaN, points, null, requested, false, true, reason);
    }

    /** Robust z of {@code observed} against this baseline, floor = max(unit, 5 % of |median|); NaN when insufficient. */
    public double z(double observed, double unit) {
        if (insufficient) return Double.NaN;
        return RobustStats.z(observed, median, scaledMad, RobustStats.floor(median, unit));
    }
}

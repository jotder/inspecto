package com.gamma.anomaly.baseline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Peer baseline (design §4.3): median / MAD of the per-entity observed values of every entity in a cohort for the
 * scored period. A cohort below {@code minGroupSize} is insufficient (D-AD11 a); {@code populationFallback} is the
 * opt-in {@code peers.fallback: population} (D-AD11 b), always flagged via {@link Baseline#fellBack()}.
 */
public record PeerBaseline(int minGroupSize, boolean populationFallback) {

    public static final String KIND = "peer";
    public static final String POPULATION = "population";
    public static final int DEFAULT_MIN_GROUP_SIZE = 30;

    public PeerBaseline {
        if (minGroupSize < 1) throw new IllegalArgumentException("minGroupSize must be >= 1");
    }

    /**
     * @param observed entity to observed value for the scored period (the whole population)
     * @param cohortOf entity to cohort key; an entity without one is in the population only
     * @param cohort   the cohort to baseline
     */
    public Baseline compute(Map<String, Double> observed, Map<String, String> cohortOf, String cohort) {
        List<Double> members = new ArrayList<>();
        observed.forEach((e, v) -> {
            if (usable(v) && cohort != null && cohort.equals(cohortOf.get(e))) members.add(v);
        });
        if (members.size() >= minGroupSize) {
            return of(members, cohort, cohort, false, "peers %s, %d entities".formatted(cohort, members.size()));
        }
        if (populationFallback) {
            List<Double> all = observed.values().stream().filter(PeerBaseline::usable).toList();
            if (all.size() >= minGroupSize) {
                return of(all, POPULATION, cohort, true,
                        "cohort %s has %d < %d entities; fell back to the population (%d)"
                                .formatted(cohort, members.size(), minGroupSize, all.size()));
            }
        }
        return Baseline.insufficient(KIND, members.size(), cohort,
                "cohort %s has %d < %d entities".formatted(cohort, members.size(), minGroupSize));
    }

    private static boolean usable(Double v) {
        return v != null && !v.isNaN();
    }

    private static Baseline of(List<Double> values, String basis, String requested, boolean fell, String reason) {
        double m = RobustStats.median(values);
        return new Baseline(KIND, m, RobustStats.scaledMad(values, m), values.size(), basis, requested, fell, false,
                reason);
    }
}

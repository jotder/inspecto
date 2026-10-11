package com.gamma.anomaly;

import com.gamma.anomaly.baseline.Baseline;
import com.gamma.anomaly.baseline.BaselineStatistic;
import com.gamma.anomaly.baseline.BaselineStatistics;
import com.gamma.anomaly.baseline.PeerBaseline;
import com.gamma.anomaly.baseline.RobustStats;
import com.gamma.anomaly.baseline.Seasonality;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ANOMALY-PEER-GROUPING-1: the grouped {@code AnomalyScoreEvaluator.PeerSide} must be behaviour-identical to the
 * per-cohort population scan it replaced. {@link LegacyPeerSide} is that old implementation, kept verbatim as a
 * test-only reference; randomised runs compare every entity x Feature outcome (baseline fields, reason, shift).
 */
class PeerSideEquivalenceTest {

    private static final LocalDateTime SCORED = LocalDate.parse("2026-09-30").atStartOfDay();

    /** The pre-grouping implementation (AnomalyScoreEvaluator.PeerSide at 074cd95d4). */
    private static final class LegacyPeerSide {
        private final PeerBaseline peer;
        private final Map<String, String> cohortOf, labelOf;
        private final Map<String, Map<String, Map<LocalDateTime, Double>>> histories;
        private final Map<String, Map<String, Double>> observedByFeature;
        private final BaselineStatistic statistic;
        private final Map<String, AnomalyScoreEvaluator.PeerSide.Cohort> cache = new LinkedHashMap<>();

        LegacyPeerSide(PeerBaseline peer, AnomalyScoreEvaluator.Cohorts c,
                       Map<String, Map<String, Map<LocalDateTime, Double>>> histories,
                       Map<String, Map<String, Double>> observedByFeature, BaselineStatistic statistic) {
            this.peer = peer;
            this.cohortOf = c.keyOf();
            this.labelOf = c.labelOf();
            this.histories = histories;
            this.observedByFeature = observedByFeature;
            this.statistic = statistic;
        }

        AnomalyScoreEvaluator.PeerSide.Cohort of(String feature, String entity) {
            String cohort = cohortOf.get(entity);
            return cache.computeIfAbsent(feature + "\u0000" + cohort, k -> compute(feature, cohort));
        }

        private AnomalyScoreEvaluator.PeerSide.Cohort compute(String feature, String cohort) {
            Baseline b = peer.compute(observedByFeature.get(feature), cohortOf, cohort, labelOf.get(cohort));
            if (b.insufficient()) return new AnomalyScoreEvaluator.PeerSide.Cohort(b, 1.0);
            boolean population = b.fellBack();
            Map<LocalDateTime, List<Double>> byDay = new TreeMap<>();
            histories.forEach((entity, perFeature) -> {
                if (!population && !cohort.equals(cohortOf.get(entity))) return;
                perFeature.get(feature).forEach((d, v) -> {
                    if (v != null && !v.isNaN()) byDay.computeIfAbsent(d, x -> new ArrayList<>()).add(v);
                });
            });
            Map<LocalDateTime, Double> medians = new TreeMap<>();
            byDay.forEach((d, vs) -> medians.put(d, RobustStats.median(vs)));
            Baseline usual = statistic.compute(medians, SCORED);
            double k = usual.insufficient() || !(usual.median() > 0) ? 1.0 : b.median() / usual.median();
            return new AnomalyScoreEvaluator.PeerSide.Cohort(b, k);
        }
    }

    private static AnomalyModel.Feature feature(String id) {
        return new AnomalyModel.Feature(id, id, "d", "k", "t", "count", List.of(), null, 1.0, 1.0);
    }

    private static void runCase(long seed, int entities, int cohorts, int min, boolean fallback, double nullShare) {
        Random rnd = new Random(seed);
        List<String> features = List.of("f1", "f2");
        Map<String, String> keyOf = new LinkedHashMap<>(), labelOf = new LinkedHashMap<>();
        Map<String, Map<String, Map<LocalDateTime, Double>>> histories = new TreeMap<>();
        Map<String, Map<String, Double>> observed = new LinkedHashMap<>();
        for (String f : features) observed.put(f, new LinkedHashMap<>());
        for (int i = 0; i < entities; i++) {
            String e = "e%05d".formatted(i);
            if (rnd.nextDouble() >= nullShare) {               // else: no peers row, population only
                int c = rnd.nextInt(cohorts);
                String key = c == 0 ? "population" : "v2:c" + c;   // a cohort literally named "population"
                keyOf.put(e, key);
                labelOf.put(key, c == 0 ? "population" : c == 1 ? "\u2205|x" : "c" + c);
            }
            Map<String, Map<LocalDateTime, Double>> pf = new LinkedHashMap<>();
            for (String f : features) {
                Map<LocalDateTime, Double> h = new TreeMap<>();
                for (int d = 1; d <= 14; d++) {
                    double r = rnd.nextDouble();
                    h.put(SCORED.minusDays(d), r < 0.1 ? null : r < 0.15 ? Double.NaN : 50 + rnd.nextInt(100) * (1 + i % 3));
                }
                pf.put(f, h);
                double r = rnd.nextDouble();
                observed.get(f).put(e, r < 0.1 ? null : r < 0.15 ? Double.NaN : 40.0 + rnd.nextInt(200));
            }
            histories.put(e, pf);
        }
        AnomalyScoreEvaluator.Cohorts cohortMap = new AnomalyScoreEvaluator.Cohorts(keyOf, labelOf);
        BaselineStatistic st = BaselineStatistics.forModel(Seasonality.NONE, 3);
        PeerBaseline peer = new PeerBaseline(min, fallback);
        LegacyPeerSide old = new LegacyPeerSide(peer, cohortMap, histories, observed, st);
        AnomalyScoreEvaluator.PeerSide now = new AnomalyScoreEvaluator.PeerSide(peer, cohortMap, histories, observed, st, SCORED);
        for (String e : histories.keySet())
            for (String f : features)
                assertEquals(old.of(f, e), now.of(feature(f), e), "seed " + seed + " entity " + e + " feature " + f);
    }

    @Test
    void groupedPeerSideMatchesThePerCohortScanAcrossShapes() {
        long seed = 1;
        for (int entities : new int[] {1, 7, 60, 300})
            for (int cohorts : new int[] {1, 2, 5, 40})
                for (int min : new int[] {1, 2, 5, 30})
                    for (boolean fallback : new boolean[] {false, true})
                        for (double nullShare : new double[] {0, 0.3, 1})
                            runCase(seed++, entities, cohorts, min, fallback, nullShare);
    }
}

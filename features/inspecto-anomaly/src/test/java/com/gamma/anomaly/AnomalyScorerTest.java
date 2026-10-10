package com.gamma.anomaly;

import com.gamma.anomaly.baseline.Baseline;
import com.gamma.anomaly.baseline.BaselineStatistic;
import com.gamma.anomaly.baseline.BaselineStatistics;
import com.gamma.anomaly.baseline.Seasonality;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** {@link AnomalyScorer}: pure, on plain numbers. */
class AnomalyScorerTest {

    private static final LocalDateTime SCORED = LocalDateTime.parse("2026-09-30T00:00");

    private static AnomalyModel model(String direction1, double weight2) {
        Map<String, Object> m = new LinkedHashMap<>(AnomalyCorpus.model());
        List<Map<String, Object>> fs = ((List<Map<String, Object>>) m.get("features")).stream()
                .map(LinkedHashMap::new).map(x -> (Map<String, Object>) x).toList();
        fs.get(0).put("direction", direction1);
        fs.get(1).put("weight", weight2);
        m.put("features", fs);
        return AnomalyModel.fromMap("usage", m);
    }

    /** A 28-day history of {@code 100 ± k} (median 100, scaled MAD 1.4826·k for alternating values). */
    private static Baseline baseline(double k, int days) {
        Map<LocalDateTime, Double> h = new TreeMap<>();
        for (int d = 1; d <= days; d++) h.put(SCORED.minusDays(d), d % 2 == 0 ? 100 + k : 100 - k);
        BaselineStatistic s = BaselineStatistics.forModel(Seasonality.NONE, 7);
        return s.compute(h, SCORED);
    }

    private static Map<String, AnomalyScorer.Input> inputs(Double mb, Baseline bmb, Double sessions, Baseline bs) {
        Map<String, AnomalyScorer.Input> in = new LinkedHashMap<>();
        in.put("data_mb", new AnomalyScorer.Input(mb, bmb));
        in.put("sessions", new AnomalyScorer.Input(sessions, bs));
        return in;
    }

    @Test
    void robustZWeightedRmsAndTheScoreMapping() {
        AnomalyModel m = model("up", 1);
        Baseline b = baseline(10, 28);   // median 100, MAD 10 x 1.4826 (median |x - 100| = 10)
        assertEquals(100, b.median(), 1e-12);
        assertEquals(14.826, b.scaledMad(), 1e-9);
        AnomalyScorer.Scored s = AnomalyScorer.score(m, "e", "2026-09-30", inputs(100 + 3 * 14.826, b, 100.0, b));
        assertEquals(3.0, s.features().get(0).zSelf(), 1e-9);
        assertEquals(Math.sqrt(9.0 / 2), s.raw(), 1e-9, "weighted RMS of (3, 0)");
        assertEquals(100 * (1 - Math.exp(-Math.sqrt(4.5) / 3)), s.score(), 1e-9);
        assertEquals(1.0, s.features().get(0).share(), 1e-12, "the only deviating feature explains all of raw^2");
        assertEquals(0.0, s.features().get(1).share(), 1e-12);
        assertEquals(63.2, AnomalyScorer.score(3, 3), 0.05, "z 3 -> ~63 (design 4.4)");
        assertEquals(86.5, AnomalyScorer.score(6, 3), 0.05, "z 6 -> ~86");
    }

    @Test
    void directionAndZCap() {
        Baseline b = baseline(10, 28);
        assertEquals(0.0, AnomalyScorer.score(model("up", 1), "e", "d", inputs(0.0, b, 100.0, b)).raw(), "a fall on 'up'");
        assertTrue(AnomalyScorer.score(model("down", 1), "e", "d", inputs(0.0, b, 100.0, b)).raw() > 0, "a fall on 'down'");
        assertTrue(AnomalyScorer.score(model("both", 1), "e", "d", inputs(0.0, b, 100.0, b)).raw() > 0, "a fall on 'both'");
        AnomalyScorer.Scored huge = AnomalyScorer.score(model("up", 1), "e", "d", inputs(1e9, b, 100.0, b));
        assertEquals(10.0, huge.features().get(0).deviation(), "held to zCap");
    }

    @Test
    void insufficientFeaturesContributeZeroAndAreLeftOutOfTheWeights() {
        Baseline ok = baseline(10, 28), thin = baseline(10, 3);
        assertTrue(thin.insufficient());
        AnomalyScorer.Scored s = AnomalyScorer.score(model("up", 1), "e", "d", inputs(100 + 3 * 14.826, ok, 999.0, thin));
        assertEquals(1, s.insufficientCount());
        assertEquals(3.0, s.raw(), 1e-9, "Σw covers only the sufficient feature: raw = its own dev");
        AnomalyScorer.FeatureResult sess = s.features().stream().filter(f -> f.feature().equals("sessions")).findFirst().orElseThrow();
        assertTrue(sess.insufficient());
        assertEquals(0.0, sess.contribution());
        assertTrue(sess.reason().contains("3 baseline point(s)"), sess.reason());

        AnomalyScorer.Scored none = AnomalyScorer.score(model("up", 1), "e", "d", inputs(null, ok, 1.0, thin));
        assertEquals(2, none.insufficientCount(), "an absent observation is insufficient too");
        assertEquals(0.0, none.score());
        assertEquals("normal", none.band());
    }

    @Test
    void featuresAreSortedByContributionAndRecomputeMatches() {
        Baseline b = baseline(10, 28);
        AnomalyScorer.Scored s = AnomalyScorer.score(model("up", 4), "e", "d", inputs(130.0, b, 160.0, b));
        assertEquals("sessions", s.features().get(0).feature(), "the larger contribution first");
        List<Map<String, Object>> stored = s.features().stream().map(AnomalyScorer.FeatureResult::toMap).toList();
        double[] r = AnomalyScorer.recompute(stored, 10, 3);
        assertEquals(s.raw(), r[0], 1e-12);
        assertEquals(s.score(), r[1], 1e-12);
    }

    @Test
    void theMadFloorStopsAFlatHistoryFromReachingInfinity() {
        Map<LocalDateTime, Double> h = new TreeMap<>();
        for (int d = 1; d <= 28; d++) h.put(SCORED.minusDays(d), 5.0);
        Baseline flat = BaselineStatistics.forModel(Seasonality.NONE, 7).compute(h, SCORED);
        AnomalyScorer.Scored s = AnomalyScorer.score(model("up", 1), "e", "d", inputs(6.0, flat, 5.0, flat));
        assertEquals(1.0, s.features().stream().filter(f -> f.feature().equals("data_mb")).findFirst().orElseThrow().zSelf(), 1e-12);
    }
}

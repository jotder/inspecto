package com.gamma.risk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The Risk Score arithmetic: weights, caps, missing indicators, the 0–100 clamp and reproducibility. */
class RiskScorerTest {

    private static final double EPS = 1e-9;

    private static RiskScoreModel model(double high) {
        return RiskScoreModel.fromMap("subs", Map.of("entityType", "subscriber", "highThreshold", high,
                "factors", List.of(
                        Map.of("id", "failed_topups", "dataset", "topups", "key", "msisdn", "measure", "count",
                                "weight", 10, "cap", 25),
                        Map.of("id", "sim_swaps", "dataset", "swaps", "key", "msisdn", "measure", "count",
                                "weight", 20),
                        Map.of("id", "spend", "dataset", "topups", "key", "msisdn", "measure", "sum(amount)",
                                "weight", 0.01))));
    }

    @Test
    void contributionIsWeightTimesIndicatorAndTheScoreIsTheirSum() {
        RiskScorer.Scored s = RiskScorer.score(model(50), "m2",
                Map.of("failed_topups", 1.0, "sim_swaps", 1.0, "spend", 100.0), Map.of());
        assertEquals(10.0, s.factors().get(0).contribution(), EPS);
        assertEquals(20.0, s.factors().get(1).contribution(), EPS);
        assertEquals(1.0, s.factors().get(2).contribution(), EPS);
        assertEquals(31.0, s.score(), EPS);
        assertFalse(s.high(), "31 is below the high threshold of 50");
    }

    @Test
    void theCapBoundsTheContributionNotTheIndicator() {
        RiskScorer.Scored s = RiskScorer.score(model(50), "m1",
                Map.of("failed_topups", 3.0, "sim_swaps", 0.0, "spend", 0.0), Map.of());
        RiskScorer.FactorResult f = s.factors().get(0);
        assertEquals(3.0, f.value(), EPS, "the indicator is reported as measured");
        assertTrue(f.capped());
        assertEquals(25.0, f.contribution(), EPS, "10 × 3 = 30 is held to the cap of 25");
        assertEquals(25.0, s.score(), EPS);

        RiskScorer.FactorResult under = RiskScorer.score(model(50), "m", Map.of("failed_topups", 2.0), Map.of())
                .factors().get(0);
        assertFalse(under.capped(), "20 is under the cap");
        assertEquals(20.0, under.contribution(), EPS);
    }

    @Test
    void aMissingIndicatorCountsAsZeroAndIsFlagged() {
        RiskScorer.Scored s = RiskScorer.score(model(50), "m4", Map.of("sim_swaps", 1.0), Map.of());
        RiskScorer.FactorResult failed = s.factors().get(0);
        assertTrue(failed.missing(), "no value for the factor is flagged, not dropped");
        assertNull(failed.value());
        assertEquals(0.0, failed.contribution(), EPS);
        assertEquals(3, s.factors().size(), "every factor is listed, missing or not");
        assertEquals(20.0, s.score(), EPS);
        assertFalse(s.factors().get(1).missing());
    }

    @Test
    void theScoreIsClampedToZeroToHundredAndHighIsInclusive() {
        RiskScorer.Scored big = RiskScorer.score(model(50), "m", Map.of("sim_swaps", 9.0), Map.of());
        assertEquals(100.0, big.score(), EPS, "180 clamps to 100");
        assertTrue(big.high());

        RiskScoreModel negative = RiskScoreModel.fromMap("neg", Map.of("entityType", "account", "highThreshold", 10,
                "factors", List.of(Map.of("id", "tenure", "dataset", "a", "key", "k", "measure", "max(years)",
                        "weight", -5))));
        assertEquals(0.0, RiskScorer.score(negative, "k", Map.of("tenure", 4.0), Map.of()).score(), EPS,
                "a protective (negative) factor cannot push the score below 0");

        RiskScorer.Scored exact = RiskScorer.score(model(20), "m", Map.of("sim_swaps", 1.0), Map.of());
        assertTrue(exact.high(), "a score equal to the threshold is high");
    }

    @Test
    void theCapBoundsANegativeContributionToo() throws Exception {
        RiskScoreModel m = RiskScoreModel.fromMap("prot", Map.of("entityType", "account", "highThreshold", 10,
                "factors", List.of(
                        Map.of("id", "risk", "dataset", "a", "key", "k", "measure", "count", "weight", 50),
                        Map.of("id", "tenure", "dataset", "a", "key", "k", "measure", "max(years)", "weight", -10,
                                "cap", 15))));
        RiskScorer.Scored s = RiskScorer.score(m, "k", Map.of("risk", 1.0, "tenure", 4.0), Map.of());
        RiskScorer.FactorResult tenure = s.factors().get(1);
        assertTrue(tenure.capped(), "-40 exceeds the cap of 15 in magnitude");
        assertEquals(-15.0, tenure.contribution(), EPS, "held to -cap, not left at -40 nor flipped to +15");
        assertEquals(35.0, s.score(), EPS);
        List<Map<String, Object>> stored = new ObjectMapper().readValue(
                RiskScoreEvaluator.factorsJson(s.factors()), new TypeReference<>() {});
        assertEquals(35.0, RiskScorer.recompute(stored), EPS, "recompute applies the same two-sided cap");
        assertEquals(-5.0, RiskScorer.score(m, "k", Map.of("tenure", 0.5), Map.of()).factors().get(1).contribution(),
                EPS, "under the cap a negative contribution is exact");
    }

    @Test
    void everyScoreIsReproducibleFromItsStoredFactors() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (Map<String, Double> values : List.of(
                Map.of("failed_topups", 3.0, "sim_swaps", 2.0, "spend", 60.0),
                Map.of("failed_topups", 1.0, "spend", 5.0),
                Map.<String, Double>of(),
                Map.of("sim_swaps", 7.0))) {
            RiskScorer.Scored s = RiskScorer.score(model(50), "e", values, Map.of());
            List<Map<String, Object>> stored = json.readValue(RiskScoreEvaluator.factorsJson(s.factors()),
                    new TypeReference<>() {});
            assertEquals(s.score(), RiskScorer.recompute(stored), EPS, "recomputed from JSON for " + values);
        }
    }

    @Test
    void recomputeDisagreesWithATamperedFactor() throws Exception {
        RiskScorer.Scored s = RiskScorer.score(model(50), "e", Map.of("failed_topups", 1.0), Map.of());
        List<Map<String, Object>> stored = new ObjectMapper().readValue(
                RiskScoreEvaluator.factorsJson(s.factors()), new TypeReference<>() {});
        stored.get(0).put("value", 2.0);   // the stored contribution still says 10
        assertNotEquals(s.score(), RiskScorer.recompute(stored), "recompute re-derives, it does not trust contribution");
    }
}

package com.gamma.analytics.insight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Hand-computed goldens for the I-8 surprise tests, Benjamini-Hochberg and the interestingness score. */
class InsightStatsGoldenTest {

    @Test
    void bhAdjustedValuesAreTheRunningMinimumOfPTimesMOverRank() {
        // sorted .005 .01 .03 .04 → ×4/rank .02 .02 .04 .04 → running min from the top: same
        double[] q = FalseDiscovery.adjusted(new double[] {0.01, 0.04, 0.03, 0.005});
        assertArrayEquals(new double[] {0.02, 0.04, 0.04, 0.02}, q, 1e-12);
        assertEquals(0, FalseDiscovery.adjusted(new double[0]).length);
        assertEquals(1, FalseDiscovery.adjusted(new double[] {Double.NaN})[0]);
    }

    @Test
    void normalAndKolmogorovTails() {
        assertEquals(1, Surprise.twoSided(0), 1e-7);
        assertEquals(0.05, Surprise.twoSided(1.959964), 1e-6);
        assertEquals(0.0026998, Surprise.twoSided(3), 1e-6);
        assertEquals(0.0495, Surprise.kolmogorovTail(1.36), 5e-4);
        assertEquals(1, Surprise.kolmogorovTail(0));
    }

    @Test
    void spikeIsTheRobustZOfTheLastBucket() {
        // history 10 ×6: median 10, MAD 0, floor max(1, 0.5) = 1 → z = (20 − 10) / 1
        Surprise.Finding f = Surprise.spike(new Double[] {10d, 10d, 10d, 10d, 10d, 10d, 20d}, 0, 5, 1);
        assertEquals(10, f.statistic(), 1e-12);
        assertEquals(10, f.expected(), 1e-12);
        assertEquals(20, f.observed(), 1e-12);
        assertEquals(6, f.at());
        assertTrue(f.p() < 1e-20);
        assertNull(Surprise.spike(new Double[] {10d, 10d, null}, 0, 5, 1), "no value now → nothing");
        assertNull(Surprise.spike(new Double[] {10d, 10d, 20d}, 0, 5, 1), "too little history");
    }

    @Test
    void spikeUsesTheSameSeasonSlotWhenItHasEnough() {
        // weekly season: slot values 100 every 7th bucket, other days 10. Last bucket (a slot day) = 100 → no surprise.
        Double[] s = new Double[36];
        for (int i = 0; i < s.length; i++) s[i] = (i % 7 == 0) ? 100d : 10d;
        s[35] = 100d;   // 35 % 7 == 0
        Surprise.Finding f = Surprise.spike(s, 7, 4, 1);
        assertEquals(Boolean.TRUE, f.detail().get("seasonal"));
        assertEquals(0, f.statistic(), 1e-12);
    }

    @Test
    void changepointFindsTheLevelShiftAndItsAge() {
        // 0 0 0 0 10 10 10 10: mean 5, |cusum| peaks 20 at split 4; diffs' MAD 0 → σ = floor max(1, .25) = 1;
        // stat = 20 / (1 · √8)
        Double[] s = {0d, 0d, 0d, 0d, 10d, 10d, 10d, 10d};
        Surprise.Finding f = Surprise.changepoint(s, 3, 6, 1);
        assertEquals(4, f.at());
        assertEquals(0, f.expected(), 1e-12);
        assertEquals(10, f.observed(), 1e-12);
        assertEquals(20 / Math.sqrt(8), f.statistic(), 1e-12);
        assertNull(Surprise.changepoint(s, 3, 3, 1), "the change is 4 buckets old, older than maxAge 3");
        assertNull(Surprise.changepoint(new Double[] {1d, 2d, 3d}, 3, 6, 1), "too short");
    }

    @Test
    void driftIsMannKendallWithSensSlope() {
        // 1..5: S = 10, var = 5·4·15/18, Z = (10 − 1)/√var; slope 1, line 1 → 5
        Surprise.Finding f = Surprise.drift(new Double[] {1d, 2d, 3d, 4d, 5d}, 4);
        assertEquals(9 / Math.sqrt(5 * 4 * 15 / 18.0), f.statistic(), 1e-12);
        assertEquals(1, f.expected(), 1e-12);
        assertEquals(5, f.observed(), 1e-12);
        assertEquals(1.0, (double) f.detail().get("slopePerBucket"), 1e-12);
        assertNull(Surprise.drift(new Double[] {3d, 3d, 3d, 3d}, 4), "all tied: no variance");
    }

    @Test
    void scoreIsImpactTimesSurpriseTimesNovelty() {
        // share 0.05 → impact 1 − e⁻¹; q 0.01 → −log10 = 2 → surprise 1 − e⁻¹; novelty 1
        double f = 1 - Math.exp(-1);
        InsightScore.Parts p = InsightScore.of(5, 100, 0.01, 1);
        assertEquals(f, p.impact(), 1e-12);
        assertEquals(f, p.surprise(), 1e-12);
        assertEquals(100 * f * f, p.score(), 1e-9);
        assertEquals(0, InsightScore.of(5, 100, 0.01, 0).score());
        assertEquals(0.5, InsightScore.novelty(1));
        assertEquals("high", InsightScore.of(100, 100, 1e-12, 1).band());
    }
}

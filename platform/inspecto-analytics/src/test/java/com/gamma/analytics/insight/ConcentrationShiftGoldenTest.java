package com.gamma.analytics.insight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Hand-computed goldens for the I-9 concentration and I-10 distribution-shift statistics. */
class ConcentrationShiftGoldenTest {

    // parts 50 30 10 10 (total 100), given out of order
    private static final double[] PARTS = {10, 50, 10, 30};

    @Test
    void topShareAndParetoPoint() {
        assertEquals(0.5, Concentration.topShare(PARTS, 1), 1e-12);
        assertEquals(0.8, Concentration.topShare(PARTS, 2), 1e-12);
        assertEquals(1.0, Concentration.topShare(PARTS, 9), 1e-12, "more than there are → all of it");
        assertEquals(2, Concentration.paretoCount(PARTS, 0.8), "50 + 30 reach 80 %");
        assertEquals(3, Concentration.paretoCount(PARTS, 0.85));
        assertEquals(1, Concentration.paretoCount(new double[] {7}, 0.8));
    }

    @Test
    void hhiIsTheSumOfSquaredShares() {
        // .25 + .09 + .01 + .01
        assertEquals(0.36, Concentration.hhi(PARTS), 1e-12);
        assertEquals(0.25, Concentration.hhi(new double[] {3, 3, 3, 3}), 1e-12, "k equal parts → 1/k");
        assertEquals(1, Concentration.hhi(new double[] {0, 9, 0}), 1e-12);
    }

    @Test
    void giniOverTheAscendingParts() {
        // ascending 10 10 30 50: Σ i·x = 10 + 20 + 90 + 200 = 320; 2·320/(4·100) − 5/4 = 0.35
        assertEquals(0.35, Concentration.gini(PARTS), 1e-12);
        assertEquals(0, Concentration.gini(new double[] {4, 4, 4}), 1e-12);
        // one holds all of 4: ascending 0 0 0 1 → 2·4/4 − 5/4 = 0.75 = (k − 1)/k
        assertEquals(0.75, Concentration.gini(new double[] {0, 0, 1, 0}), 1e-12);
    }

    @Test
    void concentrationRefusesNegativePartsAndHasNoneForNothing() {
        assertThrows(IllegalArgumentException.class, () -> Concentration.hhi(new double[] {5, -1}));
        assertThrows(IllegalArgumentException.class, () -> Concentration.topShare(new double[] {Double.NaN}, 1));
        assertTrue(Double.isNaN(Concentration.gini(new double[] {0, 0})));
        assertTrue(Double.isNaN(Concentration.topShare(new double[0], 3)));
    }

    @Test
    void psiOfAHalfHalfToAQuarterThreeQuarterMix() {
        // (.25 − .5)·ln(.5) + (.75 − .5)·ln(1.5) = .25·ln 3
        double[] base = {50, 50}, cur = {25, 75};
        assertEquals(0.25 * Math.log(3), DistributionShift.psi(base, cur), 1e-12);
        assertEquals(0, DistributionShift.psi(base, new double[] {7, 7}), 1e-12, "same shares, any scale → 0");
        // a value new in the current period: base share floored at 1e-4 → finite
        double p = DistributionShift.psi(new double[] {1, 0}, new double[] {1, 1});
        assertEquals((0.5 - 1) * Math.log(0.5) + (0.5 - 1e-4) * Math.log(0.5 / 1e-4), p, 1e-12);
    }

    @Test
    void ksAndTotalVariation() {
        // cumulative base .5, 1; current .25, 1 → D = .25; TVD = (.25 + .25)/2
        double[] base = {50, 50}, cur = {25, 75};
        assertEquals(0.25, DistributionShift.ksStatistic(base, cur), 1e-12);
        assertEquals(0.25, DistributionShift.totalVariation(base, cur), 1e-12);
        // n1 = n2 = 100: √nₑ = √50 = 7.0711; λ = (7.0711 + .12 + .01556)·.25 = 1.80166; 2·e^(−2λ²) = .00303
        assertEquals(0.00303, DistributionShift.ksPValue(0.25, 100, 100), 3e-5);
        assertEquals(1, DistributionShift.ksPValue(0, 100, 100), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> DistributionShift.psi(base, new double[] {1, 2, 3}));
        assertTrue(Double.isNaN(DistributionShift.psi(base, new double[] {0, 0})));
    }
}

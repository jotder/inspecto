package com.gamma.analytics.time;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ChangePointTest {

    private static final ChangePoint.Params P = ChangePoint.Params.of(1.0);   // z 4, K 3, k 0.25, h 8

    static List<Integer> at(boolean[] flags) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < flags.length; i++) if (flags[i]) out.add(i);
        return out;
    }

    private static double[] quiet(int n) {
        double[] e = new double[n];
        for (int i = 0; i < n; i++) e[i] = (i % 2 == 0 ? 0.3 : -0.3);
        return e;
    }

    @Test
    void aSingleSpikeIsOneBandBreachAndNothingElse() {
        double[] e = quiet(30);
        e[12] = 9;
        var r = ChangePoint.detect(e, P);
        assertEquals(List.of(12), at(r.outsideBand()));
        assertEquals(List.of(), at(r.regimeChange()));
        assertEquals(List.of(), at(r.driftChange()));
    }

    @Test
    void kConsecutiveOutsidePointsAreOneRegimeChangeOnTheKth() {
        double[] e = quiet(30);
        e[10] = e[11] = e[12] = 6;
        var r = ChangePoint.detect(e, P);
        assertEquals(List.of(12), at(r.regimeChange()));
        assertEquals(List.of(), at(r.outsideBand()), "a run that became a regime change is that one event");
        // one short of K: two band breaches, no regime change
        e[12] = 0.2;
        r = ChangePoint.detect(e, P);
        assertEquals(List.of(10, 11), at(r.outsideBand()));
        assertEquals(List.of(), at(r.regimeChange()));
    }

    @Test
    void withoutRebaseAPersistentShiftRepeatsAndWithRebaseItIsOneEvent() {
        double[] e = quiet(40);
        for (int i = 10; i < 40; i++) e[i] += 6;
        assertEquals(List.of(12, 15, 18, 21, 24, 27, 30, 33, 36, 39), at(ChangePoint.detect(e, P).regimeChange()));
        var r = ChangePoint.detect(e, P.withRebase(true));
        assertEquals(List.of(12), at(r.regimeChange()));
        assertEquals(List.of(), at(r.driftChange()), "the new level reads as residual ~0");
    }

    @Test
    void aSlowRampTripsTheCusumOnceNotTheBand() {
        double[] e = quiet(60);
        for (int i = 20; i < 60; i++) e[i] += 0.12 * (i - 19);        // 0.12 sigma per step, never above 4 sigma until late
        var r = ChangePoint.detect(Slice.upTo(e, 45), P.withRebase(true));
        assertEquals(1, at(r.driftChange()).size(), at(r.driftChange()).toString());
        assertEquals(List.of(), at(r.outsideBand()));
        assertEquals(List.of(), at(r.regimeChange()));
    }

    @Test
    void outsidePointsDoNotFeedTheCusumAndWarmupSuppressesFlags() {
        double[] e = quiet(30);
        e[5] = 50;                                                    // a huge spike must not push S+ past h
        var r = ChangePoint.detect(e, P);
        assertEquals(List.of(), at(r.driftChange()));
        var w = ChangePoint.detect(e, P.withWarmup(8));
        assertEquals(List.of(), at(w.outsideBand()), "the spike at 5 is in the warm-up");
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), at(w.warmup()));
    }

    @Test
    void aSymmetricNegativeShiftIsDetectedToo() {
        double[] e = quiet(30);
        e[8] = e[9] = e[10] = -7;
        assertEquals(List.of(10), at(ChangePoint.detect(e, P).regimeChange()));
    }

    @Test
    void scaleOfDifferencesIsTheScaledMadOfTheDifferencesOverRootTwo() {
        double[] x = {1, 3, 2, 6, 3, 7, 5, 4};
        // lag 1 differences: 2, -1, 4, -3, 4, -2, -1 -> median -1, |d - med| = 3, 0, 5, 2, 5, 1, 0 -> sorted 0 0 1 2 3 5 5 -> MAD 2
        assertEquals(1.4826 * 2 / Math.sqrt(2), ChangePoint.scaleOfDifferences(x, 1), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> ChangePoint.scaleOfDifferences(new double[] {1, 2}, 2));
    }

    @Test
    void inputRules() {
        assertThrows(IllegalArgumentException.class, () -> ChangePoint.Params.of(0));
        assertThrows(IllegalArgumentException.class, () -> new ChangePoint.Params(1, 4, 1, 0.25, 8, 0, false));
        assertThrows(IllegalArgumentException.class, () -> ChangePoint.detect(new double[] {1, Double.NaN}, P));
        assertEquals(0, ChangePoint.detect(new double[0], P).regimeChange().length);
        // determinism
        double[] e = new Random(3).doubles(500).map(v -> (v - 0.5) * 3).toArray();
        assertArrayEquals(ChangePoint.detect(e, P).outsideBand(), ChangePoint.detect(e, P).outsideBand());
    }

    /** Test helper: the first n values. */
    private static final class Slice {
        static double[] upTo(double[] a, int n) {
            return java.util.Arrays.copyOf(a, n);
        }
    }
}

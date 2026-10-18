package com.gamma.analytics.model;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.graph.GraphAborted;
import com.gamma.graph.RunControl;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class KMeansTest {

    /** Three well-separated 2-D blobs of 40 points each, in a fixed order. */
    static double[][] blobs() {
        Random r = new Random(7);
        double[][] centres = {{0, 0}, {100, 0}, {0, 1000}};
        double[][] out = new double[120][];
        for (int i = 0; i < out.length; i++) {
            double[] c = centres[i % 3];
            out[i] = new double[] {c[0] + r.nextGaussian(), c[1] + r.nextGaussian() * 5};
        }
        return out;
    }

    @Test
    void recoversWellSeparatedBlobsAndNumbersTheClustersByCentroid() {
        KMeans.Fit fit = KMeans.fit(blobs(), KMeans.Spec.of(3, 42), RunControl.NONE);
        assertTrue(fit.model().converged());
        assertArrayEquals(new long[] {40, 40, 40}, fit.model().sizes());
        for (int i = 0; i < 120; i++)
            assertEquals(fit.labels()[i % 3], fit.labels()[i], "points of one blob share a cluster");
        // centroids ascend lexicographically in standardised space: label 0 holds the lowest first coordinate
        double[][] c = fit.model().centroids();
        assertTrue(c[0][0] <= c[1][0] && c[1][0] <= c[2][0]);
        // a point near blob 1 (x = 100) is assigned where blob 1's members are
        assertEquals(fit.labels()[1], fit.model().assign(new double[] {99, 3}));
    }

    @Test
    void theSameSeedAndOrderGiveTheSameModelBitForBit() {
        KMeans.Fit a = KMeans.fit(blobs(), KMeans.Spec.of(4, 99), RunControl.NONE);
        KMeans.Fit b = KMeans.fit(blobs(), KMeans.Spec.of(4, 99), RunControl.NONE);
        assertArrayEquals(a.labels(), b.labels());
        assertEquals(a.model().inertia(), b.model().inertia(), 0);
        assertEquals(a.model().iterations(), b.model().iterations());
        for (int c = 0; c < 4; c++) assertArrayEquals(a.model().centroids()[c], b.model().centroids()[c], 0);
    }

    @Test
    void standardisationMakesTheUnitsIrrelevant() {
        // the third blob differs only on the large-scale column; unstandardised it would dwarf the others
        double[][] scaled = blobs();
        for (double[] p : scaled) p[1] *= 1e6;
        KMeans.Fit plain = KMeans.fit(blobs(), KMeans.Spec.of(3, 5), RunControl.NONE);
        KMeans.Fit big = KMeans.fit(scaled, KMeans.Spec.of(3, 5), RunControl.NONE);
        assertArrayEquals(plain.labels(), big.labels());
    }

    @Test
    void aConstantColumnContributesNothing() {
        double[][] pts = new double[30][];
        for (int i = 0; i < 30; i++) pts[i] = new double[] {i < 15 ? 0 : 10, 7.0};
        KMeans.Fit fit = KMeans.fit(pts, KMeans.Spec.of(2, 1), RunControl.NONE);
        assertEquals(1.0, fit.model().scales()[1], 0, "a zero spread is scale 1, not a division by zero");
        assertArrayEquals(new long[] {15, 15}, fit.model().sizes());
    }

    @Test
    void duplicatePointsStillGiveKClustersWithoutFailing() {
        double[][] pts = new double[10][];
        Arrays.setAll(pts, i -> new double[] {1.0, 1.0});
        KMeans.Fit fit = KMeans.fit(pts, KMeans.Spec.of(3, 3), RunControl.NONE);
        assertEquals(3, fit.model().centroids().length);
        assertEquals(10L, Arrays.stream(fit.model().sizes()).sum());
    }

    @Test
    void anEmptyClusterIsRefilledDeterministically() {
        // two far groups and k = 3 where the start may strand a centre: the fit still returns k populated clusters twice
        double[][] pts = new double[24][];
        for (int i = 0; i < 24; i++) pts[i] = new double[] {i < 12 ? i * 0.01 : 50 + i * 0.01};
        for (long seed = 0; seed < 20; seed++) {
            KMeans.Fit a = KMeans.fit(pts, KMeans.Spec.of(3, seed), RunControl.NONE);
            KMeans.Fit b = KMeans.fit(pts, KMeans.Spec.of(3, seed), RunControl.NONE);
            assertArrayEquals(a.labels(), b.labels());
            for (long s : a.model().sizes()) assertTrue(s > 0, "seed " + seed + " left an empty cluster");
        }
    }

    @Test
    void theIterationCapStopsTheFitAndSaysSo() {
        KMeans.Spec one = new KMeans.Spec(3, 42, 1, 1000);
        KMeans.Fit fit = KMeans.fit(blobs(), one, RunControl.NONE);
        assertEquals(1, fit.model().iterations());
        assertFalse(fit.model().converged(), "one sweep cannot confirm no assignment changed");
    }

    @Test
    void moreRowsThanTheCapFailsTheFitRatherThanSamplingASubset() {
        KMeans.Spec cap = new KMeans.Spec(3, 1, 10, 100);
        InputCap.Exceeded e = assertThrows(InputCap.Exceeded.class, () -> KMeans.fit(blobs(), cap, RunControl.NONE));
        assertTrue(e.getMessage().contains("120 rows") && e.getMessage().contains("cap of 100"), e.getMessage());
    }

    @Test
    void badInputIsRefusedByName() {
        assertThrows(IllegalArgumentException.class, () -> KMeans.Spec.of(1, 0));
        assertThrows(IllegalArgumentException.class, () -> KMeans.Spec.of(KMeans.MAX_K + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new KMeans.Spec(3, 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> KMeans.fit(new double[0][], KMeans.Spec.of(2, 0), RunControl.NONE));
        assertThrows(IllegalArgumentException.class, () -> KMeans.fit(new double[][] {{1}, {2}}, KMeans.Spec.of(3, 0), RunControl.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> KMeans.fit(new double[][] {{1}, {Double.NaN}, {3}}, KMeans.Spec.of(2, 0), RunControl.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> KMeans.fit(new double[][] {{1}, {2, 3}, {3}}, KMeans.Spec.of(2, 0), RunControl.NONE));
    }

    @Test
    void aCancelledRunStopsAtItsNextCheckpoint() {
        RunControl control = RunControl.create();
        control.cancel();
        assertThrows(GraphAborted.class, () -> KMeans.fit(blobs(), KMeans.Spec.of(3, 42), control));
        RunControl budget = RunControl.withBudget(2);   // k-means++ takes 2 checkpoints for k = 3; the first sweep is the third
        assertThrows(GraphAborted.class, () -> KMeans.fit(blobs(), KMeans.Spec.of(3, 42), budget));
    }

    @Test
    void theRandomStreamIsPinned() {
        // SplitMix64 reference values for seed 0 (the published test vector)
        SeededRandom r = new SeededRandom(0);
        assertEquals(0xE220A8397B1DCDAFL, r.nextLong());
        assertEquals(0x6E789E6AA1B965F4L, r.nextLong());
        assertEquals(0x06C45D188009454FL, r.nextLong());
    }
}

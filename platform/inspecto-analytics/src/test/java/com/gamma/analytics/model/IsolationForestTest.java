package com.gamma.analytics.model;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.graph.GraphAborted;
import com.gamma.graph.RunControl;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class IsolationForestTest {

    /** 500 points of a tight 3-D cluster (feature 0 and 1 carry noise, feature 2 is flat) plus 5 outliers on feature 1. */
    static double[][] cloud() {
        Random r = new Random(5);
        double[][] pts = new double[505][];
        for (int i = 0; i < 500; i++) pts[i] = new double[] {r.nextGaussian(), r.nextGaussian(), 0};
        for (int i = 500; i < 505; i++) pts[i] = new double[] {r.nextGaussian(), 9 + r.nextDouble(), 0};
        return pts;
    }

    @Test
    void averagePathMatchesTheDefinition() {
        assertEquals(0.0, IsolationForest.averagePath(1), 0);
        assertEquals(1.0, IsolationForest.averagePath(2), 0);
        // c(256) = 2(ln 255 + 0.5772156649) - 2*255/256 = 10.2448...
        assertEquals(10.2448, IsolationForest.averagePath(256), 1e-3);
    }

    @Test
    void outliersScoreHigherThanTheClusterAndTheScoreIsInTheOpenUnitInterval() {
        double[][] pts = cloud();
        IsolationForest f = IsolationForest.fit(pts, null, IsolationForest.Spec.of(1), RunControl.NONE);
        double maxNormal = 0, minOutlier = 1;
        double meanNormal = 0;
        for (int i = 0; i < 500; i++) {
            double s = f.path(pts[i]).score();
            assertTrue(s > 0 && s <= 1);
            maxNormal = Math.max(maxNormal, s);
            meanNormal += s / 500;
        }
        for (int i = 500; i < 505; i++) minOutlier = Math.min(minOutlier, f.path(pts[i]).score());
        assertTrue(minOutlier > 0.7, "outliers isolate fast: " + minOutlier);
        assertTrue(meanNormal < 0.55, "a typical point scores about 0.5: " + meanNormal);
        assertTrue(minOutlier > meanNormal + 0.15);
    }

    @Test
    void theSharesNameTheFeatureThatIsolatedThePointAndNeverTheFlatOne() {
        double[][] pts = cloud();
        IsolationForest f = IsolationForest.fit(pts, null, IsolationForest.Spec.of(1), RunControl.NONE);
        double[] shares = f.attribute(pts[502], new double[] {0, 0, 0});
        assertEquals(1.0, Arrays.stream(shares).sum(), 1e-12);
        assertTrue(shares[1] > 0.9, "feature 1 carries the outlier: " + Arrays.toString(shares));
        assertEquals(0.0, shares[2], 0, "a feature equal to its reference has no share");
        // a typical point has no excess, so nothing to attribute
        double[] typical = f.attribute(new double[] {0, 0, 0}, new double[] {0, 0, 0});
        assertEquals(0.0, Arrays.stream(typical).sum(), 0);
    }

    @Test
    void theSameSeedAndOrderGiveTheSameScoresBitForBitAndADifferentSeedDoesNot() {
        double[][] pts = cloud();
        IsolationForest a = IsolationForest.fit(pts, null, IsolationForest.Spec.of(42), RunControl.NONE);
        IsolationForest b = IsolationForest.fit(pts, null, IsolationForest.Spec.of(42), RunControl.NONE);
        IsolationForest c = IsolationForest.fit(pts, null, IsolationForest.Spec.of(43), RunControl.NONE);
        boolean differs = false;
        for (int i = 0; i < pts.length; i += 7) {
            assertEquals(a.path(pts[i]).score(), b.path(pts[i]).score(), 0);
            assertArrayEquals(a.attribute(pts[i], new double[3]), b.attribute(pts[i], new double[3]), 0);
            differs |= a.path(pts[i]).score() != c.path(pts[i]).score();
        }
        assertTrue(differs);
    }

    @Test
    void featureWeightsSteerTheSplits() {
        double[][] pts = new double[400][];
        Random r = new Random(9);
        for (int i = 0; i < pts.length; i++) pts[i] = new double[] {r.nextGaussian(), r.nextGaussian()};
        IsolationForest even = IsolationForest.fit(pts, null, IsolationForest.Spec.of(3), RunControl.NONE);
        IsolationForest skew = IsolationForest.fit(pts, new double[] {1, 99}, IsolationForest.Spec.of(3), RunControl.NONE);
        // weighted splits land on feature 1 far more often, so the same point is isolated at a different depth
        double[] probe = {0.3, 0.2};
        assertNotEquals(even.path(probe).meanPathLength(), skew.path(probe).meanPathLength());
    }

    @Test
    void identicalPointsScoreTypicalNotAnomalous() {
        double[][] pts = new double[50][];
        Arrays.setAll(pts, i -> new double[] {1, 2});
        IsolationForest.Path p = IsolationForest.fit(pts, null, IsolationForest.Spec.of(1), RunControl.NONE).path(pts[0]);
        assertEquals(0.5, p.score(), 1e-12);
        assertEquals(0.0, p.excess(), 1e-12);
    }

    @Test
    void theSampleIsCappedAtTheSampleSizeAndAtTheNumberOfPoints() {
        assertEquals(256, IsolationForest.fit(cloud(), null, IsolationForest.Spec.of(1), RunControl.NONE).sampleSize());
        double[][] few = {{0}, {1}, {2}};
        assertEquals(3, IsolationForest.fit(few, null, IsolationForest.Spec.of(1), RunControl.NONE).sampleSize());
    }

    @Test
    void moreRowsThanTheCapFailsAndBadInputIsRefused() {
        IsolationForest.Spec cap = new IsolationForest.Spec(10, 64, 1, 100);
        assertThrows(InputCap.Exceeded.class, () -> IsolationForest.fit(cloud(), null, cap, RunControl.NONE));
        assertThrows(IllegalArgumentException.class, () -> IsolationForest.fit(new double[][] {{1}}, null, IsolationForest.Spec.of(1), RunControl.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> IsolationForest.fit(new double[][] {{1}, {Double.NaN}}, null, IsolationForest.Spec.of(1), RunControl.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> IsolationForest.fit(new double[][] {{1, 2}, {3, 4}}, new double[] {1}, IsolationForest.Spec.of(1), RunControl.NONE));
        assertThrows(IllegalArgumentException.class, () -> new IsolationForest.Spec(0, 64, 1, 100));
        assertThrows(IllegalArgumentException.class, () -> new IsolationForest.Spec(10, 1, 1, 100));
    }

    @Test
    void aCancelledRunStopsAtItsNextCheckpoint() {
        RunControl c = RunControl.create();
        c.cancel();
        assertThrows(GraphAborted.class, () -> IsolationForest.fit(cloud(), null, IsolationForest.Spec.of(1), c));
        RunControl budget = RunControl.withBudget(5);   // one checkpoint per tree; the default is 100 trees
        assertThrows(GraphAborted.class, () -> IsolationForest.fit(cloud(), null, IsolationForest.Spec.of(1), budget));
    }
}

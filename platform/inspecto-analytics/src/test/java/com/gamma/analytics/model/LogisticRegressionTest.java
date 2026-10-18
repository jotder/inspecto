package com.gamma.analytics.model;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.split.HoldoutSplit;
import com.gamma.graph.GraphAborted;
import com.gamma.graph.RunControl;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LogisticRegressionTest {

    static final int N = 2000;
    static String[] keys;
    static double[][] x;
    static boolean[] y;

    static {
        // y ~ Bernoulli(sigmoid(-0.5 + 2*a - 1.5*b)); c is noise; a is on a large scale
        Random r = new Random(31);
        keys = new String[N];
        x = new double[N][];
        y = new boolean[N];
        for (int i = 0; i < N; i++) {
            double a = r.nextGaussian(), b = r.nextGaussian(), c = r.nextGaussian();
            double p = 1 / (1 + Math.exp(-(-0.5 + 2 * a - 1.5 * b)));
            keys[i] = "e" + String.format("%05d", i);
            x[i] = new double[] {a * 1000 + 5000, b, c};
            y[i] = r.nextDouble() < p;
        }
    }

    static LogisticRegression.Spec spec() {
        return LogisticRegression.Spec.of(HoldoutSplit.HashSplit.of(7, 0.6, 0.2, 0.2));
    }

    @Test
    void fitsTheGeneratingModelAndGeneralisesToHeldOutEntities() {
        LogisticRegression.Model m = LogisticRegression.fit(keys, x, y, spec(), RunControl.NONE);
        assertTrue(m.converged());
        // standardised coefficients: a is ~ +2 (a's unit scale is 1000, so z = a), b ~ -1.5, c ~ 0 (ridge shrinks a little)
        assertEquals(2.0, m.coefficients()[0], 0.35);
        assertEquals(-1.5, m.coefficients()[1], 0.35);
        assertEquals(0.0, m.coefficients()[2], 0.3);
        assertEquals(-0.5, m.intercept(), 0.3);
        assertTrue(m.train().rows() + m.validation().rows() + m.test().rows() == N);
        assertTrue(m.validation().auc().orElseThrow() > 0.85, "validation AUC " + m.validation().auc());
        assertTrue(m.test().auc().orElseThrow() > 0.85, "test AUC " + m.test().auc());
        assertTrue(m.test().logLoss() < 0.55);
    }

    @Test
    void theSameRowsAndSpecGiveTheSameModelBitForBit() {
        LogisticRegression.Model a = LogisticRegression.fit(keys, x, y, spec(), RunControl.NONE);
        LogisticRegression.Model b = LogisticRegression.fit(keys, x, y, spec(), RunControl.NONE);
        assertEquals(a.intercept(), b.intercept(), 0);
        assertArrayEquals(a.coefficients(), b.coefficients(), 0);
        assertEquals(a.test().logLoss(), b.test().logLoss(), 0);
    }

    @Test
    void theSplitIsTheHoldoutKernelsAndStandardisationUsesTrainRowsOnly() {
        HoldoutSplit.HashSplit split = spec().split();
        double sum = 0;
        long n = 0;
        for (int i = 0; i < N; i++)
            if (split.role(keys[i]) == HoldoutSplit.Role.TRAIN) {
                sum += x[i][0];
                n++;
            }
        LogisticRegression.Model m = LogisticRegression.fit(keys, x, y, spec(), RunControl.NONE);
        assertEquals(n, m.train().rows());
        assertEquals(sum / n, m.means()[0], 1e-9, "the mean is the train rows' mean, not all rows'");
    }

    @Test
    void aScoreIsRecomputableFromItsExplanationAlone() {
        LogisticRegression.Model m = LogisticRegression.fit(keys, x, y, spec(), RunControl.NONE);
        for (int i = 0; i < 50; i++) {
            double[] c = m.contributions(x[i]);
            assertEquals(m.score(x[i]), LogisticRegression.recompute(m.intercept(), c), 1e-9);
            assertTrue(m.score(x[i]) >= 0 && m.score(x[i]) <= 100);
        }
    }

    @Test
    void separableDataStaysFiniteThanksToTheRidge() {
        String[] k = new String[200];
        double[][] f = new double[200][];
        boolean[] lab = new boolean[200];
        for (int i = 0; i < 200; i++) {
            k[i] = "s" + i;
            f[i] = new double[] {i < 100 ? -1 - i * 0.01 : 1 + i * 0.01};
            lab[i] = i >= 100;
        }
        LogisticRegression.Model m = LogisticRegression.fit(k, f, lab, spec(), RunControl.NONE);
        assertTrue(Double.isFinite(m.coefficients()[0]) && m.coefficients()[0] > 0);
        assertTrue(m.test() == null || m.test().accuracy() > 0.95);
    }

    @Test
    void theIterationCapIsHonouredAndReported() {
        LogisticRegression.Spec one = new LogisticRegression.Spec(spec().split(), 1.0, 1, 10_000);
        LogisticRegression.Model m = LogisticRegression.fit(keys, x, y, one, RunControl.NONE);
        assertEquals(1, m.iterations());
        assertFalse(m.converged());
    }

    @Test
    void moreRowsThanTheCapFailsAndBadInputIsRefused() {
        LogisticRegression.Spec cap = new LogisticRegression.Spec(spec().split(), 1.0, 10, 100);
        assertThrows(InputCap.Exceeded.class, () -> LogisticRegression.fit(keys, x, y, cap, RunControl.NONE));
        assertThrows(IllegalArgumentException.class, () -> new LogisticRegression.Spec(spec().split(), 0, 10, 100));
        boolean[] oneClass = new boolean[N];
        assertThrows(IllegalArgumentException.class, () -> LogisticRegression.fit(keys, x, oneClass, spec(), RunControl.NONE));
        assertThrows(IllegalArgumentException.class, () -> LogisticRegression.fit(keys, x, new boolean[3], spec(), RunControl.NONE));
        double[][] withNaN = x.clone();
        withNaN[0] = new double[] {Double.NaN, 0, 0};
        assertThrows(IllegalArgumentException.class, () -> LogisticRegression.fit(keys, withNaN, y, spec(), RunControl.NONE));
    }

    @Test
    void aCancelledRunStopsAtItsNextCheckpoint() {
        RunControl c = RunControl.create();
        c.cancel();
        assertThrows(GraphAborted.class, () -> LogisticRegression.fit(keys, x, y, spec(), c));
    }
}

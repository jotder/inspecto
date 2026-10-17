package com.gamma.analytics.accuracy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Hand-computed figures for every {@link Accuracy} definition and its input rules. */
class AccuracyTest {

    // e = predicted - actual = {2, 1, -5, 0}
    private static final double[] A = {10, 0, 20, 5};
    private static final double[] P = {12, 1, 15, 5};
    private static final double[] LO = {9, 0, 16, 5};
    private static final double[] HI = {11, 2, 19, 6};

    @Test
    void goldenFigures() {
        Accuracy.Summary s = Accuracy.summary(A, P, LO, HI);
        assertEquals(4, s.n());
        assertEquals(2.0, s.mae(), 1e-12);
        assertEquals(Math.sqrt(7.5), s.rmse(), 1e-12);
        // MAPE over 10, 20, 5 only: (0.2 + 0.25 + 0) / 3 = 15 %; the zero actual is excluded and counted.
        assertEquals(15.0, s.mape().getAsDouble(), 1e-12);
        assertEquals(1, s.mapeZeroActuals());
        // sMAPE: (400/22 + 200/1 + 1000/35 + 0) / 4
        assertEquals((400.0 / 22 + 200 + 1000.0 / 35) / 4, s.smape(), 1e-12);
        assertEquals(-0.5, s.bias(), 1e-12, "negative bias = under-forecast");
        // 10 in [9,11], 0 in [0,2] (inclusive), 20 not in [16,19], 5 in [5,6] (inclusive)
        assertEquals(0.75, s.coverage().getAsDouble(), 1e-12);
        assertEquals(2.0, s.meanWidth().getAsDouble(), 1e-12);
    }

    @Test
    void singleMeasuresAgreeWithTheSummary() {
        assertEquals(2.0, Accuracy.mae(A, P), 1e-12);
        assertEquals(Math.sqrt(7.5), Accuracy.rmse(A, P), 1e-12);
        assertEquals(15.0, Accuracy.mape(A, P).getAsDouble(), 1e-12);
        assertEquals(-0.5, Accuracy.bias(A, P), 1e-12);
        assertEquals(0.75, Accuracy.coverage(A, LO, HI), 1e-12);
        assertEquals(2.0, Accuracy.meanIntervalWidth(LO, HI), 1e-12);
        assertTrue(Accuracy.summary(A, P).coverage().isEmpty(), "no band, no coverage");
    }

    @Test
    void zeroActualRules() {
        Accuracy.Summary allZero = Accuracy.summary(new double[] {0, 0}, new double[] {0, 3});
        assertTrue(allZero.mape().isEmpty(), "every actual zero: MAPE has no value, never a division by zero");
        assertEquals(2, allZero.mapeZeroActuals());
        // a = p = 0 contributes 0 to sMAPE; a = 0, p = 3 contributes 200
        assertEquals(100.0, allZero.smape(), 1e-12);
        assertEquals(0.0, Accuracy.smape(new double[] {0}, new double[] {0}), 0.0);
        assertEquals(200.0, Accuracy.smape(new double[] {5}, new double[] {-5}), 1e-12, "sMAPE tops out at 200");
    }

    @Test
    void perfectForecastScoresZero() {
        double[] x = {3, -1, 7.5};
        Accuracy.Summary s = Accuracy.summary(x, x.clone());
        assertEquals(0, s.mae());
        assertEquals(0, s.rmse());
        assertEquals(0, s.mape().getAsDouble());
        assertEquals(0, s.smape());
        assertEquals(0, s.bias());
    }

    @Test
    void inputRules() {
        assertThrows(IllegalArgumentException.class, () -> Accuracy.summary(new double[0], new double[0]));
        assertThrows(IllegalArgumentException.class, () -> Accuracy.summary(new double[] {1}, new double[] {1, 2}));
        assertThrows(IllegalArgumentException.class, () -> Accuracy.summary(new double[] {Double.NaN}, new double[] {1}));
        assertThrows(IllegalArgumentException.class, () -> Accuracy.coverage(new double[] {1}, new double[] {2}, new double[] {1}),
                "lower > upper");
        assertThrows(NullPointerException.class, () -> Accuracy.summary(new double[] {1}, null));
        IllegalArgumentException onlyLower = assertThrows(IllegalArgumentException.class,
                () -> Accuracy.summary(new double[] {1}, new double[] {1}, new double[] {0}, null));
        assertEquals("a band needs both lower and upper", onlyLower.getMessage());
        IllegalArgumentException onlyUpper = assertThrows(IllegalArgumentException.class,
                () -> Accuracy.summary(new double[] {1}, new double[] {1}, null, new double[] {2}));
        assertEquals("a band needs both lower and upper", onlyUpper.getMessage());
        double[] a = A.clone();
        Accuracy.summary(a, P, LO, HI);
        assertArrayEquals(A, a, "input never modified");
    }
}

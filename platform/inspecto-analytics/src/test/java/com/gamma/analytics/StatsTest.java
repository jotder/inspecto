package com.gamma.analytics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Golden-value conformance tests: one hand-computed figure per definition in docs/okf/backend/analytics-kernel.md. */
class StatsTest {

    private static final double EPS = 1e-12;
    /** The textbook set: mean 5, population variance 4, population stddev 2. */
    private static final double[] EIGHT = {2, 4, 4, 4, 5, 5, 7, 9};
    private static final double[] ONE_TO_FOUR = {4, 1, 3, 2};

    @Test
    void meanVarianceAndStddevMatchTheTextbookSet() {
        assertEquals(5.0, Stats.mean(EIGHT), EPS);
        assertEquals(4.0, Stats.variancePopulation(EIGHT), EPS);
        assertEquals(2.0, Stats.stddevPopulation(EIGHT), EPS);
        assertEquals(32.0 / 7, Stats.varianceSample(EIGHT), EPS);
        assertEquals(Math.sqrt(32.0 / 7), Stats.stddevSample(EIGHT), EPS);
        assertEquals(0.0, Stats.variancePopulation(new double[] {7}), EPS, "one value has no spread");
    }

    @Test
    void sampleVarianceOfOneValueIsUndefined() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Stats.varianceSample(new double[] {3}));
        assertTrue(e.getMessage().contains("at least 2"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Stats.stddevSample(new double[] {3}));
    }

    @Test
    void medianModesDifferOnlyForAnEvenCount() {
        assertEquals(4.5, Stats.median(EIGHT), EPS);
        assertEquals(4.5, Stats.median(EIGHT, MedianMode.STANDARD), EPS);
        assertEquals(4.0, Stats.median(EIGHT, MedianMode.LOWER), EPS, "lower median = the lower middle value");
        double[] odd = {3, 1, 2};
        assertEquals(2.0, Stats.median(odd, MedianMode.STANDARD), EPS);
        assertEquals(2.0, Stats.median(odd, MedianMode.LOWER), EPS);
    }

    @Test
    void lowerMedianIsVolumeBaselinesRule() {
        // VolumeBaseline: prior.sort(null); prior.get((prior.size() - 1) / 2)
        Random r = new Random(7);
        for (int n = 1; n <= 40; n++) {
            double[] v = r.doubles(n, -50, 50).map(Math::rint).toArray();
            List<Double> prior = new ArrayList<>();
            for (double x : v) prior.add(x);
            prior.sort(null);
            assertEquals(prior.get((prior.size() - 1) / 2), Stats.median(v, MedianMode.LOWER), 0.0, "n=" + n);
        }
    }

    @Test
    void continuousQuantileInterpolatesBetweenClosestRanks() {
        assertEquals(1.0, Stats.quantile(ONE_TO_FOUR, 0), EPS);
        assertEquals(1.75, Stats.quantile(ONE_TO_FOUR, 0.25), EPS);
        assertEquals(2.5, Stats.quantile(ONE_TO_FOUR, 0.5), EPS);
        assertEquals(3.7, Stats.quantile(ONE_TO_FOUR, 0.9), EPS);
        assertEquals(4.0, Stats.quantile(ONE_TO_FOUR, 1), EPS);
        assertEquals(Stats.quantile(ONE_TO_FOUR, 0.9), Stats.quantile(ONE_TO_FOUR, 0.9, QuantileMode.CONTINUOUS), 0.0,
                "continuous is the default");
    }

    @Test
    void discreteAndNearestRankAlwaysReturnAnObservedValue() {
        for (QuantileMode m : new QuantileMode[] {QuantileMode.DISCRETE, QuantileMode.NEAREST_RANK}) {
            assertEquals(1.0, Stats.quantile(ONE_TO_FOUR, 0, m), EPS, m.name());
            assertEquals(1.0, Stats.quantile(ONE_TO_FOUR, 0.25, m), EPS, m.name());
            assertEquals(2.0, Stats.quantile(ONE_TO_FOUR, 0.26, m), EPS, m.name());
            assertEquals(2.0, Stats.quantile(ONE_TO_FOUR, 0.5, m), EPS, m.name());
            assertEquals(4.0, Stats.quantile(ONE_TO_FOUR, 0.9, m), EPS, m.name());
            assertEquals(4.0, Stats.quantile(ONE_TO_FOUR, 1, m), EPS, m.name());
        }
    }

    @Test
    void nearestRankIsReportServicesPercentile() {
        // ReportService.percentile: rank = (int) Math.ceil(p / 100.0 * n); idx = min(max(rank, 1), n) - 1
        Random r = new Random(11);
        for (int n = 1; n <= 60; n++) {
            List<Long> durs = new ArrayList<>();
            for (int i = 0; i < n; i++) durs.add((long) r.nextInt(10_000));
            List<Long> s = new ArrayList<>(durs);
            Collections.sort(s);
            double[] v = durs.stream().mapToDouble(Long::doubleValue).toArray();
            for (double p : new double[] {0, 1, 5, 25, 50, 75, 90, 95, 99, 100}) {
                int rank = (int) Math.ceil(p / 100.0 * s.size());
                long expected = s.get(Math.min(Math.max(rank, 1), s.size()) - 1);
                assertEquals((double) expected, Stats.quantile(v, p / 100.0, QuantileMode.NEAREST_RANK), 0.0,
                        "n=" + n + " p=" + p);
            }
        }
    }

    @Test
    void madIsTheMedianAbsoluteDeviationAndScaledMadMultipliesIt() {
        double[] v = {1, 1, 2, 2, 4, 6, 9};   // median 2; |x-2| = 1,1,0,0,2,4,7 -> median 1
        assertEquals(1.0, Stats.mad(v), EPS);
        assertEquals(1.4826, Stats.scaledMad(v), EPS);
        assertEquals(2.0, Stats.mad(v, 0), EPS, "about centre 0: |x| median is 2");
        assertEquals(1.4826 * 2, Stats.scaledMad(v, 0), EPS);
    }

    @Test
    void zScoreIsTheDistanceFromTheMeanInStddevsAndRefusesNoSpread() {
        // {2,4,4,4,5,5,7,9}: mean 5, population stddev 2 -> z(9) = 2; the sample stddev is the caller's choice.
        double[] v = {2, 4, 4, 4, 5, 5, 7, 9};
        assertEquals(2.0, Stats.zScore(9, Stats.mean(v), Stats.stddevPopulation(v)), EPS);
        assertEquals(-1.5, Stats.zScore(2, 5, 2), EPS);
        assertThrows(IllegalArgumentException.class, () -> Stats.zScore(1, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> Stats.zScore(1, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> Stats.zScore(1, 5, Double.POSITIVE_INFINITY));
        assertEquals("((\"v\" - 5.0) / 2.0)", StatsSql.zScore("\"v\"", 5, 2));
    }

    @Test
    void robustZUsesTheFloorWhenTheSpreadIsTiny() {
        // constant history: scaled MAD 0, floor = max(unit 1, 5 % of 10 = 0.5) = 1
        assertEquals(3.0, Stats.robustZ(13, new double[] {10, 10, 10, 10}, 1), EPS);
        // floor = max(1, 5 % of 100 = 5) = 5
        assertEquals(5.0, Stats.robustZFloor(100, 1), EPS);
        assertEquals(2.0, Stats.robustZ(110, new double[] {100, 100, 100, 100, 100}, 1), EPS);
        assertEquals(5.0, Stats.robustZFloor(-100, 1), EPS, "the floor takes |median|");
        // a real spread wins over the floor: (20 - 10) / max(4, 1)
        assertEquals(2.5, Stats.robustZ(20, 10, 4, 1), EPS);
        assertEquals(-2.5, Stats.robustZ(0, 10, 4, 1), EPS);
    }

    @Test
    void iqrAndTukeyFences() {
        double[] v = {1, 2, 3, 4, 5, 6, 7, 8};   // Q1 = 2.75, Q3 = 6.25 (continuous)
        assertEquals(3.5, Stats.iqr(v), EPS);
        Stats.Fences f = Stats.tukeyFences(v);
        assertEquals(-2.5, f.lower(), EPS);
        assertEquals(11.5, f.upper(), EPS);
        assertFalse(f.outside(11.5), "a fence value is inside");
        assertTrue(f.outside(11.6));
        assertTrue(f.outside(-2.6));
        Stats.Fences far = Stats.tukeyFences(v, 3);
        assertEquals(2.75 - 10.5, far.lower(), EPS);
        assertEquals(6.25 + 10.5, far.upper(), EPS);
        assertThrows(IllegalArgumentException.class, () -> Stats.tukeyFences(v, -1));
    }

    @Test
    void emptyInputThrowsForEveryStatistic() {
        double[] none = {};
        List<Runnable> calls = List.of(
                () -> Stats.mean(none), () -> Stats.variancePopulation(none), () -> Stats.varianceSample(none),
                () -> Stats.stddevPopulation(none), () -> Stats.stddevSample(none), () -> Stats.median(none),
                () -> Stats.median(none, MedianMode.LOWER), () -> Stats.quantile(none, 0.5),
                () -> Stats.quantile(none, 0.5, QuantileMode.DISCRETE), () -> Stats.mad(none), () -> Stats.scaledMad(none),
                () -> Stats.robustZ(1, none, 1), () -> Stats.iqr(none), () -> Stats.tukeyFences(none));
        for (Runnable c : calls) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::run);
            assertTrue(e.getMessage().endsWith("of no values"), e.getMessage());
        }
        assertEquals("median of no values",
                assertThrows(IllegalArgumentException.class, () -> Stats.median(none)).getMessage());
    }

    @Test
    void nanInputThrowsInfinityIsAValue() {
        double[] withNaN = {1, Double.NaN, 3};
        assertThrows(IllegalArgumentException.class, () -> Stats.mean(withNaN));
        assertThrows(IllegalArgumentException.class, () -> Stats.median(withNaN));
        assertThrows(IllegalArgumentException.class, () -> Stats.quantile(withNaN, 0.5));
        assertThrows(IllegalArgumentException.class, () -> Stats.mad(withNaN));
        assertThrows(IllegalArgumentException.class, () -> Stats.mad(new double[] {1, 2}, Double.NaN),
                "a NaN centre makes every deviation NaN");
        assertEquals(Double.POSITIVE_INFINITY, Stats.median(new double[] {1, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY}));
        assertThrows(NullPointerException.class, () -> Stats.mean(null));
    }

    @Test
    void quantileFractionOutsideZeroOneThrows() {
        for (double q : new double[] {-0.01, 1.01, Double.NaN}) {
            for (QuantileMode m : QuantileMode.values()) {
                assertThrows(IllegalArgumentException.class, () -> Stats.quantile(ONE_TO_FOUR, q, m), q + " " + m);
            }
        }
    }

    @Test
    void inputIsNeverModified() {
        double[] v = {3, 1, 2, 5, 4};
        double[] copy = v.clone();
        Stats.median(v);
        Stats.quantile(v, 0.3, QuantileMode.DISCRETE);
        Stats.mad(v);
        Stats.tukeyFences(v);
        assertArrayEquals(copy, v);
    }

    @Test
    void ratioDividesAndIsEmptyForAZeroDenominator() {
        assertEquals(2.5, Stats.ratio(5, 2).orElseThrow(), 0.0);
        assertEquals(-0.25, Stats.ratio(-1, 4).orElseThrow(), 0.0);
        assertEquals(0.0, Stats.ratio(0, 7).orElseThrow(), 0.0);
        assertTrue(Stats.ratio(3, 0).isEmpty(), "x / 0 has no number");
        assertTrue(Stats.ratio(0, 0).isEmpty(), "0 / 0 is empty, never NaN");
        assertTrue(Stats.ratio(3, -0.0).isEmpty(), "negative zero is zero");
        assertThrows(IllegalArgumentException.class, () -> Stats.ratio(Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> Stats.ratio(1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Stats.ratio(Double.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> Stats.ratio(1, Double.NEGATIVE_INFINITY));
    }

    @Test
    void rateIsAShareOfACountAndIsEmptyWithNoTrials() {
        assertEquals(0.25, Stats.rate(1, 4).orElseThrow(), 0.0);
        assertEquals(0.0, Stats.rate(0, 9).orElseThrow(), 0.0);
        assertEquals(1.0, Stats.rate(9, 9).orElseThrow(), 0.0);
        assertEquals(1.0 / 3, Stats.rate(1, 3).orElseThrow(), 0.0);
        assertTrue(Stats.rate(0, 0).isEmpty(), "no trials, no rate; never NaN");
        assertThrows(IllegalArgumentException.class, () -> Stats.rate(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> Stats.rate(5, 4), "more events than trials");
    }
}

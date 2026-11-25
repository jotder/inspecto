package com.gamma.analytics.forecast;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `BI-INSIGHTS-1` I-12 — {@link PeriodProjection}: the landing of a period in progress, its measured interval and
 * P(goal), and the fail-closed "no projection" answers.
 */
class PeriodProjectionTest {

    private static final int[] DAY_CANDIDATES = {7, 365};
    /** Weekdays 100, weekends 20. */
    private static final double[] WEEK = {100, 100, 100, 100, 100, 20, 20};

    /** {@code weeks} weeks of the weekly pattern with a small deterministic wobble (never random). */
    private static double[] weekly(int days) {
        double[] y = new double[days];
        for (int i = 0; i < days; i++) y[i] = WEEK[i % 7] + 5 * Math.sin(i * 1.7);
        return y;
    }

    @Test
    void theLandingIsTheActualPlusTheForecastOfTheDaysLeft() {
        double[] series = weekly(140);   // 20 weeks; the series ends on a Saturday (index 139 = day 6 of week 20)
        int horizon = 14;                 // two more weeks: 10 weekdays + 4 weekend days ≈ 1080
        PeriodProjection.Result r = PeriodProjection.project(series, 500, horizon, DAY_CANDIDATES, 7);
        assertTrue(r.projected(), r.reason());
        assertEquals(7, r.period());
        assertTrue(r.detected(), "the weekly season is in the series");
        assertEquals(1080, r.remaining(), 60, "the forecast of the days left follows the weekly pattern");
        assertEquals(500 + r.remaining(), r.value(), 1e-9);
        assertTrue(r.lower() < r.value() && r.value() < r.upper(), "a band around the landing");
        assertTrue(r.origins() >= PeriodProjection.MIN_ORIGINS);
        assertTrue(r.accuracy().mape().isPresent());
    }

    @Test
    void theProbabilityFollowsWhereTheTargetSits() {
        PeriodProjection.Result r = PeriodProjection.project(weekly(140), 500, 14, DAY_CANDIDATES, 7);
        double far = 10 * (r.upper() - r.lower()) + 1;
        assertEquals(1.0, r.probability(PeriodProjection.Goal.AT_LEAST, r.value() - far, 0).getAsDouble(), 1e-6);
        assertEquals(0.0, r.probability(PeriodProjection.Goal.AT_LEAST, r.value() + far, 0).getAsDouble(), 1e-6);
        assertEquals(0.5, r.probability(PeriodProjection.Goal.AT_LEAST, r.value(), 0).getAsDouble(), 1e-6);
        assertEquals(0.5, r.probability(PeriodProjection.Goal.AT_MOST, 0, r.value()).getAsDouble(), 1e-6);
        // the 80 % band holds 80 % of the mass
        assertEquals(0.8, r.probability(PeriodProjection.Goal.BETWEEN, r.lower(), r.upper()).getAsDouble(), 1e-6);
    }

    @Test
    void lessThanTwoSeasonsIsNoProjection() {
        PeriodProjection.Result r = PeriodProjection.project(weekly(10), 100, 5, DAY_CANDIDATES, 7);
        assertFalse(r.projected());
        assertTrue(r.reason().contains("two seasons"), r.reason());
        assertEquals(OptionalDouble.empty(), r.probability(PeriodProjection.Goal.AT_LEAST, 1, 1));
    }

    @Test
    void tooFewPastProjectionsToMeasureTheIntervalIsNoProjection() {
        // 20 days, 10 left: origins 14 … 10 → none whose 10 days are all observed after two seasons of training
        PeriodProjection.Result r = PeriodProjection.project(weekly(20), 100, 10, DAY_CANDIDATES, 7);
        assertFalse(r.projected());
        assertTrue(r.reason().contains("past projections"), r.reason());
    }

    @Test
    void aCompletePeriodLandsWhereItIs() {
        PeriodProjection.Result r = PeriodProjection.project(weekly(3), 42, 0, DAY_CANDIDATES, 7);
        assertTrue(r.projected());
        assertEquals(42, r.value());
        assertEquals(1.0, r.probability(PeriodProjection.Goal.AT_LEAST, 42, 0).getAsDouble());
        assertEquals(0.0, r.probability(PeriodProjection.Goal.AT_LEAST, 43, 0).getAsDouble());
    }

    @Test
    void normalCdfInvertsTheQuantile() {
        for (double p : new double[] {0.001, 0.025, 0.1, 0.5, 0.8, 0.975, 0.999})
            assertEquals(p, PredictionInterval.normalCdf(PredictionInterval.normalQuantile(p)), 2e-7);
        assertEquals(0.5, PredictionInterval.normalCdf(0), 1e-9);
        assertEquals(0, PredictionInterval.normalCdf(Double.NEGATIVE_INFINITY));
        assertEquals(1, PredictionInterval.normalCdf(Double.POSITIVE_INFINITY));
    }
}

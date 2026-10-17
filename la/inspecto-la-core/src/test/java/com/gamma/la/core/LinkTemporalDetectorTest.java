package com.gamma.la.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinkTemporalDetectorTest {

    private static final long S = 1000;

    @Test
    void aDenseRunIsOneMergedBurstNotOnePerAnchor() {
        // 6 events 10 s apart, then one an hour later: a 30 s window holds 4, minEvents 4 -> ONE burst over the six.
        long[] t = {0, 10 * S, 20 * S, 30 * S, 40 * S, 50 * S, 3_600 * S};
        List<LinkTemporalDetector.Burst> b = LinkTemporalDetector.bursts(t, 30 * S, 4);
        assertEquals(List.of(new LinkTemporalDetector.Burst(0, 50 * S, 6)), b);
    }

    @Test
    void separateDenseRunsAreSeparateBurstsAndASparseSeriesHasNone() {
        long[] t = {0, S, 2 * S, 1_000 * S, 1_001 * S, 1_002 * S};
        assertEquals(2, LinkTemporalDetector.bursts(t, 5 * S, 3).size());
        assertEquals(List.of(), LinkTemporalDetector.bursts(new long[]{0, 100 * S, 200 * S, 300 * S}, 5 * S, 2),
                "a window never holds two events here");
    }

    @Test
    void theWindowEdgeIsInclusive() {
        assertEquals(1, LinkTemporalDetector.bursts(new long[]{0, 10 * S}, 10 * S, 2).size());
        assertEquals(0, LinkTemporalDetector.bursts(new long[]{0, 10 * S + 1}, 10 * S, 2).size());
    }

    @Test
    void aRegularSeriesIsPeriodicWithItsMedianGapAndAJitteredOneIsNot() {
        LinkTemporalDetector.Periodic p = LinkTemporalDetector.periodicity(new long[]{0, 60 * S, 120 * S, 180 * S, 240 * S}, 5, 0.1);
        assertNotNull(p);
        assertEquals(60 * S, p.periodMs());
        assertEquals(0.0, p.cv(), 1e-9);
        assertNull(LinkTemporalDetector.periodicity(new long[]{0, 10 * S, 130 * S, 140 * S, 400 * S}, 5, 0.1));
    }

    @Test
    void tooFewEventsAndSimultaneousEventsAreNeverPeriodic() {
        assertNull(LinkTemporalDetector.periodicity(new long[]{0, 60 * S, 120 * S}, 5, 0.1), "below minEvents");
        assertNull(LinkTemporalDetector.periodicity(new long[]{5, 5, 5, 5, 5}, 5, 1.0), "zero-mean gaps");
    }

    // ── golden figures (ANALYTICS-CORE-1 M2): pinned BEFORE the median and spread moved to the Analytics Kernel ──

    private static void assertGolden(long periodMs, double cv, long... t) {
        LinkTemporalDetector.Periodic p = LinkTemporalDetector.periodicity(t, 3, 10.0);
        assertNotNull(p, java.util.Arrays.toString(t));
        assertEquals(periodMs, p.periodMs(), "median gap of " + java.util.Arrays.toString(t));
        assertEquals(cv, p.cv(), 0.0, "cv of " + java.util.Arrays.toString(t));
        assertEquals(t.length, p.events());
    }

    @Test
    void goldenMedianGapAndCvOverOddEvenAndTiedGaps() {
        assertGolden(1000, 0.35355339059327379, 0, 1000, 3000, 4000);                  // odd gap count
        assertGolden(1500, 0.47380354147934284, 0, 1000, 3000, 4000, 7000);            // even: the middle pair averaged
        assertGolden(10, 0.047619047619047616, 0, 10, 21, 31, 42);                     // even: (10 + 11) / 2 truncates to 10
        assertGolden(150, 0.33333333333333331, 0, 100, 300);                           // two gaps
        assertGolden(500, 0.38709677419354838, 0, 500, 1000, 1500, 2600, 3100);        // tied gaps
        assertGolden(60500, 0.019587445462231234, 0, 60_000, 121_000, 179_000, 240_500, 300_000, 361_000);
    }

    @Test
    void goldenAgainstThePreKernelArithmeticOnSeededSeries() {
        java.util.Random r = new java.util.Random(20261017);
        for (int k = 0; k < 2000; k++) {
            int n = 3 + r.nextInt(40);
            long[] t = new long[n];
            long base = 1_700_000_000_000L + r.nextInt(1_000_000);                     // epoch ms, as PatternRoutes passes
            for (int i = 0; i < n; i++) t[i] = base + (r.nextInt(3) == 0 ? 0 : r.nextInt(86_400_000));
            java.util.Arrays.sort(t);
            // the pre-kernel arithmetic, frozen here
            long[] gaps = new long[n - 1];
            double sum = 0;
            for (int i = 1; i < n; i++) { gaps[i - 1] = t[i] - t[i - 1]; sum += gaps[i - 1]; }
            double mean = sum / gaps.length;
            LinkTemporalDetector.Periodic p = LinkTemporalDetector.periodicity(t, 3, Double.MAX_VALUE);
            if (mean <= 0) { assertNull(p); continue; }
            double sq = 0;
            for (long g : gaps) sq += (g - mean) * (g - mean);
            double cv = Math.sqrt(sq / gaps.length) / mean;
            long[] s = gaps.clone();
            java.util.Arrays.sort(s);
            long median = s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
            assertNotNull(p);
            assertEquals(median, p.periodMs(), "series " + k);
            assertEquals(cv, p.cv(), 0.0, "series " + k);
        }
    }
}

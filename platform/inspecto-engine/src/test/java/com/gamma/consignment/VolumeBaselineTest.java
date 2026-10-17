package com.gamma.consignment;

import com.gamma.consignment.DbConsignmentOutputStore.DailyVolume;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** K3 — the rolling baseline, and the four states it must keep apart. */
class VolumeBaselineTest {

    private static DailyVolume day(String d, long rows) {
        return new DailyVolume(d, 1, rows);
    }

    /** A week at a steady 1000 rows, ending on another 1000. */
    private static List<DailyVolume> steadyWeek(long target) {
        List<DailyVolume> series = new ArrayList<>();
        for (int d = 1; d <= 7; d++) series.add(day(String.format("2026-08-%02d", d), 1000));
        series.add(day("2026-08-08", target));
        return series;
    }

    @Test
    @DisplayName("a steady pipeline reports no deviation")
    void steady() {
        var a = VolumeBaseline.assess(steadyWeek(1000), "2026-08-08", 7, 3, 0.3);
        assertEquals(VolumeBaseline.Status.STEADY, a.status());
        assertEquals(1000, a.baselineRows());
        assertEquals(0.0, a.deviation(), 1e-9);
        assertEquals(7, a.baselineDays());
    }

    @Test
    @DisplayName("a halved day breaches")
    void halvedBreaches() {
        var a = VolumeBaseline.assess(steadyWeek(500), "2026-08-08", 7, 3, 0.3);
        assertEquals(VolumeBaseline.Status.BREACH, a.status());
        assertEquals(-0.5, a.deviation(), 1e-9);
    }

    @Test
    @DisplayName("a shortfall inside the tolerance is not a breach, and a surplus never is")
    void toleranceAndSurplus() {
        assertEquals(VolumeBaseline.Status.STEADY,
                VolumeBaseline.assess(steadyWeek(800), "2026-08-08", 7, 3, 0.3).status());
        var surplus = VolumeBaseline.assess(steadyWeek(5000), "2026-08-08", 7, 3, 0.3);
        assertEquals(VolumeBaseline.Status.STEADY, surplus.status());
        assertEquals(4.0, surplus.deviation(), 1e-9);
    }

    @Test
    @DisplayName("🔴 an empty history reads as no baseline yet, never as a 100% drop")
    void emptyHistoryIsNotADrop() {
        var a = VolumeBaseline.assess(List.of(day("2026-08-08", 10)), "2026-08-08", 7, 3, 0.3);
        assertEquals(VolumeBaseline.Status.NO_BASELINE, a.status());
        assertEquals(10, a.actualRows());
        assertEquals(-1, a.baselineRows());
        assertNull(a.deviation());
        assertEquals(0, a.baselineDays());
    }

    @Test
    @DisplayName("a history one day short of the minimum is still no baseline")
    void justUnderTheMinimum() {
        var series = List.of(day("2026-08-06", 1000), day("2026-08-07", 1000), day("2026-08-08", 1));
        assertEquals(VolumeBaseline.Status.NO_BASELINE,
                VolumeBaseline.assess(series, "2026-08-08", 7, 3, 0.3).status());
        assertEquals(VolumeBaseline.Status.BREACH,
                VolumeBaseline.assess(series, "2026-08-08", 7, 2, 0.3).status());
    }

    @Test
    @DisplayName("a target day absent from the series is NO_OBSERVATION, not a zero-row breach")
    void absentTargetDay() {
        List<DailyVolume> series = new ArrayList<>();
        for (int d = 1; d <= 7; d++) series.add(day(String.format("2026-08-%02d", d), 1000));
        var a = VolumeBaseline.assess(series, "2026-08-08", 7, 3, 0.3);
        assertEquals(VolumeBaseline.Status.NO_OBSERVATION, a.status());
        assertEquals(-1, a.baselineRows());
        assertNull(a.deviation());
    }

    @Test
    @DisplayName("🔴 absent days do not enter the baseline as zeros — a weekday-only pipeline stays steady")
    void absentDaysAreNotZeros() {
        // Only three of the seven prior days produced; the rest are absent, not empty.
        var series = List.of(day("2026-08-02", 1000), day("2026-08-04", 1000), day("2026-08-06", 1000),
                day("2026-08-08", 1000));
        var a = VolumeBaseline.assess(series, "2026-08-08", 7, 3, 0.3);
        assertEquals(VolumeBaseline.Status.STEADY, a.status());
        assertEquals(1000, a.baselineRows());
        assertEquals(3, a.baselineDays());
    }

    @Test
    @DisplayName("the window rolls: days older than it are dropped")
    void windowRolls() {
        var series = List.of(day("2026-07-01", 1_000_000), day("2026-08-06", 1000),
                day("2026-08-07", 1000), day("2026-08-08", 1000));
        var a = VolumeBaseline.assess(series, "2026-08-08", 3, 2, 0.3);
        assertEquals(2, a.baselineDays());
        assertEquals(1000, a.baselineRows());
        assertEquals(VolumeBaseline.Status.STEADY, a.status());
    }

    @Test
    @DisplayName("the window counts calendar days, so it crosses a month boundary")
    void windowCrossesMonthBoundary() {
        var series = List.of(day("2026-07-30", 1000), day("2026-07-31", 1000), day("2026-08-01", 200));
        var a = VolumeBaseline.assess(series, "2026-08-01", 7, 2, 0.3);
        assertEquals(2, a.baselineDays());
        assertEquals(VolumeBaseline.Status.BREACH, a.status());
    }

    @Test
    @DisplayName("🔴 the median absorbs one spike where a mean would hide the next day's breach")
    void medianAbsorbsASpike() {
        // Mean of 1000,1000,1000,1000,50000 is 10 800 — 600 would read as a 94% drop against it and as an
        // exact 40% drop against the median. With the spike, a mean would also mask a genuine 600-row day.
        var series = List.of(day("2026-08-04", 1000), day("2026-08-05", 1000), day("2026-08-06", 1000),
                day("2026-08-07", 1000), day("2026-08-03", 50_000), day("2026-08-08", 600));
        var a = VolumeBaseline.assess(series, "2026-08-08", 7, 3, 0.3);
        assertEquals(1000, a.baselineRows());
        assertEquals(-0.4, a.deviation(), 1e-9);
        assertEquals(VolumeBaseline.Status.BREACH, a.status());
    }

    @Test
    @DisplayName("an even-sized window takes the lower median, never above a day really received")
    void lowerMedian() {
        var series = List.of(day("2026-08-06", 100), day("2026-08-07", 200), day("2026-08-08", 100));
        var a = VolumeBaseline.assess(series, "2026-08-08", 7, 2, 0.3);
        assertEquals(100, a.baselineRows());
        assertEquals(VolumeBaseline.Status.STEADY, a.status());
    }

    @Test
    @DisplayName("🔴 the unknown-day bucket answers for no day — it neither raises the baseline nor fills it")
    void unknownBucketIsIgnored() {
        var series = List.of(new DailyVolume(null, 3, 9_000_000), day("2026-08-06", 1000),
                day("2026-08-07", 1000), day("2026-08-08", 600));
        var a = VolumeBaseline.assess(series, "2026-08-08", 7, 2, 0.3);
        assertEquals(2, a.baselineDays());
        assertEquals(1000, a.baselineRows());
        assertEquals(VolumeBaseline.Status.BREACH, a.status());

        // And it cannot stand in for a missing target day either.
        var noTarget = VolumeBaseline.assess(
                List.of(new DailyVolume(null, 1, 5), day("2026-08-06", 1000), day("2026-08-07", 1000)),
                "2026-08-08", 7, 2, 0.3);
        assertEquals(VolumeBaseline.Status.NO_OBSERVATION, noTarget.status());
    }

    @Test
    @DisplayName("🔴 a baseline of zero has no shortfall — the first productive day is not an alert")
    void zeroBaselineIsUndefinedNotInfinite() {
        var series = List.of(day("2026-08-06", 0), day("2026-08-07", 0), day("2026-08-08", 500));
        var a = VolumeBaseline.assess(series, "2026-08-08", 7, 2, 0.3);
        assertEquals(VolumeBaseline.Status.STEADY, a.status());
        assertEquals(0, a.baselineRows());
        assertNull(a.deviation());
    }

    @Test
    @DisplayName("a day that does not parse is excluded from the baseline rather than guessed at")
    void unparseableDayExcluded() {
        var series = List.of(day("not-a-day", 1_000_000), day("2026-08-06", 1000),
                day("2026-08-07", 1000), day("2026-08-08", 1000));
        assertEquals(2, VolumeBaseline.assess(series, "2026-08-08", 7, 2, 0.3).baselineDays());
    }

    @Test
    @DisplayName("the arguments are checked rather than silently corrected")
    void argumentsRefused() {
        var series = steadyWeek(1000);
        assertThrows(IllegalArgumentException.class,
                () -> VolumeBaseline.assess(series, " ", 7, 3, 0.3));
        assertThrows(IllegalArgumentException.class,
                () -> VolumeBaseline.assess(series, "2026-08-08", 0, 3, 0.3));
        assertThrows(IllegalArgumentException.class,
                () -> VolumeBaseline.assess(series, "2026-08-08", 7, 0, 0.3));
        assertThrows(IllegalArgumentException.class,
                () -> VolumeBaseline.assess(series, "2026-08-08", 7, 3, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> VolumeBaseline.assess(series, "2026-08-08", 7, 3, Double.NaN));
    }

    // ── golden figures (ANALYTICS-CORE-1 M2): pinned BEFORE the median moved to the Analytics Kernel ─────────

    /** The prior days {@code rows[0..]} end on 2026-08-30; the target 2026-08-31 received {@code actual}. */
    private static VolumeBaseline.Assessment golden(long actual, long... rows) {
        List<DailyVolume> series = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) series.add(day(String.format("2026-08-%02d", 30 - i), rows[i]));
        series.add(day("2026-08-31", actual));
        return VolumeBaseline.assess(series, "2026-08-31", 30, 1, 0.3);
    }

    private static VolumeBaseline.Assessment expect(long actual, long baseline, int days) {
        VolumeBaseline.Status status = (actual - (double) baseline) / baseline < -0.3
                ? VolumeBaseline.Status.BREACH : VolumeBaseline.Status.STEADY;
        return new VolumeBaseline.Assessment("2026-08-31", status, actual, baseline,
                (actual - (double) baseline) / baseline, days);
    }

    @Test
    @DisplayName("golden: the lower median over odd, even, tied, single and huge histories")
    void goldenLowerMedian() {
        assertEquals(expect(1000, 700, 1), golden(1000, 700));                       // n = 1
        assertEquals(expect(1000, 300, 2), golden(1000, 700, 300));                  // n = 2: the lower, not 500
        assertEquals(expect(1000, 3, 3), golden(1000, 5, 1, 3));                     // odd
        assertEquals(expect(1000, 4, 4), golden(1000, 4, 4, 1, 9));                  // even with a tie in the middle
        assertEquals(expect(1000, 6, 6), golden(1000, 2, 8, 6, 4, 10, 12));          // even: 6, never 7
        assertEquals(expect(1000, 7, 4), golden(1000, 7, 7, 7, 7));                  // all tied
        assertEquals(expect(1000, 1500, 2), golden(1000, 1500, 2500));               // BREACH against the lower
        assertEquals(expect(1000, 1, 2), golden(1000, 9_000_000_000_000L, 1));       // a huge outlier
        assertEquals(expect(5_000_000_000L, 4_000_000_000L, 3),
                golden(5_000_000_000L, 4_000_000_000L, 3_000_000_000L, 4_500_000_000L));
        assertEquals(new VolumeBaseline.Assessment("2026-08-31", VolumeBaseline.Status.STEADY, 1000, 0, null, 4),
                golden(1000, 0, 0, 5, 9));                                           // lower median 0: no deviation
    }

    @Test
    @DisplayName("golden: the baseline equals the pre-kernel lower median on 2,000 seeded histories")
    void goldenAgainstThePreKernelRule() {
        java.util.Random r = new java.util.Random(20261017);
        for (int k = 0; k < 2000; k++) {
            int n = 1 + r.nextInt(30);
            long[] rows = new long[n];
            for (int i = 0; i < n; i++) rows[i] = r.nextInt(4) == 0 ? r.nextInt(5) : r.nextInt(2_000_000);
            List<Long> prior = new ArrayList<>();
            for (long v : rows) prior.add(v);
            prior.sort(null);
            long frozen = prior.get((prior.size() - 1) / 2);   // VolumeBaseline's rule as shipped before M2
            assertEquals(frozen, golden(1000, rows).baselineRows(), "history " + prior);
        }
    }
}

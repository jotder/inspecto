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
}

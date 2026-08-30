package com.gamma.consignment;

import com.gamma.consignment.DbConsignmentOutputStore.DailyVolume;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <b>K3 of the completeness KPI</b> — how far one record-day's received volume sits below a <em>rolling</em>
 * baseline built from the days before it.
 *
 * <p>Pure and side-effect free: it takes the series {@link DbConsignmentOutputStore#dailyVolume} already
 * returned and answers one question about one day. It opens nothing and refuses nothing K1 has not already
 * refused — a disabled registry is K1's failure to raise, and this class never sees one.
 *
 * <h2>A missing day is not a zero</h2>
 * 🔴 K1 reports what the registry holds and invents no calendar, so a day absent from the series covers both
 * "the pipeline received nothing" and "the pipeline was not expected to run" — and nothing in the registry
 * separates them. Folding absent days in as zeros would drag every weekday-only or newly-created pipeline's
 * baseline toward zero and then read its ordinary days as a surplus; treating an absent <em>target</em> day
 * as a zero would breach every pipeline on its first quiet Sunday. So absent days never enter the baseline,
 * and an absent target day yields {@link Status#NO_OBSERVATION}, not a breach. Which days were expected is a
 * schedule, not a measurement, and belongs to the caller.
 *
 * <h2>Median, not mean</h2>
 * The baseline is the <b>median</b> of the observed prior days. A single recompute spike or one half-day
 * outage inside the window would drag a mean far enough to manufacture or to hide the next day's breach; a
 * median absorbs it. With an even count it takes the lower of the two middle values, so the baseline never
 * reads higher than a day actually received.
 */
public final class VolumeBaseline {

    /** What can be said about a day — 🔴 three of these four are <em>not</em> "healthy". */
    public enum Status {
        /** Within tolerance of the baseline, or above it: a surplus is not a completeness failure. */
        STEADY,
        /** Below the baseline by more than the tolerance. The only status that is an incident. */
        BREACH,
        /** Fewer observed prior days than {@code minBaselineDays}: unknown, not healthy and not a breach. */
        NO_BASELINE,
        /** The target day is absent from the series — nothing was registered for it. See the class note. */
        NO_OBSERVATION
    }

    /**
     * @param baselineRows the median of the observed prior days, or {@code -1} under {@link Status#NO_BASELINE}
     *                     and {@link Status#NO_OBSERVATION}, where there is no number to state.
     * @param deviation    signed fraction of the baseline: {@code -0.5} is a day at half its usual volume,
     *                     {@code +0.2} a fifth above. {@code null} wherever there is nothing to divide by,
     *                     including a baseline of exactly zero — a shortfall from zero is undefined, not
     *                     infinite.
     * @param baselineDays how many prior days the median was taken over. A deviation drawn from two days and
     *                     one drawn from thirty are not equally worth alerting on, and the reader of an
     *                     assessment must be able to tell which they hold.
     */
    public record Assessment(String recordDay, Status status, long actualRows, long baselineRows,
                             Double deviation, int baselineDays) {}

    private VolumeBaseline() {}

    /**
     * Assess {@code recordDay} against the days before it in {@code series}.
     *
     * @param series          per-day volumes, in any order. ⚠ The <b>unknown-day bucket</b> (a null
     *                        {@code recordDay}) is ignored entirely: its rows belong to no day, so they can
     *                        neither raise a baseline nor answer for the target day. Attributing them
     *                        anywhere would invent the date K1 took care not to invent.
     * @param window          how many calendar days before {@code recordDay} may contribute. Older days are
     *                        dropped, so the baseline rolls rather than accumulating a pipeline's history.
     * @param minBaselineDays the fewest observed days that make a baseline. Below it the answer is
     *                        {@link Status#NO_BASELINE} — 🔴 a fresh pipeline's first day must never read as
     *                        a 100% drop from a history that does not exist.
     * @param tolerance       the fraction below the baseline that is still ordinary, e.g. {@code 0.3} for 30%.
     */
    public static Assessment assess(List<DailyVolume> series, String recordDay, int window,
                                    int minBaselineDays, double tolerance) {
        Objects.requireNonNull(series, "series");
        if (recordDay == null || recordDay.isBlank())
            throw new IllegalArgumentException("assess requires a record day");
        if (window < 1) throw new IllegalArgumentException("window must be at least 1 day, got " + window);
        if (minBaselineDays < 1)
            throw new IllegalArgumentException("minBaselineDays must be at least 1, got " + minBaselineDays);
        if (!(tolerance >= 0) || tolerance > 1)
            throw new IllegalArgumentException("tolerance must be a fraction in [0,1], got " + tolerance);

        Long actual = null;
        List<Long> prior = new ArrayList<>();
        for (DailyVolume v : series) {
            if (v == null || v.recordDay() == null) continue;   // the unknown bucket answers for no day
            int cmp = v.recordDay().compareTo(recordDay);
            if (cmp == 0) actual = v.rows();
            else if (cmp < 0 && withinWindow(v.recordDay(), recordDay, window)) prior.add(v.rows());
        }
        if (actual == null) return new Assessment(recordDay, Status.NO_OBSERVATION, 0, -1, null, prior.size());
        if (prior.size() < minBaselineDays)
            return new Assessment(recordDay, Status.NO_BASELINE, actual, -1, null, prior.size());

        prior.sort(null);
        long baseline = prior.get((prior.size() - 1) / 2);      // lower median: never above a day really received
        if (baseline == 0)
            // Every fraction of zero is undefined rather than infinite. A pipeline whose usual day is empty
            // has no shortfall to measure, and "-100%" here would alert on the first day it produced anything.
            return new Assessment(recordDay, Status.STEADY, actual, 0, null, prior.size());

        double deviation = (actual - (double) baseline) / baseline;
        Status status = deviation < -tolerance ? Status.BREACH : Status.STEADY;
        return new Assessment(recordDay, status, actual, baseline, deviation, prior.size());
    }

    /**
     * Whether {@code day} is one of the {@code window} days immediately before {@code target}.
     *
     * <p>Both are the ISO {@code yyyy-MM-dd} strings {@code record_day} carries on either derivation path
     * ({@code ConsignmentOutputs.recordDay}), so the distance is counted in days and a window crossing a
     * month or year boundary behaves like one inside a month. A day that does not parse is excluded rather
     * than guessed at.
     */
    private static boolean withinWindow(String day, String target, int window) {
        try {
            long days = ChronoUnit.DAYS.between(LocalDate.parse(day), LocalDate.parse(target));
            return days >= 1 && days <= window;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}

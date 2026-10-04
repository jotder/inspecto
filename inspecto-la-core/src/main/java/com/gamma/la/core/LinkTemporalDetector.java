package com.gamma.la.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Burst and periodicity detection over the event times of one LINK (a directed source→target pair), the pure half of
 * {@code POST /inv/pattern/temporal} (LA-INVESTIGATION-OPS-DEFERRED-1). No I/O, no clock, no randomness: the same times
 * always give the same answer.
 *
 * <p><b>Burst</b> — a window of {@code windowMs} that holds at least {@code minEvents} events. Windows are anchored at an
 * event (the earliest one in it); overlapping anchored windows are merged into one maximal burst, so a long run of dense
 * events is ONE burst, not one per anchor. Reported: first and last event time and the event count.
 *
 * <p><b>Periodicity</b> — at least {@code minEvents} events whose gaps are regular: the coefficient of variation of the
 * gaps (population standard deviation over the mean) is at most {@code maxCv}. Reported: the median gap as the period,
 * the CV, the event count and the first/last time. Zero-mean gaps (every event at one instant) are never periodic.
 */
public final class LinkTemporalDetector {

    private LinkTemporalDetector() { }

    /** One merged burst: {@code startMs} and {@code endMs} are the first and last event times inside it. */
    public record Burst(long startMs, long endMs, int events) { }

    /** One regular series. */
    public record Periodic(long periodMs, double cv, int events, long firstMs, long lastMs) { }

    /**
     * @param sortedTimes epoch milliseconds, ascending
     * @return the maximal bursts, in time order
     */
    public static List<Burst> bursts(long[] sortedTimes, long windowMs, int minEvents) {
        List<Burst> out = new ArrayList<>();
        int n = sortedTimes.length;
        int curStart = -1, curEnd = -1;
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (j < i) j = i;
            while (j + 1 < n && sortedTimes[j + 1] - sortedTimes[i] <= windowMs) j++;
            if (j - i + 1 < minEvents) continue;
            if (curStart >= 0 && i <= curEnd) {
                curEnd = Math.max(curEnd, j);                              // overlaps the burst in progress: extend it
            } else {
                if (curStart >= 0) out.add(new Burst(sortedTimes[curStart], sortedTimes[curEnd], curEnd - curStart + 1));
                curStart = i;
                curEnd = j;
            }
        }
        if (curStart >= 0) out.add(new Burst(sortedTimes[curStart], sortedTimes[curEnd], curEnd - curStart + 1));
        return out;
    }

    /** @return the series' regularity, or null when it is not periodic under the stated bounds */
    public static Periodic periodicity(long[] sortedTimes, int minEvents, double maxCv) {
        int n = sortedTimes.length;
        if (n < minEvents || n < 3) return null;                           // two events make one gap: nothing to call regular
        long[] gaps = new long[n - 1];
        double sum = 0;
        for (int i = 1; i < n; i++) {
            gaps[i - 1] = sortedTimes[i] - sortedTimes[i - 1];
            sum += gaps[i - 1];
        }
        double mean = sum / gaps.length;
        if (mean <= 0) return null;
        double sq = 0;
        for (long g : gaps) sq += (g - mean) * (g - mean);
        double cv = Math.sqrt(sq / gaps.length) / mean;
        if (cv > maxCv) return null;
        long[] sorted = gaps.clone();
        Arrays.sort(sorted);
        long median = sorted.length % 2 == 1 ? sorted[sorted.length / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
        return new Periodic(median, cv, n, sortedTimes[0], sortedTimes[n - 1]);
    }

    /** Most regular first, then the longer series, so a result cut to a limit keeps the strongest. */
    public static final Comparator<Periodic> STRONGEST_PERIODIC =
            Comparator.comparingDouble(Periodic::cv).thenComparing(Comparator.comparingInt(Periodic::events).reversed());
}

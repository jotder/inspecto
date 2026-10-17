package com.gamma.analytics.score;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The run files of a score output directory, and opt-in history retention. Each run writes ONE file
 * {@code scores-<epochMillis>-<runId>.parquet} (the run id with anything outside {@code [A-Za-z0-9_-]} as {@code _}),
 * so the stamp orders runs without reading them.
 */
public final class ScoreRunFiles {
    private ScoreRunFiles() {}

    private static final String PREFIX = "scores-";
    /** Matches every run file of a directory, and nothing else in it (not the marker, not a {@code .tmp}). */
    public static final String GLOB = "scores-*.parquet";

    /** The run file name of a run stamped {@code scoredAt}. */
    public static String name(Instant scoredAt, String runId) {
        return PREFIX + scoredAt.toEpochMilli() + "-" + runId.replaceAll("[^A-Za-z0-9_-]", "_") + ".parquet";
    }

    /** The epoch-millis stamp of a run file name; {@link Long#MAX_VALUE} when it has none (sorts last, never expires). */
    public static long stampOf(String fileName) {
        String n = fileName.substring(PREFIX.length());
        try { return Long.parseLong(n.substring(0, n.indexOf('-'))); } catch (RuntimeException e) { return Long.MAX_VALUE; }
    }

    /**
     * Opt-in history retention: whole older runs by file stamp ({@code days}), or all but the newest {@code runs}. At
     * most one is set; both null is off. It never touches {@code _latest} or the run just written.
     * A pruned run's explanation is no longer reproducible: the audit-vs-storage trade the operator chose.
     */
    public record Retention(Integer days, Integer runs) {
        public static final Retention OFF = new Retention(null, null);

        /** The history run files to delete after writing {@code keep} at {@code now}; {@code runFiles} in any order. */
        public List<String> expired(List<String> runFiles, Instant now, String keep) {
            if (days == null && runs == null) return List.of();
            List<String> sorted = new ArrayList<>(runFiles);
            sorted.sort(Comparator.comparingLong(ScoreRunFiles::stampOf));
            long cutoff = days == null ? Long.MIN_VALUE : now.minus(Duration.ofDays(days)).toEpochMilli();
            int excess = runs == null ? 0 : Math.max(0, sorted.size() - runs);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                String f = sorted.get(i);
                if (f.equals(keep)) continue;
                if (days != null ? stampOf(f) < cutoff : i < excess) out.add(f);
            }
            return out;
        }
    }
}

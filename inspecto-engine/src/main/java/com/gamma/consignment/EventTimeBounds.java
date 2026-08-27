package com.gamma.consignment;

import com.gamma.api.PublicApi;

/**
 * The event-time range of the rows in one written output file (consignment addressing §3.1) — the substrate
 * the Consignment Selector needs to answer <em>"which files can possibly overlap {@code [T_lo, T_hi)}"</em>
 * without opening them.
 *
 * <p><b>Wall-clock, not instants.</b> {@code min}/{@code max} are ISO-8601 local date-times with no zone
 * offset, exactly as the event-time column parsed — the pipeline's timezone is pinned per-pipeline (§5.6) and
 * stamping a zone here would assert one this layer does not know. They are strings for the same reason
 * {@code writtenAt} is: the registry is read by SQL that compares them lexicographically, which is correct for
 * this format and needs no driver-dependent temporal mapping.
 *
 * <p>{@code spreadMs} is derivable from the other two, and is stored anyway: it is the one figure compaction
 * (§6.3) and late-arrival segregation (§9) actually filter on, and deriving it in SQL from two VARCHARs means
 * a cast on every row of every scan.
 *
 * @param min      earliest event time among the file's rows, ISO-8601 local ({@code 2026-08-04T00:12:30})
 * @param max      latest event time among the file's rows
 * @param spreadMs {@code max - min} in milliseconds; {@code 0} when every row shares one event time
 */
@PublicApi(since = "4.0.0")
public record EventTimeBounds(String min, String max, long spreadMs) {

    /**
     * The bounds of {@code [min, max]}, with {@code spreadMs} <b>derived</b> rather than stated.
     *
     * <p>Every producer inside the engine gets {@code spreadMs} from SQL ({@code datediff}) as a by-product of
     * computing the extremes, so it cannot disagree with them. A hand-built {@code new EventTimeBounds(min, max,
     * spread)} can — and a spread that disagrees with its own endpoints misfilters compaction (§6.3) and
     * late-arrival segregation (§9) while looking perfectly well-formed. This factory is the seam for callers
     * that hold two timestamps and nothing else; prefer it to the canonical constructor.
     *
     * @throws java.time.format.DateTimeParseException when either endpoint is not ISO-8601 local date-time
     */
    public static EventTimeBounds of(String min, String max) {
        java.time.LocalDateTime lo = java.time.LocalDateTime.parse(min);
        java.time.LocalDateTime hi = java.time.LocalDateTime.parse(max);
        return new EventTimeBounds(min, max, java.time.Duration.between(lo, hi).toMillis());
    }
}

package com.gamma.consignment;

import com.gamma.api.PublicApi;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * <b>Consignment addressing §3.6 — the completeness primitive.</b> Overlap (§3.1's bounds) answers "which files
 * <em>can</em> contain this window". Revenue Assurance and every completeness-needing rule (non-monotonic,
 * absence, gap) also need "has this window <em>closed</em>", and window close is undefined without a watermark:
 *
 * <pre>{@code watermark(stream) = min over producers of max(event_time_max)}</pre>
 *
 * <p>A window {@code [lo, hi)} is complete when {@code watermark >= hi + allowedLateness} — see
 * {@link #windowComplete}. Parallel producers into one table are the normal CDR case, which is why this is not
 * simply {@code max(event_time_max)} over the stream: one producer running ahead must not make a window look
 * closed while a slower one still owes rows inside it.
 *
 * <p><b>Derived, never stored.</b> There is no watermark table and no update path — this folds the same catalog
 * rows the Selector reads. {@link DbConsignmentOutputStore#producerHighWater} does the per-producer aggregation
 * in SQL; everything below is a pure function of its result.
 *
 * <p><b>Conservative in every direction it can be.</b> The dangerous failure is advancing too far: a window
 * declared complete while rows are still owed fires an absence rule against data that was merely late. So a
 * producer is only dropped when its staleness is <em>proven</em>, and any producer whose delivered event time is
 * unknown suppresses the answer entirely (see {@link ProducerHighWater}). An absent watermark is the honest
 * result and the D3 posture: completeness-needing rules simply do not fire, rather than firing on a guess.
 *
 * <p>⚠ <b>Vocabulary.</b> "Watermark" is reserved for this concept (`GLOSSARY.md` §6-B). The repo holds two
 * unrelated things that were already using the word: {@code PipelineWatermarkStore} is an incremental <em>read
 * cursor</em> ({@code max(incremental_column)} per pipeline+store, no producer set, no completeness claim), and
 * {@code $job.last_success_time} is <em>wall clock</em>. Neither answers window close.
 */
@PublicApi(since = "5.0.0")
public final class StreamWatermark {

    private StreamWatermark() {}

    /**
     * How long a producer may be silent before it stops holding the watermark back — decision <b>D4</b>, resolved
     * as <em>observed within a horizon</em> rather than a declared producer set.
     *
     * <p>The alternative, declaring the expected producers per stream, is exact but is one more thing to
     * configure and goes stale silently: a decommissioned producer nobody removed from the list freezes the
     * stream's watermark forever. Observed-within-horizon self-heals — a producer that stops writing drops out
     * after {@code H} and the watermark resumes — at the cost of a window that closes too early for a producer
     * whose outage outlasts {@code H}. A day is the smallest horizon that survives a normal overnight gap in
     * operator CDR delivery; streams with known slow reporters override it at the call site, and step 9's
     * per-stream configuration is where a declared override would land if one is ever needed.
     */
    public static final Duration DEFAULT_HORIZON = Duration.ofHours(24);

    /**
     * The stream's watermark, or empty when it cannot be established.
     *
     * <p>Empty means <b>unknown</b>, and callers must read it as "no window has closed" — never as "everything is
     * closed". It happens when the stream has no catalog history yet, when every producer is past the horizon,
     * or when any in-horizon producer has delivered rows whose event time is unknown.
     *
     * @param observed per-producer high water from {@link DbConsignmentOutputStore#producerHighWater}
     * @param horizon  how long a producer may be silent before dropping out ({@link #DEFAULT_HORIZON})
     * @param now      the reference instant the horizon is measured back from
     */
    public static Optional<LocalDateTime> of(List<ProducerHighWater> observed, Duration horizon, Instant now) {
        if (observed == null || observed.isEmpty()) return Optional.empty();
        Instant cutoff = now.minus(horizon);

        LocalDateTime min = null;
        boolean any = false;
        for (ProducerHighWater p : observed) {
            // Staleness must be proven: an unreadable last-seen keeps the producer in, so a bad timestamp
            // cannot silently let the watermark advance past a producer that is still delivering.
            if (p.lastSeen() != null && p.lastSeen().isBefore(cutoff)) continue;
            any = true;
            Optional<LocalDateTime> max = parse(p.eventTimeMax());
            if (max.isEmpty()) return Optional.empty();     // in-horizon producer with no placeable event time
            if (min == null || max.get().isBefore(min)) min = max.get();
        }
        return any ? Optional.ofNullable(min) : Optional.empty();
    }

    /**
     * Whether the window {@code [lo, hi)} has closed: {@code watermark >= hi + allowedLateness}.
     *
     * <p>Takes the {@link Optional} {@link #of} returns rather than a nullable, because "there is no watermark"
     * is the common case on a young stream and has to stay visible at the call site: it answers {@code false},
     * the same as a watermark that has not reached {@code hi} yet.
     *
     * @param watermark       the stream's watermark, as returned by {@link #of}
     * @param hiExclusive     the window's exclusive upper bound, in the same event-time frame as the bounds
     * @param allowedLateness how far past {@code hi} a record may still arrive (step 9's per-stream declaration)
     */
    public static boolean windowComplete(Optional<LocalDateTime> watermark, LocalDateTime hiExclusive,
                                         Duration allowedLateness) {
        return watermark.isPresent() && !watermark.get().isBefore(hiExclusive.plus(allowedLateness));
    }

    /** Catalog event-time text as a {@link LocalDateTime}; empty for null or anything unparseable, which the
     *  fold treats identically to "this producer's event time is unknown". */
    private static Optional<LocalDateTime> parse(String eventTime) {
        if (eventTime == null) return Optional.empty();
        try {
            return Optional.of(LocalDateTime.parse(eventTime));
        } catch (DateTimeParseException malformed) {
            return Optional.empty();
        }
    }
}

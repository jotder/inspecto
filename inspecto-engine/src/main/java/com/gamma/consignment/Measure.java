package com.gamma.consignment;

import com.gamma.api.PublicApi;

/**
 * One measure on a summary row, carrying the <b>declaration</b> §7.2 requires: how it composes when partial
 * aggregates are combined.
 *
 * <p>§7.2's rule is that every measure must be algebraically composable, because partial aggregates are forced
 * (§7.1) — a Consignment only ever sees its own slice, so a summary is always combined with others later. A
 * measure that does not compose produces a <em>quietly</em> wrong number rather than an error, which is why the
 * declaration is mandatory here rather than inferred.
 *
 * @param name          the measure's name. {@code count} is reserved and mandatory on every row
 *                      ({@link SummaryRow}).
 * @param composability how this measure combines across partial aggregates; never {@code null}.
 * @param value         the measured value.
 */
@PublicApi(since = "4.0.0")
public record Measure(String name, Composability composability, double value) {

    /** An additive measure — the common, always-safe case ({@code count}, {@code sum}, {@code bytes}). */
    public static Measure additive(String name, double value) {
        return new Measure(name, Composability.ADDITIVE, value);
    }

    /**
     * How a measure combines when partial aggregates are merged (§7.2).
     *
     * <p>There is deliberately no "unknown" member: an undeclared measure is
     * {@linkplain SummaryEmitter refused}, because guessing is exactly the failure §7.2 describes.
     */
    public enum Composability {
        /** Merges by addition. Safe to combine across any set of partial aggregates. */
        ADDITIVE,
        /**
         * Not additive on its own, but carried as a distribution the merge can re-derive from — a histogram
         * or quantile sketch rather than a single collapsed number. §7.3's partition summaries store these.
         */
        BUCKETED,
        /**
         * Not composable at all and deliberately not stored as a merged value: it is recomputed from detail
         * when asked. §7.5 defers the general non-additive story; this is the escape hatch that keeps such a
         * measure honest in the meantime.
         */
        COMPUTED_FROM_DETAIL
    }
}

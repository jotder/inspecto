package com.gamma.consignment;

import com.gamma.api.PublicApi;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;

/**
 * <b>D-9's {@code scope:} key.</b> How far a {@code transform.dedup} step looks for an earlier sighting
 * of a business key.
 *
 * <pre>
 * steps[1]:
 *   - dedup:
 *       keys[1]: MSISDN
 *       order_by: event_time DESC   # REQUIRED once scope is a window — see below
 *       scope: window(P4D)
 * </pre>
 *
 * <ul>
 *   <li>{@code scope:} absent, or {@code consignment} — today's behaviour, unchanged: dedup within one
 *       Consignment via {@code QUALIFY ROW_NUMBER()}, no ledger, no cross-run state.</li>
 *   <li>{@code window(<ISO-8601 period>)} — additionally suppress a key already admitted within that
 *       period, through {@link DbDedupLedger}.</li>
 * </ul>
 *
 * <h3>🔴 {@code order_by} becomes REQUIRED once a window is declared</h3>
 * {@code RowShaper.dedup} leaves {@code ORDER BY} out of its window function when {@code order_by} is
 * absent, and DuckDB then guarantees <em>no</em> particular row wins. Inside one Consignment that
 * non-determinism is a latent bug: re-run the batch and a different row may survive. Against a
 * <b>durable</b> ledger it is unrepeatable data loss — the row that won is gone, and the ledger now
 * refuses every other candidate for that key. So a window without an explicit tie-break is refused at
 * authoring time rather than producing a result nobody can reproduce.
 *
 * <h3>⛔ Unbounded history is not expressible, deliberately</h3>
 * The amendment's D-9 row says the windowed ledger must <b>never</b> be faked with unbounded history.
 * There is no {@code window(all)} and no zero/negative period: a window that never advances grows without
 * limit and turns a dedup step into a slow storage leak.
 */
@PublicApi(since = "4.0.0")
public sealed interface DedupScope {

    /** The default: one Consignment, no ledger, byte-identical to pre-D-9 behaviour. */
    record WithinConsignment() implements DedupScope {}

    /** Cross-Consignment suppression over {@code period}, backed by {@link DbDedupLedger}. */
    record Window(Period period) implements DedupScope {

        /** The window a record with this event date belongs to — the ledger's {@code window_start}. */
        public LocalDate startFor(LocalDate eventDate) {
            // Anchored on the epoch so every Consignment computes the SAME boundaries for the same period.
            // ⚠ Anchoring on "today" instead would give two runs on different days different windows for
            // the same record, and a key could then be admitted twice by construction.
            long days = Math.max(1, period.toTotalMonths() * 30 + period.getDays());
            long since = eventDate.toEpochDay();
            return LocalDate.ofEpochDay(Math.floorDiv(since, days) * days);
        }

        /** Everything strictly before this may be pruned once {@code eventDate} is the newest seen. */
        public LocalDate cutoffFor(LocalDate eventDate) {
            return startFor(eventDate).minusDays(1);
        }
    }

    /** Whether this scope needs the ledger at all. */
    default boolean isWindowed() {
        return this instanceof Window;
    }

    /**
     * Parse a {@code scope:} value. {@code null}/blank/{@code consignment} ⇒ {@link WithinConsignment}.
     *
     * @throws IllegalArgumentException on any other spelling, on a malformed period, or on a period that
     *                                  is not strictly positive
     */
    static DedupScope parse(String raw) {
        if (raw == null || raw.isBlank() || "consignment".equalsIgnoreCase(raw.trim()))
            return new WithinConsignment();
        String v = raw.trim();
        if (!v.regionMatches(true, 0, "window(", 0, 7) || !v.endsWith(")"))
            throw new IllegalArgumentException("dedup scope must be 'consignment' or 'window(<ISO-8601 "
                    + "period>)', e.g. window(P4D) — got: " + raw);
        String inner = v.substring(7, v.length() - 1).trim();
        Period p;
        try {
            p = Period.parse(inner);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("dedup scope window '" + inner + "' is not an ISO-8601 period"
                    + " (P4D = four days, P1M = one month): " + e.getMessage(), e);
        }
        if (p.isZero() || p.isNegative())
            throw new IllegalArgumentException("dedup scope window must be a positive period — got " + inner
                    + ". ⛔ There is no unbounded window: one that never advances grows without limit.");
        return new Window(p);
    }

    /**
     * The authoring refusal, kept beside the vocabulary it enforces so a caller cannot forget it.
     *
     * @return the refusal message, or {@code null} when the pair is legal
     */
    static String refusal(DedupScope scope, String orderBy) {
        if (scope != null && scope.isWindowed() && (orderBy == null || orderBy.isBlank()))
            return "transform.dedup declares scope: window(...) but no order_by — a windowed dedup writes a "
                    + "DURABLE ledger, so the winning row must be chosen explicitly. Without ORDER BY the "
                    + "winner is whatever the engine happened to emit first, and the rows it suppressed "
                    + "cannot be recovered. Author order_by, or drop scope: to dedup within the Consignment.";
        return null;
    }
}

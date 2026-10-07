package com.gamma.signal;

/**
 * The home for dotted <b>Signal types</b> (operator, 2026-10-06 — completeness KPI §7-e).
 *
 * <p>⛔ Not {@link com.gamma.event.EventType}: that class holds {@code Event.type} values ({@code UPPER_SNAKE}).
 * A {@link Signal} persists as an Event of type {@code SIGNAL} and its dotted type rides in the attributes,
 * so the two vocabularies stay in separate classes.
 *
 * <p>⚠ Deliberately open, like {@code EventType}: a constant here is a name, not a closed set. It starts
 * with the {@code kpi.completeness.*} types; the older literals ({@code job.run.failed},
 * {@code recon.run.completed}, …) are still scattered and migrate here when next touched.
 */
public final class SignalType {

    private SignalType() {}

    /** Every {@code kpi.completeness} run that produced an answer — status, volume, and the unknown-day bucket. */
    public static final String KPI_COMPLETENESS_EVALUATED = "kpi.completeness.evaluated";

    /** A {@code kpi.completeness} run whose day sits below its baseline by more than the tolerance. */
    public static final String KPI_COMPLETENESS_BREACHED = "kpi.completeness.breached";

    /** Three or more consecutive days with nothing registered — a WARN, never an Incident (operator, 2026-10-06). */
    public static final String KPI_COMPLETENESS_UNKNOWN_STREAK = "kpi.completeness.unknown_streak";
}

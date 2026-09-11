package com.gamma.config.spec;

import com.gamma.api.PublicApi;

/**
 * The one catalog of stable diagnostic codes carried by {@link Finding#code()} (authoring-residuals
 * R1, 2026-09-01). Codes are {@code ERR_}/{@code WARN_}-prefixed SCREAMING_SNAKE, grouped by
 * category — Topology / Arming / Schema / Safety / Parsing — and are API: a UI or automation may
 * key on them, so a code, once shipped, is never renamed or reused for a different meaning.
 *
 * <p>A category with no entries yet gains them as its producers are wired to the catalog (the
 * validator issues and {@code PipelineCompileException} codes register here as they migrate). A
 * refusal that fires at two severities carries an {@code ERR_}/{@code WARN_} pair for the same
 * mechanism, because the severity split IS the contract (an active pipeline refuses, an inactive
 * draft warns).
 *
 * <p>Lives beside {@link Finding} in {@code inspecto-config} — the lowest module every producer
 * already depends on — so no producer needs an upward dependency to cite a code.
 */
@PublicApi(since = "4.0.0")
public final class FindingCodes {

    private FindingCodes() {}

    // ── Topology ─────────────────────────────────────────────────────────────────────────────
    // (graph-shape findings register here as they are wired to the catalog)

    // ── Arming ───────────────────────────────────────────────────────────────────────────────

    /** {@code active: true} with no schema source at all — registers, then is skipped every cycle. */
    public static final String ERR_ARMED_WITHOUT_SCHEMA = "ERR_ARMED_WITHOUT_SCHEMA";

    /** An ACTIVE {@code route:} block that would refuse to arm ({@code RouteArming} rules). */
    public static final String ERR_ROUTE_UNARMABLE = "ERR_ROUTE_UNARMABLE";

    /** The same {@code route:} refusal on an INACTIVE draft — it bites only at activation. */
    public static final String WARN_ROUTE_UNARMABLE = "WARN_ROUTE_UNARMABLE";

    /** ACTIVE {@code processing.disabled_steps} that cannot PARK ({@code StepDisableArming} rules). */
    public static final String ERR_STEP_DISABLE_UNPARKABLE = "ERR_STEP_DISABLE_UNPARKABLE";

    /** The same disabled-steps refusal on an INACTIVE draft — it bites only at activation. */
    public static final String WARN_STEP_DISABLE_UNPARKABLE = "WARN_STEP_DISABLE_UNPARKABLE";

    /** An ACTIVE windowed dedup ({@code scope: window(...)}) with no {@code order_by} tie-break, or a
     *  malformed {@code scope:} value — a durable ledger must never record a non-deterministic winner (D-9). */
    public static final String ERR_DEDUP_WINDOW_UNARMABLE = "ERR_DEDUP_WINDOW_UNARMABLE";

    /** The same windowed-dedup refusal on an INACTIVE draft — it bites only at activation. */
    public static final String WARN_DEDUP_WINDOW_UNARMABLE = "WARN_DEDUP_WINDOW_UNARMABLE";

    /** An ACTIVE {@code route:} branch whose {@code where:} predicate reads a column the pipeline's
     *  declared schema does not carry — the branch binds nowhere and throws on the first row
     *  (`TYPEFLOW-CONSUMERS-1` (a)). */
    public static final String ERR_ROUTE_PREDICATE_COLUMN = "ERR_ROUTE_PREDICATE_COLUMN";

    /** The same predicate refusal on an INACTIVE draft — it bites only at activation. */
    public static final String WARN_ROUTE_PREDICATE_COLUMN = "WARN_ROUTE_PREDICATE_COLUMN";

    /** An ACTIVE {@code transform.summarize} measure aggregating a NON-NUMERIC declared field
     *  ({@code sum}/{@code avg} only) — DuckDB refuses it at run time (`TYPEFLOW-CONSUMERS-1` (a)). */
    public static final String ERR_SUMMARIZE_MEASURE_TYPE = "ERR_SUMMARIZE_MEASURE_TYPE";

    /** The same measure-type refusal on an INACTIVE draft — it bites only at activation. */
    public static final String WARN_SUMMARIZE_MEASURE_TYPE = "WARN_SUMMARIZE_MEASURE_TYPE";

    // ── Schema ───────────────────────────────────────────────────────────────────────────────
    // (schema-resolution / compatibility findings register here as they are wired)

    // ── Safety ───────────────────────────────────────────────────────────────────────────────
    // (ConfigSafetyValidator issues register here as they migrate to the catalog)

    // ── Parsing ──────────────────────────────────────────────────────────────────────────────
    // (PipelineCompileException / parser refusal codes register here as they migrate)
}

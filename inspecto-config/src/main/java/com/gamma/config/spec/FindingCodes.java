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

    /** An ACTIVE pipeline whose {@code lookup} / {@code profile} / {@code dedup} / {@code filter} step config
     *  the run would refuse — a missing or malformed required key, or a column the declared schema does not
     *  carry ({@code PROCESSOR-RELEASE-READINESS-1} G4). */
    public static final String ERR_STEP_CONFIG_INVALID = "ERR_STEP_CONFIG_INVALID";

    /** The same step-config refusal on an INACTIVE draft — it bites only at activation. */
    public static final String WARN_STEP_CONFIG_INVALID = "WARN_STEP_CONFIG_INVALID";

    /** An ACTIVE pipeline whose Collector config the run cannot honour — a {@code post_action} MOVE with no
     *  {@code archive_path} ({@code PROCESSOR-RELEASE-READINESS-1}, sink.archive, 2026-09-24). */
    public static final String ERR_COLLECTOR_CONFIG_INVALID = "ERR_COLLECTOR_CONFIG_INVALID";

    /** The same Collector refusal on an INACTIVE draft — it bites only at activation. */
    public static final String WARN_COLLECTOR_CONFIG_INVALID = "WARN_COLLECTOR_CONFIG_INVALID";

    /** Remote-only Collector keys ({@code fetch}, {@code retry}, {@code circuit_breaker}, a non-RETAIN
     *  {@code post_action}) on a LOCAL inbox Collector — accepted, but they never engage. Advisory only. */
    public static final String WARN_COLLECTOR_KEY_INERT = "WARN_COLLECTOR_KEY_INERT";

    /** Two {@code sinks[]} destinations whose effective ducklake is one catalog + one registered table —
     *  every batch would insert both destinations' files into it ({@code SinkLakeCollisions}). A shape
     *  rule, not an arming one: refused regardless of {@code active}, so it has no {@code WARN_} twin. */
    public static final String ERR_SINK_DUCKLAKE_SHARED_TABLE = "ERR_SINK_DUCKLAKE_SHARED_TABLE";

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

    // ── Referential integrity ────────────────────────────────────────────────────────────────

    /** A bundle-imported pipeline binds a connection profile the target space does not hold. WARNING,
     *  not ERROR, on purpose: a pipeline bundle never carries connection profiles (secrets never
     *  travel), the import lands inactive, and refusing would make promotion into a fresh space
     *  impossible — see {@code PipelineBundleRoutes.classifyRequirements}. */
    public static final String WARN_UNRESOLVED_CONNECTION = "WARN_UNRESOLVED_CONNECTION";

    /** A {@code webhook:} block naming a Connection this Space does not hold — every run would refuse
     *  before sending ({@code WebhookSink.plan}). Refused regardless of {@code active}, like an unknown
     *  collector Connection; on bundle import it is {@link #WARN_UNRESOLVED_CONNECTION} instead. */
    public static final String ERR_WEBHOOK_CONNECTION_UNKNOWN = "ERR_WEBHOOK_CONNECTION_UNKNOWN";

    /** A {@code webhook:} block naming a Connection that exists but is not an {@code https} one — the
     *  sink refuses any other connector at run time, so the save refuses it first, import included. */
    public static final String ERR_WEBHOOK_CONNECTION_NOT_HTTPS = "ERR_WEBHOOK_CONNECTION_NOT_HTTPS";

    /** A {@code webhook:} block its own parser refuses — an authored {@code url:}/{@code token:}, an
     *  unknown key, no {@code connection}, a {@code batch_size} out of bounds. The parser's message is
     *  carried verbatim; the pipeline would otherwise not load at all. */
    public static final String ERR_WEBHOOK_INVALID = "ERR_WEBHOOK_INVALID";

    // ── Schema ───────────────────────────────────────────────────────────────────────────────
    // (schema-resolution / compatibility findings register here as they are wired)

    // ── Safety ───────────────────────────────────────────────────────────────────────────────
    // (ConfigSafetyValidator issues register here as they migrate to the catalog)

    // ── Parsing ──────────────────────────────────────────────────────────────────────────────
    // (PipelineCompileException / parser refusal codes register here as they migrate)

    // ── Config keys ──────────────────────────────────────────────────────────────────────────
    // Its own category on purpose: the five above are about a config that is WRONG; this one is
    // about a config that is IGNORED (`DUCKLE-C3-DEAD-PROPERTY-1`).

    /** A config block no component reads — the engine ignores it, so what it configures is lost
     *  ({@link AcceptedConfigKeys}). ERROR at an authoring gate: the save is the last moment the
     *  author is present to be told. */
    public static final String ERR_UNKNOWN_CONFIG_KEY = "ERR_UNKNOWN_CONFIG_KEY";

    /** The same dead-key finding where a config already on disk is merely being read, not authored —
     *  refusing there would brick a deployed pipeline over a key that was never load-bearing. */
    public static final String WARN_UNKNOWN_CONFIG_KEY = "WARN_UNKNOWN_CONFIG_KEY";
}

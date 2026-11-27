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

    /** An ACTIVE pipeline with a Stage-2 chain Step (a {@code steps:} entry, or {@code processing.dedup} /
     *  {@code summarize} / {@code profile} / {@code join}) and no top-level {@code output_store:} — the chain
     *  runs only at rest, writing that store, so without one it never runs (TRANSFORMER-TWO-NODES-1). */
    public static final String ERR_CHAIN_WITHOUT_OUTPUT_STORE = "ERR_CHAIN_WITHOUT_OUTPUT_STORE";

    /** The same chain-without-store refusal on an INACTIVE draft — it bites only at activation. */
    public static final String WARN_CHAIN_WITHOUT_OUTPUT_STORE = "WARN_CHAIN_WITHOUT_OUTPUT_STORE";

    /** A pipeline with a Stage-2 chain and a top-level {@code output_store:} that no enabled {@code type: pipeline}
     *  Job runs ({@code pipeline_config:} naming its file) — the chain never runs. Always a WARNING: the Job is a
     *  separate config, and refusing would make the order of authoring matter (PIPELINE-CHAIN-NO-JOB-1). */
    public static final String WARN_CHAIN_WITHOUT_PIPELINE_JOB = "WARN_CHAIN_WITHOUT_PIPELINE_JOB";

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

    /** A Standard+ feature this build does not include — Alert Rules, the {@code post_action: MOVE}
     *  archive, DuckLake registration ({@code com.gamma.etl.EditionFeatures}, `PROCESSOR-RELEASE-READINESS-1`
     *  G9). Refused regardless of {@code active}: activating does not change the edition. */
    public static final String ERR_EDITION_FEATURE = "ERR_EDITION_FEATURE";

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

    /** An {@code excel:} block its parser refuses (no path, a path leaving the data root, a bad or duplicate
     *  sheet name, {@code max_rows} out of bounds, an unknown key) or a sheet whose {@code sql} {@code SqlGuard}
     *  refuses — the pipeline would otherwise not load, or fail its first run ({@code sink.excel}, 2026-10-06). */
    public static final String ERR_EXCEL_INVALID = "ERR_EXCEL_INVALID";

    /** A {@code processing.map.fields[]} row calling the Reference lookup ({@code lookup.reference}): the ingest
     *  lane has no Reference resolver, so every batch would fail on the unknown function. A lookup runs only
     *  in a Pipeline {@code transform.sql} Step or a {@code transform.join} (LOOKUP-1, 2026-11-23). */
    public static final String ERR_LOOKUP_ON_INGEST = "ERR_LOOKUP_ON_INGEST";

    // ── Schema ───────────────────────────────────────────────────────────────────────────────
    // (schema-resolution / compatibility findings register here as they are wired)

    /** An ACTIVE pipeline whose {@code schema_file} resolves nowhere beside its config — it registers,
     *  then never loads ("Schema file not found"), so the save refuses it (SCHEMA-FILE-NAME-1). */
    public static final String ERR_SCHEMA_FILE_UNRESOLVED = "ERR_SCHEMA_FILE_UNRESOLVED";

    /** The same unresolved {@code schema_file} on an INACTIVE draft — it bites only at activation. */
    public static final String WARN_SCHEMA_FILE_UNRESOLVED = "WARN_SCHEMA_FILE_UNRESOLVED";

    /** A delimited / fixed-width pipeline whose schema names a column where the load needs its zero-based
     *  INDEX as the {@code raw.fields[].selector} — the pipeline would not load, so the save refuses it
     *  regardless of {@code active} (LA-ONBOARD-FIXES-1, F-4). */
    public static final String ERR_SELECTOR_NOT_INDEX = "ERR_SELECTOR_NOT_INDEX";

    /** A Dataset Measure Alert Rule's row filter ({@code when}) that cannot be applied as written: not a complete
     *  Condition Language group, a field outside the Dataset's Schema, or a field the Space masks — an alert never
     *  reveals, so it may not count rows by a masked value (ALERT-RULE-ROW-FILTER-1). */
    public static final String ERR_ALERT_ROW_FILTER_INVALID = "ERR_ALERT_ROW_FILTER_INVALID";

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

    /** A column classification the Space does not know: it would mask nothing (CLASSIFICATION-UNMASKED-CLASS-1). */
    public static final String ERR_UNKNOWN_CLASSIFICATION = "ERR_UNKNOWN_CLASSIFICATION";

    /** The same dead-key finding where a config already on disk is merely being read, not authored —
     *  refusing there would brick a deployed pipeline over a key that was never load-bearing. */
    public static final String WARN_UNKNOWN_CONFIG_KEY = "WARN_UNKNOWN_CONFIG_KEY";

    /** A config that implies advancing progress state (a remote/DB-export/Kafka Collector, content dedup,
     *  markers + duplicate_check, windowed {@code transform.dedup}, a job's {@code incremental_column}) saved
     *  into a Space whose effective Safety Policy has {@code permit.advance_state: false} (policy-narrowing D9). */
    public static final String ERR_SAFETY_STATE_ADVANCE_REFUSED = "ERR_SAFETY_STATE_ADVANCE_REFUSED";

    /** A {@code sql} Step's {@code fields[]} row carrying column metadata ({@code description}/{@code unit}/
     *  {@code classification}/{@code synonym}) that is not a plain string, or that names no distinct output
     *  column (blank or duplicate {@code name}) — the Catalog and the masking lineage read it
     *  ({@code AUTHORING-REDESIGN-1} (e)). Always ERROR: a classification nothing can attach is a silent loss. */
    public static final String ERR_STEP_COLUMN_METADATA_INVALID = "ERR_STEP_COLUMN_METADATA_INVALID";

    // ── Request refusals (REFUSAL-CODES-1) ───────────────────────────────────────────────────
    // The 422s agents hit most, given a code of their own so they need not read the message (evidence: the
    // agent-builder spike 2026-11-21 and the W4 golden runs). Carried as the one finding of a refusal:
    // error.details.findings[0].code, with error.errorCode still CONFIG_VALIDATION_FAILED.

    /** A request or config names a column the Dataset does not have (a BI {@code orderBy}, a Link Analysis
     *  query's column or filter field, an Entity Identity or index column). */
    public static final String ERR_COLUMN_UNKNOWN = "ERR_COLUMN_UNKNOWN";

    /** A request body lacks a field the route requires (a Link Analysis query's {@code dataset},
     *  {@code startNode}, {@code value}, {@code ids}, or a column key). */
    public static final String ERR_REQUEST_FIELD_MISSING = "ERR_REQUEST_FIELD_MISSING";

    /** A request field holds a value outside its fixed set (a Link Analysis query's {@code direction}). */
    public static final String ERR_REQUEST_VALUE_NOT_ALLOWED = "ERR_REQUEST_VALUE_NOT_ALLOWED";

    /** A BI query filter names an operator the compiler does not know. */
    public static final String ERR_BI_FILTER_OP_UNKNOWN = "ERR_BI_FILTER_OP_UNKNOWN";

    /** A virtual Dataset (one with {@code sql}) names no {@code sourceName} store for its {@code FROM}. */
    public static final String ERR_DATASET_SQL_NO_SOURCE = "ERR_DATASET_SQL_NO_SOURCE";

    /** A Dataset's {@code calculated[]} entry is not a {@code {name, expr}} object with a plain-identifier name
     *  and an expression. */
    public static final String ERR_CALCULATED_COLUMN_INVALID = "ERR_CALCULATED_COLUMN_INVALID";

    /** Reading a Dataset's rows failed in the database (a table or column its relation names does not exist,
     *  a cast fails); the message carries the database's text, withheld while masking is in force. */
    public static final String ERR_DATASET_READ_FAILED = "ERR_DATASET_READ_FAILED";

    /** A Reconciliation's {@code cardinality} is not one of the four words. */
    public static final String ERR_RECON_CARDINALITY_UNKNOWN = "ERR_RECON_CARDINALITY_UNKNOWN";

    /** A Reconciliation's {@code compareColumns[]} entry is refused: a key column, the reserved record count, an
     *  {@code agg} other than sum|count, a {@code toleranceType} other than exact|absolute|percent, or a
     *  negative {@code tolerance}. */
    public static final String ERR_RECON_COMPARE_COLUMN_INVALID = "ERR_RECON_COMPARE_COLUMN_INVALID";

    /** A Decision Rule has no {@code name}. */
    public static final String ERR_DECISION_RULE_NO_NAME = "ERR_DECISION_RULE_NO_NAME";

    /** A Decision Rule has no {@code consequences}, or an empty list. */
    public static final String ERR_DECISION_RULE_NO_CONSEQUENCE = "ERR_DECISION_RULE_NO_CONSEQUENCE";

    /** A Decision Rule's {@code when} is not a condition group ({@code {kind: group, op, items}}). */
    public static final String ERR_DECISION_RULE_WHEN_INVALID = "ERR_DECISION_RULE_WHEN_INVALID";
}

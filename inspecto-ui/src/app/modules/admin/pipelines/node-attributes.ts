import {
    type AttributeSpec,
    COLLECTOR_ATTRIBUTES,
    MARKER_DEDUP_ATTRIBUTES,
    UNPACK_ATTRIBUTES,
    OUTPUT_ATTRIBUTES,
} from 'app/inspecto/component-model';

/**
 * Per-node-type config attribute schemas for the generic {@link NodeConfigDialog} — the non-parser
 * counterpart to `parser-types.ts` (parsers have their own rich dialog). Each authored node type
 * declares its config attributes with a disclosure tier (`required | optional | advanced`), so the
 * shared `<inspecto-schema-form>` renders a right-sized form instead of the old free-form key/value
 * grid (review R2/R4: `docs/superpower/reviews/node-config.md`).
 *
 * <p>**Keyed by the engine's own node types** (`BuiltinNodeType`), which is what
 * `GET /pipelines/node-types` serves. They used to be invented (`collector.file`, `sink.file`,
 * `transform.record`, …) — strings the backend has never had, so `PipelineCompiler` silently dropped
 * those nodes while `PipelineValidator` only warned. Corrected 2026-07-31 (W2/U-D); the palette port
 * lives in `mock/handlers/pipelines.handler.ts`.
 *
 * <p>⚠ **This table has a Java mirror — change both.** `NodeConfigNameContractTest` (inspecto-engine)
 * drives each key below through the editor's real save path (`PipelineEditable.toMap` → `lower` →
 * `ConfigCodec.toToon` → `PipelineConfig.load`) and asserts the value lands on the engine field that reads
 * it, so a key that reaches nothing fails there rather than silently no-opping in production. Adding or
 * renaming a key here without updating its `contracts()` entry leaves the new key unguarded — nothing
 * fails, which is precisely the drift the check exists to stop.
 *
 * <p>⚠ **A key here IS the config key** — `AttributeSpec.key` is written verbatim into `node.config`
 * (`node-config.dialog`, no case-conversion layer anywhere in the app), so it must equal the string the
 * engine reads. `transform.filter` shipped as `predicate` while `RowShaper.filter` reads
 * `str(node, "where")`; renamed to `where` 2026-08-03 (recorded as D1 in the archived
 * `docs/archived-documents/plans-archive/vocabulary-and-config-contract-plan.md`) so the key matches
 * the engine's reader.
 * `transform.validate` reads `rule`, not `where` — it stays unspecced.
 *
 * <p>⚠ **`transform.filter` has TWO filtering moments, and they are not synonyms** (D7, resolved
 * 2026-08-03). The only host is `pipeline-editor.component`, which round-trips the flat
 * `*_pipeline.toon`; its lower merges every `transform.filter` node's cfg wholesale into
 * `processing.csv_settings` (`PipelineEditable.java:277`, mirrored at `mock/pipeline-editable.ts`).
 * That map has two readers:
 *
 * <ul>
 *   <li>**pre-parse** — `include_prefixes`/`include_regex`/`exclude_prefixes`/`exclude_regex` anchored on
 *       `filter_target_column`, matched with `regexp_matches()`/`LIKE` against ONE raw physical column
 *       inside the `read_csv` SELECT, before any field is named or typed
 *       (`DuckDbCsvIngester.filterWhere`). Round-trips through `PipelineLift.filterConfig` — it has
 *       always worked here; it was simply never declared, so the dialog couldn't reach it.</li>
 *   <li>**post-parse** — `where`, a SQL predicate over the mapped, typed target columns, applied by
 *       `DataTransformer.materialize` (added 2026-08-03; `PipelineConfig.CsvSettings.hasRowPredicate`).</li>
 * </ul>
 *
 * A predicate like `amount > 0` is inexpressible as a pre-parse regex, and a regex over an unparsed
 * column is inexpressible as a predicate — so do NOT collapse the two, and do not "simplify" the spec by
 * dropping either group. `RowShaper` (the authored `*_flow.toon` runtime, reachable only via
 * `PipelineJobRunner`) reads the same `where` key, but this editor cannot write that representation
 * (`POST`/`PUT /pipelines/authored` are 405 since W5).
 *
 * <p>**`acquisition` reuses the shared `COLLECTOR_ATTRIBUTES`** (`inspecto/component-model`, moved there
 * from `catalog/onboarding/` in the same change) — this is U-D's "one table per concern": both features
 * author the same `collector:` block, so a second hand-written table is exactly the drift that produced
 * fictional keys (`recursive`, `min_age_seconds`) here while Onboarding had the real ones
 * (`recursive_depth`, `stability__window`). One table, one truth — the WHOLE table, `duplicate__*`
 * included: D9's split onto a `transform.dedup.fingerprint` node was undone 2026-08-04 when that node
 * was removed (file dedup executes in the `CollectorProcessor` poll cycle, so it is collector-block
 * policy, never a transform).
 *
 * <p>⚠ **A connector's OWN options are not node config.** `query`, `watermark_column`, `topic`,
 * `bootstrap_servers` live in the ConnectionProfile's `options:` map, read by each connector
 * (`DbExportConnector`, `KafkaConnector`) — the node only names the profile via `connection`. So there
 * is no `acquisition` variant per source kind, and never was: the old
 * `collector.database`/`collector.stream` tables were a different concern wearing this one's clothes.
 * (`fetch_size`, `group_id` and `batch_size` from those tables are read nowhere in the backend at all —
 * `KafkaConnector` deliberately uses no consumer group, tracking offsets in the acquisition ledger.)
 *
 * <p>A node type absent from this map has **no** schema and falls back to the dialog's free-form
 * key/value editor — and every type keeps that editor as a collapsed "Additional config" escape hatch
 * for keys outside its schema. Types are deliberately left absent rather than guessed: the remaining
 * `transform.*` shapes are not specced server-side (`docs/FEATURE_INVENTORY.md` §G), and a best-guess
 * table that looks authoritative is what this change was cleaning up.
 */
// The `output:` block a sink writes is the SAME concern Onboarding's Dataset & Go-live stage
// authors, so it is the shared `OUTPUT_ATTRIBUTES` (component-model) — W4a collapsed the local
// `SINK_ATTRIBUTES` fork exactly as U-D collapsed the collector one. All three sink kinds write
// the same block — the kind is the materialisation behaviour, not a different config shape.
// The entry-node schedule (`trigger:`, T13 — PipelineTrigger): per-pipeline cadence, gated per tick
// by PipelineScheduler.dueThisTick. NOT part of the `collector:` block — the trigger: map is a
// top-level config section the acquisition node borrows (like the marker keys). Cron wins when both
// are stated; the space's poll tick is the resolution floor for `every`. Unspecced sub-keys
// (`type`, `coalesce`, `on`, `from`) survive a save untouched.
const TRIGGER_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'trigger__every',
        label: 'Run every',
        type: 'string',
        tier: 'optional',
        placeholder: '30s',
        help: "This pipeline's own poll cadence — 30s, 5m, 2h, 1d, or a bare number of seconds. Blank = every tick of the space's poll interval. The space poll interval is the resolution floor: a 30s pipeline needs the space tick at 30s or less.",
    },
    {
        key: 'trigger__cron',
        label: 'Run on a cron schedule',
        type: 'string',
        tier: 'optional',
        placeholder: '0 0 2 * * *',
        help: "Calendar cadence in the operations time zone (e.g. daily at 02:00). When both are stated, cron wins over 'Run every'.",
    },
    // The event-trigger half (ELT P3 S3b / UI-S7): `{type: event, on: dataset, from: …}`. `type`
    // stays derived — buildConfiguredNode writes `event` when `on` is picked, because `on:` under a
    // schedule type is config the trigger parser silently ignores. `on: commit` (upstream Pipeline
    // commit) stays authorable via Additional config only.
    {
        key: 'trigger__on',
        label: 'Run when',
        type: 'select',
        tier: 'optional',
        options: [
            { value: '', label: 'Poll / schedule (the default)' },
            { value: 'dataset', label: 'A Dataset is written' },
        ],
        help: "Event trigger: run when the watched source publishes, instead of on a cadence. 'A Dataset is written' pairs naturally with a dataset-entry collector — the write Signal supplies the latency, the acquire cycle does the copy.",
    },
    {
        key: 'trigger__from',
        label: 'Dataset to watch',
        type: 'autocomplete',
        tier: 'optional',
        required: true,
        dependsOn: { key: 'trigger__on', equals: 'dataset' },
        placeholder: 'datasets/orders_rollup',
        help: 'The Dataset whose writes fire this pipeline — datasets/<id> (a bare id works too). Usually the same Dataset the collector consumes.',
    },
    {
        key: 'trigger__coalesce',
        label: 'Coalesce window',
        type: 'string',
        tier: 'advanced',
        dependsOn: { key: 'trigger__on', equals: 'dataset' },
        placeholder: '30s',
        help: 'Debounce a burst of writes into one admitted run — 30s, 5m, or a bare number of seconds. Blank = run per write.',
    },
];

const NODE_ATTRIBUTES: Record<string, AttributeSpec[]> = {
    // The acquisition NODE's spec = the `collector:` block it authors + the marker-dedup keys it
    // borrows from `processing:`/`dirs:` (P5-a). Onboarding's Collection stage keeps the block table
    // alone — see MARKER_DEDUP_ATTRIBUTES for why the two are not merged.
    acquisition: [...COLLECTOR_ATTRIBUTES, ...MARKER_DEDUP_ATTRIBUTES, ...UNPACK_ATTRIBUTES, ...TRIGGER_ATTRIBUTES],
    // The persistent sink adds its destination to the shared output block: `database` is the one key
    // `PipelineEditable.lower` HARD-requires on the primary sink (`NO_PERSISTENT_SINK` refuses the save
    // without it), so it must be askable up front — but `required: false`, because a quarantine sink is
    // a `sink.persistent` too and sets `dir`, never `database`.
    'sink.persistent': [
        {
            key: 'database',
            label: 'Database directory',
            type: 'string',
            tier: 'required',
            required: false,
            placeholder: 'data/<pipeline>/database',
            help: "Directory where committed batches land. The pipeline's primary sink must set this; a quarantine sink writes unmatched files to 'dir' instead.",
        },
        ...OUTPUT_ATTRIBUTES,
        // Consignment formation left this node 2026-09-02 (CONSIGNMENT-HOME-1): it is a collector-block
        // key now — see COLLECTOR_ATTRIBUTES' "Consignment" group.
        // Concurrency (scheduler-system-config plan Part B): priority is a flat processing key
        // (SINK_PROC_OWNED, like threads); intake__* nests to the node's `intake` map, which lowers
        // to the processing.intake: block the IntakeGovernor reads.
        {
            key: 'priority',
            label: 'Priority',
            type: 'number',
            tier: 'advanced',
            min: 1,
            max: 3,
            help: "Share weight (1-3) for this pipeline's consignments when execution slots are contended: 3 gets ~3x the throughput share of 1. Shares, never precedence — a priority-1 pipeline always keeps making progress. Blank = 1.",
        },
        {
            key: 'intake__max_files_per_cycle',
            label: 'Intake cap (files/cycle)',
            type: 'number',
            tier: 'advanced',
            min: 0,
            help: "This pipeline's admission cap, overriding the -Dingest.maxFilesPerCycle global; 0 = explicitly unbounded (exempts this pipeline from a fleet-wide cap). Blank = inherit the global.",
        },
        {
            key: 'intake__min_files_per_cycle',
            label: 'Intake cap floor',
            type: 'number',
            tier: 'advanced',
            min: 1,
            help: "Floor the adaptive controller may halve this pipeline's cap down to. Blank = inherit -Dingest.minFilesPerCycle.",
        },
        {
            key: 'intake__adaptive',
            label: 'Adaptive intake control',
            type: 'boolean',
            tier: 'advanced',
            help: "Whether cycle overrun adjusts this pipeline's cap; off pins it at the stated cap. Blank = inherit -Dingest.backpressure.adaptive.",
        },
    ],
    'sink.materialized': OUTPUT_ATTRIBUTES,
    'sink.view': OUTPUT_ATTRIBUTES,
    // Two DIFFERENT filtering moments, both real on the flat path — see the D7 note in the file header.
    // Neither is individually mandatory (a node may use either), so both are visible with
    // `required: false` rather than one being `tier: 'required'` and forcing the wrong choice.
    'transform.filter': [
        {
            key: 'where',
            label: 'Row predicate (after parsing)',
            type: 'string',
            tier: 'required',
            required: false,
            placeholder: 'amount > 0',
            help: 'SQL over the mapped, typed columns. Rows matching are kept; NULL results are dropped.',
        },
        {
            key: 'include_regex',
            label: 'Include matching (before parsing)',
            type: 'list',
            tier: 'optional',
            placeholder: '^CALL',
            help: 'Keep rows whose target column matches any of these regexes, checked on the raw text before parsing.',
        },
        {
            key: 'exclude_regex',
            label: 'Exclude matching (before parsing)',
            type: 'list',
            tier: 'optional',
            placeholder: '^TEST',
            help: 'Drop rows whose target column matches any of these regexes.',
        },
        {
            key: 'include_prefixes',
            label: 'Include by prefix (before parsing)',
            type: 'list',
            tier: 'optional',
            help: 'Keep rows whose target column starts with any of these.',
        },
        {
            key: 'exclude_prefixes',
            label: 'Exclude by prefix (before parsing)',
            type: 'list',
            tier: 'optional',
            help: 'Drop rows whose target column starts with any of these.',
        },
        {
            key: 'filter_target_column',
            label: 'Target column index',
            type: 'number',
            tier: 'advanced',
            min: 0,
            help: 'Which raw column (0-based) the four before-parsing lists above match against. Ignored by the row predicate.',
        },
    ],
    // `branches` — the list of `{key, where, database}` that actually does the routing — has no spec:
    // the `list` type added with D7 is `string[]`, and these are MAPS, so it does not apply here.
    // ⛔ Do not close that with a map-list spec type: branches have their own surface (the Recipe
    // view's branch rows — add/remove, name, `when` predicate), and `database`/`key` are DERIVED from
    // the `route:<key>` edge and its sink, so a form-owned `branches` would be replaced wholesale on
    // save and destroy that stamping. `mode` is the only scalar.
    'transform.route': [
        {
            key: 'mode',
            label: 'Route mode',
            type: 'select',
            tier: 'required',
            default: 'case',
            options: [
                { value: 'case', label: 'case (exclusive)' },
                { value: 'clone', label: 'clone (fan-out)' },
            ],
            help: 'Branch keys, predicates and destinations are edited on the branch rows in the Recipe view.',
        },
    ],
    // Record-grain dedup (→ processing.dedup) — distinct from the file-level duplicate Guarantees.
    'transform.dedup': [
        {
            key: 'keys',
            label: 'Dedup keys',
            type: 'list',
            tier: 'required',
            help: 'Rows sharing these column values are duplicates; the first (per "Order by") is kept.',
            placeholder: 'call_id',
        },
        {
            key: 'order_by',
            label: 'Order by',
            type: 'string',
            tier: 'optional',
            help: 'Which duplicate wins — SQL ordering over the typed columns; blank = input order. REQUIRED once Scope declares a window: a durable ledger must never record a non-deterministic winner.',
            placeholder: 'event_ts DESC',
        },
        {
            key: 'scope',
            label: 'Scope',
            type: 'string',
            tier: 'advanced',
            help: 'How far back a key is a duplicate: blank/consignment = within this Consignment only; window(<ISO-8601 period>), e.g. window(P4D), also suppresses keys admitted by earlier Consignments inside that window via the durable dedup ledger (runs at rest).',
            placeholder: 'window(P4D)',
        },
    ],
    // `transform.dedup.marker` is deliberately ABSENT: P5-a moved marker dedup onto the acquisition
    // node (MARKER_DEDUP_ATTRIBUTES above), and nothing emits the node any more — it is read-compat
    // only, so a spec here would invite editing a node the lift never produces.
    // Group-by rollup (→ processing.summarize) — authoring-only until the branch-aware executor arms it.
    'transform.summarize': [
        {
            key: 'group_by',
            label: 'Group by',
            type: 'list',
            tier: 'required',
            help: 'Grouping columns of the rollup.',
            placeholder: 'region',
        },
        {
            key: 'measures',
            label: 'Measures',
            type: 'list',
            tier: 'required',
            help: 'Aggregate expressions computed per group.',
            placeholder: 'sum(amount)',
        },
    ],
    // Reference join (→ processing.join, D-4) — `reference` names a registered Reference component.
    'transform.join': [
        {
            key: 'reference',
            label: 'Reference',
            type: 'autocomplete',
            tier: 'required',
            help: 'The registered Reference component joined onto the row set.',
            placeholder: 'reference/rates',
        },
        {
            key: 'on',
            label: 'Join keys',
            type: 'list',
            tier: 'required',
            help: 'Column(s) equated between the rows and the Reference.',
            placeholder: 'currency',
        },
    ],
    // SQL transformer v1 (sql-transform-v1-plan.md, B1) — one author SELECT over the typed input.
    // No `where` here on purpose (D3): filtering stays a separate transform.filter Step.
    'transform.sql': [
        {
            key: 'sql',
            label: 'SQL',
            type: 'multiline',
            tier: 'required',
            help: "One SELECT statement over the typed input relation, addressed by the fixed alias 'input' (FROM input) — the engine rewrites it to the real relation at execution. No DDL/DML, no multiple statements.",
            placeholder: 'SELECT TRIM(name) AS customer, TRY_CAST(amt AS DOUBLE) FROM input',
        },
    ],
};

/**
 * The declared attribute schema for a node type, or `undefined` when it has none (free-form only).
 *
 * <p>⚠ **Since §3.1 this table is the FALLBACK, not the source.** The server publishes the same vocabulary
 * on `GET /pipelines/node-types` (`attributes[]`, built by `NodeAttributes.java`), and the node dialog
 * prefers that. This copy is what the editor uses before the catalog resolves and what the offline/mock
 * build runs on — so it must stay identical to the served one, which
 * `node-attributes.spec.ts` enforces against the committed
 * `inspecto/contracts/node-attributes.contract.json` that the Java `NodeAttributesContractTest` also checks.
 * Neither side can drift without one of the two suites failing.
 */
export function nodeAttributesFor(type: string | undefined): AttributeSpec[] | undefined {
    return type ? NODE_ATTRIBUTES[type] : undefined;
}

/** Every node type this fallback table speccs — the drift check's iteration source. */
export function speccedNodeTypes(): string[] {
    return Object.keys(NODE_ATTRIBUTES);
}

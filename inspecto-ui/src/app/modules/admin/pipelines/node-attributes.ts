import { type AttributeSpec, COLLECTOR_ATTRIBUTES, OUTPUT_ATTRIBUTES } from 'app/inspecto/component-model';

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
const NODE_ATTRIBUTES: Record<string, AttributeSpec[]> = {
    acquisition: COLLECTOR_ATTRIBUTES,
    'sink.persistent': OUTPUT_ATTRIBUTES,
    'sink.materialized': OUTPUT_ATTRIBUTES,
    'sink.view': OUTPUT_ATTRIBUTES,
    // Two DIFFERENT filtering moments, both real on the flat path — see the D7 note in the file header.
    // Neither is individually mandatory (a node may use either), so both are visible with
    // `required: false` rather than one being `tier: 'required'` and forcing the wrong choice.
    'transform.filter': [
        { key: 'where', label: 'Row predicate (after parsing)', type: 'string', tier: 'required', required: false, placeholder: 'amount > 0', help: 'SQL over the mapped, typed columns. Rows matching are kept; NULL results are dropped.' },
        { key: 'include_regex', label: 'Include matching (before parsing)', type: 'list', tier: 'optional', placeholder: '^CALL', help: 'Keep rows whose target column matches any of these regexes, checked on the raw text before parsing.' },
        { key: 'exclude_regex', label: 'Exclude matching (before parsing)', type: 'list', tier: 'optional', placeholder: '^TEST', help: 'Drop rows whose target column matches any of these regexes.' },
        { key: 'include_prefixes', label: 'Include by prefix (before parsing)', type: 'list', tier: 'optional', help: 'Keep rows whose target column starts with any of these.' },
        { key: 'exclude_prefixes', label: 'Exclude by prefix (before parsing)', type: 'list', tier: 'optional', help: 'Drop rows whose target column starts with any of these.' },
        { key: 'filter_target_column', label: 'Target column index', type: 'number', tier: 'advanced', min: 0, help: 'Which raw column (0-based) the four before-parsing lists above match against. Ignored by the row predicate.' },
    ],
    // `branches` — the list of `{key, where}` that actually does the routing — has no spec: the `list`
    // type added with D7 is `string[]`, and these are MAPS, so it does not apply here. The named routes
    // are authored on the canvas edges anyway. `mode` is the only scalar.
    'transform.route': [
        { key: 'mode', label: 'Route mode', type: 'select', tier: 'required', default: 'case', options: [{ value: 'case', label: 'case (exclusive)' }, { value: 'clone', label: 'clone (fan-out)' }], help: 'Named routes and their predicates are edited on the canvas edges.' },
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
 * `inspecto/mock/node-attributes.contract.json` that the Java `NodeAttributesContractTest` also checks.
 * Neither side can drift without one of the two suites failing.
 */
export function nodeAttributesFor(type: string | undefined): AttributeSpec[] | undefined {
    return type ? NODE_ATTRIBUTES[type] : undefined;
}

/** Every node type this fallback table speccs — the drift check's iteration source. */
export function speccedNodeTypes(): string[] {
    return Object.keys(NODE_ATTRIBUTES);
}

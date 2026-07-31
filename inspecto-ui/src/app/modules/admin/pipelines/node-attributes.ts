import { type AttributeSpec, COLLECTOR_ATTRIBUTES } from 'app/inspecto/component-model';

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
 * <p>**`acquisition` reuses the shared `COLLECTOR_ATTRIBUTES`** (`inspecto/component-model`, moved there
 * from `catalog/onboarding/` in the same change) — this is U-D's "one table per concern": both features
 * author the same `collector:` block, so a second hand-written table is exactly the drift that produced
 * fictional keys (`recursive`, `min_age_seconds`) here while Onboarding had the real ones
 * (`recursive_depth`, `stability__window`). One table, one truth.
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
/**
 * The `output:` block a sink writes, per `PipelineCompiler.toConfigMap` → `PipelineConfigParser:255-257`:
 * `format` (CSV | PARQUET, default CSV — `PartitionWriter`), `compression`, `ducklake`. The old tables
 * also offered `partition_by`, `table`, `mode` and `key_columns`; **the backend reads none of them**, so
 * they are gone rather than kept as convincing-looking dead knobs. All three sink kinds write the same
 * `output:` block — the kind is the materialisation behaviour, not a different config shape.
 */
const SINK_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'format', label: 'Format', type: 'select', tier: 'required', default: 'CSV',
        options: [{ value: 'CSV', label: 'CSV' }, { value: 'PARQUET', label: 'Parquet' }],
    },
    { key: 'compression', label: 'Compression', type: 'string', tier: 'advanced', placeholder: 'zstd', help: 'Codec name, e.g. snappy / zstd / gzip.' },
];

const NODE_ATTRIBUTES: Record<string, AttributeSpec[]> = {
    acquisition: COLLECTOR_ATTRIBUTES,
    'sink.persistent': SINK_ATTRIBUTES,
    'sink.materialized': SINK_ATTRIBUTES,
    'sink.view': SINK_ATTRIBUTES,
    'transform.filter': [
        { key: 'predicate', label: 'Keep-when predicate', type: 'string', tier: 'required', placeholder: 'amount > 0', help: 'Rows matching are kept; the rest go to the dropped branch.' },
    ],
    'transform.route': [
        { key: 'mode', label: 'Route mode', type: 'select', tier: 'required', default: 'case', options: [{ value: 'case', label: 'case (exclusive)' }, { value: 'clone', label: 'clone (fan-out)' }] },
        { key: 'route_column', label: 'Route by column', type: 'string', tier: 'optional', help: 'Named routes are edited on the canvas edges.' },
    ],
};

/** The declared attribute schema for a node type, or `undefined` when it has none (free-form only). */
export function nodeAttributesFor(type: string | undefined): AttributeSpec[] | undefined {
    return type ? NODE_ATTRIBUTES[type] : undefined;
}

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
// The `output:` block a sink writes is the SAME concern Onboarding's Dataset & Go-live stage
// authors, so it is the shared `OUTPUT_ATTRIBUTES` (component-model) — W4a collapsed the local
// `SINK_ATTRIBUTES` fork exactly as U-D collapsed the collector one. All three sink kinds write
// the same block — the kind is the materialisation behaviour, not a different config shape.
const NODE_ATTRIBUTES: Record<string, AttributeSpec[]> = {
    acquisition: COLLECTOR_ATTRIBUTES,
    'sink.persistent': OUTPUT_ATTRIBUTES,
    'sink.materialized': OUTPUT_ATTRIBUTES,
    'sink.view': OUTPUT_ATTRIBUTES,
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

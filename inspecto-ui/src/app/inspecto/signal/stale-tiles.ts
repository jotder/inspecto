/**
 * `SIGNAL-STALE-TILES-1` — resolve "which dashboard tiles has a data disruption invalidated?"
 *
 * <h3>Why the chain starts at a PIPELINE and not a Dataset</h3>
 * 🔴 The obvious design — a Signal that names the Dataset it invalidated — **is not what the tree emits**,
 * and building against it would have produced a resolver with no input. Grounded 2026-09-11:
 * * `SEQUENCE_GAP` is a raw **Event**, not a Signal (`AcquisitionTelemetry`), and it carries the
 *   **pipeline** plus the missing sequence key — no store, table or Dataset id.
 * * **Quarantine emits no signal at all**; a quarantined file is a `FILE_QUARANTINED` event, and rejected
 *   rows are batch metrics.
 * * **Widgets and Dashboards are not Catalog nodes** (a control grep over the `catalog` package returns
 *   zero hits for either, while the same grep returns dozens elsewhere), so `/catalog/graph` cannot answer
 *   "which widgets depend on Dataset X" — the traversal the backlog row named does not exist.
 *
 * So the anchor is the identity that IS carried: the pipeline. The chain is
 * `pipeline → the store it produces → Datasets on that store → widgets bound to them → tiles`, every hop
 * over a field that already exists and is already validated server-side (`ComponentIntegrity` checks
 * `widget.datasetId → dataset` and `dashboard.tiles[].widgetId → widget`).
 *
 * ⚠ **The granularity that follows, stated plainly:** a disruption marks **every** Dataset fed by that
 * pipeline, not only the rows or the column that actually gapped. That is a deliberate over-approximation
 * — a tile wrongly marked stale costs a second look, a tile wrongly left clean is a number someone acts on
 * — and it is the honest limit of a pipeline-level anchor. Narrowing it needs the emitters to carry a
 * store identity (`INCIDENT`-style `Ref` subject), which is a backend change, not a resolver change.
 *
 * <h3>Self-clearing by construction</h3>
 * There is no stored "stale" flag anywhere, and deliberately so. Staleness is **derived**: a pipeline is
 * stale while its most recent disruption is NEWER than its most recent successful commit. "Clear the badge
 * on the next successful run" is therefore not a separate mechanism that could be forgotten or could leak —
 * it falls out of the comparison. ⛔ Do not "improve" this by persisting a flag.
 *
 * Framework-free on purpose (`inspecto/signal` holds no Angular), and typed structurally so the real
 * `EventRow` / `PipelineSummary` / `Dataset` / `Widget` satisfy these without importing them.
 */

/** An event that disrupts data — a `SEQUENCE_GAP` or `FILE_QUARANTINED`. */
export interface DisruptionEvent {
    type: string;
    pipeline: string | null;
    ts: number;
    message?: string;
}

/** A successful batch commit — the thing that clears a disruption. */
export interface CommitEvent {
    pipeline: string | null;
    ts: number;
}

/** Structural view of `PipelineSummary`: the stores this pipeline writes. */
export interface ProducingPipeline {
    name: string;
    produces: string[];
}

/** Structural view of a `Dataset`: which store it reads. */
export interface DatasetBinding {
    id: string;
    sourceName: string;
}

/** Structural view of a `Widget`: which Dataset it renders. */
export interface WidgetBinding {
    id: string;
    datasetId: string;
}

/** Why a tile is marked stale — rendered as the badge's tooltip, so it must read as a sentence. */
export interface StaleMark {
    /** The pipeline whose disruption caused it. */
    pipeline: string;
    /** The disruption's event type, e.g. `SEQUENCE_GAP`. */
    type: string;
    /** When the disruption happened (epoch millis). */
    at: number;
    /** The store that went stale — the join between the pipeline and the Dataset. */
    store: string;
    /** A human sentence naming the pipeline and what happened. */
    reason: string;
}

/** The event types that mark data as disrupted. Extending this is the supported way to add a trigger. */
export const DISRUPTION_TYPES = ['SEQUENCE_GAP', 'FILE_QUARANTINED'] as const;

/**
 * Pipelines whose most recent disruption has **not** been followed by a successful commit.
 *
 * ⚠ The comparison is strictly "disruption newer than commit". A commit at the same millisecond as the
 * disruption counts as clearing it: the commit is the later fact operationally, and treating a tie as
 * still-stale would leave a badge that no subsequent run could remove.
 */
export function stalePipelines(
    disruptions: readonly DisruptionEvent[],
    commits: readonly CommitEvent[],
): Map<string, StaleMark> {
    const latestCommit = new Map<string, number>();
    for (const c of commits) {
        if (!c.pipeline) continue;
        latestCommit.set(c.pipeline, Math.max(latestCommit.get(c.pipeline) ?? 0, c.ts));
    }

    const worst = new Map<string, DisruptionEvent>();
    for (const d of disruptions) {
        if (!d.pipeline) continue; // a service-wide event invalidates no particular dataset
        const seen = worst.get(d.pipeline);
        if (!seen || d.ts > seen.ts) worst.set(d.pipeline, d);
    }

    const out = new Map<string, StaleMark>();
    for (const [pipeline, d] of worst) {
        if (d.ts <= (latestCommit.get(pipeline) ?? 0)) continue; // a later run already fixed it
        out.set(pipeline, {
            pipeline,
            type: d.type,
            at: d.ts,
            store: '',
            reason: reasonFor(d, pipeline),
        });
    }
    return out;
}

/**
 * Widget ids whose Dataset reads a store produced by a stale pipeline — the whole chain in one call.
 *
 * A widget with no `datasetId` (a view-bound geo/link widget) is never marked: it reads no Dataset, so no
 * pipeline can invalidate it.
 */
export function staleWidgets(
    disruptions: readonly DisruptionEvent[],
    commits: readonly CommitEvent[],
    pipelines: readonly ProducingPipeline[],
    datasets: readonly DatasetBinding[],
    widgets: readonly WidgetBinding[],
): Map<string, StaleMark> {
    const stale = stalePipelines(disruptions, commits);
    if (stale.size === 0) return new Map();

    // store → the mark of the stale pipeline that produces it. A store fed by two stale pipelines keeps
    // the most recent disruption, which is the one an operator would look at first.
    const staleStores = new Map<string, StaleMark>();
    for (const p of pipelines) {
        const mark = stale.get(p.name);
        if (!mark) continue;
        for (const store of p.produces ?? []) {
            const existing = staleStores.get(store);
            if (!existing || mark.at > existing.at) staleStores.set(store, { ...mark, store });
        }
    }

    const staleDatasets = new Map<string, StaleMark>();
    for (const d of datasets) {
        const mark = staleStores.get(d.sourceName);
        if (mark) staleDatasets.set(d.id, mark);
    }

    const out = new Map<string, StaleMark>();
    for (const w of widgets) {
        if (!w.datasetId) continue;
        const mark = staleDatasets.get(w.datasetId);
        if (mark) out.set(w.id, mark);
    }
    return out;
}

function reasonFor(d: DisruptionEvent, pipeline: string): string {
    const what =
        d.type === 'SEQUENCE_GAP'
            ? 'a sequence gap'
            : d.type === 'FILE_QUARANTINED'
              ? 'a quarantined file'
              : d.type.toLowerCase().replace(/_/g, ' ');
    return `${pipeline} reported ${what} and has not completed a batch since. This tile may be missing data.`;
}

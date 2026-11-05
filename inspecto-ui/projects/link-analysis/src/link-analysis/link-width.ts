import type { G6GraphData, GraphSourceId, GraphSourceQuery } from '@inspecto/core/graph';
import type { GraphDisplayOptions } from '@inspecto/core/graph/graph-view.component';

/**
 * What a link's drawn width follows (LA-COLUMN-ROLES-2 D-2): the rows folded into it (`count`), or the total of the link
 * mapping's primary Measure over those rows (`measure`). Count is the default, so nothing changes until the analyst asks.
 */
export type LinkWidthBy = 'count' | 'measure';

/** The Link Analysis display options a saved view keeps: the shared graph view's, plus the link width (absent = count). */
export interface LaDisplayOptions extends GraphDisplayOptions {
    linkWidth?: 'measure';
}

/**
 * The `weight` the largest Measure total is drawn at. The graph view scales a weight log-style (1.5 + log2(w + 1) px, at
 * most 12), so a raw money total would put nearly every link at the ceiling: totals are drawn relative to the largest on
 * the canvas instead, the largest at about 11.5 px and one a thousandth of it at about 2.5 px.
 */
export const MEASURE_WEIGHT_CEILING = 1023;

/** A link's total when it is a positive number, else 0 (no number, or a sum at or below zero: drawn thinnest). */
function positiveTotal(data: object): number {
    const t = (data as { total?: number | null }).total;
    return typeof t === 'number' && t > 0 ? t : 0;
}

/**
 * The canvas data with each link's `data.weight` set from `by`, which the shared graph view turns into its width: the
 * link's row count, or its Measure total relative to the largest on the canvas (a null total is thinnest). A link with
 * no count (a super-node stand-in, a path hop) is left unweighted.
 */
export function weighLinks(g: G6GraphData | null, by: LinkWidthBy): G6GraphData | null {
    if (!g) return g;
    const max = by === 'measure' ? g.edges.reduce((m, e) => Math.max(m, positiveTotal(e.data)), 0) : 0;
    return {
        ...g,
        edges: g.edges.map((e) => {
            const weight =
                by === 'measure'
                    ? max > 0
                        ? (MEASURE_WEIGHT_CEILING * positiveTotal(e.data)) / max
                        : 0
                    : (e.data as { count?: number }).count;
            return weight === undefined ? e : { ...e, data: { ...e.data, weight } };
        }),
    };
}

/** The link mappings of a read: the Datasets they read, and where a Measure column goes on each. */
function mappingDatasets(sourceId: GraphSourceId, q: GraphSourceQuery): string[] {
    if (sourceId === 'entity-projection-multi') return (q.multi?.edges ?? []).map((m) => m.dataset);
    if (sourceId !== 'entity-projection') return [];
    return (q.projections?.length ? q.projections : q.projection ? [q.projection] : []).map((p) => p.datasetId);
}

/**
 * The primary Measure every link mapping of a read names (each Dataset's `defaultLinkMapping.primaryMeasure`, the same
 * column name across them), else undefined: then the Display menu offers no Width by Measure. One Dataset's read is the
 * common case; a read over several is offered it only when their Measures are one by name, so a link two Datasets fold
 * together never adds two different Measures.
 */
export function sharedLinkMeasure(
    sourceId: GraphSourceId,
    q: GraphSourceQuery,
    measureOf: (datasetId: string) => string | undefined,
): string | undefined {
    const names = new Set(mappingDatasets(sourceId, q).map(measureOf));
    return names.size === 1 ? [...names][0] : undefined;
}

/** `q` with `column` summed on every link mapping: `measureCol` on a projection, `measureColumn` on a multi edge mapping. */
export function withLinkMeasure(q: GraphSourceQuery, column: string): GraphSourceQuery {
    return {
        ...q,
        ...(q.projection ? { projection: { ...q.projection, measureCol: column } } : {}),
        ...(q.projections?.length ? { projections: q.projections.map((p) => ({ ...p, measureCol: column })) } : {}),
        ...(q.multi
            ? { multi: { ...q.multi, edges: q.multi.edges.map((m) => ({ ...m, measureColumn: column })) } }
            : {}),
    };
}

/** True when every link mapping of `q` already sums `column`, so its links carry the totals (no re-read needed). */
export function carriesLinkMeasure(q: GraphSourceQuery, column: string): boolean {
    const ps = q.projections?.length ? q.projections : q.projection ? [q.projection] : [];
    const cols = [...ps.map((p) => p.measureCol), ...(q.multi?.edges ?? []).map((m) => m.measureColumn)];
    return cols.length > 0 && cols.every((c) => c === column);
}

/** What the drawn width means, for the Display menu's line under Link width. */
export function linkWidthHint(by: LinkWidthBy, measure: string | undefined): string {
    return by === 'measure' && measure
        ? `Thicker links carry a larger total of ${measure}, relative to the largest; a link with no number is thinnest.`
        : 'Thicker links fold more rows.';
}

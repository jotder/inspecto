import { ConditionGroup } from 'app/inspecto/query';
import { humanizeColumn } from '../column-label';
import { channelMeasure, channelMeasureId } from '../query-spec';
import { compareCell, compareFragment, hasCompare } from '../compare';
import { hasOverlay, overlayCell, overlayFragment } from '../chart-overlays';
import { ChannelValue, ControlValues, QuerySpec, TimeGrain, VizProps, VizRenderOptions, VizSeries } from '../viz-types';

/**
 * Shared pure helpers for the standard chart plugins: turn the field mapping into a {@link QuerySpec}, and
 * pivot result rows into Chart.js-ready labels + series. No Angular, no chart.js — just data.
 */

export interface QueryCtx {
    datasetId: string;
    sourceName: string;
    filters?: ConditionGroup | null;
    /** The widget's options — read by plugins whose query depends on one (the table's `tableSort`). */
    options?: VizRenderOptions;
}

function field(cv?: ChannelValue[]): string | undefined {
    return cv?.[0]?.field;
}

function num(v: unknown): number {
    const n = typeof v === 'number' ? v : Number(v);
    return Number.isFinite(n) ? n : 0;
}

function str(v: unknown): string {
    return v == null ? '' : String(v);
}

/** Distinct values of `key` across rows, in first-seen order. */
function distinct(rows: Record<string, unknown>[], key: string): string[] {
    const seen = new Set<string>();
    const out: string[] = [];
    for (const r of rows) {
        const v = str(r[key]);
        if (!seen.has(v)) {
            seen.add(v);
            out.push(v);
        }
    }
    return out;
}

/** Group-by = the x + series (dimension/temporal) channels; measures = the y channels' aggregations. */
export function buildXyQuery(values: ControlValues, ctx: QueryCtx): QuerySpec {
    const dims = [values.x?.[0], values.series?.[0]].filter((cv): cv is ChannelValue => !!cv?.field);
    const groupBy = dims.map((cv) => cv.field);
    const measures = (values.y ?? []).map(channelMeasure);
    return {
        datasetId: ctx.datasetId,
        sourceName: ctx.sourceName,
        groupBy,
        ...(channelGrains(dims) ?? {}),
        measures,
        filters: ctx.filters ?? null,
        // Categories follow ROW order, and an unordered GROUP BY comes back in hash order — months read
        // "Oct, Jan, Mar, … Nov, Dec". Order by the dimensions; a widget's `sort` option still re-sorts by value.
        // A comparison refuses a hidden-aggregate order (the server compares over the result's own columns).
        ...(groupBy.length
            ? {
                  orderBy: categoryOrderBy(
                      groupBy,
                      compareFragment(ctx.options) || overlayFragment(ctx.options)
                          ? undefined
                          : ctx.options?.categoryOrder,
                  ),
              }
            : {}),
        ...withCompare(ctx),
        // I-11 / I-7 read along a date grouped by day, week or month on x; any other x would only be refused (422).
        ...(values.x?.[0]?.grain && values.x[0].grain !== 'auto' ? withOverlay(ctx) : {}),
    };
}

/** The `{overlay}` fragment of a query (BI-INSIGHTS-1 I-11 / I-7), or nothing — a spec without one keeps its exact shape. */
export function withOverlay(ctx: QueryCtx): Pick<QuerySpec, 'overlay'> {
    const overlay = overlayFragment(ctx.options);
    return overlay ? { overlay } : {};
}

/** The `{compare}` fragment of a query (BI-INSIGHTS-1 I-1), or nothing — so a spec without one keeps its exact shape. */
export function withCompare(ctx: QueryCtx): Pick<QuerySpec, 'compare'> {
    const compare = compareFragment(ctx.options);
    return compare ? { compare } : {};
}

/** The ORDER BY of an XY query (terms over a grouped dimension carry `cosmetic`: the server drops them rather than 422s when
 *  that dimension is a masked key for a caller who cannot reveal): alphabetical by the dimensions, or — when the widget set `options.categoryOrder` —
 *  that term first (a hidden `agg` only for a column the result does not carry), the dimensions still breaking ties. */
function categoryOrderBy(groupBy: string[], co: VizRenderOptions['categoryOrder']): NonNullable<QuerySpec['orderBy']> {
    const alphabetical = groupBy.map((f) => ({ field: f, dir: 'asc' as const, cosmetic: true }));
    if (!co?.field) return alphabetical;
    const hidden = !groupBy.includes(co.field);
    const term = {
        field: co.field,
        dir: co.dir,
        ...(hidden ? { agg: co.agg ?? ('min' as const) } : { cosmetic: true }),
    };
    return [term, ...alphabetical.filter((t) => t.field !== co.field)];
}

/** The `{grains}` fragment for the dimension channels that picked a real bucket (`auto` = none), or
 *  `null` so an ungrained spec carries no key at all. Only *grouped* channels may appear — the server
 *  refuses a grain on a column it is not grouping by. */
export function channelGrains(dims: (ChannelValue | undefined)[]): Pick<QuerySpec, 'grains'> | null {
    const grains: Record<string, TimeGrain> = {};
    for (const cv of dims) if (cv?.field && cv.grain && cv.grain !== 'auto') grains[cv.field] = cv.grain;
    return Object.keys(grains).length ? { grains } : null;
}

/** Pivot aggregated rows into {labels (x), one series per `series` value (or a single measure series)}. */
export function transformXy(rows: Record<string, unknown>[], values: ControlValues): VizProps {
    const xField = field(values.x);
    const seriesField = field(values.series);
    const ycv = values.y?.[0];
    if (!xField || !ycv) return { labels: [], series: [] };
    const mId = channelMeasureId(ycv);
    const labels = distinct(rows, xField);

    const compared = hasCompare(rows, mId);
    const overlaid = hasOverlay(rows, mId);
    // I-11: a forecast row carries no actual — the line stops there (NaN), never drops to 0.
    const value = (r: Record<string, unknown> | undefined): number =>
        overlaid && r && r[mId] == null ? NaN : num(r?.[mId]);
    if (seriesField) {
        const seriesVals = distinct(rows, seriesField);
        const series: VizSeries[] = [];
        for (const sv of seriesVals) {
            const at = labels.map((l) => rows.find((row) => str(row[xField]) === l && str(row[seriesField]) === sv));
            series.push({ label: sv, data: at.map(value) });
            if (overlaid) series[series.length - 1]['overlay'] = at.map((r) => overlayCell(r, mId));
            if (compared) series.push(...compareSeries(series[series.length - 1], at, mId));
        }
        return { labels, series };
    }

    const at = labels.map((l) => rows.find((row) => str(row[xField]) === l));
    // The legend/tooltip text: a reader's wording ("First ings score (total)", "Count"), not the SQL-ish
    // `sum(FIRST_INGS_SCORE)` — the same default the table plugin gives the column's header.
    const main: VizSeries = { label: humanizeColumn(mId), data: at.map(value) };
    if (overlaid) main['overlay'] = at.map((r) => overlayCell(r, mId));
    return { labels, series: compared ? [main, ...compareSeries(main, at, mId)] : [main] };
}

/**
 * BI-INSIGHTS-1 I-1: the compared series drawn beside `main` (marked `compareOf`, the host names it by the kind) and
 * the server's change at each point on `main` itself (`compare`, read by the tooltip and the latest-change line). A
 * point with no compared value is `NaN` — a gap in the line, never a 0.
 */
function compareSeries(main: VizSeries, at: (Record<string, unknown> | undefined)[], mId: string): VizSeries[] {
    const cells = at.map((r) => compareCell(r, mId));
    main['compare'] = cells;
    return [{ label: main.label, compareOf: main.label, data: cells.map((c) => (c.cmp == null ? NaN : c.cmp)) }];
}

/** Single headline measure over the (single-row, ungrouped) result — the KPI value. */
export function buildValueQuery(values: ControlValues, ctx: QueryCtx): QuerySpec {
    const cv = values.value?.[0] ?? values.y?.[0];
    const measures = cv ? [channelMeasure(cv)] : [];
    // UIE-1: a KPI's optional prior-period measure rides the same one-row query.
    const cmp = values.compare?.[0];
    if (cmp && !measures.some((m) => m.id === channelMeasure(cmp).id)) measures.push(channelMeasure(cmp));
    // I-1: with no `compare` channel, a target comparison rides the same one-row query (a KPI has no time axis or
    // groups, so the previous period, last year and the average have nothing to look along).
    const server = !cmp && ctx.options?.compare?.kind === 'target' ? withCompare(ctx) : {};
    return {
        datasetId: ctx.datasetId,
        sourceName: ctx.sourceName,
        groupBy: [],
        measures,
        filters: ctx.filters ?? null,
        ...server,
    };
}

export function transformValue(rows: Record<string, unknown>[], values: ControlValues): VizProps {
    const cv = values.value?.[0] ?? values.y?.[0];
    if (!cv) return { labels: [], series: [], value: 0 };
    const mId = channelMeasureId(cv);
    const cmp = values.compare?.[0];
    const compare = cmp
        ? rows[0]?.[channelMeasureId(cmp)] != null
            ? num(rows[0][channelMeasureId(cmp)])
            : undefined
        : (compareCell(rows[0], mId).cmp ?? undefined); // I-1: the server's compared value, absent stays absent
    return { labels: [], series: [], value: num(rows[0]?.[mId]), ...(compare === undefined ? {} : { compare }) };
}

import { ConditionGroup } from 'app/inspecto/query';
import { channelMeasure, channelMeasureId } from '../query-spec';
import { ChannelValue, ControlValues, QuerySpec, TimeGrain, VizProps } from '../viz-types';

/**
 * Shared pure helpers for the standard chart plugins: turn the field mapping into a {@link QuerySpec}, and
 * pivot result rows into Chart.js-ready labels + series. No Angular, no chart.js — just data.
 */

export interface QueryCtx {
    datasetId: string;
    sourceName: string;
    filters?: ConditionGroup | null;
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
    };
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

    if (seriesField) {
        const seriesVals = distinct(rows, seriesField);
        const series = seriesVals.map((sv) => ({
            label: sv,
            data: labels.map((l) => {
                const r = rows.find((row) => str(row[xField]) === l && str(row[seriesField]) === sv);
                return num(r?.[mId]);
            }),
        }));
        return { labels, series };
    }

    const data = labels.map((l) => {
        const r = rows.find((row) => str(row[xField]) === l);
        return num(r?.[mId]);
    });
    return { labels, series: [{ label: ycv.agg ? `${ycv.agg}(${ycv.field})` : ycv.field, data }] };
}

/** Single headline measure over the (single-row, ungrouped) result — the KPI value. */
export function buildValueQuery(values: ControlValues, ctx: QueryCtx): QuerySpec {
    const cv = values.value?.[0] ?? values.y?.[0];
    const measures = cv ? [channelMeasure(cv)] : [];
    return {
        datasetId: ctx.datasetId,
        sourceName: ctx.sourceName,
        groupBy: [],
        measures,
        filters: ctx.filters ?? null,
    };
}

export function transformValue(rows: Record<string, unknown>[], values: ControlValues): VizProps {
    const cv = values.value?.[0] ?? values.y?.[0];
    if (!cv) return { labels: [], series: [], value: 0 };
    const mId = channelMeasureId(cv);
    return { labels: [], series: [], value: num(rows[0]?.[mId]) };
}

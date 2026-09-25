import { humanizeColumn } from '../column-label';
import { channelMeasure, channelMeasureId } from '../query-spec';
import { ChannelValue, ControlSpec, ControlValues, QuerySpec, VizPlugin, VizProps, VizSeries } from '../viz-types';
import { channelGrains, QueryCtx } from './plugin-helpers';

/**
 * Combo plugin — bars and a line on one category axis ("alerts per week (bars) + precision % (line)"). `y` holds the
 * bar measure(s), `y2` the line measure(s); the render host puts the lines on a secondary right axis unless
 * `options.combo.secondaryAxis` is `false`, and reads their numbers in `options.format2`. Each series carries
 * `kind: 'bar' | 'line'` so the host knows which is which after the generic sort/limit.
 */
const CONTROLS: ControlSpec[] = [
    { channel: 'x', label: 'X axis', acceptRoles: ['temporal', 'dimension'], required: true },
    { channel: 'y', label: 'Bars', acceptRoles: ['measure'], isMeasure: true, multiple: true, required: true },
    { channel: 'y2', label: 'Line', acceptRoles: ['measure'], isMeasure: true, multiple: true },
];

/** The bar measures then the line measures, each once (a measure picked on both channels queries once). */
function comboMeasures(values: ControlValues): ChannelValue[] {
    const seen = new Set<string>();
    return [...(values.y ?? []), ...(values.y2 ?? [])].filter((cv) => {
        if (!cv?.field) return false;
        const id = channelMeasureId(cv);
        if (seen.has(id)) return false;
        seen.add(id);
        return true;
    });
}

export function buildComboQuery(values: ControlValues, ctx: QueryCtx): QuerySpec {
    const x = values.x?.[0];
    return {
        datasetId: ctx.datasetId,
        sourceName: ctx.sourceName,
        groupBy: x?.field ? [x.field] : [],
        ...(channelGrains([x]) ?? {}),
        measures: comboMeasures(values).map(channelMeasure),
        filters: ctx.filters ?? null,
    };
}

/** One labelled series per bar measure (`kind: 'bar'`) then per line measure (`kind: 'line'`), over the x categories. */
export function transformCombo(rows: Record<string, unknown>[], values: ControlValues): VizProps {
    const xField = values.x?.[0]?.field;
    if (!xField || !values.y?.length) return { labels: [], series: [] };
    const str = (v: unknown): string => (v == null ? '' : String(v));
    const num = (v: unknown): number => {
        const n = typeof v === 'number' ? v : Number(v);
        return Number.isFinite(n) ? n : 0;
    };
    const labels: string[] = [];
    const byLabel = new Map<string, Record<string, unknown>>();
    for (const r of rows) {
        const l = str(r[xField]);
        if (!byLabel.has(l)) {
            byLabel.set(l, r);
            labels.push(l);
        }
    }
    const seriesFor = (cvs: ChannelValue[] | undefined, kind: 'bar' | 'line'): VizSeries[] =>
        (cvs ?? [])
            .filter((cv) => !!cv?.field)
            .map((cv) => {
                const id = channelMeasureId(cv);
                return { label: humanizeColumn(id), data: labels.map((l) => num(byLabel.get(l)?.[id])), kind };
            });
    return { labels, series: [...seriesFor(values.y, 'bar'), ...seriesFor(values.y2, 'line')] };
}

export const COMBO_PLUGIN: VizPlugin = {
    meta: {
        type: 'combo',
        label: 'Combo (bars + line)',
        icon: 'heroicons_outline:presentation-chart-bar',
        fit: { minMeasure: 2, minDim: 1, maxCardinality: 30 },
    },
    controls: CONTROLS,
    buildQuery: buildComboQuery,
    transformProps: transformCombo,
    render: { kind: 'chartjs', chartType: 'bar' },
};

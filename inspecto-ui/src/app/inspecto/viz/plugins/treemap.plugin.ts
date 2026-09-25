import { channelMeasure, channelMeasureId } from '../query-spec';
import { TreemapRow } from '../treemap-layout';
import { ChannelValue, ControlSpec, VizPlugin } from '../viz-types';
import { channelGrains } from './plugin-helpers';

/**
 * Treemap plugin — part-to-whole across many categories, optionally two levels ("Fraud loss by typology and
 * channel"). Groups by `group` (and `subgroup`), sums one measure, and hands the render host one normalised row per
 * cell; `treemap-layout.ts` folds, lays out and colours them at render time, where `options.treemap.limit` and the
 * box size are known. Renders through the component escape hatch (`TreemapComponent`): positioned DOM rectangles,
 * so every cell is a focusable, labelled button — no canvas, no new dependency.
 */
const CONTROLS: ControlSpec[] = [
    { channel: 'group', label: 'Group by', acceptRoles: ['dimension'], required: true },
    { channel: 'subgroup', label: 'Then by (optional)', acceptRoles: ['dimension'] },
    { channel: 'value', label: 'Size', acceptRoles: ['measure'], isMeasure: true, required: true },
];

export const TREEMAP_PLUGIN: VizPlugin = {
    meta: {
        type: 'treemap',
        label: 'Treemap',
        icon: 'heroicons_outline:squares-2x2',
        // Many categories is the point: no cardinality ceiling (the tail folds into "Other"). No `temporal` bonus
        // either, so Show-Me offers it without ever making it the default pick over bar / pie / line.
        fit: { minMeasure: 1, maxMeasure: 1, minDim: 1, maxDim: 2 },
    },
    controls: CONTROLS,
    buildQuery: (values, ctx) => {
        const dims = [values.group?.[0], values.subgroup?.[0]].filter((cv): cv is ChannelValue => !!cv?.field);
        const value = values.value?.[0];
        return {
            datasetId: ctx.datasetId,
            sourceName: ctx.sourceName,
            groupBy: dims.map((cv) => cv.field),
            ...(channelGrains(dims) ?? {}),
            measures: value ? [channelMeasure(value)] : [],
            filters: ctx.filters ?? null,
        };
    },
    transformProps: (rows, values) => {
        const group = values.group?.[0]?.field;
        const sub = values.subgroup?.[0]?.field;
        const value = values.value?.[0];
        if (!group || !value) return { labels: [], series: [], treemap: [] };
        const id = channelMeasureId(value);
        const treemap = rows.map(
            (r): TreemapRow => ({
                group: text(r[group]),
                ...(sub ? { subgroup: text(r[sub]) } : {}),
                // A value that is not a number stays NaN, so the layout counts it as excluded rather than as a zero.
                value: r[id] == null || r[id] === '' ? NaN : Number(r[id]),
            }),
        );
        return { labels: [], series: [], treemap };
    },
    render: { kind: 'component', componentKey: 'treemap' },
};

function text(v: unknown): string {
    return v == null ? '' : String(v);
}

import { humanizeColumn } from '../column-label';
import { pivotHeatmap } from '../heatmap';
import { channelMeasure, channelMeasureId } from '../query-spec';
import { ChannelValue, VizPlugin } from '../viz-types';
import { channelGrains } from './plugin-helpers';

/**
 * Heatmap — a matrix of two dimensions (`rows` × `columns`) with one aggregate per cell: control × day pass-rate,
 * hour × weekday alert volume, month × KPI. The query groups by both dimensions; the transform pivots the rows into a
 * {@link HeatmapMatrix} (sorted by label, a missing pair left empty). Renders through `HeatmapComponent`, a real
 * `<table>`, coloured on the Widget's `options.heatmap.scale` (sequential / diverging / status).
 *
 * The `value` channel also accepts a dimension so a STATUS column can fill the cells (`max(status)` — pick max or min;
 * the server refuses sum/avg on text), which the `status` scale colours by tone: the RAG matrix.
 */
/**
 * A heatmap needs EVERY cell, so it asks for the server's ceiling (`BiRoutes.MAX_LIMIT`) instead of taking the
 * 500-row default — 24 controls × 30 days is 720 cells. Without it the server returned an arbitrary 500 of an
 * unordered GROUP BY and a different 500 on each reload: scattered holes that read as missed runs (R2-01).
 */
export const HEATMAP_MAX_CELLS = 10_000;

export const HEATMAP_PLUGIN: VizPlugin = {
    meta: {
        type: 'heatmap',
        label: 'Heatmap',
        icon: 'heroicons_outline:table-cells',
        fit: { minDim: 2, maxDim: 2, minMeasure: 1, maxMeasure: 1 },
    },
    controls: [
        { channel: 'rows', label: 'Rows', acceptRoles: ['dimension', 'temporal'], required: true },
        { channel: 'columns', label: 'Columns', acceptRoles: ['temporal', 'dimension'], required: true },
        { channel: 'value', label: 'Value', acceptRoles: ['measure', 'dimension'], isMeasure: true, required: true },
    ],
    buildQuery: (values, ctx) => {
        const dims = [values.rows?.[0], values.columns?.[0]].filter((cv): cv is ChannelValue => !!cv?.field);
        const value = values.value?.[0];
        const groupBy = dims.map((cv) => cv.field);
        return {
            datasetId: ctx.datasetId,
            sourceName: ctx.sourceName,
            groupBy,
            ...(channelGrains(dims) ?? {}),
            measures: value ? [channelMeasure(value)] : [],
            filters: ctx.filters ?? null,
            // Ordered, so a matrix past the ceiling loses its LAST rows rather than random cells.
            ...(groupBy.length ? { orderBy: groupBy.map((f) => ({ field: f, dir: 'asc' as const })) } : {}),
            limit: HEATMAP_MAX_CELLS,
        };
    },
    transformProps: (rows, values) => {
        const rowField = values.rows?.[0]?.field;
        const columnField = values.columns?.[0]?.field;
        const value = values.value?.[0];
        if (!rowField || !columnField || !value) return { labels: [], series: [] };
        const valueId = channelMeasureId(value);
        const matrix = pivotHeatmap(rows, rowField, columnField, valueId);
        return {
            labels: matrix.rows,
            series: [],
            heatmap: {
                ...matrix,
                rowLabel: humanizeColumn(rowField),
                columnLabel: humanizeColumn(columnField),
                valueLabel: humanizeColumn(valueId),
            },
        };
    },
    render: { kind: 'component', componentKey: 'heatmap' },
};

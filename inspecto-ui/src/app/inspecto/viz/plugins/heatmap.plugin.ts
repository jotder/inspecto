import { humanizeColumn } from '../column-label';
import { pivotHeatmap, worstCell } from '../heatmap';
import { buildMeasure, channelMeasure, channelMeasureId } from '../query-spec';
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
 *
 * ⚠ `max`/`min` of TEXT is alphabetical — Fail < Pass < Warning, Amber < Green < Red — so a cell holding a Fail and a
 * Warning would show Warning. So on the `status` scale a plain `max`/`min` value is NOT aggregated by the server: the
 * query also groups by the value field (`count` as the measure) and `transformProps` folds each cell's distinct values
 * with {@link worstCell} — the WORST status by the shared tone severity (both `max` and `min` mean worst there; numbers
 * keep their `max`/`min`). The rows grow to Σ cells × distinct values, still under {@link HEATMAP_MAX_CELLS} (a status
 * column has a handful of values: ~2 000 cells at 5); a NUMERIC `max`/`min` on the status scale grows by its distinct
 * values, so a matrix past the ceiling loses its tail sooner. Every other scale / aggregate is queried as before.
 */
/**
 * A heatmap needs EVERY cell, so it asks for the server's ceiling (`BiRoutes.MAX_LIMIT`) instead of taking the
 * 500-row default — 24 controls × 30 days is 720 cells. Without it the server returned an arbitrary 500 of an
 * unordered GROUP BY and a different 500 on each reload: scattered holes that read as missed runs (R2-01).
 */
export const HEATMAP_MAX_CELLS = 10_000;

/** A plain `max`/`min` value — on the `status` scale it is read per distinct value and reduced to the worst client-side. */
function maxMinAgg(value: ChannelValue | undefined): 'max' | 'min' | null {
    return value && !value.expression && (value.agg === 'max' || value.agg === 'min') ? value.agg : null;
}

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
        const worst = ctx.options?.heatmap?.scale === 'status' ? maxMinAgg(value) : null;
        const dimFields = dims.map((cv) => cv.field);
        const groupBy = worst && value ? [...new Set([...dimFields, value.field])] : dimFields;
        return {
            datasetId: ctx.datasetId,
            sourceName: ctx.sourceName,
            groupBy,
            ...(channelGrains(dims) ?? {}),
            measures: !value ? [] : worst ? [buildMeasure('count', value.field)] : [channelMeasure(value)],
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
        // A worst-of query (see the plugin comment) returns the raw value column instead of the aggregate. When it hit
        // the ceiling, the last cell may be cut mid-way: blank it rather than show a status that is not its worst.
        const worst = maxMinAgg(value);
        const perValue = worst !== null && rows.length > 0 && !(valueId in rows[0]) && value.field in rows[0];
        let matrix;
        if (perValue) {
            const last = rows[rows.length - 1];
            const whole =
                rows.length < HEATMAP_MAX_CELLS
                    ? rows
                    : rows.filter((r) => r[rowField] !== last[rowField] || r[columnField] !== last[columnField]);
            matrix = pivotHeatmap(whole, rowField, columnField, value.field, (a, b) => worstCell(a, b, worst));
        } else {
            matrix = pivotHeatmap(rows, rowField, columnField, valueId);
        }
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

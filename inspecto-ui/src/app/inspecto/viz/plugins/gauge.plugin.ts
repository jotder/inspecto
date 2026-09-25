import { VizPlugin } from '../viz-types';
import { buildValueQuery, transformValue } from './plugin-helpers';

/**
 * Gauge plugin — a single headline measure rendered as a half-circle doughnut (`VizRenderComponent` styles
 * the `gauge`-typed plugin's Chart.js config: 180° circumference, a wide cutout, value-vs-remainder slices).
 * Shares its query/transform with {@link KPI_PLUGIN} — same shape (one ungrouped measure), different render.
 * The arc spans `options.gauge.min`–`max` (default 0–100, see `viz/gauge-scale.ts`), printed as end labels under it.
 * UIE-8: the host also prints the value under the arc in the widget's `options.format`, and with `options.kpi.target`
 * draws a thin inner ring split into bad / good zones at the target plus an on / off target line in words.
 */
export const GAUGE_PLUGIN: VizPlugin = {
    meta: { type: 'gauge', label: 'Gauge', icon: 'heroicons_outline:chart-pie', fit: { minMeasure: 1, maxDim: 0 } },
    controls: [{ channel: 'value', label: 'Value', acceptRoles: ['measure'], isMeasure: true, required: true }],
    buildQuery: buildValueQuery,
    transformProps: transformValue,
    render: { kind: 'chartjs', chartType: 'doughnut' },
};

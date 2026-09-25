import { ControlSpec, ControlValues, VizPlugin } from '../viz-types';
import { buildXyQuery, QueryCtx, transformXy } from './plugin-helpers';

/**
 * KPI trend — a KPI tile with a sparkline: the LAST point of a measure over a time (or otherwise ordered)
 * dimension as the headline, its change against an earlier point, and the whole series as a small line. Renders
 * via the component escape hatch (`kpi-trend`, {@link KpiTrendComponent}); the query is the line chart's, ordered
 * by `x` so "last" means latest.
 */
const CONTROLS: ControlSpec[] = [
    { channel: 'x', label: 'Over (time)', acceptRoles: ['temporal', 'dimension'], required: true },
    { channel: 'value', label: 'Value', acceptRoles: ['measure'], isMeasure: true, required: true },
];

/** The line chart's mapping: `value` plays `y`. */
function asXy(values: ControlValues): ControlValues {
    return { x: values.x, y: values.value };
}

export const KPI_TREND_PLUGIN: VizPlugin = {
    meta: {
        type: 'kpi-trend',
        label: 'KPI trend',
        icon: 'heroicons_outline:arrow-trending-up',
        fit: { minMeasure: 1, maxMeasure: 1, maxDim: 0, temporal: true },
    },
    controls: CONTROLS,
    buildQuery: (values: ControlValues, ctx: QueryCtx) => {
        const spec = buildXyQuery(asXy(values), ctx);
        const x = values.x?.[0]?.field;
        return x ? { ...spec, orderBy: [{ field: x, dir: 'asc' }] } : spec;
    },
    transformProps: (rows, values) => transformXy(rows, asXy(values)),
    render: { kind: 'component', componentKey: 'kpi-trend' },
};

/** The headline point, the point it is compared with, and the series range. `null` for an empty series. */
export interface TrendSummary {
    last: number;
    lastIndex: number;
    /** The baseline `compareBack` points before the last; absent when the series is too short. */
    prev?: number;
    prevIndex?: number;
    min: number;
    max: number;
}

/** Summarise a series for the tile: last point, the point `compareBack` (≥1, default 1) steps earlier, min/max. */
export function trendSummary(values: readonly number[], compareBack = 1): TrendSummary | null {
    if (!values.length) return null;
    const back = Number.isFinite(compareBack) && compareBack >= 1 ? Math.floor(compareBack) : 1;
    const lastIndex = values.length - 1;
    const prevIndex = lastIndex - back;
    return {
        last: values[lastIndex],
        lastIndex,
        ...(prevIndex >= 0 ? { prev: values[prevIndex], prevIndex } : {}),
        min: Math.min(...values),
        max: Math.max(...values),
    };
}

/** SVG geometry for a sparkline in a `width`×`height` viewBox: the line, its closed area, the last point, and the
 *  target's y when one is given (the target widens the value range, so its line is always inside the box). */
export interface Sparkline {
    line: string;
    area: string;
    last: { x: number; y: number };
    targetY?: number;
}

export function sparkline(values: readonly number[], width: number, height: number, target?: number): Sparkline | null {
    if (!values.length) return null;
    const hasTarget = target != null && Number.isFinite(target);
    const range = hasTarget ? [...values, target] : values;
    const lo = Math.min(...range);
    const hi = Math.max(...range);
    const pad = 2; // keep the stroke and the last-point dot inside the box
    const y = (v: number): number => (hi === lo ? height / 2 : pad + (1 - (v - lo) / (hi - lo)) * (height - 2 * pad));
    const x = (i: number): number => (values.length === 1 ? width : (i / (values.length - 1)) * width);
    const pts = values.map((v, i) => `${round(x(i))},${round(y(v))}`);
    const line = `M${pts.join(' L')}`;
    const area = `${line} L${round(x(values.length - 1))},${height} L${round(x(0))},${height} Z`;
    const lastI = values.length - 1;
    return {
        line,
        area,
        last: { x: round(x(lastI)), y: round(y(values[lastI])) },
        ...(hasTarget ? { targetY: round(y(target)) } : {}),
    };
}

function round(n: number): number {
    return Math.round(n * 100) / 100;
}

import { ControlSpec, VizPlugin } from '../viz-types';
import { buildXyQuery, transformXy } from './plugin-helpers';

/**
 * Waterfall (bridge) plugin — how an opening value moves to a closing one through signed steps ("Billed revenue →
 * leakage by root cause → recovered → net"). One step dimension, one measure holding each step's SIGNED change. The
 * query orders by the step dimension so the steps arrive in a stable order; the render host (`waterfall-chart.ts`)
 * turns the deltas into floating bars with running totals, an optional opening step (`options.waterfall.start`) and a
 * trailing computed total. Chart.js-native: a bar chart of `[low, high]` pairs.
 */
const CONTROLS: ControlSpec[] = [
    { channel: 'x', label: 'Step', acceptRoles: ['dimension', 'temporal'], required: true },
    { channel: 'y', label: 'Change (signed)', acceptRoles: ['measure'], isMeasure: true, required: true },
];

export const WATERFALL_PLUGIN: VizPlugin = {
    meta: {
        type: 'waterfall',
        label: 'Waterfall',
        icon: 'heroicons_outline:chart-bar-square',
        fit: { minMeasure: 1, maxMeasure: 1, minDim: 1, maxDim: 1, maxCardinality: 15 },
    },
    controls: CONTROLS,
    buildQuery: (values, ctx) => {
        // One step dimension, one measure: a stray break-down or second measure would split or double the steps.
        const query = buildXyQuery({ x: values.x, y: values.y?.slice(0, 1) }, ctx);
        const step = values.x?.[0]?.field;
        return step ? { ...query, orderBy: [{ field: step, dir: 'asc' }] } : query;
    },
    transformProps: (rows, values) => transformXy(rows, { x: values.x, y: values.y }),
    render: { kind: 'chartjs', chartType: 'bar' },
};

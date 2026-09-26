import { ControlSpec, SortDir, VizPlugin } from '../viz-types';
import { Better, targetStatus } from '../target-status';
import { buildXyQuery, transformXy } from './plugin-helpers';

/**
 * Progress list — a ranked list of labelled horizontal bars with their values, optionally against a target ("Top
 * detectors by Alerts", "Controls by pass rate vs 95 %"). Renders via the component escape hatch (`progress-list`,
 * {@link ProgressListComponent}) as an accessible `<ol>`, not a canvas. The query is the bar chart's; ranking and the
 * top-N trim happen client-side so the list can say how many rows it left out.
 */
const CONTROLS: ControlSpec[] = [
    { channel: 'x', label: 'Label', acceptRoles: ['dimension'], required: true },
    { channel: 'y', label: 'Measure', acceptRoles: ['measure'], isMeasure: true, required: true },
];

export const PROGRESS_LIST_PLUGIN: VizPlugin = {
    meta: {
        type: 'progress-list',
        label: 'Progress list',
        icon: 'heroicons_outline:bars-3-bottom-left',
        fit: { minMeasure: 1, maxMeasure: 1, minDim: 1, maxDim: 1, temporal: false },
    },
    controls: CONTROLS,
    buildQuery: buildXyQuery,
    transformProps: transformXy,
    render: { kind: 'component', componentKey: 'progress-list' },
};

export const PROGRESS_DEFAULT_LIMIT = 10;

export interface ProgressOptions {
    /** Rows shown before "+N more" (default {@link PROGRESS_DEFAULT_LIMIT}). */
    limit?: number;
    /** `desc` (default) ranks the largest first. */
    sort?: SortDir;
    /** The value a full bar stands for; default the largest value shown. */
    max?: number;
    target?: number;
    better?: Better;
}

export interface ProgressRow {
    label: string;
    value: number;
    /** Bar length, 0–100 % of `max`. */
    pct: number;
    /** Where the target tick sits, 0–100 % of `max` — only with a target. */
    targetPct?: number;
    /** Whether the value meets the target — only with a target. */
    met?: boolean;
}

export interface ProgressModel {
    rows: ProgressRow[];
    /** Rows left out by the limit. */
    more: number;
    max: number;
    /** True when `max` is the `options.progress.max` given; false when it defaulted to the largest value shown. */
    maxSet: boolean;
}

/** Rank `labels`/`values`, keep the top `limit`, and size each bar as a percentage of `max`. Pure. */
export function rankProgress(
    labels: readonly string[],
    values: readonly number[],
    opts: ProgressOptions = {},
): ProgressModel {
    const dir = opts.sort === 'asc' ? 1 : -1;
    const all = labels
        .map((label, i) => ({ label, value: Number.isFinite(values[i]) ? values[i] : 0 }))
        .sort((a, b) => dir * (a.value - b.value));
    const limit = opts.limit != null && opts.limit >= 1 ? Math.floor(opts.limit) : PROGRESS_DEFAULT_LIMIT;
    const shown = all.slice(0, limit);
    const maxSet = opts.max != null && Number.isFinite(opts.max) && opts.max > 0;
    const max = maxSet ? (opts.max as number) : Math.max(0, ...shown.map((r) => r.value));
    const pctOf = (v: number): number => (max > 0 ? clamp((v / max) * 100) : 0);
    const rows = shown.map(({ label, value }): ProgressRow => {
        const status = targetStatus(value, opts.target, opts.better ?? 'higher');
        return {
            label,
            value,
            pct: pctOf(value),
            ...(status ? { targetPct: pctOf(opts.target as number), met: status.met } : {}),
        };
    });
    return { rows, more: all.length - shown.length, max, maxSet };
}

function clamp(n: number): number {
    return Math.max(0, Math.min(100, n));
}

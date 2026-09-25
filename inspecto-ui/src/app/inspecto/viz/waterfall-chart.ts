import { ChartData } from 'chart.js';
import { CHART_CONNECTOR, CHART_SERIES, CHART_TONE } from 'app/inspecto/theme/chart-tokens';
import { formatNumber, NumberFormat } from './number-format';
import { Better } from './target-status';
import { VizRenderOptions } from './viz-types';

/**
 * The Waterfall's pure core: signed step deltas → floating bars with running totals. Framework-free apart from the
 * Chart.js data TYPE; `VizRenderComponent` draws the result.
 *
 * - `options.waterfall.start` names the step (an `x` value) whose measure is the OPENING total: it is drawn from zero
 *   and placed first. A name that matches no step is ignored.
 * - The remaining steps move the running total, in query order (`order: 'data'`) or sorted by signed change.
 * - `options.waterfall.totalLabel` (default `Total`) appends a computed closing bar from zero; `''` omits it.
 */
export type WaterfallStepKind = 'start' | 'increase' | 'decrease' | 'total';

export interface WaterfallStep {
    /** The label drawn on the axis. */
    label: string;
    kind: WaterfallStepKind;
    /** The step's signed change (for `start`/`total`, the value itself). */
    delta: number;
    /** Running total before and after the step (`start`/`total` run from 0). */
    from: number;
    to: number;
    /** The raw category the step came from — what a drill click filters on. `null` for the computed total. */
    source: string | null;
}

export type WaterfallOptions = NonNullable<VizRenderOptions['waterfall']>;

export function waterfallSteps(
    labels: readonly string[],
    deltas: readonly number[],
    o: WaterfallOptions = {},
): WaterfallStep[] {
    const rows = labels.map((label, i) => ({ label, delta: Number.isFinite(deltas[i]) ? deltas[i] : 0 }));
    const startIndex = o.start ? rows.findIndex((r) => r.label === o.start) : -1;
    let moves = rows.filter((_, i) => i !== startIndex);
    if (o.order === 'asc' || o.order === 'desc') {
        const dir = o.order === 'asc' ? 1 : -1;
        moves = [...moves].sort((a, b) => dir * (a.delta - b.delta));
    }
    const steps: WaterfallStep[] = [];
    let running = 0;
    if (startIndex >= 0) {
        const s = rows[startIndex];
        steps.push({ label: s.label, kind: 'start', delta: s.delta, from: 0, to: s.delta, source: s.label });
        running = s.delta;
    }
    for (const m of moves) {
        const from = running;
        running += m.delta;
        steps.push({
            label: m.label,
            kind: m.delta < 0 ? 'decrease' : 'increase',
            delta: m.delta,
            from,
            to: running,
            source: m.label,
        });
    }
    const totalLabel = o.totalLabel ?? 'Total';
    if (totalLabel !== '' && steps.length)
        steps.push({ label: totalLabel, kind: 'total', delta: running, from: 0, to: running, source: null });
    return steps;
}

/** The colour of each step kind: a rise is good (success) unless lower is better, then a fall is; totals are primary. */
export function waterfallTones(better: Better = 'higher'): Record<WaterfallStepKind, string> {
    const [up, down] =
        better === 'lower' ? [CHART_TONE.error, CHART_TONE.success] : [CHART_TONE.success, CHART_TONE.error];
    return { start: CHART_SERIES.primary, increase: up, decrease: down, total: CHART_SERIES.primary };
}

/** What a step kind is called in words — the legend, tooltip and text alternative (colour is never the only signal). */
export const WATERFALL_KIND_LABEL: Record<WaterfallStepKind, string> = {
    start: 'Opening',
    increase: 'Increase',
    decrease: 'Decrease',
    total: 'Total',
};

/**
 * Chart.js data: dataset 0 is the floating bars (`[low, high]` each); dataset 1 is a dashed stepped line at the running
 * total, drawn BEHIND the bars so only its horizontal runs — the connectors between neighbouring bars — show.
 */
export function waterfallChartData(
    steps: readonly WaterfallStep[],
    labels: readonly string[],
    better?: Better,
): ChartData {
    const tones = waterfallTones(better);
    return {
        labels: [...labels],
        datasets: [
            {
                type: 'bar',
                label: 'Change',
                data: steps.map((s) => [Math.min(s.from, s.to), Math.max(s.from, s.to)] as [number, number]),
                backgroundColor: steps.map((s) => tones[s.kind]),
                borderColor: steps.map((s) => tones[s.kind]),
                borderSkipped: false,
                order: 0,
            },
            {
                type: 'line',
                label: 'Running total',
                data: steps.map((s) => s.to),
                stepped: 'after',
                borderColor: CHART_CONNECTOR,
                borderWidth: 1,
                borderDash: [3, 3],
                pointRadius: 0,
                pointHoverRadius: 0,
                pointHitRadius: 0,
                fill: false,
                order: 1,
            },
        ],
    } as ChartData;
}

/** One step as a reader hears it: "Leakage: decrease −50, running total 950". */
export function waterfallStepText(s: WaterfallStep, format?: NumberFormat): string {
    const label = s.label.trim() === '' ? '(blank)' : s.label;
    if (s.kind === 'start' || s.kind === 'total') return `${label}: ${formatNumber(s.to, format)}`;
    return `${label}: ${WATERFALL_KIND_LABEL[s.kind].toLowerCase()} ${signed(s.delta, format)}, running total ${formatNumber(s.to, format)}`;
}

/** The canvas's text alternative — every step (capped at 12) with its change and running total. */
export function waterfallAltText(steps: readonly WaterfallStep[], format?: NumberFormat): string {
    if (!steps.length) return 'Waterfall chart with no steps.';
    const shown = steps.slice(0, 12).map((s) => waterfallStepText(s, format));
    const more = steps.length > 12 ? `; and ${steps.length - 12} more steps` : '';
    return `Waterfall chart. ${shown.join('; ')}${more}.`;
}

/** A change with its sign: `+50`, `−50` (a real minus sign), `0`. */
export function signed(n: number, format?: NumberFormat): string {
    const text = formatNumber(Math.abs(n), format);
    return n > 0 ? `+${text}` : n < 0 ? `−${text}` : text;
}

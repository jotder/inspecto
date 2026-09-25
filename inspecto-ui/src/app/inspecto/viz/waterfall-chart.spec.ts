import { describe, expect, it } from 'vitest';
import { CHART_CONNECTOR, CHART_SERIES, CHART_TONE } from 'app/inspecto/theme/chart-tokens';
import { WATERFALL_PLUGIN } from './plugins';
import { signed, waterfallAltText, waterfallChartData, waterfallSteps, waterfallTones } from './waterfall-chart';

// "Billed revenue → leakage by root cause → recovered → net": the opening total is itself a row of the Dataset.
const LABELS = ['Billed revenue', 'Rating errors', 'Unbilled usage', 'Recovered'];
const DELTAS = [1000, -120, -80, 150];

describe('waterfallSteps', () => {
    it('runs the total through every step and appends a computed Total bar from zero', () => {
        const steps = waterfallSteps(['A', 'B', 'C'], [100, -30, 20]);
        expect(steps.map((s) => [s.label, s.kind, s.from, s.to])).toEqual([
            ['A', 'increase', 0, 100],
            ['B', 'decrease', 100, 70],
            ['C', 'increase', 70, 90],
            ['Total', 'total', 0, 90],
        ]);
        expect(steps[3].source).toBeNull();
    });

    it('draws the `start` step from zero as the opening total, placed first whatever its row position', () => {
        const steps = waterfallSteps(['Rating errors', 'Billed revenue', 'Recovered'], [-120, 1000, 150], {
            start: 'Billed revenue',
            totalLabel: 'Net billed',
        });
        expect(steps.map((s) => [s.label, s.kind, s.from, s.to])).toEqual([
            ['Billed revenue', 'start', 0, 1000],
            ['Rating errors', 'decrease', 1000, 880],
            ['Recovered', 'increase', 880, 1030],
            ['Net billed', 'total', 0, 1030],
        ]);
    });

    it('ignores a `start` that names no step, and an empty totalLabel draws no total bar', () => {
        const steps = waterfallSteps(['A', 'B'], [5, -2], { start: 'Nope', totalLabel: '' });
        expect(steps.map((s) => s.kind)).toEqual(['increase', 'decrease']);
    });

    it('orders the change steps by signed change on asc/desc, keeping the opening first and the total last', () => {
        const asc = waterfallSteps(LABELS, DELTAS, { start: 'Billed revenue', order: 'asc' });
        expect(asc.map((s) => s.label)).toEqual([
            'Billed revenue',
            'Rating errors',
            'Unbilled usage',
            'Recovered',
            'Total',
        ]);
        const desc = waterfallSteps(LABELS, DELTAS, { start: 'Billed revenue', order: 'desc' });
        expect(desc.map((s) => s.label)).toEqual([
            'Billed revenue',
            'Recovered',
            'Unbilled usage',
            'Rating errors',
            'Total',
        ]);
        expect(desc.at(-1)?.to).toBe(950);
    });

    it('draws nothing (not even a Total) for no rows', () => {
        expect(waterfallSteps([], [])).toEqual([]);
    });
});

describe('waterfallTones', () => {
    it('tones a rise success and a fall error when higher is better, totals primary', () => {
        expect(waterfallTones('higher')).toEqual({
            start: CHART_SERIES.primary,
            increase: CHART_TONE.success,
            decrease: CHART_TONE.error,
            total: CHART_SERIES.primary,
        });
    });

    it('flips the tones when lower is better (a fall in exposure is good news)', () => {
        const t = waterfallTones('lower');
        expect(t.increase).toBe(CHART_TONE.error);
        expect(t.decrease).toBe(CHART_TONE.success);
    });
});

describe('waterfallChartData', () => {
    it('floats each bar over [low, high] and draws a stepped connector at the running total, behind the bars', () => {
        const steps = waterfallSteps(LABELS, DELTAS, { start: 'Billed revenue' });
        const data = waterfallChartData(
            steps,
            steps.map((s) => s.label),
            'higher',
        );
        const [bars, connector] = data.datasets as unknown as Record<string, unknown>[];
        expect(bars['data']).toEqual([
            [0, 1000],
            [880, 1000],
            [800, 880],
            [800, 950],
            [0, 950],
        ]);
        expect(bars['backgroundColor']).toEqual([
            CHART_SERIES.primary,
            CHART_TONE.error,
            CHART_TONE.error,
            CHART_TONE.success,
            CHART_SERIES.primary,
        ]);
        expect(connector['data']).toEqual([1000, 880, 800, 950, 950]);
        expect(connector['stepped']).toBe('after');
        expect(connector['borderColor']).toBe(CHART_CONNECTOR);
        expect(connector['order'] as number).toBeGreaterThan(bars['order'] as number);
    });
});

describe('waterfall words', () => {
    it('states every step with its sign and running total — colour is not the only signal', () => {
        const steps = waterfallSteps(LABELS, DELTAS, { start: 'Billed revenue' });
        expect(waterfallAltText(steps)).toBe(
            'Waterfall chart. Billed revenue: 1,000; Rating errors: decrease −120, running total 880; ' +
                'Unbilled usage: decrease −80, running total 800; Recovered: increase +150, running total 950; Total: 950.',
        );
    });

    it('signs a change with a plus or a real minus, in the widget format', () => {
        expect(signed(1500, { compact: true })).toBe('+1.5K');
        expect(signed(-3)).toBe('−3');
        expect(signed(0)).toBe('0');
    });
});

describe('WATERFALL_PLUGIN', () => {
    it('queries one step dimension and one measure, ordered by the step so the steps arrive stably', () => {
        const q = WATERFALL_PLUGIN.buildQuery(
            {
                x: [{ field: 'root_cause' }],
                y: [
                    { field: 'delta_sar', agg: 'sum' },
                    { field: 'other', agg: 'sum' },
                ],
                series: [{ field: 'region' }],
            },
            { datasetId: 'd', sourceName: 's' },
        );
        expect(q.groupBy).toEqual(['root_cause']);
        expect(q.measures.map((m) => m.id)).toEqual(['sum_delta_sar']);
        expect(q.orderBy).toEqual([{ field: 'root_cause', dir: 'asc' }]);
    });

    it('turns rows into step labels and one series of signed changes', () => {
        const props = WATERFALL_PLUGIN.transformProps(
            [
                { root_cause: 'Billed', sum_delta_sar: 1000 },
                { root_cause: 'Leak', sum_delta_sar: -50 },
            ],
            { x: [{ field: 'root_cause' }], y: [{ field: 'delta_sar', agg: 'sum' }] },
        );
        expect(props.labels).toEqual(['Billed', 'Leak']);
        expect(props.series[0].data).toEqual([1000, -50]);
    });
});

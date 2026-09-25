import { describe, expect, it } from 'vitest';
import { COMBO_PLUGIN } from './plugins';
import { comboAltText, comboChartData, comboSeriesFormat, comboUsesSecondaryAxis } from './combo-chart';
import { VizProps } from './viz-types';

// "Alerts per week (bars) + precision % (line, right axis)".
const PROPS: VizProps = {
    labels: ['W1', 'W2'],
    series: [
        { label: 'Alerts', data: [12, 30], kind: 'bar' },
        { label: 'Precision (avg)', data: [81.5, 64], kind: 'line' },
    ],
};
const same = (l: string): string => l;

describe('COMBO_PLUGIN query and transform', () => {
    it('groups by x and queries the bar measures then the line measures, each once', () => {
        const q = COMBO_PLUGIN.buildQuery(
            {
                x: [{ field: 'week', grain: 'week' }],
                y: [{ field: 'alert_id', agg: 'count' }],
                y2: [
                    { field: 'precision_pct', agg: 'avg' },
                    { field: 'alert_id', agg: 'count' },
                ],
            },
            { datasetId: 'd', sourceName: 's' },
        );
        expect(q.groupBy).toEqual(['week']);
        expect(q.grains).toEqual({ week: 'week' });
        expect(q.measures.map((m) => m.id)).toEqual(['count', 'avg_precision_pct']);
    });

    it('builds one bar series per y measure and one line series per y2 measure, over the x categories', () => {
        const props = COMBO_PLUGIN.transformProps(
            [
                { week: 'W1', sum_exposure_sar: 100, avg_recovery_rate: 40 },
                { week: 'W2', sum_exposure_sar: 250, avg_recovery_rate: 55 },
            ],
            {
                x: [{ field: 'week' }],
                y: [{ field: 'exposure_sar', agg: 'sum' }],
                y2: [{ field: 'recovery_rate', agg: 'avg' }],
            },
        );
        expect(props.labels).toEqual(['W1', 'W2']);
        expect(props.series.map((s) => [s.label, s['kind'], s.data])).toEqual([
            ['Exposure (SAR)', 'bar', [100, 250]],
            ['Recovery rate (average)', 'line', [40, 55]],
        ]);
    });

    it('draws nothing without an x or a bar measure', () => {
        expect(COMBO_PLUGIN.transformProps([{ a: 1 }], { y2: [{ field: 'a', agg: 'sum' }] })).toEqual({
            labels: [],
            series: [],
        });
    });
});

describe('comboChartData', () => {
    it('puts bars on y and lines on the secondary y2, lines drawn on top and shaped differently in the legend', () => {
        const data = comboChartData(PROPS, PROPS.labels, ['c0', 'c1'], undefined, same);
        const [bar, line] = data.datasets as unknown as Record<string, unknown>[];
        expect(bar).toMatchObject({ type: 'bar', yAxisID: 'y', backgroundColor: 'c0', pointStyle: 'rect', order: 1 });
        expect(line).toMatchObject({ type: 'line', yAxisID: 'y2', borderColor: 'c1', pointStyle: 'circle', order: 0 });
    });

    it('keeps the line on the shared y axis when secondaryAxis is false', () => {
        const o = { combo: { secondaryAxis: false } };
        expect(comboUsesSecondaryAxis(PROPS, o)).toBe(false);
        const line = comboChartData(PROPS, PROPS.labels, ['c0', 'c1'], o, same).datasets[1] as unknown as Record<
            string,
            unknown
        >;
        expect(line['yAxisID']).toBe('y');
    });

    it('needs no secondary axis without a line measure', () => {
        expect(comboUsesSecondaryAxis({ labels: ['a'], series: [{ label: 'm', data: [1], kind: 'bar' }] })).toBe(false);
    });
});

describe('combo formats and words', () => {
    it('reads a line measure in format2 (falling back to format) and a bar measure in format', () => {
        const o = { format: { compact: true }, format2: { style: 'percent' as const } };
        expect(comboSeriesFormat(true, o)).toEqual({ style: 'percent' });
        expect(comboSeriesFormat(false, o)).toEqual({ compact: true });
        expect(comboSeriesFormat(true, { format: { decimals: 0 } })).toEqual({ decimals: 0 });
    });

    it('names which measures are bars and which the line, then every category in the right format', () => {
        expect(comboAltText(PROPS, PROPS.labels, { format2: { style: 'percent' } }, same)).toBe(
            'Combo chart, bars: Alerts; line: Precision (avg) (right axis). W1: Alerts 12, Precision (avg) 81.5 %; ' +
                'W2: Alerts 30, Precision (avg) 64 %.',
        );
    });
});

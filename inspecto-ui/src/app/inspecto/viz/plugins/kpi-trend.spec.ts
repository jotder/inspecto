import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { kpiDelta } from '../kpi-delta';
import { NumberFormat } from '../number-format';
import { KpiTrendComponent } from './kpi-trend.component';
import { KPI_TREND_PLUGIN, sparkline, trendSummary } from './kpi-trend.plugin';

describe('KPI trend — pure transforms', () => {
    it('takes the last point as the headline and the previous one as the baseline', () => {
        expect(trendSummary([3, 5, 4, 8])).toEqual({ last: 8, lastIndex: 3, prev: 4, prevIndex: 2, min: 3, max: 8 });
    });

    it('compares compareBack points earlier, and has no baseline when the series is too short', () => {
        expect(trendSummary([10, 20, 30, 40], 3)).toMatchObject({ last: 40, prev: 10, prevIndex: 0 });
        expect(trendSummary([10, 20], 3)).not.toHaveProperty('prev');
        expect(trendSummary([10, 20, 30], 0)).toMatchObject({ prev: 20 }); // an invalid compareBack falls back to 1
        expect(trendSummary([])).toBeNull();
    });

    it('queries x + value ordered by x, and shapes rows like a line chart', () => {
        const values = { x: [{ field: 'month' }], value: [{ field: 'leakage', agg: 'sum' as const }] };
        const spec = KPI_TREND_PLUGIN.buildQuery(values, { datasetId: 'd', sourceName: 's' });
        expect(spec.groupBy).toEqual(['month']);
        expect(spec.orderBy).toEqual([{ field: 'month', dir: 'asc' }]);
        expect(spec.measures).toHaveLength(1);
        const id = spec.measures[0].id;
        const props = KPI_TREND_PLUGIN.transformProps(
            [
                { month: '2026-08-01', [id]: 4 },
                { month: '2026-09-01', [id]: 6 },
            ],
            values,
        );
        expect(props.labels).toEqual(['2026-08-01', '2026-09-01']);
        expect(props.series[0].data).toEqual([4, 6]);
    });

    it('draws the sparkline inside its box, with the last point marked and the target in range', () => {
        const s = sparkline([0, 10], 100, 32)!;
        expect(s.line).toBe('M0,30 L100,2');
        expect(s.area).toBe('M0,30 L100,2 L100,32 L0,32 Z');
        expect(s.last).toEqual({ x: 100, y: 2 });
        // A target above every value widens the range, so its line is at the top and the data sits lower.
        const t = sparkline([0, 10], 100, 32, 20)!;
        expect(t.targetY).toBe(2);
        expect(t.last.y).toBe(16);
        expect(sparkline([], 100, 32)).toBeNull();
        expect(sparkline([5], 100, 32)!.last).toEqual({ x: 100, y: 16 }); // one point: centred, at the right
    });

    it('shares the KPI tile delta wording, against any baseline label', () => {
        expect(kpiDelta(110, 100, 'higher', undefined, 'Aug 2026')).toEqual({
            text: '▲ Up 10.0 % (+10) vs Aug 2026',
            tone: 'good',
        });
        expect(kpiDelta(88.9, 91.5, 'higher', { style: 'percent' })?.text).toBe('▼ Down 2.6 pts vs prior period');
        expect(kpiDelta(110, 100, 'lower')?.tone).toBe('bad');
        expect(kpiDelta(5, 5, 'higher', undefined, 'Aug 2026')).toEqual({
            text: 'No change vs Aug 2026',
            tone: 'flat',
        });
    });
});

function create(inputs: {
    labels?: string[];
    values?: number[];
    format?: NumberFormat;
    target?: number;
    better?: 'higher' | 'lower';
    compareBack?: number;
}) {
    TestBed.configureTestingModule({ imports: [KpiTrendComponent] });
    const fixture = TestBed.createComponent(KpiTrendComponent);
    for (const k of ['labels', 'values', 'format', 'target', 'better', 'compareBack'] as const)
        if (inputs[k] !== undefined) fixture.componentRef.setInput(k, inputs[k]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const text = (id: string) => el.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? null;
    return { el, text };
}

const MONTHS = ['2026-06-01', '2026-07-01', '2026-08-01', '2026-09-01'];

describe('KpiTrendComponent', () => {
    it('shows the latest value, its date, and the delta vs the previous point in words', () => {
        const { text } = create({
            labels: MONTHS,
            values: [1.2, 1.4, 1.1, 0.9],
            format: { style: 'percent' },
            better: 'lower',
        });
        expect(text('kpi-trend-value')).toBe('0.9 %');
        expect(text('kpi-trend-as-of')).toBe('Sep 2026');
        expect(text('kpi-trend-delta')).toBe('▼ Down 0.2 pts vs Aug 2026');
    });

    it('compares compareBack points back and states the target', () => {
        const { text, el } = create({ labels: MONTHS, values: [100, 120, 130, 90], compareBack: 3, target: 95 });
        expect(text('kpi-trend-delta')).toBe('▼ Down 10.0 % (−10) vs Jun 2026');
        expect(el.querySelector('[data-testid="kpi-trend-delta"] span')?.className).toContain('red');
        expect(text('kpi-trend-target')).toBe('Target 95 — below target');
        expect(el.querySelector('.spark-target')).not.toBeNull();
    });

    it('labels the sparkline with its range and latest point', () => {
        const { el } = create({ labels: MONTHS, values: [100, 120, 130, 90] });
        expect(el.querySelector('[data-testid="kpi-trend-spark"]')?.getAttribute('aria-label')).toBe(
            'Trend of 4 points from Jun 2026 to Sep 2026: low 90, high 130, latest 90',
        );
    });

    it('with no rows shows a dash and no sparkline or delta', () => {
        const { text, el } = create({ labels: [], values: [] });
        expect(text('kpi-trend-value')).toBe('—');
        expect(el.querySelector('svg')).toBeNull();
        expect(text('kpi-trend-delta')).toBeNull();
    });

    it('renders with no a11y violations, delta and target included', async () => {
        const { el } = create({ labels: MONTHS, values: [100, 120, 130, 90], target: 95 });
        await expectNoA11yViolations(el);
    });
});

import { describe, expect, it } from 'vitest';
import { bandBadge, onePerPeriod, rankedFeatures, sparklinePoints, stampLabel } from './anomaly-score-view';

describe('anomaly-score-view', () => {
    it('maps each band to a tone and label', () => {
        expect(bandBadge('high')).toEqual({ value: 'error', label: 'High' });
        expect(bandBadge('elevated')).toEqual({ value: 'warning', label: 'Elevated' });
        expect(bandBadge('normal')).toEqual({ value: 'success', label: 'Normal' });
    });

    it('ranks features by contribution with their share of the total', () => {
        const r = rankedFeatures([
            { feature: 'a', contribution: 0.25 },
            { feature: 'b', contribution: 0.75 },
        ]);
        expect(r.map((f) => f.feature)).toEqual(['b', 'a']);
        expect(r.map((f) => f.share)).toEqual([75, 25]);
    });

    it('draws the history oldest first on a fixed 0..100 axis', () => {
        const pts = sparklinePoints(
            [
                { periodStart: 'd2', score: 100, band: 'high', runId: 'r2' },
                { periodStart: 'd1', score: 0, band: 'normal', runId: 'r1' },
            ],
            100,
            20,
        );
        expect(pts).toBe('0.0,20.0 100.0,0.0');
        expect(sparklinePoints([{ periodStart: 'd', score: 5, band: 'normal', runId: 'r' }], 100, 20)).toBe('');
    });

    it('collapses re-runs of one period to the latest run, so a day is one point', () => {
        const h = (periodStart: string, score: number, runId: string) => ({
            periodStart,
            score,
            band: 'normal' as const,
            runId,
        });
        const history = [h('d2', 90, 'r5'), h('d2', 10, 'r4'), h('d2', 10, 'r3'), h('d1', 0, 'r1')];
        expect(onePerPeriod(history).map((x) => x.runId)).toEqual(['r5', 'r1']);
        expect(sparklinePoints(history, 100, 20)).toBe('0.0,20.0 100.0,2.0');
        expect(sparklinePoints([h('d', 5, 'a'), h('d', 6, 'b')], 100, 20)).toBe('');
    });

    it('words server timestamps in the app spelling, whole-day for a period', () => {
        expect(stampLabel('2026-10-13 23:54:15.428051')).toBe('13 Oct 2026, 23:54:15');
        expect(stampLabel('2026-10-12 00:00:00.0', true)).toBe('12 Oct 2026');
        expect(stampLabel('not a date')).toBe('not a date');
        expect(stampLabel(null)).toBe('');
    });
});

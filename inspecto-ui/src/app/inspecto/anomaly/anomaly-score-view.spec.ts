import { describe, expect, it } from 'vitest';
import { bandBadge, rankedFeatures, sparklinePoints } from './anomaly-score-view';

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
});

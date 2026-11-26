import { describe, expect, it } from 'vitest';
import {
    bandBadge,
    calendarEntries,
    insightProfileAttributes,
    insightProfileInitial,
    sliceLabel,
    toInsightProfileContent,
} from './insight-profile-form';

describe('insight-profile-form', () => {
    it('asks the id only on create', () => {
        expect(insightProfileAttributes(false).some((a) => a.key === 'id')).toBe(true);
        expect(insightProfileAttributes(true).some((a) => a.key === 'id')).toBe(false);
    });

    it('round-trips stored content: measure, numbers, season, calendar and unauthored keys', () => {
        const stored = {
            id: 'sales',
            dataset: 'orders',
            measure: { agg: 'sum', field: 'amount' },
            field: 'order_date',
            grain: 'week',
            dimensions: ['region'],
            types: ['anomaly', 'concentration'],
            season: 52,
            filters: [{ field: 'x', op: '=', value: 1 }],
            note: 'kept',
            calendar: [{ from: '2026-12-25', label: 'Christmas', mode: 'exclude' }],
        };
        const init = insightProfileInitial(stored);
        expect(init['measureAgg']).toBe('sum');
        expect(init['measureField']).toBe('amount');
        expect(init['season']).toBe('52');
        const cal = calendarEntries(stored);
        expect(cal).toEqual([{ from: '2026-12-25', to: '2026-12-25', label: 'Christmas', mode: 'exclude' }]);
        const out = toInsightProfileContent('sales', { ...init, minImpact: '0.02', maxValues: '' }, cal, stored);
        expect(out).toMatchObject({
            id: 'sales',
            measure: { agg: 'sum', field: 'amount' },
            types: ['anomaly', 'concentration'],
            season: 52,
            minImpact: 0.02,
            filters: stored.filters,
            calendar: [{ from: '2026-12-25', label: 'Christmas', mode: 'exclude' }],
        });
        expect('maxValues' in out).toBe(false);
        expect(out['note']).toBe('kept');
    });

    it('a count Measure carries no field and a new Profile defaults its types', () => {
        expect(insightProfileInitial({})['types']).toEqual(['anomaly', 'trend-break', 'drift']);
        const out = toInsightProfileContent('c', { measureAgg: 'count', measureField: 'amount', season: 'auto' }, []);
        expect(out['measure']).toEqual({ agg: 'count' });
        expect(out['season']).toBe('auto');
        expect('calendar' in out).toBe(false);
    });

    it('names bands and slices in words', () => {
        expect(bandBadge('high')).toEqual({ tone: 'error', label: 'High' });
        expect(bandBadge('elevated').label).toBe('Elevated');
        expect(bandBadge('normal').label).toBe('Normal');
        expect(sliceLabel({ dimension: '*', slice_value: null })).toBe('Whole Measure');
        expect(sliceLabel({ dimension: 'region', slice_value: 'North' })).toBe('region = North');
    });
});

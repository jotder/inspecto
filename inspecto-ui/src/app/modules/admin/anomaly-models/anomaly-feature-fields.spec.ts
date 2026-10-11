import { describe, expect, it } from 'vitest';
import { filterDrafts, toFilter, unitText, withFeatureFields } from './anomaly-feature-fields';

describe('anomaly-feature-fields', () => {
    it('reads stored filters as text, keeping the stored map', () => {
        const [a, b] = filterDrafts({
            filters: [
                { field: 'plan', op: 'in', value: ['POST', 'PRE'] },
                { field: 'mb', op: 'gte', value: 10, note: 'kept' },
            ],
        });
        expect(a).toEqual(expect.objectContaining({ field: 'plan', op: 'in', value: 'POST, PRE' }));
        expect(b).toEqual(expect.objectContaining({ field: 'mb', op: 'gte', value: '10' }));
        expect(filterDrafts({})).toEqual([]);
    });

    it('writes an untouched value back as stored, and re-types an edited one by operator', () => {
        const [inList, alias] = filterDrafts({
            filters: [
                { field: 'plan', op: 'in', value: ['POST', 'PRE'] },
                { field: 'mb', op: 'gte', value: 10, note: 'kept' },
            ],
        });
        expect(toFilter(inList)).toEqual({ field: 'plan', op: 'in', value: ['POST', 'PRE'] });
        expect(toFilter(alias)).toEqual({ field: 'mb', op: 'gte', value: 10, note: 'kept' });
        expect(toFilter({ ...alias, value: '12' })).toEqual({ field: 'mb', op: 'gte', value: '12', note: 'kept' });
        expect(toFilter({ ...alias, op: 'notNull' })).toEqual({ field: 'mb', op: 'notNull', note: 'kept' });
        expect(toFilter({ ...alias, op: 'in', value: '1, 2,,3' })).toEqual({
            field: 'mb',
            op: 'in',
            value: ['1', '2', '3'],
            note: 'kept',
        });
    });

    it('keeps an unchanged unit as stored, writes an edited one as a number and drops a blank one', () => {
        expect(unitText({ unit: 5 })).toBe('5');
        expect(withFeatureFields({ unit: 5, other: true }, [], '5')).toEqual({ unit: 5, other: true });
        expect(withFeatureFields({ unit: 5 }, [], '0.5')).toEqual({ unit: 0.5 });
        expect(withFeatureFields({ unit: 5, filters: [{}] }, [], ' ')).toEqual({});
    });
});

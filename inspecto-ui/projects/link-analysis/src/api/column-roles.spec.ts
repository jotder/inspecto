import { describe, expect, it } from 'vitest';
import { MAX_CARRIED, carriedColumns } from './column-roles';

describe('carriedColumns (LA-COLUMN-ROLES-1 P2)', () => {
    it('every Time, Measure and Category column, in column order, unless kept off; never a link end', () => {
        const cols = [
            { name: 'from_account', role: 'entity', classification: 'ACCOUNT' },
            { name: 'channel', role: 'dimension' },
            { name: 'booked_at', role: 'Temporal' },
            { name: 'fee', role: 'measure', carried: false },
            { name: 'amount', role: 'measure' },
            { name: 'note', role: 'detail' },
            { name: 'plain' },
            { name: 'to_account', role: 'dimension' },
            null,
        ];
        expect(carriedColumns(cols, ['FROM_ACCOUNT', 'to_account'])).toEqual(['channel', 'booked_at', 'amount']);
        expect(carriedColumns(undefined)).toEqual([]);
        expect(carriedColumns([{ name: 'x' }])).toEqual([]); // an entry without roles carries nothing extra
        const wide = Array.from({ length: 60 }, (_, i) => ({ name: `m${i}`, role: 'measure' }));
        expect(carriedColumns(wide)).toHaveLength(MAX_CARRIED); // the index build's own bound
        expect(carriedColumns(wide).at(-1)).toBe('m49');
    });
});

import { describe, expect, it } from 'vitest';
import { MAX_CARRIED, carriedColumns, hintedRole, matchingHint, recordPhrase } from './column-roles';

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

describe("a record type's usual roles (ENTITY-TYPE-RECORD-ROLES-1)", () => {
    const hints = { time: ['start'], measure: ['duration', '^fee$'], category: ['cause', 'service'] };

    it('matches a hint as the server does: case-insensitive, anywhere unless anchored', () => {
        expect(matchingHint(['duration'], 'Call_DURATION_s')).toBe('duration');
        expect(matchingHint(['^fee$'], 'FEE')).toBe('^fee$');
        expect(matchingHint(['^fee$'], 'fee_total')).toBeNull();
        expect(matchingHint(['(bad'], 'bad')).toBeNull();
        expect(matchingHint(undefined, 'x')).toBeNull();
    });

    it('gives a column the first role its hints name whose role the type can hold', () => {
        expect(hintedRole('start_at', 'TIMESTAMP', hints)).toEqual({ role: 'temporal', hint: 'start' });
        expect(hintedRole('start_at', 'timestamp', hints)?.role).toBe('temporal');
        expect(hintedRole('start_at', 'VARCHAR', hints)).toBeNull();
        expect(hintedRole('call_duration', 'BIGINT', hints)?.role).toBe('measure');
        expect(hintedRole('call_duration', 'number', hints)?.role).toBe('measure');
        expect(hintedRole('call_duration', 'string', hints)).toBeNull();
        expect(hintedRole('cause', 'BIGINT', hints)?.role).toBe('dimension');
        expect(hintedRole('cause', 'DOUBLE', hints)).toBeNull();
        expect(hintedRole('cause', null, hints)?.role).toBe('dimension');
        expect(hintedRole('cause', 'VARCHAR', undefined)).toBeNull();
    });

    it('reads a record type inside a sentence', () => {
        expect(recordPhrase('Voice call record')).toBe('a voice call record');
        expect(recordPhrase('Invoice')).toBe('an invoice');
        expect(recordPhrase('SMS record')).toBe('an SMS record');
    });
});

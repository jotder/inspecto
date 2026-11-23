import { describe, expect, it } from 'vitest';
import { formatLookups, lookupsProblem, outputNames, parseLookups } from './join-lookups';

/** The example from the user guide (§8.7): a rename map with a default, and a details-only mapping. */
const STORED = [
    {
        on: 'SERVED_MSISDN',
        columns: { dial_digit_key: 'SERVED_MSISDN_DIAL_DIGIT_KEY', provider_desc: 'SERVED_MSISDN_OPER' },
        default: { SERVED_MSISDN_DIAL_DIGIT_KEY: -99 },
    },
    { on: 'OTHER_MSISDN', columns: {}, details: true },
];

describe('join lookups', () => {
    it('round-trips what it loaded, keeping a default in its stored type', () => {
        expect(formatLookups(parseLookups(STORED)!)).toEqual(STORED);
    });

    it('reads no value as no mappings and a value it cannot edit as null', () => {
        expect(parseLookups(undefined)).toEqual([]);
        expect(formatLookups([])).toBeUndefined();
        expect(parseLookups('x')).toBeNull();
        expect(parseLookups([{ on: 'a', nope: 1 }])).toBeNull();
        expect(parseLookups([{ on: 'a', default: ['no equals sign'] }])).toBeNull();
    });

    it('keeps a details map as written and a columns list as a list', () => {
        const stored = [{ on: 'a', columns: ['k', 'v'], as: 'x_', details: { matched_key: 'MK' } }];
        expect(formatLookups(parseLookups(stored)!)).toEqual([
            { on: 'a', columns: ['k', 'v'], as: 'x_', details: { matched_key: 'MK' } },
        ]);
    });

    it('turns a name=value default list into a map', () => {
        const rows = parseLookups([{ on: 'a', columns: ['k'], default: ['a_k=-1'] }])!;
        expect(formatLookups(rows)![0]['default']).toEqual({ a_k: '-1' });
    });

    it('names a blank output the way the engine does when a rename map needs every one named', () => {
        const rows = parseLookups([{ on: 'SERVED', columns: { k: 'KEY', v: '' } }])!;
        expect(formatLookups(rows)![0]['columns']).toEqual({ k: 'KEY', v: 'SERVED_v' });
    });

    it('lists the output names, details included', () => {
        const [a, b] = parseLookups(STORED)!;
        expect(outputNames(a)).toEqual(['SERVED_MSISDN_DIAL_DIGIT_KEY', 'SERVED_MSISDN_OPER']);
        expect(outputNames(b)).toEqual(['OTHER_MSISDN_matched_key', 'OTHER_MSISDN_match_kind']);
    });

    it('refuses a missing input, a repeated output name and a default for a column it does not add', () => {
        expect(lookupsProblem(parseLookups(STORED)!)).toBeNull();
        expect(lookupsProblem(parseLookups([{ on: '' }])!)).toContain('needs the column it looks up');
        const clash = parseLookups([
            { on: 'a', columns: { k: 'X' } },
            { on: 'b', columns: { k: 'x' } },
        ])!;
        expect(lookupsProblem(clash)).toContain('named x');
        const stray = parseLookups([{ on: 'a', columns: { k: 'X' }, default: { Y: 1 } }])!;
        expect(lookupsProblem(stray)).toContain('not one of its output columns');
    });

    it('does not check a default when the mapping returns every Reference column', () => {
        expect(lookupsProblem(parseLookups([{ on: 'a', default: { anything: 1 } }])!)).toBeNull();
    });
});

import { describe, expect, it } from 'vitest';
import { expiryInstant, parseEntityListEntries } from './entity-list-entries';

describe('parseEntityListEntries (ASSURE-ENTITY-LISTS-RESIDUALS-1)', () => {
    it('splits keys, prefixes, ranges and CIDR blocks, skipping blank lines', () => {
        const p = parseEntityListEntries('+447700900123\n\n +4478* \n447800..447899\n10.1.0.0/16\n2001:db8::/32');
        expect(p.errors).toEqual([]);
        expect(p.keys).toEqual(['+447700900123']);
        expect(p.ranges).toEqual([
            { prefix: '+4478' },
            { from: '447800', to: '447899' },
            { cidr: '10.1.0.0/16' },
            { cidr: '2001:db8::/32' },
        ]);
    });

    it('refuses malformed lines by line number and keeps nothing silently', () => {
        const p = parseEntityListEntries('44785..447899\n447899..447800\n*\n44*78\nnot/a/cidr\n1..2..3');
        expect(p.errors).toEqual([
            expect.stringContaining('Line 1: both ends of a range need the same length'),
            expect.stringContaining('Line 2: the low end is above the high end'),
            expect.stringContaining('Line 3: a prefix'),
            expect.stringContaining('Line 4: “*” is only allowed at the end'),
            expect.stringContaining('Line 5'),
            expect.stringContaining('Line 6: a range is written low..high'),
        ]);
        expect(p.keys).toEqual([]);
    });
});

describe('expiryInstant', () => {
    const now = new Date('2026-10-06T12:00:00Z');
    it('blank is permanent; a future time becomes an ISO instant', () => {
        expect(expiryInstant('', now)).toEqual({});
        expect(expiryInstant('2026-10-07T12:00Z', now).iso).toBe('2026-10-07T12:00:00.000Z');
    });
    it('refuses the past and garbage', () => {
        expect(expiryInstant('2026-10-06T11:59Z', now).error).toContain('future');
        expect(expiryInstant('nope', now).error).toContain('Not a date');
    });
});

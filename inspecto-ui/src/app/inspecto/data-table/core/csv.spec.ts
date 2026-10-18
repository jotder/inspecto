import { describe, expect, it } from 'vitest';
import { csvSafeCell, parseCsv, toCsv } from './csv';

describe('toCsv', () => {
    it('writes header + rows', () => {
        expect(toCsv([{ a: 1, b: 'x' }], ['a', 'b'])).toBe('a,b\n1,x');
    });

    it('quotes cells with commas, quotes and newlines (RFC-4180)', () => {
        expect(toCsv([{ a: 'x,y', b: 'he said "hi"', c: 'l1\nl2' }], ['a', 'b', 'c'])).toBe(
            'a,b,c\n"x,y","he said ""hi""","l1\nl2"',
        );
    });

    it('renders null/undefined cells as blank', () => {
        expect(toCsv([{ a: null, b: undefined }], ['a', 'b'])).toBe('a,b\n,');
    });

    it('emits the header alone when there are no rows', () => {
        expect(toCsv([], ['a', 'b'])).toBe('a,b');
    });

    it('neutralises a formula-looking string cell by default, quoting a cell that then needs it', () => {
        expect(toCsv([{ k: '=A1', n: -4, s: '-3', r: '\rx' }], ['k', 'n', 's', 'r'])).toBe(
            "k,n,s,r\n'=A1,-4,-3,\"'\rx\"",
        );
    });

    it('keeps cells verbatim when the guard is off (a file that is re-imported)', () => {
        expect(toCsv([{ v: '=x' }], ['v'], { formulaGuard: false })).toBe('v\n=x');
    });
});

describe('csvSafeCell', () => {
    it.each(['=1+1', '+cmd|x', '-1+1', '@SUM(A1)', '\tx', '\rx', '=HYPERLINK("http://x")'])(
        'prefixes a quote on the formula-looking string %j',
        (s) => expect(csvSafeCell(s)).toBe(`'${s}`),
    );

    it.each(['-5', '3.2', '+7', '1e5', '-0.5', '.5'])('leaves the numeric string %j alone', (s) =>
        expect(csvSafeCell(s)).toBe(s),
    );

    it('passes numbers, blanks and plain text through', () => {
        expect(csvSafeCell(-5)).toBe(-5);
        expect(csvSafeCell('')).toBe('');
        expect(csvSafeCell(null)).toBeNull();
    });
});

describe('parseCsv', () => {
    it('drops the leading BOM downloadCsv writes', () => {
        expect(parseCsv('﻿a,b\n1,2')).toEqual([
            ['a', 'b'],
            ['1', '2'],
        ]);
    });
});

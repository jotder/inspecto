import { describe, expect, it } from 'vitest';
import { csvSafeCell, csvSafeRow } from './csv-safe';

describe('csvSafeCell', () => {
    it.each(['=1+1', '+cmd|x', '-1+1', '@SUM(A1)', '\tx', '\rx', '=HYPERLINK("http://x")'])(
        'prefixes a quote on the formula-looking string %j',
        (s) => expect(csvSafeCell(s)).toBe(`'${s}`),
    );

    it.each(['-5', '3.2', '+7', '1e5', '-0.5', '.5'])('leaves the numeric string %j alone', (s) =>
        expect(csvSafeCell(s)).toBe(s),
    );

    it('leaves real numbers, blanks and ordinary text alone', () => {
        expect(csvSafeCell(-5)).toBe(-5);
        expect(csvSafeCell('')).toBe('');
        expect(csvSafeCell('EU · voice')).toBe('EU · voice');
        expect(csvSafeCell(null)).toBeNull();
    });

    it('sanitises every cell of a row', () => {
        expect(csvSafeRow({ key: '=A1', diff: -4, left: '-3' })).toEqual({ key: "'=A1", diff: -4, left: '-3' });
    });
});

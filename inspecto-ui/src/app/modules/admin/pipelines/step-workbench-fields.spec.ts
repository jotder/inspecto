import { describe, expect, it } from 'vitest';
import { FIELD_LIST_CAP, filterFields, referencedIdentifiers, workbenchFields } from './step-workbench-fields';

describe('referencedIdentifiers', () => {
    const cols = ['amount', 'total_amount', 'customer_id', 'note'];

    it('matches a bare identifier without matching a longer one that contains it', () => {
        // 🔴 The case a naive \b regex gets wrong: `_` IS a word character, so \bamount\b matches inside
        // `total_amount` and the field list would claim a column is used when nothing reads it.
        const hit = referencedIdentifiers('SELECT total_amount FROM t', cols);
        expect(hit.has('total_amount')).toBe(true);
        expect(hit.has('amount'), 'amount must NOT be reported for total_amount').toBe(false);
    });

    it('matches the quoted form and is case-insensitive', () => {
        expect(referencedIdentifiers('SELECT "Amount" FROM t', cols).has('amount')).toBe(true);
        expect(referencedIdentifiers('select AMOUNT from t', cols).has('amount')).toBe(true);
    });

    it('ignores identifiers that only appear inside a string literal or a comment', () => {
        expect(referencedIdentifiers("SELECT 1 WHERE x = 'amount'", cols).has('amount')).toBe(false);
        expect(referencedIdentifiers('SELECT 1 -- amount\nFROM t', cols).has('amount')).toBe(false);
        expect(referencedIdentifiers('SELECT 1 /* amount */ FROM t', cols).has('amount')).toBe(false);
    });

    it('handles a column name containing regex metacharacters', () => {
        // A quoted column may legally be `a.b` or `c(1)`; an unescaped name would make the RegExp match
        // the wrong thing, or throw.
        const odd = ['a.b', 'c(1)'];
        expect(referencedIdentifiers('SELECT "a.b" FROM t', odd).has('a.b')).toBe(true);
        expect(referencedIdentifiers('SELECT axb FROM t', odd).has('a.b'), '. must not match any char').toBe(false);
        expect(() => referencedIdentifiers('SELECT 1', odd)).not.toThrow();
    });

    it('returns nothing for empty or absent SQL rather than throwing', () => {
        expect(referencedIdentifiers('', cols).size).toBe(0);
        expect(referencedIdentifiers(undefined as unknown as string, cols).size).toBe(0);
    });
});

describe('workbenchFields', () => {
    it('puts referenced fields first and preserves upstream order within each group', () => {
        const fields = workbenchFields(['a', 'b', 'c', 'd'], { a: 'VARCHAR', c: 'DOUBLE' }, 'SELECT c, a FROM t');
        expect(fields.map((f) => f.name)).toEqual(['a', 'c', 'b', 'd']);
        expect(fields.filter((f) => f.referenced).map((f) => f.name)).toEqual(['a', 'c']);
    });

    it('carries the declared type, and an empty string when the upstream declared none', () => {
        const fields = workbenchFields(['a', 'b'], { a: 'DOUBLE' }, '');
        expect(fields.find((f) => f.name === 'a')?.type).toBe('DOUBLE');
        expect(fields.find((f) => f.name === 'b')?.type).toBe('');
    });
});

describe('filterFields', () => {
    const fields = workbenchFields(['amount', 'customer_id', 'note'], {}, '');

    it('filters case-insensitively on the name', () => {
        expect(filterFields(fields, 'AMO').map((f) => f.name)).toEqual(['amount']);
        expect(filterFields(fields, '_id').map((f) => f.name)).toEqual(['customer_id']);
    });

    it('does not match on the type — "int" must not select every integer column', () => {
        const typed = workbenchFields(['amount', 'qty'], { amount: 'INTEGER', qty: 'INTEGER' }, '');
        expect(filterFields(typed, 'int')).toEqual([]);
    });

    it('returns everything for a blank filter, and searches BEYOND the render cap', () => {
        expect(filterFields(fields, '   ').length).toBe(3);
        // The cap is a rendering limit, not a search limit: a column past it must still be reachable by
        // typing its name, otherwise a wide feed hides columns with no way to get at them.
        const wide = workbenchFields(
            Array.from({ length: FIELD_LIST_CAP + 10 }, (_, i) => `col_${i}`),
            {},
            '',
        );
        expect(filterFields(wide, `col_${FIELD_LIST_CAP + 5}`).map((f) => f.name)).toEqual([
            `col_${FIELD_LIST_CAP + 5}`,
        ]);
    });
});

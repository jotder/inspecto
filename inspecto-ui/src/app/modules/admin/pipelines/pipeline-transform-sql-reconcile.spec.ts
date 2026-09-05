import { describe, expect, it } from 'vitest';
import { generateSql, SqlField } from './pipeline-transform-sql';
import { reconcileSql, splitTopLevel } from './pipeline-transform-sql-reconcile';

function rows(sql: string): Omit<SqlField, 'id'>[] {
    const r = reconcileSql(sql);
    if (r.unsupported) throw new Error(r.unsupported);
    return r.fields.map(({ id: _id, ...rest }) => rest);
}

describe('reconcileSql: projections become fields', () => {
    it('a bare column is Keep as it is, named after itself', () => {
        expect(rows('SELECT ORDER_ID FROM input')).toEqual([
            { name: 'ORDER_ID', from: 'ORDER_ID', fn: 'keep', args: {} },
        ]);
    });

    it('a quoted column keeps its exact spelling', () => {
        expect(rows('SELECT "Order Id" AS "Order Id" FROM input')).toEqual([
            { name: 'Order Id', from: 'Order Id', fn: 'keep', args: {} },
        ]);
    });

    it('recognises a catalog function and reads its parameters back', () => {
        expect(rows("SELECT TRY_STRPTIME(ORDER_DATE, '%d/%m/%Y') AS ORDER_DATE FROM input")).toEqual([
            { name: 'ORDER_DATE', from: 'ORDER_DATE', fn: 'date.parse', args: { format: '%d/%m/%Y' } },
        ]);
    });

    it('recognises functions regardless of keyword case and spacing', () => {
        expect(rows('select trim( customer ) as buyer from input')).toEqual([
            { name: 'buyer', from: 'customer', fn: 'text.trim', args: {} },
        ]);
    });

    it('reads a text parameter with an escaped quote', () => {
        expect(rows("SELECT REPLACE(note, '''', '') AS note FROM input")).toEqual([
            { name: 'note', from: 'note', fn: 'text.replace', args: { find: "'", replacement: '' } },
        ]);
    });

    it('reads enum, number and column parameters', () => {
        expect(
            rows("SELECT EXTRACT(MONTH FROM d) AS m, ROUND(amt, 2) AS r, CONCAT(a, ' ', b) AS ab FROM input"),
        ).toEqual([
            { name: 'm', from: 'd', fn: 'date.part', args: { part: 'MONTH' } },
            { name: 'r', from: 'amt', fn: 'num.round', args: { decimals: '2' } },
            { name: 'ab', from: 'a', fn: 'text.join', args: { separator: ' ', other: 'b' } },
        ]);
    });

    it('keeps an expression the catalog does not know as a custom row, verbatim', () => {
        expect(rows('SELECT round(AMOUNT / 1.19, 2) AS AMOUNT_NET FROM input')).toEqual([
            { name: 'AMOUNT_NET', from: '', fn: 'custom', args: { expression: 'round(AMOUNT / 1.19, 2)' } },
        ]);
    });

    it('an unaliased expression is still recognised, but with no name so the grid asks for one', () => {
        expect(rows('SELECT a, UPPER(b), x + 1 FROM input')).toEqual([
            { name: 'a', from: 'a', fn: 'keep', args: {} },
            { name: '', from: 'b', fn: 'text.upper', args: {} },
            { name: '', from: '', fn: 'custom', args: { expression: 'x + 1' } },
        ]);
    });

    it('accepts an implicit alias, comments and a trailing semicolon', () => {
        expect(rows('-- projection\nSELECT LOWER(x) y /* c */ FROM input;')).toEqual([
            { name: 'y', from: 'x', fn: 'text.lower', args: {} },
        ]);
    });

    it('SELECT * alone is an empty field list (everything passes through)', () => {
        expect(rows('SELECT * FROM input')).toEqual([]);
    });

    it('never recognises a function whose regeneration would rewrite the SQL', () => {
        // Bare CAST is not TRY_CAST: the catalog's convert.type would change the semantics, so custom.
        expect(rows('SELECT CAST(a AS BIGINT) AS a FROM input')[0].fn).toBe('custom');
    });

    it('round-trips: whatever it reads regenerates to equivalent SQL', () => {
        const sql =
            "SELECT\n  ORDER_ID,\n  TRY_STRPTIME(ORDER_DATE, '%d/%m/%Y') AS ORDER_DATE,\n  round(AMOUNT / 1.19, 2) AS AMOUNT_NET\nFROM input";
        const r = reconcileSql(sql);
        expect(r.unsupported).toBeUndefined();
        const regenerated = generateSql(r.fields!);
        const norm = (s: string) =>
            s
                .replace(/\s+/g, ' ')
                .replace(/\s*([(),])\s*/g, '$1')
                .toLowerCase();
        expect(norm(regenerated)).toBe(
            norm(
                "SELECT ORDER_ID AS ORDER_ID, TRY_STRPTIME(ORDER_DATE, '%d/%m/%Y') AS ORDER_DATE, round(AMOUNT / 1.19, 2) AS AMOUNT_NET FROM input",
            ),
        );
    });
});

describe('reconcileSql: what fields cannot show', () => {
    it.each([
        'SELECT a FROM input WHERE a > 0',
        'SELECT a FROM input JOIN other ON 1=1',
        'SELECT a, count(*) FROM input GROUP BY a',
        'WITH x AS (SELECT 1) SELECT * FROM x',
        'SELECT DISTINCT a FROM input',
        'SELECT *, a FROM input',
        'SELECT a FROM other',
        'DELETE FROM input',
    ])('refuses %s with a reason', (sql) => {
        const r = reconcileSql(sql);
        expect(r.unsupported).toMatch(/cannot be shown as fields/);
    });
});

describe('splitTopLevel', () => {
    it('splits on commas outside parentheses and quotes only', () => {
        expect(splitTopLevel('a, f(b, c), \'x,y\', "q,r" , d')).toEqual(['a', 'f(b, c)', "'x,y'", '"q,r"', 'd']);
    });
});

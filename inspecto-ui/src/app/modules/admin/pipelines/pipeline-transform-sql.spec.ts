import { describe, expect, it } from 'vitest';
import {
    SqlField,
    applyFunction,
    compileField,
    generateSql,
    readFields,
    seedFields,
} from './pipeline-transform-sql';
import { SQL_FUNCTIONS, renderExpression, sqlFunction, sqlFunctionsByCategory } from './sql-functions';

function field(partial: Partial<SqlField> & Pick<SqlField, 'name' | 'fn'>): SqlField {
    return { id: partial.name, from: '', args: {}, ...partial };
}

/** Compile a row and fail loudly with the row's own problem when it was supposed to succeed. */
function expr(f: SqlField): string {
    const c = compileField(f);
    if (c.problem) throw new Error(`expected an expression, got problem: ${c.problem}`);
    return c.expr!;
}

describe('sql-functions: the catalog', () => {
    it('every function id is unique', () => {
        const ids = SQL_FUNCTIONS.map((f) => f.id);
        expect(new Set(ids).size).toBe(ids.length);
    });

    it('every parameter a template mentions is declared, and every declared parameter is used', () => {
        for (const fn of SQL_FUNCTIONS) {
            const inTemplate = [...fn.template.matchAll(/\{(\w+)\}/g)]
                .map((m) => m[1])
                .filter((n) => n !== 'source');
            const declared = (fn.params ?? []).map((p) => p.name);
            expect(new Set(inTemplate), `${fn.id} template placeholders`).toEqual(new Set(declared));
        }
    });

    it('every enum parameter declares options, and its default is one of them', () => {
        for (const fn of SQL_FUNCTIONS) {
            for (const p of fn.params ?? []) {
                if (p.type !== 'enum') continue;
                expect(p.options, `${fn.id}.${p.name} options`).toBeTruthy();
                if (p.default) expect(p.options).toContain(p.default);
            }
        }
    });

    it('never emits a bare CAST — every conversion is forgiving', () => {
        for (const fn of SQL_FUNCTIONS) {
            expect(fn.template, fn.id).not.toMatch(/(^|[^_])\bCAST\(/);
        }
        expect(sqlFunction('convert.type')!.template).toContain('TRY_CAST');
    });

    it('groups into categories in declaration order without repeating a category', () => {
        const groups = sqlFunctionsByCategory();
        const names = groups.map((g) => g.category);
        expect(new Set(names).size).toBe(names.length);
        expect(names[0]).toBe('Keep');
    });
});

describe('sql-functions: rendering parameters', () => {
    it('binds the row source column to {source} automatically', () => {
        expect(expr(field({ name: 'buyer', from: 'customer', fn: 'text.trim' }))).toBe('TRIM(customer)');
    });

    it('quotes a source column that is not a plain identifier', () => {
        expect(expr(field({ name: 'a', from: 'Order Date', fn: 'text.trim' }))).toBe('TRIM("Order Date")');
    });

    it('renders a text parameter as an escaped SQL string literal', () => {
        const f = field({ name: 'x', from: 'notes', fn: 'text.replace', args: { find: "it's", replacement: '-' } });
        expect(expr(f)).toBe("REPLACE(notes, 'it''s', '-')");
    });

    it('renders a number parameter verbatim and refuses a non-number', () => {
        expect(expr(field({ name: 'x', from: 'amt', fn: 'num.round', args: { decimals: '2' } }))).toBe('ROUND(amt, 2)');
        expect(compileField(field({ name: 'x', from: 'amt', fn: 'num.round', args: { decimals: 'two' } })).problem)
            .toMatch(/needs a number/);
    });

    it('renders a column parameter as a quoted identifier, not a literal', () => {
        const f = field({ name: 'full', from: 'first', fn: 'text.join', args: { separator: ' ', other: 'last name' } });
        expect(expr(f)).toBe(`CONCAT(first, ' ', "last name")`);
    });

    it('refuses an enum value outside its options', () => {
        const bad = field({ name: 'x', from: 'd', fn: 'date.part', args: { part: 'FORTNIGHT' } });
        expect(compileField(bad).problem).toMatch(/Choose a value/);
    });

    it('falls back to a parameter default when the arg is absent', () => {
        expect(expr(field({ name: 'x', from: 'amt', fn: 'num.round' }))).toBe('ROUND(amt, 0)');
    });

    it('lets an optional text parameter be blank', () => {
        const f = field({ name: 'x', from: 'n', fn: 'text.replace', args: { find: '-', replacement: '' } });
        expect(expr(f)).toBe("REPLACE(n, '-', '')");
    });

    it('a source-less function needs no column', () => {
        const f = field({ name: 'x', fn: 'custom', args: { expression: 'ROUND(amount * 100)' } });
        expect(expr(f)).toBe('ROUND(amount * 100)');
    });

    it('a source-using function without a column reports it rather than emitting broken SQL', () => {
        expect(compileField(field({ name: 'x', from: '', fn: 'text.trim' })).problem).toMatch(/Pick the column/);
    });

    it('a text parameter carrying a quote cannot break out of its literal', () => {
        const f = field({ name: 'x', from: 'c', fn: 'logic.default_if_empty', args: { fallback: "a' OR 1=1 --" } });
        expect(expr(f)).toBe(`COALESCE(NULLIF(c, ''), 'a'' OR 1=1 --')`);
    });

    it('reports an unknown function instead of dropping the row', () => {
        expect(compileField(field({ name: 'x', from: 'c', fn: 'text.teleport' })).problem).toMatch(/no function/);
    });

    it('requires a field name', () => {
        expect(compileField(field({ name: '  ', from: 'c', fn: 'keep' })).problem).toMatch(/name/);
    });

    it('renderExpression is the one place an expression is built', () => {
        const fn = sqlFunction('text.upper')!;
        expect(renderExpression(fn, 'status', {})).toEqual({ expr: 'UPPER(status)' });
    });
});

describe('pipeline-transform-sql: generateSql', () => {
    it('emits one aliased column per complete row over the fixed input relation', () => {
        const sql = generateSql([
            field({ name: 'order_id', from: 'order_id', fn: 'keep' }),
            field({ name: 'buyer', from: 'customer', fn: 'text.trim' }),
            field({ name: 'cents', from: 'amount', fn: 'num.multiply', args: { factor: '100' } }),
        ]);
        expect(sql).toBe(
            'SELECT\n  order_id AS order_id,\n  TRIM(customer) AS buyer,\n  (amount * 100) AS cents\nFROM input',
        );
    });

    it('quotes an output name that is not a plain identifier', () => {
        expect(generateSql([field({ name: 'Order Id', from: 'a', fn: 'keep' })])).toContain('AS "Order Id"');
    });

    it('skips a row with a problem so the generated SQL always parses', () => {
        const sql = generateSql([
            field({ name: 'ok', from: 'a', fn: 'keep' }),
            field({ name: 'broken', from: '', fn: 'text.trim' }),
        ]);
        expect(sql).toBe('SELECT\n  a AS ok\nFROM input');
    });

    it('falls back to SELECT * for an empty grid', () => {
        expect(generateSql([])).toBe('SELECT * FROM input');
    });
});

describe('pipeline-transform-sql: seeding and editing', () => {
    it('seeds one Keep row per upstream column, in order', () => {
        const fields = seedFields(['a', 'b']);
        expect(fields.map((f) => [f.name, f.from, f.fn])).toEqual([
            ['a', 'a', 'keep'],
            ['b', 'b', 'keep'],
        ]);
        expect(generateSql(fields)).toBe('SELECT\n  a AS a,\n  b AS b\nFROM input');
    });

    it('applyFunction seeds the new function’s parameter defaults', () => {
        const next = applyFunction(field({ name: 'x', from: 'amt', fn: 'keep' }), 'num.round');
        expect(next.args).toEqual({ decimals: '0' });
    });

    it('applyFunction keeps a value the author already typed for a parameter of the same name', () => {
        const typed = field({ name: 'x', from: 'd', fn: 'date.parse', args: { format: '%d-%m-%Y' } });
        expect(applyFunction(typed, 'date.format').args['format']).toBe('%d-%m-%Y');
    });

    it('applyFunction clears the source column for a source-less function', () => {
        const next = applyFunction(field({ name: 'x', from: 'amt', fn: 'keep' }), 'custom');
        expect(next.from).toBe('');
    });
});

describe('pipeline-transform-sql: reading persisted fields', () => {
    it('round-trips what the grid wrote', () => {
        const written = [field({ name: 'cents', from: 'amount', fn: 'num.multiply', args: { factor: '100' } })];
        expect(readFields(JSON.parse(JSON.stringify(written)))).toEqual(written);
    });

    it('returns null for anything unusable, so the caller seeds instead', () => {
        expect(readFields(undefined)).toBeNull();
        expect(readFields([])).toBeNull();
        expect(readFields('SELECT 1')).toBeNull();
        expect(readFields([{ from: 'a' }])).toBeNull();
    });

    it('opens a node saved by the retired five-verb grid', () => {
        const legacy = [
            { id: '1', name: 'buyer', from: 'customer', verb: 'trim' },
            { id: '2', name: 'amount', from: 'amt', verb: 'cast', castType: 'DOUBLE' },
            { id: '3', name: 'cents', from: '', verb: 'formula', formula: 'amount * 100' },
        ];
        const read = readFields(legacy)!;
        expect(read.map((f) => f.fn)).toEqual(['text.trim', 'convert.type', 'custom']);
        expect(generateSql(read)).toBe(
            'SELECT\n  TRIM(customer) AS buyer,\n  TRY_CAST(amt AS DOUBLE) AS amount,\n  amount * 100 AS cents\nFROM input',
        );
    });
});

import { describe, expect, it } from 'vitest';
import { SqlField, exprFor, generateSql, seedFields } from './pipeline-transform-sql';

function field(partial: Partial<SqlField> & Pick<SqlField, 'name' | 'from' | 'verb'>): SqlField {
    return { id: partial.name, ...partial };
}

describe('pipeline-transform-sql: the five verbs', () => {
    it('keep passes the source column through unchanged', () => {
        expect(exprFor(field({ name: 'order_id', from: 'order_id', verb: 'keep' }))).toBe('order_id');
    });

    it('trim wraps the source in TRIM(...)', () => {
        expect(exprFor(field({ name: 'buyer', from: 'customer', verb: 'trim' }))).toBe('TRIM(customer)');
    });

    it('upper wraps the source in UPPER(...)', () => {
        expect(exprFor(field({ name: 'status', from: 'status', verb: 'upper' }))).toBe('UPPER(status)');
    });

    it('cast always emits TRY_CAST, never bare CAST', () => {
        const expr = exprFor(field({ name: 'amount', from: 'amount', verb: 'cast', castType: 'DOUBLE' }));
        expect(expr).toBe('TRY_CAST(amount AS DOUBLE)');
        expect(expr).not.toMatch(/^CAST/);
        expect(expr).not.toContain(' CAST(');
    });

    it('cast defaults to VARCHAR when no type is picked yet', () => {
        expect(exprFor(field({ name: 'x', from: 'x', verb: 'cast' }))).toBe('TRY_CAST(x AS VARCHAR)');
    });

    it('formula emits the verbatim expression text', () => {
        const expr = exprFor(
            field({ name: 'amount_cents', from: '', verb: 'formula', formula: 'ROUND(amount * 100)' }),
        );
        expect(expr).toBe('ROUND(amount * 100)');
    });

    it('an empty formula compiles to NULL rather than dropping the row', () => {
        expect(exprFor(field({ name: 'x', from: '', verb: 'formula' }))).toBe('NULL');
    });
});

describe('pipeline-transform-sql: generateSql framing', () => {
    it('every row emits "<expr> AS <name>"', () => {
        const sql = generateSql([
            field({ name: 'buyer', from: 'customer', verb: 'trim' }),
            field({ name: 'status', from: 'status', verb: 'upper' }),
        ]);
        expect(sql).toContain('TRIM(customer) AS buyer');
        expect(sql).toContain('UPPER(status) AS status');
    });

    it('the statement is always framed as SELECT ... FROM input', () => {
        const sql = generateSql([field({ name: 'a', from: 'a', verb: 'keep' })]);
        expect(sql.startsWith('SELECT')).toBe(true);
        expect(sql.trimEnd().endsWith('FROM input')).toBe(true);
    });

    it('an empty field list still produces a valid statement', () => {
        expect(generateSql([])).toBe('SELECT * FROM input');
    });

    it('editing a field Name rewrites exactly that alias and nothing else', () => {
        const fields: SqlField[] = [
            field({ name: 'order_id', from: 'order_id', verb: 'keep' }),
            field({ name: 'buyer', from: 'customer', verb: 'trim' }),
            field({ name: 'status', from: 'status', verb: 'upper' }),
        ];
        const before = generateSql(fields);

        const renamed = fields.map((f) => (f.name === 'buyer' ? { ...f, name: 'customer_name' } : f));
        const after = generateSql(renamed);

        // Every other row's alias is byte-identical between the two statements.
        expect(after).toContain('order_id AS order_id');
        expect(after).toContain('UPPER(status) AS status');
        // Only the touched row's alias changed, and its expression (source + verb) did not.
        expect(before).toContain('TRIM(customer) AS buyer');
        expect(after).toContain('TRIM(customer) AS customer_name');
        expect(after).not.toContain('AS buyer');
        // Nothing else in the statement differs but the one alias.
        expect(after.replace('customer_name', 'buyer')).toBe(before);
    });
});

describe('pipeline-transform-sql: seeding a new step', () => {
    it('produces one Keep row per upstream column, in order', () => {
        const fields = seedFields(['order_id', 'customer', 'amount', 'order_date']);
        expect(fields.map((f) => f.name)).toEqual(['order_id', 'customer', 'amount', 'order_date']);
        expect(fields.every((f) => f.verb === 'keep')).toBe(true);
        expect(fields.every((f, i) => f.from === ['order_id', 'customer', 'amount', 'order_date'][i])).toBe(true);
    });

    it('an empty upstream schema seeds an empty grid, not a placeholder row', () => {
        expect(seedFields([])).toEqual([]);
    });
});

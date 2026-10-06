import { describe, expect, it } from 'vitest';
import { describeGroup, supportsIgnoreCase, supportsValueField, validateCondition } from './condition-rules';
import { compileSql, compileSqlWithParams } from './query-sql';
import { ColumnMeta, Condition, ConditionGroup, QueryModel, QuerySource } from './query-types';

const COLS: ColumnMeta[] = [
    { name: 'a', type: 'string' },
    { name: 'b', type: 'string' },
    { name: 'n', type: 'number' },
];
const SRC: QuerySource = { name: 't', rows: [], columns: COLS };
const leaf = (p: Partial<Condition>): Condition => ({ kind: 'condition', field: 'a', operator: '=', ...p });
const grp = (items: ConditionGroup['items'], p: Partial<ConditionGroup> = {}): ConditionGroup => ({
    kind: 'group',
    op: 'AND',
    items,
    ...p,
});
const where = (g: ConditionGroup): string =>
    compileSql({ projection: '*', where: g } as QueryModel, SRC).split('WHERE ')[1];

describe('operator support sets', () => {
    it('mirror the Java sets', () => {
        expect(supportsValueField('contains')).toBe(true);
        expect(supportsValueField('in')).toBe(false);
        expect(supportsValueField('matches')).toBe(false);
        expect(supportsIgnoreCase('matches')).toBe(true);
        expect(supportsIgnoreCase('<')).toBe(false);
        expect(supportsIgnoreCase('isNull')).toBe(false);
    });
});

describe('validateCondition', () => {
    it('accepts a plain condition and a simple pattern', () => {
        expect(validateCondition(leaf({ value: 'x' }))).toBeNull();
        expect(validateCondition(leaf({ operator: 'matches', value: '^ab+c$' }))).toBeNull();
    });
    it('refuses value + valueField, and valueField on a wrong operator', () => {
        expect(validateCondition(leaf({ valueField: 'b', value: 'x' }))).toMatch(/not both/);
        expect(validateCondition(leaf({ operator: 'in', valueField: 'b' }))).toMatch(/another field only works/);
    });
    it('refuses ignoreCase on an ordering operator', () => {
        expect(validateCondition(leaf({ operator: '<', value: '1', ignoreCase: true }))).toMatch(/Ignore case/);
    });
    it('refuses over-long, non-RE2 and uncompilable patterns', () => {
        expect(validateCondition(leaf({ operator: 'matches', value: 'a'.repeat(257) }))).toMatch(/256/);
        for (const p of ['(?=x)', '(?!x)', '(?<=x)', '(?<!x)', '(?>x)', '(a)\\1', 'a++', 'a*+', 'a?+'])
            expect(validateCondition(leaf({ operator: 'matches', value: p })), p).toMatch(/lookahead/);
        expect(validateCondition(leaf({ operator: 'matches', value: '(' }))).toMatch(/Not a valid/);
    });
    it('leaves a blank pattern alone (still being built)', () => {
        expect(validateCondition(leaf({ operator: 'matches', value: '' }))).toBeNull();
    });
});

describe('describeGroup', () => {
    it('says NOT (…) for a negated group and words for operators', () => {
        const g = grp(
            [leaf({ value: '1' }), leaf({ field: 'b', operator: 'contains', value: 'x', ignoreCase: true })],
            {
                negate: true,
            },
        );
        expect(describeGroup(g)).toBe('NOT (a equals 1 and b contains x, ignoring case)');
    });
    it('describes field-to-field and skips incomplete conditions', () => {
        const g = grp([leaf({ operator: '<', valueField: 'b' }), leaf({ value: '' })]);
        expect(describeGroup(g)).toBe('a is less than the value of b');
        expect(describeGroup(grp([]))).toBe('no conditions');
    });
});

describe('SQL preview of the extensions (Java ConditionSql shapes)', () => {
    it('wraps NOT as (NOT COALESCE((…), FALSE)) at root and nested, without double parentheses', () => {
        expect(where(grp([leaf({ value: 'x' })], { negate: true }))).toBe(`(NOT COALESCE(("a" = 'x'), FALSE))`);
        expect(where(grp([leaf({ value: 'x' }), grp([leaf({ field: 'b', value: 'y' })], { negate: true })]))).toBe(
            `"a" = 'x' AND (NOT COALESCE(("b" = 'y'), FALSE))`,
        );
    });
    it('keeps an empty negated group out of the SQL', () => {
        expect(compileSql({ projection: '*', where: grp([], { negate: true }) } as QueryModel, SRC)).toBe(
            'SELECT *\nFROM "t"',
        );
    });
    it('renders matches via regexp_matches with quote escaping and the i flag', () => {
        expect(where(grp([leaf({ operator: 'matches', value: "it's" })]))).toBe(
            `regexp_matches(CAST("a" AS VARCHAR), 'it''s')`,
        );
        expect(where(grp([leaf({ operator: 'matches', value: 'x', ignoreCase: true })]))).toBe(
            `regexp_matches(CAST("a" AS VARCHAR), 'x', 'i')`,
        );
    });
    it('renders ignoreCase through LOWER on the string path', () => {
        expect(where(grp([leaf({ value: 'AbC', ignoreCase: true })]))).toBe(`LOWER(CAST("a" AS VARCHAR)) = 'abc'`);
        expect(where(grp([leaf({ operator: '!=', value: 'AbC', ignoreCase: true })]))).toBe(
            `LOWER(CAST("a" AS VARCHAR)) <> 'abc'`,
        );
        expect(where(grp([leaf({ operator: 'in', value: 'A, b', ignoreCase: true })]))).toBe(
            `(LOWER(CAST("a" AS VARCHAR)) = 'a' OR LOWER(CAST("a" AS VARCHAR)) = 'b')`,
        );
        expect(where(grp([leaf({ operator: 'contains', value: '50%_', ignoreCase: true })]))).toBe(
            `LOWER(CAST("a" AS VARCHAR)) LIKE '%50\\%\\_%' ESCAPE '\\'`,
        );
    });
    it('renders field-to-field with quoted identifiers (hostile names doubled)', () => {
        const sql = where(grp([leaf({ field: 'a"x', operator: '>=', valueField: 'b' })]));
        expect(sql).toContain('CAST("a""x" AS VARCHAR)');
        expect(sql).toContain('CAST("b" AS VARCHAR)');
        expect(sql).toContain('CASE WHEN regexp_full_match');
        expect(where(grp([leaf({ operator: 'contains', valueField: 'b' })]))).toBe(
            `(LOWER(CAST("a" AS VARCHAR)) LIKE '%' || REPLACE(REPLACE(REPLACE(LOWER(CAST("b" AS VARCHAR)), '\\', '\\\\'), '%', '\\%'), '_', '\\_') || '%' ESCAPE '\\')`,
        );
    });
    it('the parameterised compiler carries the same shapes (no binds for them)', () => {
        const r = compileSqlWithParams(
            {
                projection: '*',
                where: grp([leaf({ operator: 'matches', value: 'x' })], { negate: true }),
            } as QueryModel,
            SRC,
        );
        expect(r.params).toEqual([]);
        expect(r.sql).toContain(`(NOT COALESCE((regexp_matches(CAST("a" AS VARCHAR), 'x')), FALSE))`);
    });
    it('leaves untouched conditions byte-identical', () => {
        expect(where(grp([leaf({ operator: 'contains', value: 'x' })]))).toBe(`"a" LIKE '%x%'`);
    });
});

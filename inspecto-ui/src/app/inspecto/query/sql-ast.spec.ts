import { describe, expect, it } from 'vitest';
import SQL_AST_CONTRACT from 'app/inspecto/contracts/sql-ast.contract.json';
import { compileWhere } from './query-sql';
import {
    LIKE_FUNCTIONS,
    READ_NODE_TYPES,
    SqlAstNode,
    astToConditionGroup,
    hasSqlComment,
    predicateRows,
    sameSqlStructure,
} from './sql-ast';

// ── Fixtures: the exact shapes DuckDB's json_serialize_sql emits (measured on 1.5.x, and the key names
//    pinned against the real engine by SqlAstContractTest). `query_location` values are arbitrary here —
//    the reader never looks at them.
const col = (...names: string[]): SqlAstNode => ({
    class: 'COLUMN_REF',
    type: 'COLUMN_REF',
    alias: '',
    query_location: 1,
    column_names: names,
});
const constant = (id: string, value: unknown, typeInfo: unknown = null): SqlAstNode => ({
    class: 'CONSTANT',
    type: 'VALUE_CONSTANT',
    alias: '',
    query_location: 2,
    value: { type: { id, type_info: typeInfo }, is_null: false, value },
});
const int = (v: number) => constant('INTEGER', v);
const str = (v: string) => constant('VARCHAR', v);
const bool = (v: boolean): SqlAstNode => ({
    class: 'CAST',
    type: 'OPERATOR_CAST',
    alias: '',
    query_location: 2 ** 64 - 1, // DuckDB's "no location" — beyond what a JS number holds exactly
    child: str(v ? 't' : 'f'),
    cast_type: { id: 'BOOLEAN', type_info: null },
    try_cast: false,
});
const cmp = (type: string, left: SqlAstNode, right: SqlAstNode): SqlAstNode => ({
    class: 'COMPARISON',
    type,
    alias: '',
    query_location: 3,
    left,
    right,
});
const op = (type: string, ...children: SqlAstNode[]): SqlAstNode => ({
    class: 'OPERATOR',
    type,
    alias: '',
    query_location: 4,
    children,
});
const and = (...children: SqlAstNode[]): SqlAstNode => ({
    class: 'CONJUNCTION',
    type: 'CONJUNCTION_AND',
    alias: '',
    query_location: 5,
    children,
});
const or = (...children: SqlAstNode[]): SqlAstNode => ({ ...and(...children), type: 'CONJUNCTION_OR' });
const between = (input: SqlAstNode, lower: SqlAstNode, upper: SqlAstNode): SqlAstNode => ({
    class: 'BETWEEN',
    type: 'COMPARE_BETWEEN',
    alias: '',
    query_location: 6,
    input,
    lower,
    upper,
});
const fn = (name: string, ...children: SqlAstNode[]): SqlAstNode => ({
    class: 'FUNCTION',
    type: 'FUNCTION',
    alias: '',
    query_location: 7,
    function_name: name,
    schema: '',
    children,
    filter: null,
    is_operator: true,
});

describe('sql-ast reader vs the committed contract (Q5)', () => {
    it('handles exactly the node types the contract pins — no more, no fewer', () => {
        const pinned = new Set(SQL_AST_CONTRACT.nodes.map((n) => n.type));
        expect([...READ_NODE_TYPES].sort()).toEqual([...pinned].sort());
    });

    it('recognises exactly the LIKE-family function names the contract pins', () => {
        expect([...LIKE_FUNCTIONS].sort()).toEqual(SQL_AST_CONTRACT.likeFunctions.map((f) => f.name).sort());
    });
});

describe('predicateRows — the read-only table (design Step 2)', () => {
    it('reads the committed Filter Step predicate STATUS = SHIPPED AND GROSS >= 30', () => {
        const rows = predicateRows(
            and(
                cmp('COMPARE_EQUAL', col('STATUS'), str('SHIPPED')),
                cmp('COMPARE_GREATERTHANOREQUALTO', col('GROSS'), int(30)),
            ),
        );
        expect(rows).toEqual([
            { level: 1, joiner: null, kind: 'condition', left: 'STATUS', operator: '=', right: "'SHIPPED'" },
            { level: 1, joiner: 'AND', kind: 'condition', left: 'GROSS', operator: '≥', right: '30' },
        ]);
    });

    it('a single comparison is one row', () => {
        expect(predicateRows(cmp('COMPARE_LESSTHAN', col('a'), int(1)))).toEqual([
            { level: 1, joiner: null, kind: 'condition', left: 'a', operator: '<', right: '1' },
        ]);
    });

    it('nests a group one level deeper, headed by its own row', () => {
        const rows = predicateRows(
            and(
                cmp('COMPARE_EQUAL', col('a'), int(1)),
                or(op('OPERATOR_IS_NULL', col('b')), cmp('COMPARE_NOTEQUAL', col('b'), str('x'))),
            ),
        );
        expect(rows.map((r) => [r.level, r.joiner, r.kind, r.groupOp ?? r.operator])).toEqual([
            [1, null, 'condition', '='],
            [1, 'AND', 'group', 'OR'],
            [2, null, 'condition', 'is null'],
            [2, 'OR', 'condition', '≠'],
        ]);
    });

    it('renders IN, BETWEEN, LIKE, decimals, booleans, NULL and qualified names', () => {
        const nul: SqlAstNode = {
            class: 'CONSTANT',
            type: 'VALUE_CONSTANT',
            value: { type: { id: 'NULL', type_info: null }, is_null: true },
        };
        const rows = predicateRows(
            and(
                op('COMPARE_IN', col('a'), int(1), int(2)),
                between(col('b'), int(1), int(5)),
                fn('!~~', col('c'), str('x%')),
                cmp('COMPARE_EQUAL', col('d'), constant('DECIMAL', 15, { scale: 1 })),
                cmp('COMPARE_EQUAL', col('e'), bool(false)),
                cmp('COMPARE_EQUAL', col('t', 'f'), nul),
            ),
        );
        expect(rows.map((r) => `${r.left} ${r.operator} ${r.right}`)).toEqual([
            'a in 1, 2',
            'b between 1 and 5',
            "c not like 'x%'",
            'd = 1.5',
            'e = FALSE',
            't.f = NULL',
        ]);
    });

    it('marks a part it cannot show as such — the row is kept, never dropped', () => {
        const rows = predicateRows(
            and(
                cmp('COMPARE_EQUAL', fn('lower', col('a')), str('x')),
                op('OPERATOR_NOT', cmp('COMPARE_EQUAL', col('b'), int(1))),
            ),
        );
        expect(rows.map((r) => r.kind)).toEqual(['unshown', 'unshown']);
    });
});

describe('astToConditionGroup — bounded recognition (design Step 3)', () => {
    it('recognises the committed predicate, with column types taken from the literals', () => {
        const r = astToConditionGroup(
            and(
                cmp('COMPARE_EQUAL', col('STATUS'), str('SHIPPED')),
                cmp('COMPARE_GREATERTHANOREQUALTO', col('GROSS'), int(30)),
            ),
        );
        if ('unsupported' in r) throw new Error(r.unsupported);
        expect(r.group).toEqual({
            kind: 'group',
            op: 'AND',
            items: [
                { kind: 'condition', field: 'STATUS', operator: '=', value: 'SHIPPED' },
                { kind: 'condition', field: 'GROSS', operator: '>=', value: '30' },
            ],
        });
        expect(r.columns).toEqual([
            { name: 'STATUS', type: 'string' },
            { name: 'GROSS', type: 'number' },
        ]);
        // …and the Query Core regenerates the SAME predicate (the typed literals are why the types matter).
        expect(compileWhere(r.group, r.columns)).toBe('"STATUS" = \'SHIPPED\' AND "GROSS" >= 30');
    });

    it('maps LIKE patterns onto contains / starts with / ends with', () => {
        const r = astToConditionGroup(
            or(fn('~~', col('a'), str('%x%')), fn('~~', col('a'), str('x%')), fn('~~', col('a'), str('%x'))),
        );
        if ('unsupported' in r) throw new Error(r.unsupported);
        expect(r.group.items.map((c) => ('operator' in c ? c.operator : ''))).toEqual([
            'contains',
            'startsWith',
            'endsWith',
        ]);
    });

    it('recognises IN, BETWEEN, IS NULL, a boolean, and a nested group', () => {
        const r = astToConditionGroup(
            and(
                op('COMPARE_IN', col('a'), int(1), int(2)),
                between(col('b'), int(1), int(5)),
                or(op('OPERATOR_IS_NOT_NULL', col('c')), cmp('COMPARE_EQUAL', col('d'), bool(true))),
            ),
        );
        if ('unsupported' in r) throw new Error(r.unsupported);
        expect(compileWhere(r.group, r.columns)).toBe(
            '"a" IN (1, 2) AND "b" BETWEEN 1 AND 5 AND ("c" IS NOT NULL OR "d" = TRUE)',
        );
    });

    it.each([
        ['a function call', cmp('COMPARE_EQUAL', fn('lower', col('a')), str('x'))],
        ['a NOT', op('OPERATOR_NOT', cmp('COMPARE_EQUAL', col('a'), int(1)))],
        ['a value on the left', cmp('COMPARE_EQUAL', int(1), col('a'))],
        ['a qualified name', cmp('COMPARE_EQUAL', col('t', 'a'), int(1))],
        ['a wildcard mid-pattern', fn('~~', col('a'), str('x%y'))],
        ['NOT LIKE', fn('!~~', col('a'), str('x%'))],
        ['NOT IN', op('COMPARE_NOT_IN', col('a'), int(1))],
        ['text compared with <', cmp('COMPARE_LESSTHAN', col('a'), str('x'))],
        [
            'one column as text AND number',
            and(cmp('COMPARE_EQUAL', col('a'), str('x')), cmp('COMPARE_EQUAL', col('a'), int(1))),
        ],
        ['a list value holding a comma', op('COMPARE_IN', col('a'), str('x,y'))],
        ['a NULL literal', cmp('COMPARE_EQUAL', col('a'), constant('NULL', null))],
    ])('refuses %s rather than guessing', (_label, ast) => {
        expect('unsupported' in astToConditionGroup(ast)).toBe(true);
    });
});

describe('sameSqlStructure — the admission test for editing (design Step 3/4)', () => {
    it('ignores source offsets (a re-spelled predicate has different ones)', () => {
        const a = cmp('COMPARE_EQUAL', col('a'), int(1));
        const b = { ...cmp('COMPARE_EQUAL', { ...col('a'), query_location: 99 }, int(1)), query_location: 42 };
        expect(sameSqlStructure(a, b)).toBe(true);
    });

    it('sees a changed operator, value or type', () => {
        const a = cmp('COMPARE_EQUAL', col('a'), int(1));
        expect(sameSqlStructure(a, cmp('COMPARE_NOTEQUAL', col('a'), int(1)))).toBe(false);
        expect(sameSqlStructure(a, cmp('COMPARE_EQUAL', col('a'), int(2)))).toBe(false);
        expect(sameSqlStructure(a, cmp('COMPARE_EQUAL', col('a'), str('1')))).toBe(false);
    });
});

describe('hasSqlComment (Q2 — a structured edit would delete the author’s comments)', () => {
    it.each([
        ['a > 1 -- only big ones', true],
        ['a > 1 /* big */', true],
        ["a = '--not a comment'", false],
        ['"odd--name" > 1', false],
        ["a = 'it''s' -- note", true],
        ['a > 1', false],
    ])('%s → %s', (text, expected) => {
        expect(hasSqlComment(text)).toBe(expected);
    });
});

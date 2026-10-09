import { describe, expect, it } from 'vitest';
import { evaluateRows } from './query-eval';
import { ColumnMeta, ConditionGroup, QueryModel, QuerySource } from './query-types';

const COLS: ColumnMeta[] = [
    { name: 'id', type: 'number' },
    { name: 'msisdn', type: 'string' },
    { name: 'dur', type: 'number' },
    { name: 'cell', type: 'string' },
];
const ROWS = [
    { id: 1, msisdn: '8801', dur: 30, cell: 'A' },
    { id: 2, msisdn: '8802', dur: 120, cell: 'B' },
    { id: 3, msisdn: '9903', dur: 90, cell: 'A' },
];
const SOURCE: QuerySource = { name: 'cdr', rows: ROWS, columns: COLS };

function m(where: ConditionGroup, projection: string[] | '*' = '*'): QueryModel {
    return { projection, where, sqlOverride: null };
}
function group(op: 'AND' | 'OR', items: unknown[]): ConditionGroup {
    return { kind: 'group', op, items: items as ConditionGroup['items'] };
}
const ids = (rows: Record<string, unknown>[]) => rows.map((r) => r['id']);

describe('evaluateRows', () => {
    it('no filter ⇒ all rows', () => {
        expect(evaluateRows(m(group('AND', [])), SOURCE).length).toBe(3);
    });

    it('numeric >=', () => {
        expect(
            ids(
                evaluateRows(
                    m(group('AND', [{ kind: 'condition', field: 'dur', operator: '>=', value: '90' }])),
                    SOURCE,
                ),
            ),
        ).toEqual([2, 3]);
    });

    it('evaluates a nested (A AND (B OR C)) exactly', () => {
        const where = group('AND', [
            { kind: 'condition', field: 'cell', operator: '=', value: 'A' },
            group('OR', [
                { kind: 'condition', field: 'dur', operator: '<', value: '40' },
                { kind: 'condition', field: 'msisdn', operator: 'startsWith', value: '99' },
            ]),
        ]);
        expect(ids(evaluateRows(m(where), SOURCE))).toEqual([1, 3]);
    });

    it('projection narrows the returned columns', () => {
        const r = evaluateRows(m(group('AND', []), ['id', 'cell']), SOURCE);
        expect(Object.keys(r[0])).toEqual(['id', 'cell']);
    });

    it('in + contains under OR', () => {
        const where = group('OR', [
            { kind: 'condition', field: 'cell', operator: 'in', value: 'B' },
            { kind: 'condition', field: 'msisdn', operator: 'contains', value: '9903' },
        ]);
        expect(ids(evaluateRows(m(where), SOURCE))).toEqual([2, 3]);
    });

    it('ignores still-incomplete conditions', () => {
        const where = group('AND', [{ kind: 'condition', field: 'cell', operator: '=', value: '' }]);
        expect(evaluateRows(m(where), SOURCE).length).toBe(3);
    });
});

/** The Condition Language extensions — same rows and expectations as Java ConditionExtensionsTest. */
describe('evaluateRows — Condition Language extensions', () => {
    const X_COLS: ColumnMeta[] = [
        { name: 'id', type: 'number' },
        { name: 'a', type: 'string' },
        { name: 'b', type: 'string' },
        { name: 'n1', type: 'number' },
        { name: 'n2', type: 'number' },
        { name: 'd1', type: 'string' },
        { name: 'd2', type: 'string' },
        { name: 'tag', type: 'string' },
    ];
    const X_ROWS = [
        { id: 1, a: 'Alpha', b: 'alpha', n1: 5, n2: 5, d1: '2026-07-01', d2: '2026-07-01', tag: 'x-1' },
        { id: 2, a: 'Beta', b: 'Gamma', n1: 10, n2: 3, d1: '2026-07-02', d2: '2026-07-05', tag: 'y-2' },
        { id: 3, a: 'Gamma', b: 'gam', n1: 2, n2: 9, d1: '2026-07-09', d2: '2026-07-03', tag: null },
        { id: 4, a: null, b: 'z', n1: null, n2: 4, d1: null, d2: '2026-01-01', tag: 'x-3' },
        { id: 5, a: '', b: '', n1: 7, n2: 7, d1: '2026-07-01T10:00:00', d2: '2026-07-01T10:00:00', tag: 'x-77' },
    ];
    const XS: QuerySource = { name: 'x', rows: X_ROWS, columns: X_COLS };
    const run = (...items: unknown[]) => ids(evaluateRows(m(group('AND', items)), XS));
    const c = (field: string, operator: string, value?: string, extra: object = {}) => ({
        kind: 'condition',
        field,
        operator,
        ...(value === undefined ? {} : { value }),
        ...extra,
    });
    const ff = (field: string, operator: string, valueField: string, extra: object = {}) =>
        c(field, operator, undefined, { valueField, ...extra });
    const neg = (...items: unknown[]) => ({ ...group('AND', items), negate: true });

    it('negate inverts a group; a NULL cell is "not > 3" so it is selected', () => {
        expect(run(c('n1', '>', '3'))).toEqual([1, 2, 5]);
        expect(run(neg(c('n1', '>', '3')))).toEqual([3, 4]);
        expect(
            ids(evaluateRows(m({ ...group('OR', [c('a', '=', 'Alpha'), c('n2', '>', '8')]), negate: true }), XS)),
        ).toEqual([2, 4, 5]);
    });

    it('an empty or incomplete negated group still imposes no constraint; negate:false is plain', () => {
        expect(run(neg())).toEqual([1, 2, 3, 4, 5]);
        expect(run(neg(c('a', '=', '')))).toEqual([1, 2, 3, 4, 5]);
        expect(run({ ...group('AND', [c('n1', '>', '3')]), negate: false })).toEqual([1, 2, 5]);
    });

    it('field-to-field: numbers as numbers, dates as instants, strings as strings', () => {
        expect(run(ff('n1', '>', 'n2'))).toEqual([2]);
        expect(run(ff('n1', '=', 'n2'))).toEqual([1, 5]);
        expect(run(ff('n1', '!=', 'n2'))).toEqual([2, 3]);
        expect(run(ff('d1', '<', 'd2'))).toEqual([2]);
        expect(run(ff('a', '=', 'b'))).toEqual([5]);
        expect(run(ff('a', '<', 'b'))).toEqual([1, 2, 3]);
        expect(run(ff('a', 'contains', 'b'))).toEqual([1, 3, 5]);
        expect(run(ff('a', 'endsWith', 'b'))).toEqual([1, 5]);
    });

    it('ignoreCase on =, !=, in; absent flag stays case-sensitive', () => {
        expect(run(c('a', '=', 'ALPHA'))).toEqual([]);
        expect(run(c('a', '=', 'ALPHA', { ignoreCase: true }))).toEqual([1]);
        expect(run(c('a', '!=', 'alpha', { ignoreCase: true }))).toEqual([2, 3, 5]);
        expect(run(c('a', 'in', 'ALPHA, beta', { ignoreCase: true }))).toEqual([1, 2]);
        expect(run(ff('a', '=', 'b', { ignoreCase: true }))).toEqual([1, 5]);
    });

    it('matches is a partial regexp match, optionally case-insensitive', () => {
        expect(run(c('a', 'matches', 'mm'))).toEqual([3]);
        expect(run(c('tag', 'matches', String.raw`^x-\d+$`))).toEqual([1, 4, 5]);
        expect(run(c('n1', 'matches', '^1'))).toEqual([2]);
        expect(run(c('a', 'matches', '^alpha$'))).toEqual([]);
        expect(run(c('a', 'matches', '^alpha$', { ignoreCase: true }))).toEqual([1]);
        expect(run(neg(c('tag', 'matches', '^x-')))).toEqual([2, 3]);
    });

    it('a pattern that will not compile matches nothing instead of throwing (Java validates at save)', () => {
        expect(run(c('a', 'matches', '(['))).toEqual([]);
    });

    it("'' counts as null for isNull — pinned (the inspecto-util Conditions text notation differs)", () => {
        expect(run(c('a', 'isNull'))).toEqual([4, 5]);
        expect(run(c('a', 'isNotNull'))).toEqual([1, 2, 3]);
    });
});

import { describe, expect, it } from 'vitest';
import type { ConditionGroup } from '@inspecto/core/query/query-types';
import {
    NO_ROLE_FILTERS,
    RoleFilters,
    categoryValuesSql,
    roleFilterConditions,
    roleFilterErrors,
    withRoleFilters,
} from './role-filters';

const f = (over: Partial<RoleFilters>): RoleFilters => ({ ...structuredClone(NO_ROLE_FILTERS), ...over });

describe('role filters (LA-COLUMN-ROLES-1 P3)', () => {
    it('a Measure range, a Category list and a Time window become conditions of the predicate tree', () => {
        expect(
            roleFilterConditions(
                f({
                    measures: {
                        amount: { min: '100', max: ' 600 ' },
                        fee: { min: '', max: '5' },
                        qty: { min: '2', max: '' },
                    },
                    categories: { channel: ['mobile', 'online', 'a,b'], status: [] },
                    time: { column: 'booked_at', from: '2026-01-01T00:00', to: '2026-02-01T00:00' },
                }),
            ),
        ).toEqual([
            { kind: 'condition', field: 'amount', operator: 'between', value: '100', value2: '600' },
            { kind: 'condition', field: 'fee', operator: '<=', value: '5' },
            { kind: 'condition', field: 'qty', operator: '>=', value: '2' },
            // a value holding a comma cannot travel in the `in` list
            { kind: 'condition', field: 'channel', operator: 'in', value: 'mobile,online' },
            { kind: 'condition', field: 'booked_at', operator: '>=', value: '2026-01-01T00:00' },
            { kind: 'condition', field: 'booked_at', operator: '<', value: '2026-02-01T00:00' },
        ]);
        expect(roleFilterConditions(NO_ROLE_FILTERS)).toEqual([]);
    });

    it('refuses a bound that is no number, a range upside down and a window that ends before it starts', () => {
        expect(
            roleFilterErrors(
                f({
                    measures: { a: { min: 'x', max: '' }, b: { min: '9', max: '3' }, c: { min: '1', max: '2' } },
                    time: { column: 't', from: '2026-02-01T00:00', to: '2026-01-01T00:00' },
                }),
            ),
        ).toEqual({
            'measure.a': 'Type a number.',
            'measure.b': 'The lowest must not be above the highest.',
            time: 'The window must end after it starts.',
        });
    });

    it('replaces only the conditions it wrote, never the analyst own (on a role column too), and ANDs onto an OR tree', () => {
        const mine = f({ measures: { amount: { min: '1', max: '' } } });
        const tree: ConditionGroup = {
            kind: 'group',
            op: 'AND',
            items: [
                { kind: 'condition', field: 'amount', operator: '>=', value: '1' }, // the panel's last: replaced
                { kind: 'condition', field: 'amount', operator: '<', value: '900' }, // the analyst's, on a role column: kept
                { kind: 'condition', field: 'note', operator: 'contains', value: 'x' },
            ],
        };
        const next = f({ measures: { amount: { min: '5', max: '' } } });
        expect(withRoleFilters(tree, next, mine).items).toEqual([
            { kind: 'condition', field: 'amount', operator: '<', value: '900' },
            { kind: 'condition', field: 'note', operator: 'contains', value: 'x' },
            { kind: 'condition', field: 'amount', operator: '>=', value: '5' },
        ]);
        expect(withRoleFilters(tree, NO_ROLE_FILTERS, mine).items).toHaveLength(2); // cleared: only the panel's went
        // an OR tree keeps its meaning: OR(amount > 1, note ~ x) AND amount >= 5
        const or: ConditionGroup = {
            kind: 'group',
            op: 'OR',
            items: [
                { kind: 'condition', field: 'amount', operator: '>', value: '1' },
                { kind: 'condition', field: 'note', operator: 'contains', value: 'x' },
            ],
        };
        expect(withRoleFilters(or, next, NO_ROLE_FILTERS)).toEqual({
            kind: 'group',
            op: 'AND',
            items: [or, { kind: 'condition', field: 'amount', operator: '>=', value: '5' }],
        });
    });

    it('reads a Category column distinct values with quoted names, at most the Category bound', () => {
        expect(categoryValuesSql('tx"s', 'chan nel')).toBe(
            'SELECT DISTINCT CAST("chan nel" AS VARCHAR) AS v FROM "tx""s" WHERE "chan nel" IS NOT NULL ORDER BY 1 LIMIT 200',
        );
    });
});

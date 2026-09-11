import { describe, expect, it } from 'vitest';

import COLUMN_ROLE_CONTRACT from 'app/inspecto/contracts/column-role.contract.json';

import { roleFor } from './result-set';
import { inferRoles } from 'app/modules/admin/studio/datasets/dataset-types';
import type { ColumnMeta, ColumnType } from 'app/inspecto/query';

/**
 * The client half of the column-role contract. `ColumnRoleContractTest` (Java) compares the same
 * committed JSON against `ResultSetDescriptor.roleFor`, so neither language can move alone.
 *
 * ⚠ The rule used to exist in TWO client copies — here and in the Studio's `dataset-types.ts`, identical
 * down to the `(^|_)id$` regex — plus the Java one, with nothing comparing any of them
 * (`TYPEFLOW-DATASET-COLUMNS-1` step 1). The client copies were collapsed into `result-set.ts`; the test
 * below drives BOTH the shared function and `inferRoles` so the collapse cannot quietly come apart.
 */
describe('column role contract', () => {
    it('assigns the contract role to every published case', () => {
        // Pinned so a silently-emptied contract cannot turn this suite into a vacuous pass.
        expect(COLUMN_ROLE_CONTRACT.cases.length).toBeGreaterThanOrEqual(12);

        for (const c of COLUMN_ROLE_CONTRACT.cases) {
            expect(roleFor(c.name, c.type as ColumnType), `role for ${c.name}:${c.type}`).toBe(c.role);
        }
    });

    it('routes inferRoles through the same shared rule, not a second copy', () => {
        const columns = COLUMN_ROLE_CONTRACT.cases.map((c) => ({
            name: c.name,
            type: c.type as ColumnType,
        })) as ColumnMeta[];

        const inferred = inferRoles(columns);
        expect(inferred.map((c) => c.role)).toEqual(COLUMN_ROLE_CONTRACT.cases.map((c) => c.role));
    });

    it('publishes only roles it can actually produce, and exercises every one', () => {
        const produced = new Set(COLUMN_ROLE_CONTRACT.cases.map((c) => roleFor(c.name, c.type as ColumnType)));
        for (const role of produced) expect(COLUMN_ROLE_CONTRACT.roles).toContain(role);
        // An unexercised published role is an unpinned one.
        for (const role of COLUMN_ROLE_CONTRACT.roles) expect(produced).toContain(role);
    });

    it('anchors the id heuristic so a word merely ending in "id" stays a measure', () => {
        // 🔴 `(^|_)id$`, not `id$`. Loosening the anchor turns `paid` into a dimension, which simply
        // removes it from every chart's measure picker — no error, just a column that stopped existing.
        expect(COLUMN_ROLE_CONTRACT.idColumn).toBe('(^|_)id$');
        expect(roleFor('paid', 'number')).toBe('measure');
        expect(roleFor('valid', 'number')).toBe('measure');
        expect(roleFor('id', 'number')).toBe('dimension');
        expect(roleFor('order_id', 'number')).toBe('dimension');
    });

    it('applies the id heuristic to the number branch only', () => {
        expect(roleFor('order_id', 'date')).toBe('temporal');
        expect(roleFor('order_id', 'string')).toBe('dimension');
    });
});

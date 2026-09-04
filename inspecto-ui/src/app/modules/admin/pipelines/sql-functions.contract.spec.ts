import { describe, expect, it } from 'vitest';
import contract from 'app/inspecto/contracts/sql-functions.contract.json';
import { SQL_FUNCTIONS, SqlFunction } from './sql-functions';

/**
 * The TS function catalog vs the committed contract written by the Java side
 * (`RecordTransformContractTest`, regenerate with `-Drecord.transform.write=true`).
 *
 * 🔴 **Why this matters more than a normal mirror test.** Since 2026-09-04 BOTH sides compile a field
 * row to SQL: this catalog renders the expression the author sees in the grid and the preview, and
 * `RecordTransform` (Java) renders the one the engine actually executes on both lanes. A silent
 * divergence would show one expression and run another — the exact failure the shared seam exists to
 * prevent. Order is compared too, because the grid's picker is grouped in declaration order.
 */
describe('sql-functions contract', () => {
    /** The TS shape, projected onto the contract's shape — optional keys omitted, exactly as Java emits. */
    function project(fn: SqlFunction): Record<string, unknown> {
        const out: Record<string, unknown> = {
            id: fn.id,
            label: fn.label,
            category: fn.category,
            template: fn.template,
        };
        if (fn.params?.length) {
            out['params'] = fn.params.map((p) => {
                const pm: Record<string, unknown> = { name: p.name, label: p.label, type: p.type };
                if (p.default !== undefined) pm['default'] = p.default;
                if (p.options?.length) pm['options'] = [...p.options];
                if (p.placeholder !== undefined) pm['placeholder'] = p.placeholder;
                if (p.optional) pm['optional'] = true;
                return pm;
            });
        }
        if (fn.help !== undefined) out['help'] = fn.help;
        return out;
    }

    it('matches the Java catalog entry for entry, in order', () => {
        expect(SQL_FUNCTIONS.map(project)).toEqual(contract);
    });

    it('names the same functions the engine can compile', () => {
        // A stored field whose `fn` the engine does not know is refused at compile time, so an id here
        // that Java lacks would author a pipeline the engine then refuses to run.
        const tsIds = SQL_FUNCTIONS.map((f) => f.id);
        const javaIds = (contract as { id: string }[]).map((f) => f.id);
        expect(tsIds).toEqual(javaIds);
    });
});

import { ColumnType } from 'app/inspecto/query';
import { FieldRole } from './viz-types';

/**
 * R3 of the living-operational-system roadmap (§4): the **Result Set** descriptor — a query's output
 * described as semantic columns (name + type + analytic role + cardinality), independent of how it is
 * rendered. The Presentation Network *matches* candidate visualizations against this shape (Show-Me),
 * so "the same result set is rendered differently by metadata alone." It generalizes the ad-hoc field
 * inference the widget builder did inline; both the widget builder and the query editor now read it.
 */

/** One column of a {@link ResultSet}: what to call it, its type, its analytic role, and (for dimensions) its spread. */
export interface ResultColumn {
    name: string;
    type: ColumnType;
    role: FieldRole;
    /** Distinct-value count (dimensions only) — feeds Show-Me's cardinality-aware scoring. */
    cardinality?: number;
}

/** A query result described structurally: its columns + row count. Fed to `recommend()` unchanged. */
export interface ResultSet {
    columns: ResultColumn[];
    rowCount: number;
}

/** Identifier-ish columns (`id`, `*_id`) are dimensions, never measures (you don't aggregate an id). */
function isIdColumn(name: string): boolean {
    return /(^|_)id$/i.test(name);
}

function inferType(sample: unknown): ColumnType {
    if (typeof sample === 'number') return 'number';
    if (typeof sample === 'boolean') return 'boolean';
    if (typeof sample === 'string' && /^\d{4}-\d{2}-\d{2}([T ]|$)/.test(sample)) return 'date';
    return 'string';
}

/**
 * The analytic role of a column from its name and coarse type: temporal for dates, measure for
 * non-id numerics, dimension otherwise.
 *
 * ⚠ **The ONE client-side statement of this rule** (`TYPEFLOW-DATASET-COLUMNS-1` step 1). It lived
 * here and, byte-for-byte again, in the Studio's `dataset-types.ts`, which now delegates to this.
 * Its Java twin is `ResultSetDescriptor.roleFor`, and the two are pinned together by
 * `column-role.contract.json` — a stored Dataset's roles and a live result set's roles must agree,
 * because the widget builder buckets both through the same Show-Me scoring.
 */
export function roleFor(name: string, type: ColumnType): FieldRole {
    if (type === 'date') return 'temporal';
    if (type === 'number' && !isIdColumn(name)) return 'measure';
    return 'dimension';
}

function distinctCount(rows: Record<string, unknown>[], col: string): number {
    return new Set(rows.map((r) => r[col])).size;
}

/**
 * Describe result rows as a {@link ResultSet}: infer each column's type from its first non-null value, its
 * role from that type + name, and (for dimensions) its cardinality. `hints` (matched by column name)
 * override the inferred `type`/`role` — the caller passes the dataset's declared column roles when known,
 * so a query over a typed dataset keeps those roles instead of re-guessing.
 */
export function describeResultSet(rows: Record<string, unknown>[], hints: ResultColumn[] = []): ResultSet {
    const names = rows.length ? Object.keys(rows[0]) : hints.map((h) => h.name);
    const byName = new Map(hints.map((h) => [h.name, h]));
    const columns: ResultColumn[] = names.map((name) => {
        const hint = byName.get(name);
        const sample = rows.find((r) => r[name] != null)?.[name];
        const type = hint?.type ?? inferType(sample);
        const role = hint?.role ?? roleFor(name, type);
        return { name, type, role, cardinality: role === 'dimension' ? distinctCount(rows, name) : undefined };
    });
    return { columns, rowCount: rows.length };
}

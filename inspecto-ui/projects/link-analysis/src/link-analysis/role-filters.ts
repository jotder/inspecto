import type { Condition, ConditionGroup } from '@inspecto/core/query/query-types';

/**
 * **Filter by role** (LA-COLUMN-ROLES-1 P3): a range per Measure, a value list per Category and a window on any Time
 * column, as conditions of the one predicate tree the Link Analysis filter pushes to the server (where it is rendered
 * over the Dataset in SQL, or over the link index's attribute columns when served from it). Framework-free.
 */
export interface RoleFilters {
    /** Per Measure column: the low and high bound as typed ('' = open). */
    measures: Record<string, { min: string; max: string }>;
    /** Per Category column: the values kept (none = no constraint). */
    categories: Record<string, string[]>;
    /** One Time column and its half-open window `[from, to)` as typed ('' = open). */
    time: { column: string; from: string; to: string };
}

export const NO_ROLE_FILTERS: RoleFilters = { measures: {}, categories: {}, time: { column: '', from: '', to: '' } };

/** The Category bound (plan Q-6): a Category column offers at most this many values. */
export const CATEGORY_VALUES_MAX = 200;

/** A DuckDB identifier, double-quoted. */
function ident(name: string): string {
    return `"${name.replace(/"/g, '""')}"`;
}

/** The SQL that reads a Category column's distinct values over `source`, at most {@link CATEGORY_VALUES_MAX}, in order. */
export function categoryValuesSql(source: string, column: string): string {
    const c = ident(column);
    return `SELECT DISTINCT CAST(${c} AS VARCHAR) AS v FROM ${ident(source)} WHERE ${c} IS NOT NULL ORDER BY 1 LIMIT ${CATEGORY_VALUES_MAX}`;
}

/** A number as typed, trimmed; '' when it is no finite number. */
function num(v: string): string {
    const t = v.trim();
    return t !== '' && Number.isFinite(Number(t)) ? t : '';
}

/** Why a typed bound is refused, by field key (`measure.<col>`, `time`); empty when every bound reads. */
export function roleFilterErrors(f: RoleFilters): Record<string, string> {
    const out: Record<string, string> = {};
    for (const [col, r] of Object.entries(f.measures)) {
        if ((r.min.trim() && !num(r.min)) || (r.max.trim() && !num(r.max))) out[`measure.${col}`] = 'Type a number.';
        else if (num(r.min) && num(r.max) && Number(r.min) > Number(r.max))
            out[`measure.${col}`] = 'The lowest must not be above the highest.';
    }
    const { column, from, to } = f.time;
    if (column && from && to && Date.parse(from) >= Date.parse(to))
        out['time'] = 'The window must end after it starts.';
    return out;
}

/**
 * The conditions the role filters stand for: a Measure range (`between`, or `>=` / `<=` when one bound is open), a
 * Category list (`in`; a value holding a comma cannot be listed and is left out), a Time window (`>=` from, `<` to).
 */
export function roleFilterConditions(f: RoleFilters): Condition[] {
    const out: Condition[] = [];
    for (const [field, r] of Object.entries(f.measures)) {
        const lo = num(r.min);
        const hi = num(r.max);
        if (lo && hi) out.push({ kind: 'condition', field, operator: 'between', value: lo, value2: hi });
        else if (lo) out.push({ kind: 'condition', field, operator: '>=', value: lo });
        else if (hi) out.push({ kind: 'condition', field, operator: '<=', value: hi });
    }
    for (const [field, values] of Object.entries(f.categories)) {
        const listed = values.filter((v) => !v.includes(','));
        if (listed.length) out.push({ kind: 'condition', field, operator: 'in', value: listed.join(',') });
    }
    const { column, from, to } = f.time;
    if (column && from) out.push({ kind: 'condition', field: column, operator: '>=', value: from });
    if (column && to) out.push({ kind: 'condition', field: column, operator: '<', value: to });
    return out;
}

/**
 * `tree` with the role filters in it: the conditions the panel wrote last time (`previous`) are taken out wherever they sit
 * at the top level, and {@link roleFilterConditions} of `next` are added. The analyst's own conditions - on a role column
 * too - are never touched. An AND root takes the new conditions beside the analyst's; an OR (or negated) root becomes one
 * group, ANDed with them, so the analyst's tree keeps its meaning.
 */
export function withRoleFilters(tree: ConditionGroup, next: RoleFilters, previous: RoleFilters): ConditionGroup {
    const mine = new Set(roleFilterConditions(previous).map((c) => JSON.stringify(c)));
    const kept = tree.items.filter((i) => i.kind !== 'condition' || !mine.has(JSON.stringify(i)));
    const added = roleFilterConditions(next);
    if (!added.length) return { ...tree, items: kept };
    if (tree.op === 'AND' && !tree.negate) return { ...tree, items: [...kept, ...added] };
    // an OR (or negated) root: the analyst's tree as one group, AND the role conditions
    return { kind: 'group', op: 'AND', items: [...(kept.length ? [{ ...tree, items: kept }] : []), ...added] };
}

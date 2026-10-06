import { columnType } from './query-columns';
import { ColumnMeta, ColumnType, Condition, ConditionGroup, QueryModel, QuerySource } from './query-types';

/**
 * The offline query engine: run the structured model over the source rows in the browser. Used for the live
 * preview while the user builds the filter. (Custom hand-edited SQL is NOT evaluated here — that path shows
 * the sample rows with a "runs on the server" note.)
 */
export function evaluateRows(model: QueryModel, source: QuerySource): Record<string, unknown>[] {
    const cols = source.columns ?? [];
    const matched = source.rows.filter((row) => matchGroup(model.where, row, cols));
    if (model.projection === '*' || model.projection.length === 0) return matched;
    const keep = model.projection;
    return matched.map((row) => {
        const out: Record<string, unknown> = {};
        for (const k of keep) out[k] = row[k];
        return out;
    });
}

function matchGroup(group: ConditionGroup, row: Record<string, unknown>, cols: ColumnMeta[]): boolean {
    const results = group.items
        .filter((it) => it.kind === 'group' || isComplete(it))
        .map((it) => (it.kind === 'group' ? matchGroup(it, row, cols) : matchCondition(it, row, cols)));
    if (results.length === 0) return true; // empty / still-being-built group ⇒ no constraint
    const res = group.op === 'AND' ? results.every(Boolean) : results.some(Boolean);
    return group.negate === true ? !res : res;
}

/** A condition contributes to the predicate only once it has enough input to evaluate. */
export function isComplete(c: Condition): boolean {
    if (!c.field || !c.operator) return false;
    if (c.operator === 'isNull' || c.operator === 'isNotNull') return true;
    if (c.operator === 'between') return !!c.value && !!c.value2;
    if (c.valueField) return true;
    return c.value != null && c.value !== '';
}

function matchCondition(c: Condition, row: Record<string, unknown>, cols: ColumnMeta[]): boolean {
    const raw = row[c.field];
    const t = columnType(cols, c.field);
    if (c.operator === 'isNull') return raw == null || raw === '';
    if (c.operator === 'isNotNull') return raw != null && raw !== '';
    if (raw == null) return false;
    const ic = c.ignoreCase === true;
    if (c.valueField) return matchFields(c.operator, raw, row[c.valueField], ic);
    const s = String(raw).toLowerCase();
    switch (c.operator) {
        case 'matches':
            // Partial match, like DuckDB regexp_matches. Patterns are vetted by validateCondition (Java
            // ConditionTree.validate is the authority); an invalid one simply matches nothing here.
            try {
                return new RegExp(c.value ?? '', ic ? 'iu' : 'u').test(String(raw));
            } catch {
                return false;
            }
        case 'contains':
            return s.includes((c.value ?? '').toLowerCase());
        case 'startsWith':
            return s.startsWith((c.value ?? '').toLowerCase());
        case 'endsWith':
            return s.endsWith((c.value ?? '').toLowerCase());
        case 'in': {
            const items = (c.value ?? '')
                .split(',')
                .map((x) => x.trim())
                .filter(Boolean);
            return items.some((x) => cmp(raw, x, t, ic) === 0);
        }
        case 'between':
            return cmp(raw, c.value ?? '', t) >= 0 && cmp(raw, c.value2 ?? '', t) <= 0;
        case '=':
            return cmp(raw, c.value ?? '', t, ic) === 0;
        case '!=':
            return cmp(raw, c.value ?? '', t, ic) !== 0;
        case '<':
            return cmp(raw, c.value ?? '', t) < 0;
        case '<=':
            return cmp(raw, c.value ?? '', t) <= 0;
        case '>':
            return cmp(raw, c.value ?? '', t) > 0;
        case '>=':
            return cmp(raw, c.value ?? '', t) >= 0;
        default:
            return false;
    }
}

/** Compare a row value with a typed string operand. Returns <0, 0, or >0. */
function cmp(raw: unknown, operand: string, type: ColumnType, ignoreCase = false): number {
    if (type === 'number') {
        const a = Number(raw);
        const b = Number(operand);
        return a === b ? 0 : a < b ? -1 : 1;
    }
    if (type === 'date') {
        const a = Date.parse(String(raw));
        const b = Date.parse(operand);
        if (!isNaN(a) && !isNaN(b)) return a === b ? 0 : a < b ? -1 : 1;
    }
    if (type === 'boolean') {
        const a = raw === true || /^true$/i.test(String(raw));
        const b = /^true$/i.test(operand);
        return a === b ? 0 : a ? 1 : -1;
    }
    const a = ignoreCase ? String(raw).toLowerCase() : String(raw);
    const b = ignoreCase ? operand.toLowerCase() : operand;
    return a === b ? 0 : a < b ? -1 : 1;
}

/**
 * Field-to-field (`valueField`). The right cell has no column type to lean on, so the rule is per row and
 * symmetric — identical to Java `ConditionTree.matchFields` and to the SQL `ConditionSql` emits: both
 * cells numeric ⇒ numbers; else both date-like ⇒ instants; else strings (folded when `ignoreCase`).
 * A null right-hand cell never matches.
 */
function matchFields(operator: string, a: unknown, b: unknown, ic: boolean): boolean {
    if (b == null) return false;
    const sa = String(a).toLowerCase();
    const sb = String(b).toLowerCase();
    switch (operator) {
        case 'contains':
            return sa.includes(sb);
        case 'startsWith':
            return sa.startsWith(sb);
        case 'endsWith':
            return sa.endsWith(sb);
    }
    const d = cmpCells(a, b, ic);
    switch (operator) {
        case '=':
            return d === 0;
        case '!=':
            return d !== 0;
        case '<':
            return d < 0;
        case '<=':
            return d <= 0;
        case '>':
            return d > 0;
        case '>=':
            return d >= 0;
        default:
            return false;
    }
}

const NUMERIC = /^-?\d+(\.\d+)?$/;
const isDateShape = (s: string): boolean => /\d{4}/.test(s) && /[-/:T]/.test(s);

function cmpCells(a: unknown, b: unknown, ic: boolean): number {
    const sa = String(a);
    const sb = String(b);
    if (NUMERIC.test(sa.trim()) && NUMERIC.test(sb.trim())) {
        const x = Number(sa);
        const y = Number(sb);
        return x === y ? 0 : x < y ? -1 : 1;
    }
    if (isDateShape(sa) && isDateShape(sb)) {
        const x = Date.parse(sa);
        const y = Date.parse(sb);
        if (!isNaN(x) && !isNaN(y)) return x === y ? 0 : x < y ? -1 : 1;
    }
    const x = ic ? sa.toLowerCase() : sa;
    const y = ic ? sb.toLowerCase() : sb;
    return x === y ? 0 : x < y ? -1 : 1;
}

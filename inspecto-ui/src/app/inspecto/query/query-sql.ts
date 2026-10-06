import { columnType } from './query-columns';
import { ColumnMeta, ColumnType, Condition, ConditionGroup, Operator, QueryModel, QuerySource } from './query-types';

/**
 * Compile the structured model to an illustrative DuckDB-style SQL string (builder → SQL only). Incomplete
 * conditions are skipped, so the SQL stays valid while the user is still building the filter.
 */
export function compileSql(model: QueryModel, source: QuerySource): string {
    const cols = source.columns ?? [];
    const proj =
        model.projection === '*' || model.projection.length === 0 ? '*' : model.projection.map(quoteIdent).join(', ');
    const from = quoteIdent(source.name);
    const where = compileGroup(model.where, cols);
    return where ? `SELECT ${proj}\nFROM ${from}\nWHERE ${where}` : `SELECT ${proj}\nFROM ${from}`;
}

/**
 * Compile just a WHERE body (no `WHERE` keyword) from a condition group — the reusable predicate compiler
 * the Studio QuerySpec compiler shares with {@link compileSql}. Empty when the group has no complete leaves.
 */
export function compileWhere(where: ConditionGroup, cols: ColumnMeta[]): string {
    return compileGroup(where, cols);
}

function compileGroup(group: ConditionGroup, cols: ColumnMeta[]): string {
    const parts = group.items
        .map((it) => (it.kind === 'group' ? wrap(compileGroup(it, cols), it) : compileCondition(it, cols)))
        .filter((s) => s.length > 0);
    return negated(parts.join(` ${group.op} `), group);
}

/** NOT over a group, as Java `ConditionSql` emits it: COALESCE pins SQL's NULL to false first, so a NULL cell
 *  is "not matching" and the negation keeps the row — the same answer as the in-memory evaluator. */
function negated(body: string, g: ConditionGroup): string {
    return body && g.negate ? `(NOT COALESCE((${body}), FALSE))` : body;
}

/** Parenthesise a nested group's body — a negated one already carries its own parentheses. */
function wrap(s: string, g: ConditionGroup): string {
    return s ? (g.negate ? s : `(${s})`) : '';
}

function compileCondition(c: Condition, cols: ColumnMeta[]): string {
    if (!c.field || !c.operator) return '';
    const id = quoteIdent(c.field);
    const t = columnType(cols, c.field);
    const ext = extensionLeaf(c, t);
    if (ext !== null) return ext;
    switch (c.operator) {
        case 'isNull':
            return `${id} IS NULL`;
        case 'isNotNull':
            return `${id} IS NOT NULL`;
        case 'contains':
            if (!c.value) return '';
            return `${id} LIKE ${lit('%' + c.value + '%', 'string')}`;
        case 'startsWith':
            if (!c.value) return '';
            return `${id} LIKE ${lit(c.value + '%', 'string')}`;
        case 'endsWith':
            if (!c.value) return '';
            return `${id} LIKE ${lit('%' + c.value, 'string')}`;
        case 'in': {
            const items = (c.value ?? '')
                .split(',')
                .map((x) => x.trim())
                .filter(Boolean);
            return items.length ? `${id} IN (${items.map((x) => lit(x, t)).join(', ')})` : '';
        }
        case 'between':
            if (!c.value || !c.value2) return '';
            return `${id} BETWEEN ${lit(c.value, t)} AND ${lit(c.value2, t)}`;
        default:
            if (c.value == null || c.value === '') return '';
            return `${id} ${c.operator} ${lit(c.value, t)}`;
    }
}

/**
 * SQL for the Condition Language extensions — `valueField`, `matches`, `ignoreCase` — in the shapes Java
 * `ConditionSql` emits, or `null` when the leaf uses none of them (the caller then renders it as before).
 * `''` means "incomplete, contributes nothing". Literals are inlined (never a bind), single quotes doubled.
 */
function extensionLeaf(c: Condition, t: ColumnType): string | null {
    const f = quoteIdent(c.field);
    if (c.valueField) return fieldsSql(f, c.operator, quoteIdent(c.valueField), c.ignoreCase === true);
    if (c.operator === 'matches') {
        if (!c.value) return '';
        return `regexp_matches(CAST(${f} AS VARCHAR), ${strLit(c.value)}${c.ignoreCase ? ", 'i')" : ')'}`;
    }
    if (!c.ignoreCase || t !== 'string') return null;
    const v = c.value ?? '';
    if (c.operator === '=' || c.operator === '!=') {
        return v === ''
            ? ''
            : `LOWER(CAST(${f} AS VARCHAR)) ${c.operator === '!=' ? '<>' : '='} ${strLit(v.toLowerCase())}`;
    }
    if (c.operator === 'in') {
        const items = v
            .split(',')
            .map((x) => x.trim())
            .filter(Boolean);
        return items.length
            ? `(${items.map((x) => `LOWER(CAST(${f} AS VARCHAR)) = ${strLit(x.toLowerCase())}`).join(' OR ')})`
            : '';
    }
    if (c.operator === 'contains' || c.operator === 'startsWith' || c.operator === 'endsWith') {
        if (!v) return '';
        const esc = v.toLowerCase().replace(/\\/g, '\\\\').replace(/%/g, '\\%').replace(/_/g, '\\_');
        const pat = (c.operator === 'startsWith' ? '' : '%') + esc + (c.operator === 'endsWith' ? '' : '%');
        return `LOWER(CAST(${f} AS VARCHAR)) LIKE ${strLit(pat)} ESCAPE '\\'`;
    }
    return null;
}

/** Field-to-field leaf — a port of Java `ConditionSql.fields` (per-row typing: numbers, else date-like
 *  instants, else strings; a NULL cell on either side never matches). `g` is an already-quoted identifier. */
function fieldsSql(f: string, operator: Operator, g: string, ic: boolean): string {
    const sa = `CAST(${f} AS VARCHAR)`;
    const sb = `CAST(${g} AS VARCHAR)`;
    const escLike = (e: string): string => `REPLACE(REPLACE(REPLACE(${e}, '\\', '\\\\'), '%', '\\%'), '_', '\\_')`;
    switch (operator) {
        case 'contains':
            return `(LOWER(${sa}) LIKE '%' || ${escLike(`LOWER(${sb})`)} || '%' ESCAPE '\\')`;
        case 'startsWith':
            return `(LOWER(${sa}) LIKE ${escLike(`LOWER(${sb})`)} || '%' ESCAPE '\\')`;
        case 'endsWith':
            return `(LOWER(${sa}) LIKE '%' || ${escLike(`LOWER(${sb})`)} ESCAPE '\\')`;
        case '=':
        case '!=':
        case '<':
        case '<=':
        case '>':
        case '>=':
            break;
        default:
            return 'FALSE';
    }
    const op = operator === '!=' ? '<>' : operator;
    const na = `TRY_CAST(${sa} AS DOUBLE)`;
    const nb = `TRY_CAST(${sb} AS DOUBLE)`;
    const ta = `TRY_CAST(${sa} AS TIMESTAMP)`;
    const tb = `TRY_CAST(${sb} AS TIMESTAMP)`;
    const shape =
        `regexp_matches(${sa}, '\\d{4}') AND regexp_matches(${sa}, '[-/:T]') AND ` +
        `regexp_matches(${sb}, '\\d{4}') AND regexp_matches(${sb}, '[-/:T]')`;
    const str = ic ? `LOWER(${sa}) ${op} LOWER(${sb})` : `${sa} ${op} ${sb}`;
    return (
        `(CASE WHEN regexp_full_match(${sa}, '-?\\d+(\\.\\d+)?') AND regexp_full_match(${sb}, '-?\\d+(\\.\\d+)?') ` +
        `THEN ${na} ${op} ${nb} WHEN ${shape} AND ${ta} IS NOT NULL AND ${tb} IS NOT NULL THEN ${ta} ${op} ${tb} ` +
        `ELSE ${str} END)`
    );
}

const strLit = (v: string): string => `'${v.replace(/'/g, "''")}'`;

/** Quote an identifier DuckDB-style (`"col"`), doubling embedded quotes. Shared with the Studio QuerySpec compiler. */
export function quoteIdent(name: string): string {
    return `"${name.replace(/"/g, '""')}"`;
}

function lit(v: string, type: ColumnType): string {
    if (type === 'number' && /^-?\d+(\.\d+)?$/.test(v)) return v;
    if (type === 'boolean' && /^(true|false)$/i.test(v)) return v.toUpperCase();
    return `'${v.replace(/'/g, "''")}'`;
}

/** One named bind extracted from a leaf condition — `:name` in the SQL, default value editable when saved. */
export interface SqlParam {
    name: string;
    field: string;
    operator: Operator;
    value: string;
}

export interface ParamSql {
    sql: string;
    params: SqlParam[];
}

/**
 * Like {@link compileSql}, but replaces each leaf condition's literal with a named bind (`:fieldValue`) and
 * returns the bind list — the basis for a reusable **rule template** whose values are supplied at run time.
 * `between` yields two binds (`:fieldValue` / `:fieldValueTo`); `in` keeps inline literals (a single bind
 * can't hold a list); `isNull`/`isNotNull` need none.
 */
export function compileSqlWithParams(model: QueryModel, source: QuerySource): ParamSql {
    const cols = source.columns ?? [];
    const params: SqlParam[] = [];
    const used = new Set<string>();

    const nameFor = (field: string, suffix = 'Value'): string => {
        const base = (field.replace(/[^A-Za-z0-9_]/g, '_').replace(/^[^A-Za-z_]/, '_') || 'p') + suffix;
        let name = base;
        let n = 2;
        while (used.has(name)) name = `${base}${n++}`;
        used.add(name);
        return name;
    };
    const add = (field: string, operator: Operator, value: string, suffix = 'Value'): string => {
        const name = nameFor(field, suffix);
        params.push({ name, field, operator, value });
        return name;
    };

    const condSql = (c: Condition): string => {
        if (!c.field || !c.operator) return '';
        const id = quoteIdent(c.field);
        const ext = extensionLeaf(c, columnType(cols, c.field));
        if (ext !== null) return ext;
        switch (c.operator) {
            case 'isNull':
                return `${id} IS NULL`;
            case 'isNotNull':
                return `${id} IS NOT NULL`;
            case 'contains':
                return c.value ? `${id} LIKE '%' || :${add(c.field, c.operator, c.value)} || '%'` : '';
            case 'startsWith':
                return c.value ? `${id} LIKE :${add(c.field, c.operator, c.value)} || '%'` : '';
            case 'endsWith':
                return c.value ? `${id} LIKE '%' || :${add(c.field, c.operator, c.value)}` : '';
            case 'in': {
                const items = (c.value ?? '')
                    .split(',')
                    .map((x) => x.trim())
                    .filter(Boolean);
                if (!items.length) return '';
                const t = columnType(cols, c.field);
                return `${id} IN (${items.map((x) => lit(x, t)).join(', ')})`;
            }
            case 'between': {
                if (!c.value || !c.value2) return '';
                const lo = add(c.field, c.operator, c.value);
                const hi = add(c.field, c.operator, c.value2, 'ValueTo');
                return `${id} BETWEEN :${lo} AND :${hi}`;
            }
            default:
                return c.value == null || c.value === ''
                    ? ''
                    : `${id} ${c.operator} :${add(c.field, c.operator, c.value)}`;
        }
    };

    const groupSql = (g: ConditionGroup): string => {
        const parts = g.items
            .map((it) => (it.kind === 'group' ? wrap(groupSql(it), it) : condSql(it)))
            .filter((s) => s.length > 0);
        return negated(parts.join(` ${g.op} `), g);
    };

    const proj =
        model.projection === '*' || model.projection.length === 0 ? '*' : model.projection.map(quoteIdent).join(', ');
    const from = quoteIdent(source.name);
    const where = groupSql(model.where);
    const sql = where ? `SELECT ${proj}\nFROM ${from}\nWHERE ${where}` : `SELECT ${proj}\nFROM ${from}`;
    return { sql, params };
}

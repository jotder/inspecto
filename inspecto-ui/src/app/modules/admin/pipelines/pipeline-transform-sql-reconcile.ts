import { compileField, SqlField } from './pipeline-transform-sql';
import { SQL_FUNCTIONS, SqlFunction, SqlFunctionParam, usesSource } from './sql-functions';

/**
 * The **SQL → fields reconciler** — the bounded way back from a `transform.sql` Step's SQL to the
 * Fields grid, so the two are peer views of ONE Step (operator decision 2026-09-05, superseding D4's
 * "never parse SQL back into rows").
 *
 * <p><b>Not a SQL parser.</b> It accepts exactly one shape — a flat projection over the fixed `input`
 * relation, `SELECT <item>, <item>, … FROM input` — and turns every select item into one row:
 *
 * <ul>
 *   <li>a bare column → <i>Keep as it is</i>;</li>
 *   <li>an expression the catalog recognises (its template matched in reverse, parameters read back
 *       out of the text) → that function with its parameters, and only when re-compiling the row
 *       reproduces the expression (whitespace and keyword case aside) — a recognition that would
 *       rewrite the SQL is not a recognition;</li>
 *   <li>anything else → <i>Write my own expression</i> with the expression verbatim.</li>
 * </ul>
 *
 * So a projection ALWAYS becomes fields, losslessly. What cannot be shown as fields is anything that is
 * not a projection — a `WHERE`, `JOIN`, `GROUP BY`, CTE, subquery, `DISTINCT`, or `*` mixed with items —
 * and that is reported as {@link Unsupported} with a reason the pane shows on the disabled Fields tab.
 * Filtering belongs to a Filter Step; that rule (D3) is unchanged.
 */
export type ReconcileResult = { fields: SqlField[]; unsupported?: undefined } | Unsupported;

export interface Unsupported {
    fields?: undefined;
    /** One plain sentence for the disabled Fields tab. */
    readonly unsupported: string;
}

const NOT_A_PROJECTION =
    'This SQL is more than a list of output columns, so it cannot be shown as fields. Filtering and joining belong to their own Steps.';

/** `SELECT … FROM input` and nothing else. Identifiers may be quoted; the trailing `;` is tolerated. */
const PROJECTION = /^\s*select\s+([\s\S]+?)\s+from\s+(?:input|"input")\s*;?\s*$/i;

const IDENT = String.raw`(?:[A-Za-z_][A-Za-z0-9_]*|"(?:[^"]|"")*")`;
const IDENT_RE = new RegExp(`^${IDENT}$`);

export function reconcileSql(sql: string): ReconcileResult {
    const text = stripComments(sql);
    const m = PROJECTION.exec(text);
    if (!m) return { unsupported: NOT_A_PROJECTION };
    const list = m[1].trim();
    if (/^distinct\b/i.test(list)) return { unsupported: NOT_A_PROJECTION };
    if (list === '*') return { fields: [] };
    const items = splitTopLevel(list);
    if (items.some((it) => it === '*' || /^\s*\*\s*$/.test(it))) return { unsupported: NOT_A_PROJECTION };
    const fields: SqlField[] = [];
    items.forEach((item, i) => {
        const { expr, name } = splitAlias(item.trim());
        fields.push({ id: `r-${i}-${name || 'expr'}`, ...matchExpression(expr, name) });
    });
    return { fields };
}

/** A projection with no `WHERE`/`JOIN`/… tail is the only thing the regex admits, so this is the tail test. */
export function isProjection(sql: string): boolean {
    return reconcileSql(sql).unsupported === undefined;
}

// ── item level ──────────────────────────────────────────────────────────────────────────────────────

/** `expr AS name`, `expr name` (bare identifier alias), or an unaliased item (a bare column names itself). */
function splitAlias(item: string): { expr: string; name: string } {
    const explicit = new RegExp(String.raw`^([\s\S]+?)\s+as\s+(${IDENT})$`, 'i').exec(item);
    if (explicit) return { expr: explicit[1].trim(), name: unquote(explicit[2]) };
    if (IDENT_RE.test(item)) return { expr: item, name: unquote(item) };
    const implicit = new RegExp(String.raw`^([\s\S]+?[)'\w"])\s+(${IDENT})$`).exec(item);
    if (implicit && !/\b(and|or|not|is|in|like|then|else|end|from|when|case)$/i.test(implicit[2])) {
        return { expr: implicit[1].trim(), name: unquote(implicit[2]) };
    }
    // Unaliased expression: DuckDB would name it after its text; the grid asks for a name instead.
    return { expr: item, name: '' };
}

function matchExpression(expr: string, name: string): Omit<SqlField, 'id'> {
    if (IDENT_RE.test(expr)) return { name, from: unquote(expr), fn: 'keep', args: {} };
    for (const fn of SQL_FUNCTIONS) {
        if (fn.id === 'keep' || fn.id === 'custom') continue;
        const read = readTemplate(fn, expr);
        if (!read) continue;
        const candidate: Omit<SqlField, 'id'> = { name, from: read.source, fn: fn.id, args: read.args };
        // Only a recognition that regenerates the same expression counts — otherwise it is a rewrite.
        const compiled = compileField({ id: 'probe', ...candidate, name: name || 'probe' });
        if (compiled.expr && normalize(compiled.expr) === normalize(expr)) return candidate;
    }
    return { name, from: '', fn: 'custom', args: { expression: expr } };
}

/** Match `expr` against a function's template, reading `{source}` and every parameter back out. */
function readTemplate(fn: SqlFunction, expr: string): { source: string; args: Record<string, string> } | null {
    if (!usesSource(fn) && !fn.params?.length) return null;
    const params = fn.params ?? [];
    const order: string[] = [];
    let pattern = '';
    const tokens = fn.template.split(/(\{[a-z_]+\})/i);
    for (const tok of tokens) {
        const hole = /^\{([a-z_]+)\}$/i.exec(tok);
        if (!hole) {
            pattern += literalPattern(tok);
            continue;
        }
        const key = hole[1];
        order.push(key);
        if (key === 'source') {
            pattern += `(${IDENT})`;
            continue;
        }
        const param = params.find((p) => p.name === key);
        if (!param) return null;
        const group = paramPattern(param);
        if (!group) return null;
        pattern += `(${group})`;
    }
    const m = new RegExp(`^\\s*${pattern}\\s*$`, 'i').exec(expr);
    if (!m) return null;
    let source = '';
    const args: Record<string, string> = {};
    order.forEach((key, i) => {
        const raw = m[i + 1];
        if (key === 'source') {
            source = unquote(raw);
            return;
        }
        const param = params.find((p) => p.name === key)!;
        args[key] = readParam(param, raw);
    });
    return { source, args };
}

/** Literal template text: keywords case-insensitive (the regex flag), any whitespace run flexible. */
function literalPattern(text: string): string {
    // Tokenise BEFORE escaping: a whitespace run becomes `\s*`, and each `(` `)` `,` allows optional
    // whitespace around it — so `TRIM( customer )` and `TRIM(customer)` both match the template.
    return text
        .split(/(\s+|[(),])/)
        .filter((piece) => piece.length > 0)
        .map((piece) => {
            if (/^\s+$/.test(piece)) return String.raw`\s*`;
            if (/^[(),]$/.test(piece)) return String.raw`\s*` + escapeRegex(piece) + String.raw`\s*`;
            return escapeRegex(piece);
        })
        .join('');
}

function paramPattern(param: SqlFunctionParam): string | null {
    switch (param.type) {
        case 'text':
            return String.raw`'(?:[^']|'')*'`;
        case 'number':
            return String.raw`-?\d+(?:\.\d+)?`;
        case 'enum':
            return (param.options ?? []).map(escapeRegex).join('|');
        case 'column':
            return IDENT;
        case 'sql':
            // Raw SQL can only be the custom escape hatch — never recognised as a catalog function.
            return null;
    }
    return null;
}

function readParam(param: SqlFunctionParam, raw: string): string {
    switch (param.type) {
        case 'text':
            return raw.slice(1, -1).replace(/''/g, "'");
        case 'column':
            return unquote(raw);
        default:
            return raw;
    }
}

// ── text helpers ────────────────────────────────────────────────────────────────────────────────────

/** Split on commas outside parentheses and outside single/double quotes. */
export function splitTopLevel(list: string): string[] {
    const out: string[] = [];
    let depth = 0;
    let quote: '"' | "'" | null = null;
    let start = 0;
    for (let i = 0; i < list.length; i++) {
        const ch = list[i];
        if (quote) {
            if (ch === quote) {
                if (list[i + 1] === quote) i++;
                else quote = null;
            }
            continue;
        }
        if (ch === "'" || ch === '"') quote = ch;
        else if (ch === '(') depth++;
        else if (ch === ')') depth--;
        else if (ch === ',' && depth === 0) {
            out.push(list.slice(start, i));
            start = i + 1;
        }
    }
    out.push(list.slice(start));
    return out.map((s) => s.trim()).filter((s) => s.length > 0);
}

function stripComments(sql: string): string {
    return sql.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*/g, ' ');
}

function unquote(ident: string): string {
    return ident.startsWith('"') ? ident.slice(1, -1).replace(/""/g, '"') : ident;
}

/** Whitespace-insensitive, case-insensitive text identity — DuckDB treats keywords, functions and unquoted identifiers that way. */
function normalize(sql: string): string {
    return sql
        .replace(/\s+/g, ' ')
        .replace(/\s*([(),])\s*/g, '$1')
        .trim()
        .toLowerCase();
}

function escapeRegex(s: string): string {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

import { operatorDef } from './query-columns';
import { ColumnMeta, ColumnType, Condition, ConditionGroup, Operator } from './query-types';

/**
 * Reading DuckDB's own parse tree of a row predicate (`POST /components/sql/ast`, `fragment: 'predicate'`)
 * — AUTHORING-REDESIGN-1 (c). Framework-free on purpose: the reconciler-style logic lives here, the
 * components only render it.
 *
 * <p>Two operator decisions (2026-09-23) bound everything in this file:
 * <ul>
 *   <li>**The tree is READ-ONLY (Q2).** Nothing here turns a tree back into SQL. When a condition is
 *       edited structurally, the SQL comes from the Query Core's own {@link compileWhere}, and only after
 *       the author accepts the exact before/after — so an untouched predicate keeps its author's text
 *       byte for byte, and a predicate carrying a comment is never offered for structured editing at all.</li>
 *   <li>**Only what we read is a contract (Q5).** {@link READ_NODE_TYPES} and {@link LIKE_FUNCTIONS} must
 *       equal `contracts/sql-ast.contract.json`, which `SqlAstContractTest` pins against the real engine.
 *       ⛔ Reading a new node type or key means adding it to the contract in the same change.
 *       ⛔ Never read `query_location`: a start offset with no end drives nothing, and a CAST node carries
 *       2^64-1 there, which a JavaScript number cannot hold exactly.</li>
 * </ul>
 *
 * ⚠ "AST" is an implementation word. No user-facing string built here may say it — the surface says
 * *condition* (GLOSSARY; design §11).
 */

/** One node of DuckDB's tree, as JSON. Deliberately loose: we read a handful of keys and ignore the rest. */
export type SqlAstNode = Record<string, unknown>;

const COMPARISONS: Record<string, { label: string; op: Operator }> = {
    COMPARE_EQUAL: { label: '=', op: '=' },
    COMPARE_NOTEQUAL: { label: '≠', op: '!=' },
    COMPARE_LESSTHAN: { label: '<', op: '<' },
    COMPARE_GREATERTHAN: { label: '>', op: '>' },
    COMPARE_LESSTHANOREQUALTO: { label: '≤', op: '<=' },
    COMPARE_GREATERTHANOREQUALTO: { label: '≥', op: '>=' },
};

const LIKE_LABELS: Record<string, string> = {
    '~~': 'like',
    '!~~': 'not like',
    '~~*': 'like (any case)',
    '!~~*': 'not like (any case)',
};

/** The LIKE-family function names DuckDB rewrites `LIKE`/`ILIKE` into — pinned by the contract. */
export const LIKE_FUNCTIONS: ReadonlySet<string> = new Set(Object.keys(LIKE_LABELS));

/** Every node type this reader handles — pinned, as a set, against the committed contract. */
export const READ_NODE_TYPES: ReadonlySet<string> = new Set([
    'CONJUNCTION_AND',
    'CONJUNCTION_OR',
    ...Object.keys(COMPARISONS),
    'OPERATOR_IS_NULL',
    'OPERATOR_IS_NOT_NULL',
    'COMPARE_IN',
    'COMPARE_NOT_IN',
    'COMPARE_BETWEEN',
    'FUNCTION',
    'COLUMN_REF',
    'VALUE_CONSTANT',
    'OPERATOR_CAST',
]);

const NUMERIC_TYPES = new Set([
    'TINYINT',
    'SMALLINT',
    'INTEGER',
    'BIGINT',
    'HUGEINT',
    'UTINYINT',
    'USMALLINT',
    'UINTEGER',
    'UBIGINT',
    'UHUGEINT',
    'FLOAT',
    'DOUBLE',
    'DECIMAL',
]);

// ── small readers ───────────────────────────────────────────────────────────────────────────────────

function obj(v: unknown): SqlAstNode | null {
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as SqlAstNode) : null;
}
function nodes(v: unknown): SqlAstNode[] {
    return Array.isArray(v) ? v.map(obj).filter((n): n is SqlAstNode => n !== null) : [];
}
function typeOf(n: SqlAstNode | null): string {
    return typeof n?.['type'] === 'string' ? (n['type'] as string) : '';
}
function isConjunction(n: SqlAstNode | null): boolean {
    const t = typeOf(n);
    return t === 'CONJUNCTION_AND' || t === 'CONJUNCTION_OR';
}
function conjunctionOp(n: SqlAstNode): 'AND' | 'OR' {
    return typeOf(n) === 'CONJUNCTION_OR' ? 'OR' : 'AND';
}
function columnNames(n: SqlAstNode | null): string[] | null {
    if (typeOf(n) !== 'COLUMN_REF') return null;
    const names = n!['column_names'];
    return Array.isArray(names) && names.every((x) => typeof x === 'string') ? (names as string[]) : null;
}

/** A literal as the Query Core would hold it: its coarse type and its raw text. `null` = not a plain literal. */
interface Literal {
    type: ColumnType;
    text: string;
}

function literal(n: SqlAstNode | null): Literal | null | 'null' {
    if (typeOf(n) === 'OPERATOR_CAST') {
        // `TRUE`/`FALSE` parse as a VARCHAR 't'/'f' CAST to BOOLEAN.
        const target = obj(n!['cast_type'])?.['id'];
        const child = literal(obj(n!['child']));
        if (target === 'BOOLEAN' && child && child !== 'null' && (child.text === 't' || child.text === 'f')) {
            return { type: 'boolean', text: child.text === 't' ? 'TRUE' : 'FALSE' };
        }
        return null;
    }
    if (typeOf(n) !== 'VALUE_CONSTANT') return null;
    const value = obj(n!['value']);
    if (!value) return null;
    if (value['is_null'] === true) return 'null';
    const t = obj(value['type']);
    const id = typeof t?.['id'] === 'string' ? (t['id'] as string) : '';
    const raw = value['value'];
    if (id === 'VARCHAR' && typeof raw === 'string') return { type: 'string', text: raw };
    if (id === 'DECIMAL' && typeof raw === 'number') {
        const scale = Number(obj(t!['type_info'])?.['scale'] ?? 0);
        return { type: 'number', text: decimalText(raw, scale) };
    }
    if (NUMERIC_TYPES.has(id) && typeof raw === 'number') return { type: 'number', text: String(raw) };
    return null;
}

/** DuckDB serializes a DECIMAL as its unscaled integer plus a scale: 15 with scale 1 is 1.5. */
function decimalText(unscaled: number, scale: number): string {
    if (!Number.isInteger(scale) || scale <= 0) return String(unscaled);
    const digits = Math.abs(unscaled)
        .toString()
        .padStart(scale + 1, '0');
    return `${unscaled < 0 ? '-' : ''}${digits.slice(0, -scale)}.${digits.slice(-scale)}`;
}

/** An operand as display text, or `null` when it is something this view does not show. */
function operandText(n: SqlAstNode | null): string | null {
    const names = columnNames(n);
    if (names) return names.join('.');
    const lit = literal(n);
    if (lit === 'null') return 'NULL';
    if (!lit) return null;
    return lit.type === 'string' ? `'${lit.text}'` : lit.text;
}

// ── Step 2: the read-only table ─────────────────────────────────────────────────────────────────────

/** One row of the read-only predicate table. `level` is the ARIA level (1 = top). */
export interface PredicateRow {
    level: number;
    /** The connector joining this row to the previous one in its group; `null` for a group's first row. */
    joiner: 'AND' | 'OR' | null;
    kind: 'group' | 'condition' | 'unshown';
    /** For a `group` row: how the rows under it combine. */
    groupOp?: 'AND' | 'OR';
    left?: string;
    operator?: string;
    right?: string;
}

/** Flatten a predicate tree into table rows. A part it cannot show becomes an `unshown` row — never dropped. */
export function predicateRows(ast: SqlAstNode): PredicateRow[] {
    const rows: PredicateRow[] = [];
    if (isConjunction(ast)) pushChildren(ast, 1, rows);
    else rows.push(leafRow(ast, 1, null));
    return rows;
}

function pushChildren(group: SqlAstNode, level: number, rows: PredicateRow[]): void {
    const op = conjunctionOp(group);
    nodes(group['children']).forEach((child, i) => {
        const joiner = i === 0 ? null : op;
        if (isConjunction(child)) {
            rows.push({ level, joiner, kind: 'group', groupOp: conjunctionOp(child) });
            pushChildren(child, level + 1, rows);
        } else {
            rows.push(leafRow(child, level, joiner));
        }
    });
}

function leafRow(n: SqlAstNode, level: number, joiner: 'AND' | 'OR' | null): PredicateRow {
    const shown = describeLeaf(n);
    return shown ? { level, joiner, kind: 'condition', ...shown } : { level, joiner, kind: 'unshown' };
}

function describeLeaf(n: SqlAstNode): { left: string; operator: string; right: string } | null {
    const t = typeOf(n);
    const cmp = COMPARISONS[t];
    if (cmp) {
        const left = operandText(obj(n['left']));
        const right = operandText(obj(n['right']));
        return left !== null && right !== null ? { left, operator: cmp.label, right } : null;
    }
    const kids = nodes(n['children']);
    if (t === 'OPERATOR_IS_NULL' || t === 'OPERATOR_IS_NOT_NULL') {
        const left = kids.length === 1 ? operandText(kids[0]) : null;
        return left !== null
            ? { left, operator: t === 'OPERATOR_IS_NULL' ? 'is null' : 'is not null', right: '' }
            : null;
    }
    if (t === 'COMPARE_IN' || t === 'COMPARE_NOT_IN') {
        const [first, ...rest] = kids.map(operandText);
        if (first == null || rest.length === 0 || rest.some((x) => x === null)) return null;
        return { left: first, operator: t === 'COMPARE_IN' ? 'in' : 'not in', right: rest.join(', ') };
    }
    if (t === 'COMPARE_BETWEEN') {
        const left = operandText(obj(n['input']));
        const lo = operandText(obj(n['lower']));
        const hi = operandText(obj(n['upper']));
        return left !== null && lo !== null && hi !== null
            ? { left, operator: 'between', right: `${lo} and ${hi}` }
            : null;
    }
    if (t === 'FUNCTION' && typeof n['function_name'] === 'string' && LIKE_FUNCTIONS.has(n['function_name'])) {
        const left = kids.length === 2 ? operandText(kids[0]) : null;
        const right = kids.length === 2 ? operandText(kids[1]) : null;
        return left !== null && right !== null ? { left, operator: LIKE_LABELS[n['function_name']], right } : null;
    }
    return null;
}

// ── Step 3: bounded recognition into the Query Core ─────────────────────────────────────────────────

/** Why a predicate cannot be offered for structured editing — worded for the author, never "AST". */
const REFUSE = (what: string) => `This condition uses ${what}, which the condition editor cannot represent.`;

/**
 * Recognise a predicate tree as a Query Core {@link ConditionGroup}, with the column types its literals
 * imply. **Bounded on purpose** — it accepts only what the `Operator` union expresses AND what
 * `compileWhere` regenerates as the same predicate; anything else is refused with a reason, never guessed.
 * The host still proves the round trip ({@link sameSqlStructure}) before it offers editing: a recognition
 * that would rewrite the predicate is not a recognition.
 */
export function astToConditionGroup(
    ast: SqlAstNode,
): { group: ConditionGroup; columns: ColumnMeta[] } | { unsupported: string } {
    const types = new Map<string, ColumnType>();
    try {
        const group = isConjunction(ast)
            ? toGroup(ast, types)
            : { kind: 'group' as const, op: 'AND' as const, items: [toCondition(ast, types)] };
        return { group, columns: [...types].map(([name, type]) => ({ name, type })) };
    } catch (e) {
        if (e instanceof Unsupported) return { unsupported: e.message };
        throw e;
    }
}

class Unsupported extends Error {}

function toGroup(n: SqlAstNode, types: Map<string, ColumnType>): ConditionGroup {
    return {
        kind: 'group',
        op: conjunctionOp(n),
        items: nodes(n['children']).map((c) => (isConjunction(c) ? toGroup(c, types) : toCondition(c, types))),
    };
}

function field(n: SqlAstNode | null): string {
    const names = columnNames(n);
    if (!names) throw new Unsupported(REFUSE('something other than a plain column on the left'));
    if (names.length !== 1) throw new Unsupported(REFUSE('a qualified column name'));
    return names[0];
}

function value(n: SqlAstNode | null): Literal {
    const lit = literal(n);
    if (lit === 'null') throw new Unsupported(REFUSE('a NULL value (use "is null" instead)'));
    if (!lit) throw new Unsupported(REFUSE('a calculated value'));
    return lit;
}

function typed(types: Map<string, ColumnType>, name: string, type: ColumnType): void {
    const prior = types.get(name);
    if (prior && prior !== type) throw new Unsupported(REFUSE(`the column ${name} as two different kinds of value`));
    types.set(name, type);
}

function condition(types: Map<string, ColumnType>, c: Condition): Condition {
    const type = types.get(c.field) ?? 'string';
    if (!operatorDef(type, c.operator))
        throw new Unsupported(REFUSE(`an operator the editor does not offer for ${type} values`));
    return c;
}

function toCondition(n: SqlAstNode, types: Map<string, ColumnType>): Condition {
    const t = typeOf(n);
    const cmp = COMPARISONS[t];
    if (cmp) {
        const name = field(obj(n['left']));
        const lit = value(obj(n['right']));
        typed(types, name, lit.type);
        return condition(types, { kind: 'condition', field: name, operator: cmp.op, value: lit.text });
    }
    const kids = nodes(n['children']);
    if (t === 'OPERATOR_IS_NULL' || t === 'OPERATOR_IS_NOT_NULL') {
        if (kids.length !== 1) throw new Unsupported(REFUSE('an unusual null test'));
        const name = field(kids[0]);
        return { kind: 'condition', field: name, operator: t === 'OPERATOR_IS_NULL' ? 'isNull' : 'isNotNull' };
    }
    if (t === 'COMPARE_IN') {
        if (kids.length < 2) throw new Unsupported(REFUSE('an empty list'));
        const name = field(kids[0]);
        const items = kids.slice(1).map(value);
        for (const it of items) {
            if (it.text.includes(',')) throw new Unsupported(REFUSE('a list value containing a comma'));
            typed(types, name, it.type);
        }
        return condition(types, {
            kind: 'condition',
            field: name,
            operator: 'in',
            value: items.map((i) => i.text).join(', '),
        });
    }
    if (t === 'COMPARE_BETWEEN') {
        const name = field(obj(n['input']));
        const lo = value(obj(n['lower']));
        const hi = value(obj(n['upper']));
        typed(types, name, lo.type);
        typed(types, name, hi.type);
        return condition(types, {
            kind: 'condition',
            field: name,
            operator: 'between',
            value: lo.text,
            value2: hi.text,
        });
    }
    if (t === 'FUNCTION' && n['function_name'] === '~~' && kids.length === 2) {
        const name = field(kids[0]);
        const pattern = value(kids[1]);
        if (pattern.type !== 'string') throw new Unsupported(REFUSE('a pattern that is not text'));
        const like = likeOperator(pattern.text);
        if (!like) throw new Unsupported(REFUSE('a pattern with wildcards the editor cannot express'));
        typed(types, name, 'string');
        return condition(types, { kind: 'condition', field: name, operator: like.op, value: like.value });
    }
    if (t === 'COMPARE_NOT_IN') throw new Unsupported(REFUSE('"not in"'));
    if (t === 'FUNCTION' && typeof n['function_name'] === 'string' && LIKE_FUNCTIONS.has(n['function_name'])) {
        throw new Unsupported(REFUSE(`"${LIKE_LABELS[n['function_name']]}"`));
    }
    if (t === 'OPERATOR_NOT') throw new Unsupported(REFUSE('a NOT'));
    throw new Unsupported(REFUSE('an expression such as a function call'));
}

/** `%x%` / `x%` / `%x`, with no other wildcard — exactly the three shapes `compileWhere` emits. */
function likeOperator(pattern: string): { op: Operator; value: string } | null {
    const plain = (s: string) => s.length > 0 && !/[%_]/.test(s);
    if (pattern.length > 2 && pattern.startsWith('%') && pattern.endsWith('%') && plain(pattern.slice(1, -1))) {
        return { op: 'contains', value: pattern.slice(1, -1) };
    }
    if (pattern.endsWith('%') && plain(pattern.slice(0, -1))) return { op: 'startsWith', value: pattern.slice(0, -1) };
    if (pattern.startsWith('%') && plain(pattern.slice(1))) return { op: 'endsWith', value: pattern.slice(1) };
    return null;
}

// ── the admission test + the comment check ─────────────────────────────────────────────────────────

/** Whether two trees are the same predicate — deep equality with every `query_location` ignored. */
export function sameSqlStructure(a: unknown, b: unknown): boolean {
    if (Array.isArray(a) || Array.isArray(b)) {
        return (
            Array.isArray(a) &&
            Array.isArray(b) &&
            a.length === b.length &&
            a.every((x, i) => sameSqlStructure(x, b[i]))
        );
    }
    const oa = obj(a);
    const ob = obj(b);
    if (!oa || !ob) return a === b;
    const keys = (o: SqlAstNode) =>
        Object.keys(o)
            .filter((k) => k !== 'query_location')
            .sort();
    const ka = keys(oa);
    const kb = keys(ob);
    return ka.length === kb.length && ka.every((k, i) => k === kb[i] && sameSqlStructure(oa[k], ob[k]));
}

/**
 * Whether the text carries a SQL comment outside a quoted string or identifier. The parse tree DROPS
 * comments (design T1), so a structured edit of such a predicate would delete them — Q2 says it must not.
 */
export function hasSqlComment(text: string): boolean {
    let quote: "'" | '"' | null = null;
    for (let i = 0; i < text.length; i++) {
        const ch = text[i];
        if (quote) {
            if (ch === quote) quote = null; // a doubled quote closes and immediately re-opens — same result
            continue;
        }
        if (ch === "'" || ch === '"') quote = ch;
        else if ((ch === '-' && text[i + 1] === '-') || (ch === '/' && text[i + 1] === '*')) return true;
    }
    return false;
}

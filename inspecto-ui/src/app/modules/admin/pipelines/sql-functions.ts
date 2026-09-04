/**
 * The **Transform function catalog** — the vocabulary the Fields grid offers a business user, and the
 * ONE place a function's plain-language name, its typed parameters and its SQL template live together.
 *
 * <p><b>Why a catalog rather than a verb enum.</b> The 2026-09-03 grid pinned five hard-coded verbs
 * (`keep`/`trim`/`upper`/`cast`/`formula`) whose only parameterised member was a cast type; the legacy
 * `transform.map` rule grid is worse still — four `transformType` constants, two of which pack their
 * arguments into a `|`-delimited string, and one (`FILENAME_DATE`) that may only ever write a single
 * hard-coded column. Neither models *a function with parameters*, which is what authoring a mapping
 * actually is (operator, 2026-09-04: "mapping with functions and parameters is not realistic").
 * A catalog entry declares its parameters, so the grid renders a real form control per parameter and
 * binds the row's source column to `{source}` automatically — the "auto map parameters against sql
 * functions" the operator asked for on 2026-09-03.
 *
 * <p><b>Forward-only.</b> A field compiles to SQL; SQL is never parsed back into a field. That is the
 * standing v1 rule (the retired D4): reading hand-written SQL into an editable row model needs an AST
 * and is gated on a `json_serialize_sql`-under-seal probe (BACKLOG AUTHORING-REDESIGN-1 (c)).
 *
 * <p><b>Forgiving by construction.</b> Every cast in this catalog is `TRY_CAST`, never bare `CAST`, and
 * every division guards its divisor with `NULLIF`. A bad cell nulls one cell instead of killing the
 * batch — the semantic the engine's cast-failure audit already assumes.
 */

/** How a parameter's value is rendered into SQL — this drives BOTH the form control and the escaping. */
export type SqlParamType =
    /** A column of the input relation. Rendered as a quoted identifier; the grid offers a column picker. */
    | 'column'
    /** Free text. Rendered as a single-quoted SQL string literal, quotes doubled. */
    | 'text'
    /** A number. Rendered verbatim after validation; a non-numeric value is a row problem. */
    | 'number'
    /** One of `options`. Rendered verbatim (they are SQL keywords/types, never user text). */
    | 'enum'
    /** Raw SQL, rendered verbatim. Only the deliberate escape hatch uses this. */
    | 'sql';

/** One parameter of a catalog function, beyond the row's source column. */
export interface SqlFunctionParam {
    /** Placeholder name inside the template, e.g. `decimals` for `{decimals}`. */
    readonly name: string;
    /** Plain-language label shown above the control. */
    readonly label: string;
    readonly type: SqlParamType;
    /** Seeded into a new row's args, so a freshly picked function is immediately valid where possible. */
    readonly default?: string;
    /** Allowed values for `type: 'enum'`. */
    readonly options?: readonly string[];
    readonly placeholder?: string;
    /** When false the parameter may be left blank and the template still renders. Defaults to true. */
    readonly optional?: boolean;
}

export type SqlFunctionCategory = 'Keep' | 'Text' | 'Numbers' | 'Dates' | 'Logic' | 'Convert' | 'Custom';

/** One function a Fields row may apply to its source column. */
export interface SqlFunction {
    /** Stable id persisted in the field's `fn` — renaming a label is safe, renaming an id is not. */
    readonly id: string;
    /** Plain language, the way the grid lists it. */
    readonly label: string;
    readonly category: SqlFunctionCategory;
    /**
     * The SQL this compiles to. `{source}` is the row's source column (already quoted); `{param}` is a
     * parameter rendered per its declared type. A template with no `{source}` needs no source column.
     */
    readonly template: string;
    readonly params?: readonly SqlFunctionParam[];
    /** One line of help shown under the row's controls. */
    readonly help?: string;
}

/** DuckDB types offered wherever a type must be chosen, in the order a business user thinks of them. */
export const SQL_TYPES = ['VARCHAR', 'BIGINT', 'DOUBLE', 'DECIMAL(18,2)', 'BOOLEAN', 'DATE', 'TIMESTAMP', 'TIME'] as const;

const DATE_PARTS = ['YEAR', 'QUARTER', 'MONTH', 'WEEK', 'DAY', 'HOUR', 'MINUTE', 'SECOND'] as const;
const COMPARISONS = ['=', '<>', '>', '>=', '<', '<=', 'LIKE'] as const;

/**
 * The catalog. Ordered by category, and within a category by how often a mapping needs it.
 *
 * ⚠ Adding an entry is a product decision, not a dev convenience: every entry becomes a permanent
 * option in front of a non-technical author. Prefer one parameterised function over three fixed ones.
 */
export const SQL_FUNCTIONS: readonly SqlFunction[] = [
    // ── Keep ────────────────────────────────────────────────────────────────────────────────────────
    {
        id: 'keep',
        label: 'Keep as it is',
        category: 'Keep',
        template: '{source}',
        help: 'The value is copied through unchanged.',
    },

    // ── Text ────────────────────────────────────────────────────────────────────────────────────────
    {
        id: 'text.trim',
        label: 'Remove spaces around the value',
        category: 'Text',
        template: 'TRIM({source})',
        help: 'Leading and trailing spaces are removed. " Anna " becomes "Anna".',
    },
    { id: 'text.upper', label: 'Make UPPERCASE', category: 'Text', template: 'UPPER({source})' },
    { id: 'text.lower', label: 'Make lowercase', category: 'Text', template: 'LOWER({source})' },
    {
        id: 'text.replace',
        label: 'Replace text',
        category: 'Text',
        template: 'REPLACE({source}, {find}, {replacement})',
        params: [
            { name: 'find', label: 'Find', type: 'text', placeholder: '-' },
            { name: 'replacement', label: 'Replace with', type: 'text', default: '', optional: true, placeholder: 'leave blank to delete' },
        ],
    },
    {
        id: 'text.substring',
        label: 'Take part of the text',
        category: 'Text',
        template: 'SUBSTRING({source}, {start}, {length})',
        params: [
            { name: 'start', label: 'Start at character', type: 'number', default: '1' },
            { name: 'length', label: 'How many characters', type: 'number', default: '10' },
        ],
        help: 'Counting starts at 1.',
    },
    {
        id: 'text.split_part',
        label: 'Take one part after splitting',
        category: 'Text',
        template: 'SPLIT_PART({source}, {delimiter}, {index})',
        params: [
            { name: 'delimiter', label: 'Split on', type: 'text', default: ',' },
            { name: 'index', label: 'Which part', type: 'number', default: '1' },
        ],
    },
    {
        id: 'text.join',
        label: 'Join with another column',
        category: 'Text',
        template: "CONCAT({source}, {separator}, {other})",
        params: [
            { name: 'separator', label: 'Separator', type: 'text', default: ' ' },
            { name: 'other', label: 'Other column', type: 'column' },
        ],
    },
    {
        id: 'text.pad_left',
        label: 'Pad on the left',
        category: 'Text',
        template: 'LPAD({source}, {length}, {pad})',
        params: [
            { name: 'length', label: 'Total length', type: 'number', default: '10' },
            { name: 'pad', label: 'Pad with', type: 'text', default: '0' },
        ],
        help: 'Useful for account or reference numbers that lost their leading zeros.',
    },

    // ── Numbers ─────────────────────────────────────────────────────────────────────────────────────
    {
        id: 'num.round',
        label: 'Round',
        category: 'Numbers',
        template: 'ROUND({source}, {decimals})',
        params: [{ name: 'decimals', label: 'Decimal places', type: 'number', default: '0' }],
    },
    {
        id: 'num.multiply',
        label: 'Multiply by',
        category: 'Numbers',
        template: '({source} * {factor})',
        params: [{ name: 'factor', label: 'Factor', type: 'number', default: '100' }],
        help: 'Multiplying by 100 turns an amount into cents.',
    },
    {
        id: 'num.divide',
        label: 'Divide by',
        category: 'Numbers',
        template: '({source} / NULLIF({divisor}, 0))',
        params: [{ name: 'divisor', label: 'Divide by', type: 'number', default: '100' }],
        help: 'Dividing by zero gives an empty value rather than failing the batch.',
    },
    { id: 'num.abs', label: 'Absolute value', category: 'Numbers', template: 'ABS({source})' },

    // ── Dates ───────────────────────────────────────────────────────────────────────────────────────
    {
        id: 'date.parse',
        label: 'Read text as a date',
        category: 'Dates',
        template: 'TRY_STRPTIME({source}, {format})',
        params: [{ name: 'format', label: 'Format it is written in', type: 'text', default: '%d/%m/%Y' }],
        help: '%d day, %m month, %Y four-digit year. 15/03/2024 is %d/%m/%Y.',
    },
    {
        id: 'date.format',
        label: 'Write a date as text',
        category: 'Dates',
        template: 'STRFTIME({source}, {format})',
        params: [{ name: 'format', label: 'Format to write', type: 'text', default: '%Y-%m-%d' }],
    },
    {
        id: 'date.part',
        label: 'Take part of a date',
        category: 'Dates',
        template: 'EXTRACT({part} FROM {source})',
        params: [{ name: 'part', label: 'Part', type: 'enum', options: DATE_PARTS, default: 'YEAR' }],
    },
    {
        id: 'date.truncate',
        label: 'Start of the period',
        category: 'Dates',
        template: "DATE_TRUNC({unit}, {source})",
        params: [{ name: 'unit', label: 'Period', type: 'text', default: 'month' }],
        help: 'A date in March with period "month" becomes the 1st of March.',
    },

    // ── Logic ───────────────────────────────────────────────────────────────────────────────────────
    {
        id: 'logic.default_if_empty',
        label: 'Use a default when empty',
        category: 'Logic',
        template: "COALESCE(NULLIF({source}, ''), {fallback})",
        params: [{ name: 'fallback', label: 'Default value', type: 'text', default: 'unknown' }],
    },
    {
        id: 'logic.if_then_else',
        label: 'If … then … otherwise …',
        category: 'Logic',
        template: 'CASE WHEN {source} {comparison} {value} THEN {then} ELSE {otherwise} END',
        params: [
            { name: 'comparison', label: 'Is', type: 'enum', options: COMPARISONS, default: '=' },
            { name: 'value', label: 'This value', type: 'text', placeholder: 'shipped' },
            { name: 'then', label: 'Then use', type: 'text', placeholder: 'Y' },
            { name: 'otherwise', label: 'Otherwise use', type: 'text', placeholder: 'N' },
        ],
    },

    // ── Convert ─────────────────────────────────────────────────────────────────────────────────────
    {
        id: 'convert.type',
        label: 'Change the type',
        category: 'Convert',
        template: 'TRY_CAST({source} AS {type})',
        params: [{ name: 'type', label: 'New type', type: 'enum', options: SQL_TYPES, default: 'VARCHAR' }],
        help: 'A value that cannot be converted becomes empty; the row is kept.',
    },

    // ── Custom ──────────────────────────────────────────────────────────────────────────────────────
    {
        id: 'custom',
        label: 'Write my own expression',
        category: 'Custom',
        template: '{expression}',
        params: [{ name: 'expression', label: 'SQL expression', type: 'sql', placeholder: 'ROUND(amount * 100)' }],
        help: 'Written into the SQL exactly as typed. Column names are used as they appear in the input.',
    },
];

const BY_ID = new Map(SQL_FUNCTIONS.map((f) => [f.id, f]));

/** The catalog entry for an id, or `undefined` when a stored field names a function this build removed. */
export function sqlFunction(id: string): SqlFunction | undefined {
    return BY_ID.get(id);
}

/** Catalog grouped for a picker, in declaration order — the grid renders one optgroup per category. */
export function sqlFunctionsByCategory(): { category: SqlFunctionCategory; functions: SqlFunction[] }[] {
    const groups: { category: SqlFunctionCategory; functions: SqlFunction[] }[] = [];
    for (const fn of SQL_FUNCTIONS) {
        const last = groups[groups.length - 1];
        if (last && last.category === fn.category) last.functions.push(fn);
        else groups.push({ category: fn.category, functions: [fn] });
    }
    return groups;
}

/** True when the function reads the row's source column, i.e. its template mentions `{source}`. */
export function usesSource(fn: SqlFunction): boolean {
    return fn.template.includes('{source}');
}

// ── rendering ───────────────────────────────────────────────────────────────────────────────────────

/** Double-quote an identifier unless it is already plain — parsed headers carry spaces and punctuation. */
export function quoteIdentifier(name: string): string {
    return /^[A-Za-z_][A-Za-z0-9_]*$/.test(name) ? name : `"${name.replace(/"/g, '""')}"`;
}

/** Single-quote a SQL string literal, doubling any embedded quote. */
export function quoteLiteral(value: string): string {
    return `'${value.replace(/'/g, "''")}'`;
}

/**
 * Render one parameter value per its declared type. Returns `null` when the value is unusable.
 *
 * ⚠ A `text` parameter is deliberately NOT trimmed: a single space is a legitimate separator for
 * "Join with another column", and trimming it reported a valid row as missing its separator.
 * Every other type is whitespace-insensitive, so those are trimmed before validating.
 */
function renderParam(param: SqlFunctionParam, raw: string | undefined): string | null {
    const supplied = raw ?? param.default ?? '';
    if (param.type === 'text') {
        if (supplied === '') return param.optional ? quoteLiteral('') : null;
        return quoteLiteral(supplied);
    }
    const value = supplied.trim();
    if (!value) return null;
    switch (param.type) {
        case 'column':
            return quoteIdentifier(value);
        case 'number':
            return /^-?\d+(\.\d+)?$/.test(value) ? value : null;
        case 'enum':
            return param.options?.includes(value) ? value : null;
        case 'sql':
            return value;
    }
    return null;
}

/**
 * The SQL expression a field compiles to, unaliased — or a `problem` naming the first parameter that is
 * missing or malformed. The grid shows that problem inline on the row and refuses to arm Apply.
 */
export function renderExpression(
    fn: SqlFunction,
    source: string,
    args: Readonly<Record<string, string>>,
): { expr: string } | { problem: string } {
    if (usesSource(fn) && !source.trim()) return { problem: 'Pick the column this field reads.' };
    let out = fn.template.replace(/\{source\}/g, quoteIdentifier(source.trim()));
    for (const param of fn.params ?? []) {
        const rendered = renderParam(param, args[param.name]);
        if (rendered === null) {
            const expected =
                param.type === 'number'
                    ? `“${param.label}” needs a number.`
                    : param.type === 'enum'
                      ? `Choose a value for “${param.label}”.`
                      : `“${param.label}” is required.`;
            return { problem: expected };
        }
        out = out.replace(new RegExp(`\\{${param.name}\\}`, 'g'), () => rendered);
    }
    return { expr: out };
}

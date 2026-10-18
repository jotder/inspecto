/**
 * CSV formula-injection guard for the Breaks export. Break keys and values come from data, and a cell that starts
 * with `=`, `+`, `-`, `@`, a tab or a carriage return runs as a formula when the file is opened in a spreadsheet.
 * The shared `toCsv` only quotes for CSV syntax, so this page neutralises such STRING cells itself.
 */

/** A plain number written as text (`-5`, `3.2`, `1e5`) — safe, and rewriting it would turn a figure into text. */
const NUMERIC_TEXT = /^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/;
const FORMULA_START = /^[=+\-@\t\r]/;

/** The cell as safe to export: a risky string gets a leading single quote; numbers and everything else pass through. */
export function csvSafeCell(v: unknown): unknown {
    if (typeof v !== 'string' || !FORMULA_START.test(v) || NUMERIC_TEXT.test(v)) return v;
    return `'${v}`;
}

/** {@link csvSafeCell} over every cell of a row. */
export function csvSafeRow(row: Record<string, unknown>): Record<string, unknown> {
    return Object.fromEntries(Object.entries(row).map(([k, v]) => [k, csvSafeCell(v)]));
}

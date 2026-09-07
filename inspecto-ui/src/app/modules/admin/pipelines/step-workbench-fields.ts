/**
 * Step workbench — the pure field-list logic (S4a).
 *
 * <p>Framework-free on purpose: reference detection is the one piece with real edge cases, so it is a
 * plain function with its own spec rather than something buried in a component that needs a TestBed to
 * exercise. The component below it only renders what these return.
 */

/** An upstream column as the workbench shows it: name, declared type, and whether this Step reads it. */
export interface WorkbenchField {
    name: string;
    /** Declared type from `upstreamColumnTypes`, or `''` when the upstream never declared one. */
    type: string;
    referenced: boolean;
}

/**
 * ⚠ The default cap on the field list. Operator decision 2026-09-07: a wide feed shows a CAPPED view with
 * the filter box always visible, rather than hundreds of rows — the same call the Parse pane's wide-feed
 * decisions D8–D10 made. The cap applies to what is RENDERED; filtering always searches every column, so a
 * field beyond the cap is still reachable by typing its name.
 */
export const FIELD_LIST_CAP = 50;

/**
 * SQL text with the things an identifier can hide inside removed, so a lexical scan cannot be fooled by
 * them: single-quoted literals, double-quoted identifiers are KEPT (they are the quoted-column case we
 * want to match), `--` line comments and block comments.
 *
 * <p>⚠ Deliberately not a SQL parser. A parser here would be a second implementation of the dialect the
 * engine already owns, and it would rot; this is a lexical approximation whose failure mode is a field
 * shown in the wrong GROUP — cosmetic — never a wrong config write.
 */
function scannableSql(sql: string): string {
    return sql
        .replace(/--[^\n]*/g, ' ')
        .replace(/\/\*[\s\S]*?\*\//g, ' ')
        .replace(/'(?:[^'\\]|\\.|'')*'/g, ' ');
}

/** Escape a column name for use inside a RegExp — a column may legally contain regex metacharacters. */
function escapeForRegExp(value: string): string {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * The subset of `columns` that `sql` references, matched case-insensitively both bare (`amount`) and
 * double-quoted (`"amount"`).
 *
 * <p>Word boundaries alone are not enough: a column named `amount` must not be reported as referenced by
 * `total_amount`, and `\b` does not sit between `_` and a letter because `_` is a word character. So the
 * match requires a NON-identifier character on each side (or a string edge), which is what SQL identifier
 * boundaries actually are.
 */
export function referencedIdentifiers(sql: string, columns: readonly string[]): Set<string> {
    const hay = scannableSql(sql ?? '');
    const hit = new Set<string>();
    for (const col of columns) {
        if (!col) continue;
        const name = escapeForRegExp(col);
        // (^|[^A-Za-z0-9_"]) … ([^A-Za-z0-9_"]|$) — a quote counts as part of the identifier so that
        // `"amount"` matches the quoted form rather than the bare one twice.
        const bare = new RegExp(`(^|[^A-Za-z0-9_"])${name}([^A-Za-z0-9_"]|$)`, 'i');
        const quoted = new RegExp(`"${name}"`, 'i');
        if (bare.test(hay) || quoted.test(hay)) hit.add(col);
    }
    return hit;
}

/**
 * The field list the workbench renders: every upstream column with its declared type and reference flag,
 * REFERENCED FIRST so the fields this Step actually uses are what the author sees without scrolling.
 * Within each group the upstream order is preserved — it is the order the relation declares, and
 * re-sorting it alphabetically would break the correspondence with the sample preview beneath.
 */
export function workbenchFields(
    columns: readonly string[],
    types: Record<string, string>,
    sql: string,
): WorkbenchField[] {
    const referenced = referencedIdentifiers(sql, columns);
    const all = columns.map((name) => ({ name, type: types[name] ?? '', referenced: referenced.has(name) }));
    return [...all.filter((f) => f.referenced), ...all.filter((f) => !f.referenced)];
}

/**
 * `fields` narrowed by the filter box. Case-insensitive substring over the NAME only — matching the type
 * too would make "int" select every integer column, which reads as a bug when you are looking for a
 * column called `int_rate`.
 *
 * <p>⚠ Filtering searches every field; the {@link FIELD_LIST_CAP} applies to the RESULT. That ordering is
 * the point of the cap: a column beyond position 50 is invisible until you type its name, and then it is
 * the first thing you see.
 */
export function filterFields(fields: readonly WorkbenchField[], filter: string): WorkbenchField[] {
    const q = (filter ?? '').trim().toLowerCase();
    return q ? fields.filter((f) => f.name.toLowerCase().includes(q)) : [...fields];
}

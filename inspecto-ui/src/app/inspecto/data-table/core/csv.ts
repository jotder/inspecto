/**
 * CSV export — framework-free (no Angular). `toCsv` is a pure RFC-4180-ish serializer; `downloadCsv` does the
 * browser Blob/anchor dance. Used by the Standard+ tiers of the data-table family.
 */

/** A plain number written as text (`-5`, `3.2`, `1e5`) — safe, and rewriting it would turn a figure into text. */
const NUMERIC_TEXT = /^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/;
/** A cell starting with one of these runs as a formula when the file is opened in a spreadsheet. */
const FORMULA_START = /^[=+\-@\t\r]/;

/** The cell as safe to export: a risky string gets a leading single quote; numbers and everything else pass through. */
export function csvSafeCell(v: unknown): unknown {
    if (typeof v !== 'string' || !FORMULA_START.test(v) || NUMERIC_TEXT.test(v)) return v;
    return `'${v}`;
}

/**
 * Serialize rows × columns to a CSV string (header + body), quoting cells that need it. String cells that would
 * run as a spreadsheet formula are neutralised ({@link csvSafeCell}); a caller whose file is re-imported verbatim
 * (the Grammar CSV) passes `{ formulaGuard: false }`.
 */
export function toCsv(
    rows: readonly Record<string, unknown>[],
    columns: readonly string[],
    opts: { formulaGuard?: boolean } = {},
): string {
    const guard = opts.formulaGuard ?? true;
    const esc = (raw: unknown): string => {
        const v = guard ? csvSafeCell(raw) : raw;
        const s = v == null ? '' : String(v);
        return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    };
    const header = columns.map(esc).join(',');
    const body = rows.map((r) => columns.map((c) => esc(r[c])).join(',')).join('\n');
    return body ? `${header}\n${body}` : header;
}

/**
 * Minimal RFC-4180 parse (quoted cells carry commas/newlines — how EXPR expressions travel).
 * Extracted from `editable-grid.component.ts` (U4, delimited-grammar-properties plan) so the
 * Grammar CSV import shares the one parser with the editable grid and the mapping editor.
 */
export function parseCsv(text: string): string[][] {
    if (text.startsWith('﻿')) text = text.slice(1); // the BOM downloadCsv writes
    const rows: string[][] = [];
    let row: string[] = [];
    let cell = '';
    let quoted = false;
    for (let i = 0; i < text.length; i++) {
        const ch = text[i];
        if (quoted) {
            if (ch === '"' && text[i + 1] === '"') {
                cell += '"';
                i++;
            } else if (ch === '"') quoted = false;
            else cell += ch;
        } else if (ch === '"') {
            quoted = true;
        } else if (ch === ',') {
            row.push(cell);
            cell = '';
        } else if (ch === '\n' || ch === '\r') {
            if (ch === '\r' && text[i + 1] === '\n') i++;
            row.push(cell);
            cell = '';
            rows.push(row);
            row = [];
        } else {
            cell += ch;
        }
    }
    if (cell.length || row.length) {
        row.push(cell);
        rows.push(row);
    }
    return rows.filter((r) => r.some((c) => c.trim().length));
}

/** Trigger a client-side CSV download (browser DOM, not Angular). A UTF-8 BOM makes Excel read non-ASCII correctly. */
export function downloadCsv(name: string, csv: string): void {
    const blob = new Blob([csv.startsWith('﻿') ? csv : '﻿' + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name.endsWith('.csv') ? name : `${name}.csv`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
}

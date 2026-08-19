/**
 * CSV export — framework-free (no Angular). `toCsv` is a pure RFC-4180-ish serializer; `downloadCsv` does the
 * browser Blob/anchor dance. Used by the Standard+ tiers of the data-table family.
 */

/** Serialize rows × columns to a CSV string (header + body), quoting cells that need it. */
export function toCsv(rows: readonly Record<string, unknown>[], columns: readonly string[]): string {
    const esc = (v: unknown): string => {
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

/** Trigger a client-side CSV download (browser DOM, not Angular). */
export function downloadCsv(name: string, csv: string): void {
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name.endsWith('.csv') ? name : `${name}.csv`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
}

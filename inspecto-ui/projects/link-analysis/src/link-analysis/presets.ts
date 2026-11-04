import type { ColDef } from 'ag-grid-community';
import type { PresetParam, PresetRunResult } from '@inspecto/link-analysis/api/inv.service';

/**
 * LA-PRESET-QUERIES-1 - the pure half of the preset panel: the table's columns, what *Draw these* starts from, and the
 * one rule a parameter's value must meet before it is sent. No Angular, no HTTP.
 */

/** The result table's columns, in the server's order and words; a number reads at most two decimals. */
export function presetColumns(r: PresetRunResult | null): ColDef[] {
    return (r?.columns ?? []).map((c) =>
        c.entity
            ? { field: c.id, headerName: c.label }
            : { field: c.id, headerName: c.label, valueFormatter: (p: { value: unknown }) => presetNumber(p.value) },
    );
}

/** A measure as read: `119292.33999999998` → `119,292.34`; anything not a number unchanged. */
export function presetNumber(v: unknown): string {
    return typeof v === 'number' && Number.isFinite(v)
        ? v.toLocaleString('en', { maximumFractionDigits: 2 })
        : v === null || v === undefined
          ? ''
          : String(v);
}

/** What *Draw these* starts from: every entity column's values (aliases when the Space masks), once each. */
export function presetEntityValues(r: PresetRunResult | null): string[] {
    if (!r) return [];
    const cols = r.columns.filter((c) => c.entity).map((c) => c.id);
    const out = new Set<string>();
    for (const row of r.rows)
        for (const c of cols) {
            const v = row[c];
            if (v !== null && v !== undefined && String(v) !== '') out.add(String(v));
        }
    return [...out];
}

/** Where the answer came from, in one line: the link index (and whether it is behind), else nothing - the Dataset is the default. */
export function presetSourceLine(r: Pick<PresetRunResult, 'source'> | null): string {
    const s = r?.source;
    if (!s || s.kind !== 'index') return '';
    const behind = s.stale ? ` It is behind the data (${s.staleReason ?? 'files were added since it was built'}).` : '';
    const approx = s.approximate ? ' Counts are an upper bound: the index has appended parts.' : '';
    return `Answered from the link index (version ${s.version}).${behind}${approx}`;
}

/** A number parameter: a plain non-negative number. A window: `<N>h` or `<N>d`. */
export const PRESET_PATTERNS: Partial<Record<PresetParam['type'], RegExp>> = {
    number: /^\d+(\.\d+)?$/,
    window: /^[1-9]\d{0,5}[hd]$/,
};

/** The values to send: trimmed, blanks left out (the server then uses the preset's default). */
export function presetParamValues(raw: Record<string, string | null>): Record<string, string> {
    const out: Record<string, string> = {};
    for (const [k, v] of Object.entries(raw)) if (v !== null && v.trim() !== '') out[k] = v.trim();
    return out;
}

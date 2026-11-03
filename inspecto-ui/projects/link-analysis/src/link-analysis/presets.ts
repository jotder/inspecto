import type { ColDef } from 'ag-grid-community';
import type { PresetParam, PresetRunResult } from '@inspecto/link-analysis/api/inv.service';

/**
 * LA-PRESET-QUERIES-1 - the pure half of the preset panel: the table's columns, what *Draw these* starts from, and the
 * one rule a parameter's value must meet before it is sent. No Angular, no HTTP.
 */

/** The result table's columns, in the server's order and words. */
export function presetColumns(r: PresetRunResult | null): ColDef[] {
    return (r?.columns ?? []).map((c) => ({ field: c.id, headerName: c.label }));
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

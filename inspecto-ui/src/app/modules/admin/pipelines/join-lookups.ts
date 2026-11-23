/**
 * The `lookups:` of a keyed `transform.join` (LOOKUP-1 P1d) as editable rows, and back. Framework-free; the form
 * is `join-lookups-editor.component.ts`. The shape is the engine's (`RowShaper.JOIN_LOOKUP_KEYS`): an entry takes
 * `on`, `columns`, `as`, `default`, `details`, and nothing else.
 *
 * Nothing the engine reads is dropped: a `details` map (custom names) is kept verbatim, a default value keeps its
 * stored type until edited, and a `columns: {}` (details only) survives. A value this cannot read as entries at all
 * gives `null`, and the pane then leaves `lookups` where it was (the Additional config row).
 */

export interface LookupColumn {
    /** The Reference column. */
    ref: string;
    /** The output name; blank = the engine's own `<as><column>`. */
    out: string;
}

export interface LookupDefault {
    /** An OUTPUT column name. */
    name: string;
    value: unknown;
}

export interface LookupRow {
    on: string;
    as: string;
    columns: LookupColumn[];
    /** `columns: {}` was stored: no Reference column, so the mapping is details only. */
    emptyColumns: boolean;
    defaults: LookupDefault[];
    /** `details: true`. */
    details: boolean;
    /** A stored `details` map, written back as it was. */
    detailsRaw?: Record<string, unknown>;
}

const ENTRY_KEYS = ['on', 'columns', 'as', 'default', 'details'];

const plain = (x: unknown): x is Record<string, unknown> => x !== null && typeof x === 'object' && !Array.isArray(x);

/** Rows for a stored `lookups`; `[]` when absent; `null` when it is not a list of entries this can edit. */
export function parseLookups(raw: unknown): LookupRow[] | null {
    if (raw === undefined || raw === null) return [];
    if (!Array.isArray(raw)) return null;
    const rows: LookupRow[] = [];
    for (const e of raw) {
        if (!plain(e) || Object.keys(e).some((k) => !ENTRY_KEYS.includes(k))) return null;
        const row: LookupRow = {
            on: String(e['on'] ?? ''),
            as: e['as'] == null ? '' : String(e['as']),
            columns: [],
            emptyColumns: false,
            defaults: [],
            details: false,
        };
        const cols = e['columns'];
        if (plain(cols)) {
            for (const [ref, out] of Object.entries(cols))
                row.columns.push({ ref, out: out == null ? '' : String(out) });
        } else if (Array.isArray(cols)) {
            for (const c of cols) row.columns.push({ ref: String(c), out: '' });
        } else if (typeof cols === 'string' && cols.trim()) {
            row.columns.push({ ref: cols.trim(), out: '' });
        } else if (cols !== undefined && cols !== null) return null;
        row.emptyColumns = cols !== undefined && cols !== null && row.columns.length === 0;
        const dflt = e['default'];
        if (plain(dflt)) {
            for (const [name, value] of Object.entries(dflt)) row.defaults.push({ name, value });
        } else if (Array.isArray(dflt)) {
            for (const d of dflt) {
                const s = String(d);
                const eq = s.indexOf('=');
                if (eq <= 0) return null;
                row.defaults.push({ name: s.slice(0, eq).trim(), value: s.slice(eq + 1) });
            }
        } else if (dflt !== undefined && dflt !== null) return null;
        const d = e['details'];
        if (plain(d)) row.detailsRaw = d;
        else row.details = d === true || String(d).toLowerCase() === 'true';
        rows.push(row);
    }
    return rows;
}

/** The prefix the engine puts before an output name that has none of its own. */
export function lookupPrefix(row: LookupRow): string {
    return (row.as.trim() === '' ? '{on}_' : row.as).replace('{on}', row.on.trim());
}

/** The names this mapping adds, as the engine will name them. */
export function outputNames(row: LookupRow): string[] {
    const prefix = lookupPrefix(row);
    const names = row.columns.filter((c) => c.ref.trim()).map((c) => c.out.trim() || prefix + c.ref.trim());
    if (row.detailsRaw) {
        for (const k of ['matched_key', 'match_kind']) {
            const v = row.detailsRaw[k];
            if (v != null && String(v).trim()) names.push(String(v).trim());
        }
    } else if (row.details) names.push(prefix + 'matched_key', prefix + 'match_kind');
    return names;
}

/** What is wrong with the rows, or null. The engine refuses the same things at save or run; this says so sooner. */
export function lookupsProblem(rows: LookupRow[]): string | null {
    const seen = new Set<string>();
    for (const [i, r] of rows.entries()) {
        const n = i + 1;
        if (!r.on.trim()) return `Mapping ${n} needs the column it looks up.`;
        if (r.columns.some((c) => !c.ref.trim())) return `Mapping ${n} has a returned column with no Reference column.`;
        if (r.defaults.some((d) => !d.name.trim())) return `Mapping ${n} has a default with no column name.`;
        for (const name of outputNames(r)) {
            const k = name.toLowerCase();
            if (seen.has(k)) return `Two outputs are named ${name}; rename one of them.`;
            seen.add(k);
        }
        // with no column listed the mapping returns every Reference column, whose names are not known here
        const known = r.columns.some((c) => c.ref.trim()) || r.emptyColumns || r.details || !!r.detailsRaw;
        const outs = outputNames(r).map((x) => x.toLowerCase());
        for (const d of known ? r.defaults : [])
            if (!outs.includes(d.name.trim().toLowerCase()))
                return `Mapping ${n} has a default for ${d.name.trim()}, which is not one of its output columns.`;
    }
    return null;
}

/** The `lookups:` value to store: `undefined` when there are no mappings (the key is then dropped). */
export function formatLookups(rows: LookupRow[]): Record<string, unknown>[] | undefined {
    if (!rows.length) return undefined;
    return rows.map((r) => {
        const e: Record<string, unknown> = { on: r.on.trim() };
        const listed = r.columns.filter((c) => c.ref.trim());
        if (listed.length) {
            const prefix = lookupPrefix(r);
            // a rename map needs every output named; a blank one takes the name the engine would have given it
            e['columns'] = listed.some((c) => c.out.trim())
                ? Object.fromEntries(listed.map((c) => [c.ref.trim(), c.out.trim() || prefix + c.ref.trim()]))
                : listed.map((c) => c.ref.trim());
        } else if (r.emptyColumns || r.details || r.detailsRaw) e['columns'] = {};
        if (r.as.trim()) e['as'] = r.as.trim();
        const defaults = r.defaults.filter((d) => d.name.trim());
        if (defaults.length) e['default'] = Object.fromEntries(defaults.map((d) => [d.name.trim(), d.value]));
        if (r.detailsRaw) e['details'] = r.detailsRaw;
        else if (r.details) e['details'] = true;
        return e;
    });
}

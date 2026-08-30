import { SchemaFieldRow } from 'app/inspecto/schema';
import { parseCsv, toCsv } from 'app/inspecto/data-table/core/csv';
import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * The Grammar CSV round-trip (§4.5 of the delimited-grammar-properties plan) — the portable,
 * Excel-editable export/import that replaced "Save as template…" on the parse surfaces.
 *
 * Tidy long-form RFC-4180, one property per row, order-independent:
 *
 *     section,key,attr,value
 *     meta,format,,delimited
 *     meta,version,,1
 *     meta,pipeline,,orders_daily
 *     meta,types,,auto
 *     option,delimiter,,"|"
 *     option,null_strings,,"NULL,N/A"
 *     column,0,include,true
 *     column,0,name,customer_id
 *
 * `option` keys are the ENGINE key names (`delimiter`, not `delimited__delimiter`); `column` keys
 * are the field's selector. Framework-free so both adopters (drawer + dialog) share one format and
 * a spec can pin the round-trip without a TestBed.
 */

/** The file's meta rows. `types` is optional (only parse steps with a columns table carry it). */
export interface GrammarCsvMeta {
    format: string;
    pipeline: string;
    types?: 'auto' | 'declared';
}

export interface GrammarCsvImport {
    meta: GrammarCsvMeta;
    /** Flat editor values (spec keys, e.g. `delimited__delimiter`) for the KNOWN option keys. */
    options: Record<string, unknown>;
    /** Option keys the spec set does not know — listed to the operator, never applied (the engine
     *  would silently drop them; §2's trap). */
    unknownKeys: string[];
    /** The columns table, replacing the current one wholesale — null when the file carries none. */
    columns: SchemaFieldRow[] | null;
}

/** The operator-specified filename: `<pipeline>_parser.csv` (verbatim — a filename is not UI copy). */
export function grammarCsvFilename(pipelineName: string): string {
    const base = (pipelineName || 'grammar').replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^_+|_+$/g, '') || 'grammar';
    return `${base}_parser.csv`;
}

/**
 * The engine key an editor spec key maps to (`delimited__delimiter` → `delimiter`,
 * `xlsx__sheet` → `sheet`; shared keys as-is). The file's `meta.format` scopes the bare name, so
 * stripping ANY frontend prefix is unambiguous — a Grammar CSV is per-format by construction.
 */
function engineKeyOf(specKey: string): string {
    return specKey.replace(/^[a-z_]+__/, '');
}

const COLUMN_ATTRS = ['include', 'name', 'type', 'synonym', 'description', 'unit', 'classification'] as const;

/** Serialize the current state. Writes every SET option + every column with all its attributes. */
export function grammarToCsv(
    meta: GrammarCsvMeta,
    specs: AttributeSpec[],
    values: Record<string, unknown>,
    columns: SchemaFieldRow[],
): string {
    const rows: Record<string, unknown>[] = [
        { section: 'meta', key: 'format', attr: '', value: meta.format },
        { section: 'meta', key: 'version', attr: '', value: '1' },
        { section: 'meta', key: 'pipeline', attr: '', value: meta.pipeline },
    ];
    if (meta.types) rows.push({ section: 'meta', key: 'types', attr: '', value: meta.types });
    for (const s of specs) {
        const v = values[s.key];
        if (v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0)) continue;
        rows.push({
            section: 'option',
            key: engineKeyOf(s.key),
            attr: '',
            value: Array.isArray(v) ? v.join(',') : String(v),
        });
    }
    columns.forEach((c) => {
        const record = c as unknown as Record<string, unknown>;
        for (const attr of COLUMN_ATTRS) {
            const v = record[attr];
            if (v === undefined || v === null || v === '') continue;
            rows.push({ section: 'column', key: String(c.selector), attr, value: String(v) });
        }
    });
    return toCsv(rows, ['section', 'key', 'attr', 'value']);
}

/**
 * Parse an exported (or hand-edited) file back into editor values + columns.
 *
 * Throws `Error` when the file is not a Grammar CSV at all (no `meta,format` row) — the caller
 * refuses outright on a format MISMATCH with the active frontend, which only it knows.
 */
export function parseGrammarCsv(text: string, specs: AttributeSpec[]): GrammarCsvImport {
    const rows = parseCsv(text);
    if (!rows.length) throw new Error('The file is empty.');
    // Tolerate a header row (`section,key,attr,value`) and files without one — order-independent.
    const body = rows.filter((r) => r[0]?.trim().toLowerCase() !== 'section');
    const meta: Partial<GrammarCsvMeta> = {};
    const byEngineKey = new Map<string, AttributeSpec>(specs.map((s) => [engineKeyOf(s.key), s]));
    // Back-compat: files exported before the prefix-strip generalized carried the RAW spec key for
    // non-delimited frontends (e.g. `fixedwidth__min_record_length`) — keep accepting those spellings.
    for (const s of specs) if (!byEngineKey.has(s.key)) byEngineKey.set(s.key, s);
    const options: Record<string, unknown> = {};
    const unknownKeys: string[] = [];
    const columnRows = new Map<string, Record<string, string>>();

    for (const r of body) {
        const [section, key, attr, value] = [r[0]?.trim(), r[1]?.trim(), r[2]?.trim(), r[3] ?? ''];
        if (!section || !key) continue;
        if (section === 'meta') {
            if (key === 'format') meta.format = value.trim();
            else if (key === 'pipeline') meta.pipeline = value.trim();
            else if (key === 'types') meta.types = value.trim() === 'auto' ? 'auto' : 'declared';
        } else if (section === 'option') {
            const spec = byEngineKey.get(key);
            if (!spec) {
                unknownKeys.push(key);
                continue;
            }
            options[spec.key] =
                spec.type === 'list'
                    ? value
                          .split(',')
                          .map((x) => x.trim())
                          .filter(Boolean)
                    : spec.type === 'boolean'
                      ? value.trim().toLowerCase() === 'true'
                      : spec.type === 'number'
                        ? Number(value)
                        : value;
        } else if (section === 'column' && attr) {
            const c = columnRows.get(key) ?? {};
            c[attr] = value;
            columnRows.set(key, c);
        }
    }

    if (!meta.format) throw new Error("The file carries no 'meta,format' row — not a Grammar CSV.");

    const columns: SchemaFieldRow[] | null = columnRows.size
        ? [...columnRows.entries()].map(([selector, attrs]) => ({
              include: (attrs['include'] ?? 'true').trim().toLowerCase() !== 'false',
              name: attrs['name'] ?? '',
              selector,
              type: attrs['type'] ?? 'VARCHAR',
              ...(attrs['synonym'] ? { synonym: attrs['synonym'] } : {}),
              // D1(b): the Catalog metadata attrs round-trip too (the export always wrote them;
              // the import used to drop them silently).
              ...(attrs['description'] ? { description: attrs['description'] } : {}),
              ...(attrs['unit'] ? { unit: attrs['unit'] } : {}),
              ...(attrs['classification'] ? { classification: attrs['classification'] } : {}),
          }))
        : null;

    return {
        meta: { format: meta.format, pipeline: meta.pipeline ?? '', types: meta.types },
        options,
        unknownKeys,
        columns,
    };
}

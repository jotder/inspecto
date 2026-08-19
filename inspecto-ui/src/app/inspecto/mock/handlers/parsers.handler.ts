import type { ParserPreview, ParserTreeNode } from '../../api/components.service';
import type { ParserDef, ServedFieldSpec } from '../../api/parsers.service';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';

/**
 * `/parsers` mock domain — the served parser catalog (`GET /parsers`) + the stateless grammar
 * preview (`POST /parsers/{id}/preview`), mirroring `ParserRoutes` + the `Parsers` registry.
 *
 * ⚠ A MOCK MUST NEVER BE MORE LENIENT THAN THE SERVER: the catalog below is a verbatim
 * transcription of `BuiltinParsers` + `XmlParserPlugin` (ids, labels, schema paths, ingestable
 * flags), and the preview enforces the same refusals — 404 unknown id, 400 missing/oversized
 * sample, 422 malformed sample/grammar with the server's message shapes. Strictness is pinned in
 * `parsers.handler.spec.ts`.
 */

const PARSERS = /\/parsers$/;
const PARSER_PREVIEW = /\/parsers\/([^/]+)\/preview$/;

/** Server cap on `sample_text` (ParserRoutes.MAX_SAMPLE_CHARS). */
const MAX_SAMPLE_CHARS = 1_000_000;

const str = (path: string, label: string, description: string): ServedFieldSpec => ({
    path,
    label,
    description,
    type: 'STRING',
});
const int = (path: string, label: string, description: string): ServedFieldSpec => ({
    path,
    label,
    description,
    type: 'INT',
});
const list = (path: string, label: string, description: string): ServedFieldSpec => ({
    path,
    label,
    description,
    type: 'LIST',
});

/** The catalog `Parsers.catalog()` serves — built-ins first, then discovered plugins. */
const CATALOG: ParserDef[] = [
    {
        id: 'delimited',
        label: 'Delimited — CSV / TSV / pipe, one record per line',
        hierarchical: false,
        ingestable: true,
        grammarSchema: [
            // Dialect
            {
                path: 'delimited.delimiter',
                label: 'Delimiter',
                type: 'STRING',
                defaultValue: ',',
                description: 'Column separator character.',
            },
            str(
                'delimited.quote',
                'Quote character',
                'Single character wrapping fields that contain the delimiter (default ").',
            ),
            str(
                'delimited.escape',
                'Escape character',
                'Single character escaping a literal quote inside a quoted field (default: the quote, doubled).',
            ),
            str(
                'delimited.comment',
                'Comment character',
                'Lines starting with this single character are skipped (default: none).',
            ),
            {
                path: 'delimited.has_header',
                label: 'First line is a header',
                type: 'BOOL',
                defaultValue: true,
                description: 'Whether line one carries the column names.',
            },
            int(
                'delimited.skip_header_lines',
                'Skip leading lines',
                'Banner/preamble lines before the data (and header).',
            ),
            int(
                'delimited.skip_junk_lines',
                'Skip junk lines (adaptive)',
                'Max preamble lines to probe past until a parseable data row; -1 = unlimited.',
            ),
            int('delimited.skip_tail_lines', 'Skip trailing lines', 'Footer lines dropped from the end of each file.'),
            int(
                'delimited.skip_tail_columns',
                'Skip trailing columns',
                'Phantom columns stripped from the right of each record.',
            ),
            str('encoding', 'Encoding', 'Character encoding (default UTF-8).'),
            // Types & columns
            list('delimited.date_formats', 'Date formats', 'Accepted DATE parse patterns, tried in order.'),
            list(
                'delimited.timestamp_formats',
                'Timestamp formats',
                'Accepted TIMESTAMP parse patterns, tried in order.',
            ),
            list('delimited.null_strings', 'Null strings', "Literal text values read as NULL, e.g. ['', 'NULL', 'N/A']."),
            // Robustness
            {
                path: 'delimited.strict_mode',
                label: 'Strict mode (RFC-4180)',
                type: 'BOOL',
                description: 'Blank = engine default (true). false tolerates quote/column drift.',
            },
            {
                path: 'delimited.engine',
                label: 'Parse engine',
                type: 'ENUM',
                enumValues: ['auto', 'duckdb', 'java'],
                defaultValue: 'auto',
                description: 'duckdb = native reader; java = univocity fallback for messy files.',
            },
            list(
                'delimited.include_prefixes',
                'Include rows: prefixes',
                'Keep only rows whose filter target column starts with any of these.',
            ),
            list(
                'delimited.include_regex',
                'Include rows: regex',
                'Keep only rows whose filter target column matches any of these.',
            ),
            list(
                'delimited.exclude_prefixes',
                'Exclude rows: prefixes',
                'Drop rows whose filter target column starts with any of these.',
            ),
            list(
                'delimited.exclude_regex',
                'Exclude rows: regex',
                'Drop rows whose filter target column matches any of these.',
            ),
            {
                path: 'delimited.filter_target_column',
                label: 'Filter target column',
                type: 'INT',
                defaultValue: 0,
                description: '0-based physical column the include/exclude filters apply to.',
            },
            {
                path: 'delimited.where',
                label: 'Row filter (SQL)',
                type: 'SQL',
                description: 'Post-parse SQL predicate over the mapped, typed columns, e.g. amount > 0.',
            },
            // Files
            str('compression', 'Input compression', 'auto / gzip / zstd / none — decompressed inline at read.'),
        ],
    },
    {
        id: 'fixedwidth',
        label: 'Fixed width — positional slices carved from each line',
        hierarchical: false,
        ingestable: true,
        grammarSchema: [
            {
                path: 'fixedwidth.fields',
                label: 'Fields',
                type: 'LIST',
                required: true,
                description: 'Positional slices: one {name, start, length} per field.',
            },
            {
                path: 'delimited.has_header',
                label: 'First line is a header',
                type: 'BOOL',
                defaultValue: true,
                description: 'Header/banner line to skip before the records.',
            },
            int(
                'fixedwidth.min_record_length',
                'Minimum record length',
                'Shorter lines (footers, blanks) are dropped. Absent = the widest field end.',
            ),
            {
                path: 'fixedwidth.trim',
                label: 'Trim fields',
                type: 'ENUM',
                enumValues: ['BOTH', 'LEFT', 'RIGHT', 'NONE'],
                defaultValue: 'BOTH',
                description: 'Whitespace trim per slice.',
            },
            str('encoding', 'Encoding', 'Character encoding (default UTF-8).'),
            str('compression', 'Input compression', 'e.g. gzip.'),
        ],
    },
    {
        id: 'json',
        label: 'JSON — NDJSON (one object per line) or a JSON array document',
        hierarchical: false,
        ingestable: true,
        grammarSchema: [
            {
                path: 'json.format',
                label: 'Document shape',
                type: 'ENUM',
                enumValues: ['newline', 'array', 'auto'],
                defaultValue: 'newline',
                description: 'NDJSON, one JSON array of records, or auto-detect.',
            },
            int('delimited.skip_header_lines', 'Skip leading lines', 'Non-JSON preamble lines before the records.'),
            {
                path: 'json.ignore_errors',
                label: 'Keep malformed records as NULL rows',
                type: 'BOOL',
                defaultValue: false,
                description: 'array/auto only: a record that fails to parse lands as an all-NULL row.',
            },
            int('json.maximum_object_size', 'Maximum object size (bytes)',
                'array/auto only: bound on a single document/record the reader buffers.'),
            str('compression', 'Input compression', 'e.g. gzip.'),
        ],
    },
    {
        id: 'xlsx',
        label: 'MS Excel — read_xlsx over a workbook (DuckDB excel extension)',
        hierarchical: false,
        ingestable: true,
        grammarSchema: [
            str('xlsx.sheet', 'Sheet', "Sheet NAME to read; empty = the workbook's first sheet."),
            str('xlsx.range', 'Cell range', 'A1-style anchor or span (B2 or A1:F100); empty = the whole sheet.'),
            {
                path: 'xlsx.header',
                label: 'First row is a header',
                type: 'BOOL',
                defaultValue: true,
                description: 'Header cells name the columns; without one, columns are A, B, C…',
            },
            {
                path: 'xlsx.stop_at_empty',
                label: 'Stop at first empty row',
                type: 'BOOL',
                defaultValue: false,
                description: 'Forced on by the extension when no explicit range is given.',
            },
            {
                path: 'xlsx.ignore_errors',
                label: 'Ignore cell errors',
                type: 'BOOL',
                defaultValue: false,
                description: 'Unrepresentable cells land as NULL instead of failing the file.',
            },
            {
                path: 'xlsx.normalize_names',
                label: 'Normalize header names',
                type: 'BOOL',
                defaultValue: false,
                description: 'Lower-snake identifiers from the header cells.',
            },
        ],
    },
    {
        id: 'text_regex',
        label: 'Text / regex — named capture groups over matching lines',
        hierarchical: false,
        ingestable: true,
        grammarSchema: [
            {
                path: 'text_regex.pattern',
                label: 'Pattern',
                type: 'STRING',
                required: true,
                description:
                    'At least one named capture group — group names become the columns; non-matching lines are dropped.',
            },
            int('delimited.skip_header_lines', 'Skip leading lines', 'Preamble lines before the records.'),
            str('encoding', 'Encoding', 'Character encoding (default UTF-8).'),
        ],
    },
    {
        id: 'xml',
        label: 'XML — XML file format',
        hierarchical: true,
        ingestable: false, // preview-only until the flatten configuration
        grammarSchema: [
            str(
                'xml.record_element',
                'Record element',
                'Element that starts one record — a local name (order) or a slash path (orders/order). Blank = every direct child of the root.',
            ),
            {
                path: 'xml.namespace_aware',
                label: 'Namespace aware',
                type: 'BOOL',
                defaultValue: false,
                description: 'Resolve namespaces (element labels then use local names).',
            },
            str('xml.encoding', 'Encoding', "Overrides the document prolog's encoding (default: auto-detect)."),
            {
                path: 'xml.max_records',
                label: 'Preview records',
                type: 'INT',
                defaultValue: 50,
                description: 'Records materialized into the preview tree (max 1000).',
            },
        ],
    },
    {
        id: 'asn1',
        label: 'ASN.1 — BER/DER encoded records',
        // Hierarchical like xml, but ingestable: Asn1RecordIngester flattens records onto segment
        // schemas through the existing parsing.plugin machinery.
        hierarchical: true,
        ingestable: true,
        ingesterClass: 'com.gamma.ingester.Asn1RecordIngester',
        grammarSchema: [
            str(
                'asn1.grammar',
                'ASN.1 grammar',
                'The ASN.1 module text (X.680 syntax) defining the record type. Leave EMPTY to dump the file’s raw TLV structure instead — BER is self-describing, so an unknown file can be inspected before its module is available. A grammar is still required to ingest.',
            ),
            str(
                'asn1.root_type',
                'Root type',
                'Name of the type in the grammar each record binds against, e.g. Record. Required when a grammar is supplied; ignored in structural mode.',
            ),
            {
                path: 'asn1.strictness',
                label: 'Strictness',
                type: 'ENUM',
                defaultValue: 'BER',
                enumValues: ['BER', 'DER', 'CER'],
                description: 'Encoding rules enforced while decoding: BER (permissive), DER, or CER.',
            },
            {
                path: 'asn1.file_header_length',
                label: 'File header bytes',
                type: 'INT',
                defaultValue: 0,
                description:
                    'Bytes to skip at the start of the file before the first record (e.g. 50 for Huawei-framed files). 0 = none.',
            },
            {
                path: 'asn1.record_header_length',
                label: 'Record header bytes',
                type: 'INT',
                defaultValue: 0,
                description:
                    'Bytes preceding each record’s TLV, skipped (e.g. 4 for Huawei-framed files). 0 = bare back-to-back TLVs. Records stay delimited by their own BER length.',
            },
            {
                path: 'asn1.max_records',
                label: 'Preview records',
                type: 'INT',
                defaultValue: 50,
                description: 'Records materialized into the preview tree (max 1000).',
            },
        ],
    },
];

export function parsersHandler(): MockHandler {
    return (req: MockRequest) => {
        const { method, url } = req;
        if (method === 'GET' && PARSERS.test(url)) return json(CATALOG);
        let m: string[] | null;
        if (method === 'POST' && (m = match(url, PARSER_PREVIEW))) return preview(m[1], req);
        return undefined;
    };
}

function preview(id: string, req: MockRequest) {
    const def = CATALOG.find((p) => p.id === id);
    if (!def) return error(404, `unknown parser: ${id}`);
    const b = (req.body ?? {}) as { grammar?: Record<string, unknown>; sample_text?: string; sample_b64?: string };
    const sample = String(b.sample_text ?? '');
    if (!sample.trim() && !b.sample_b64) return error(400, "body must include 'sample_text' or 'sample_b64'");
    if (sample.length > MAX_SAMPLE_CHARS) return error(400, `sample_text too large (max ${MAX_SAMPLE_CHARS} chars)`);
    const grammar = b.grammar ?? {};
    try {
        return json(parse(id, grammar, sample));
    } catch (e) {
        return error(422, e instanceof Error ? e.message : String(e));
    }
}

function parse(id: string, grammar: Record<string, unknown>, sample: string): ParserPreview {
    switch (id) {
        case 'delimited':
            return delimited(grammar, sample);
        case 'fixedwidth':
            return fixedwidth(grammar, sample);
        case 'json':
            return jsonRecords(grammar, sample);
        case 'text_regex':
            return textRegex(grammar, sample);
        case 'xml':
            return xmlTree(grammar, sample);
        // ASN.1 is in the catalog (the segments editor gates on its ingesterClass), but its input
        // is BINARY BER — a mock decoder would be a second ASN.1 implementation and a lie either
        // way. Refuse honestly: STRICTER than the server, never more lenient.
        case 'asn1':
            throw new Error('ASN.1 preview needs the real decoder — not available in mock mode');
        // Same rule as ASN.1: a workbook is binary and read_xlsx is the real DuckDB extension —
        // a mock xlsx reader would be a second implementation and a lie either way. STRICTER than
        // the server, never more lenient.
        case 'xlsx':
            throw new Error('Excel preview needs the real read_xlsx — not available in mock mode');
        default:
            throw new Error(`unknown parser: ${id}`);
    }
}

const lines = (sample: string): string[] => sample.split(/\r?\n/).filter((l) => l.length > 0);
const sub = (grammar: Record<string, unknown>, key: string): Record<string, unknown> =>
    (grammar[key] ?? {}) as Record<string, unknown>;

function table(
    columns: string[],
    rows: Record<string, unknown>[],
    rejected = 0,
    columnTypes?: { name: string; type: string }[],
): ParserPreview {
    const t: ParserPreview = { kind: 'table', columns, rows, rowCount: rows.length, rejectedRows: rejected };
    // B2 parity: the key is ADDITIVE and only the delimited arm supplies it — exactly like the
    // server, whose auto_detect sniff runs for the delimited frontend only.
    if (columnTypes?.length) t.columnTypes = columnTypes;
    return t;
}

/**
 * B2 mock parity: a small DETERMINISTIC type inferrer mirroring the server's auto_detect sniff —
 * same key, same casing, never more lenient (an all-empty column is VARCHAR, not a guess).
 */
function inferColumnTypes(columns: string[], rows: Record<string, unknown>[]): { name: string; type: string }[] {
    const typeOf = (values: string[]): string => {
        if (!values.length) return 'VARCHAR';
        if (values.every((v) => /^-?\d+$/.test(v))) return 'BIGINT';
        if (values.every((v) => /^-?\d+(\.\d+)?$/.test(v))) return 'DOUBLE';
        if (values.every((v) => /^\d{4}-\d{2}-\d{2}$/.test(v))) return 'DATE';
        if (values.every((v) => /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}/.test(v))) return 'TIMESTAMP';
        if (values.every((v) => /^(true|false)$/i.test(v))) return 'BOOLEAN';
        return 'VARCHAR';
    };
    return columns.map((name) => {
        const values = rows
            .map((r) => r[name])
            .filter((v): v is string => v !== null && v !== undefined && String(v) !== '')
            .map(String);
        return { name, type: typeOf(values) };
    });
}

function delimited(grammar: Record<string, unknown>, sample: string): ParserPreview {
    const g = sub(grammar, 'delimited');
    const delim = String(g['delimiter'] ?? ',') || ',';
    const skip = Number(g['skip_header_lines'] ?? 0);
    const hasHeader = g['has_header'] !== false;
    const all = lines(sample).slice(skip);
    if (all.length === 0) throw new Error('sample does not parse with this grammar: no data lines');
    const header = hasHeader ? all[0].split(delim) : all[0].split(delim).map((_, i) => `column${i}`);
    const data = hasHeader ? all.slice(1) : all;
    const rows = data.map((l) => {
        const cells = l.split(delim);
        return Object.fromEntries(header.map((h, i) => [h, cells[i] ?? null]));
    });
    return table(header, rows, 0, inferColumnTypes(header, rows));
}

function fixedwidth(grammar: Record<string, unknown>, sample: string): ParserPreview {
    const g = sub(grammar, 'fixedwidth');
    const fields = (g['fields'] ?? []) as { name: string; start: number; length: number }[];
    if (!fields.length) throw new Error('fixed width needs at least one field');
    const skip = sub(grammar, 'delimited')['has_header'] !== false ? 1 : 0;
    const rows = lines(sample)
        .slice(skip)
        .map((l) => Object.fromEntries(fields.map((f) => [f.name, l.substring(f.start, f.start + f.length).trim()])));
    return table(
        fields.map((f) => f.name),
        rows,
    );
}

/**
 * The `json` frontend, honouring the **document shape** the grammar declares (`json.format`, the same
 * `newline | array | auto` enum `BuiltinParsers` publishes).
 *
 * <p>⚠ This arm used to be NDJSON-only and ignore `json.format` outright, which made the offline preview
 * actively misleading rather than merely limited: a builder pasting a JSON **array** document and picking
 * "One JSON array of records" — the correct setting — was told 3 of its 4 lines were REJECTED, and would
 * reasonably conclude the file was broken. Refusing would have been acceptable; answering wrongly is not.
 * `auto` tries the array first and falls back, exactly as the enum's own description says.
 */
function jsonRecords(grammar: Record<string, unknown>, sample: string): ParserPreview {
    const format = String(sub(grammar, 'json')['format'] ?? 'newline');
    if (format === 'newline') return jsonTable(ndjsonRecords(sample));
    const asArray = arrayDocumentRecords(sample);
    if (asArray) return jsonTable(asArray);
    if (format === 'array') throw new Error('sample does not parse with this grammar: not one JSON array of records');
    return jsonTable(ndjsonRecords(sample)); // `auto`: the array shape did not fit, so read it line-wise
}

/** One decoded batch: the records that parsed plus how many did not. */
interface JsonRecords {
    records: Record<string, unknown>[];
    rejected: number;
}

/** One JSON array of objects, or null when the sample is not that shape (so `auto` can fall back). */
function arrayDocumentRecords(sample: string): JsonRecords | null {
    let parsed: unknown;
    try {
        parsed = JSON.parse(sample);
    } catch {
        return null;
    }
    if (!Array.isArray(parsed)) return null;
    const records = parsed.filter((v) => typeof v === 'object' && v !== null && !Array.isArray(v));
    if (!records.length) return null;
    // A non-object entry is a rejected record, the same accounting the NDJSON path uses for a bad line.
    return { records: records as Record<string, unknown>[], rejected: parsed.length - records.length };
}

/** Mirrors the server's NDJSON path: a json_valid filter — invalid lines are REJECTED, never null-padded. */
function ndjsonRecords(sample: string): JsonRecords {
    const records: Record<string, unknown>[] = [];
    let rejected = 0;
    for (const l of lines(sample)) {
        try {
            records.push(JSON.parse(l) as Record<string, unknown>);
        } catch {
            rejected++;
        }
    }
    if (records.length === 0) throw new Error('sample does not parse with this grammar: no valid JSON records');
    return { records, rejected };
}

/** Decoded records → the flat table both shapes produce: first-seen top-level keys as the columns. */
function jsonTable({ records, rejected }: JsonRecords): ParserPreview {
    const seen = new Set<string>();
    const keys: string[] = [];
    for (const r of records) for (const k of Object.keys(r)) if (!seen.has(k)) (seen.add(k), keys.push(k));
    const rows = records.map((r) =>
        Object.fromEntries(
            keys.map((k) => {
                const v = r[k];
                // json_extract_string semantics: nested values land stringified, scalars as text.
                return [
                    k,
                    v === undefined || v === null ? null : typeof v === 'object' ? JSON.stringify(v) : String(v),
                ];
            }),
        ),
    );
    return table(keys, rows, rejected);
}

function textRegex(grammar: Record<string, unknown>, sample: string): ParserPreview {
    const pattern = String(sub(grammar, 'text_regex')['pattern'] ?? '');
    if (!pattern) throw new Error('text_regex needs a pattern');
    const re = new RegExp(pattern.replaceAll('(?P<', '(?<'));
    const names = [...pattern.matchAll(/\(\?P?<([A-Za-z_][A-Za-z0-9_]*)>/g)].map((m) => m[1]);
    if (!names.length) throw new Error('the pattern needs at least one named capture group');
    const rows: Record<string, unknown>[] = [];
    let rejected = 0;
    for (const l of lines(sample)) {
        const m = re.exec(l);
        if (m) rows.push(Object.fromEntries(names.map((n) => [n, m.groups?.[n] ?? null])));
        else rejected++;
    }
    return table(names, rows, rejected);
}

/** Mirrors `XmlParserPlugin`: record elements → node forest (attributes as `@name` leaves, text as
 *  values), same refusal messages for malformed docs and unmatched record elements. */
function xmlTree(grammar: Record<string, unknown>, sample: string): ParserPreview {
    const g = sub(grammar, 'xml');
    const recordPath = String(g['record_element'] ?? '').trim();
    const maxRecords = Math.max(1, Math.min(Number(g['max_records'] ?? 50), 1000));
    const doc = new DOMParser().parseFromString(sample, 'text/xml');
    if (doc.getElementsByTagName('parsererror').length > 0 || !doc.documentElement) {
        throw new Error('sample is not well-formed XML');
    }
    const matches: Element[] = [];
    const want = recordPath ? recordPath.split('/') : null;
    const walk = (el: Element, path: string[]): void => {
        const here = [...path, el.localName];
        const isRecord = want
            ? want.length <= here.length && here.slice(-want.length).join('/') === want.join('/')
            : here.length === 2;
        if (isRecord) {
            matches.push(el);
            return; // records don't nest into themselves in the preview walk
        }
        for (const child of Array.from(el.children)) walk(child, here);
    };
    walk(doc.documentElement, []);
    if (matches.length === 0) {
        throw new Error(
            recordPath
                ? `no elements match record_element '${recordPath}'`
                : 'no record elements found under the document root',
        );
    }
    const nodes = matches.slice(0, maxRecords).map(elementNode);
    return { kind: 'tree', recordCount: matches.length, nodes };
}

function elementNode(el: Element): ParserTreeNode {
    const children: ParserTreeNode[] = [];
    for (const a of Array.from(el.attributes))
        children.push({ label: `@${a.localName}`, type: 'attr', value: a.value });
    for (const c of Array.from(el.children)) children.push(elementNode(c));
    const text = Array.from(el.childNodes)
        .filter((n) => n.nodeType === Node.TEXT_NODE || n.nodeType === Node.CDATA_SECTION_NODE)
        .map((n) => (n.textContent ?? '').trim())
        .filter(Boolean)
        .join('');
    if (children.length === 0) return { label: el.localName, type: 'element', value: text || undefined };
    if (text) children.push({ label: '#text', type: 'text', value: text });
    return { label: el.localName, type: 'element', children };
}

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

const str = (path: string, label: string, description: string): ServedFieldSpec =>
    ({ path, label, description, type: 'STRING' });
const int = (path: string, label: string, description: string): ServedFieldSpec =>
    ({ path, label, description, type: 'INT' });

/** The catalog `Parsers.catalog()` serves — built-ins first, then discovered plugins. */
const CATALOG: ParserDef[] = [
    {
        id: 'delimited', label: 'Delimited — CSV / TSV / pipe, one record per line',
        hierarchical: false, ingestable: true,
        grammarSchema: [
            { path: 'delimited.delimiter', label: 'Delimiter', type: 'STRING', defaultValue: ',', description: 'Column separator character.' },
            { path: 'delimited.has_header', label: 'First line is a header', type: 'BOOL', defaultValue: true, description: 'Whether line one carries the column names.' },
            int('delimited.skip_header_lines', 'Skip leading lines', 'Banner/preamble lines before the data (and header).'),
            str('delimited.null_strings', 'Null strings', 'Values read as NULL; comma-separate multiple.'),
            str('encoding', 'Encoding', 'Character encoding (default UTF-8).'),
            str('compression', 'Input compression', 'e.g. gzip.'),
        ],
    },
    {
        id: 'fixedwidth', label: 'Fixed width — positional slices carved from each line',
        hierarchical: false, ingestable: true,
        grammarSchema: [
            { path: 'fixedwidth.fields', label: 'Fields', type: 'LIST', required: true, description: 'Positional slices: one {name, start, length} per field.' },
            { path: 'delimited.has_header', label: 'First line is a header', type: 'BOOL', defaultValue: true, description: 'Header/banner line to skip before the records.' },
            int('fixedwidth.min_record_length', 'Minimum record length', 'Shorter lines (footers, blanks) are dropped. Absent = the widest field end.'),
            { path: 'fixedwidth.trim', label: 'Trim fields', type: 'ENUM', enumValues: ['BOTH', 'LEFT', 'RIGHT', 'NONE'], defaultValue: 'BOTH', description: 'Whitespace trim per slice.' },
            str('encoding', 'Encoding', 'Character encoding (default UTF-8).'),
            str('compression', 'Input compression', 'e.g. gzip.'),
        ],
    },
    {
        id: 'json', label: 'JSON — NDJSON (one object per line) or a JSON array document',
        hierarchical: false, ingestable: true,
        grammarSchema: [
            { path: 'json.format', label: 'Document shape', type: 'ENUM', enumValues: ['newline', 'array', 'auto'], defaultValue: 'newline', description: 'NDJSON, one JSON array of records, or auto-detect.' },
            int('delimited.skip_header_lines', 'Skip leading lines', 'Non-JSON preamble lines before the records.'),
            str('compression', 'Input compression', 'e.g. gzip.'),
        ],
    },
    {
        id: 'text_regex', label: 'Text / regex — named capture groups over matching lines',
        hierarchical: false, ingestable: true,
        grammarSchema: [
            { path: 'text_regex.pattern', label: 'Pattern', type: 'STRING', required: true, description: 'At least one named capture group — group names become the columns; non-matching lines are dropped.' },
            int('delimited.skip_header_lines', 'Skip leading lines', 'Preamble lines before the records.'),
            str('encoding', 'Encoding', 'Character encoding (default UTF-8).'),
        ],
    },
    {
        id: 'xml', label: 'XML — XML file format',
        hierarchical: true, ingestable: false, // preview-only until the flatten configuration
        grammarSchema: [
            str('xml.record_element', 'Record element', 'Element that starts one record — a local name (order) or a slash path (orders/order). Blank = every direct child of the root.'),
            { path: 'xml.namespace_aware', label: 'Namespace aware', type: 'BOOL', defaultValue: false, description: 'Resolve namespaces (element labels then use local names).' },
            str('xml.encoding', 'Encoding', "Overrides the document prolog's encoding (default: auto-detect)."),
            { path: 'xml.max_records', label: 'Preview records', type: 'INT', defaultValue: 50, description: 'Records materialized into the preview tree (max 1000).' },
        ],
    },
    {
        id: 'asn1', label: 'ASN.1 — BER/DER encoded records',
        // Hierarchical like xml, but ingestable: Asn1RecordIngester flattens records onto segment
        // schemas through the existing parsing.plugin machinery.
        hierarchical: true, ingestable: true,
        ingesterClass: 'com.gamma.ingester.Asn1RecordIngester',
        grammarSchema: [
            str('asn1.grammar', 'ASN.1 grammar', 'The ASN.1 module text (X.680 syntax) defining the record type. Leave EMPTY to dump the file’s raw TLV structure instead — BER is self-describing, so an unknown file can be inspected before its module is available. A grammar is still required to ingest.'),
            str('asn1.root_type', 'Root type', 'Name of the type in the grammar each record binds against, e.g. Record. Required when a grammar is supplied; ignored in structural mode.'),
            { path: 'asn1.strictness', label: 'Strictness', type: 'ENUM', defaultValue: 'BER', enumValues: ['BER', 'DER', 'CER'], description: 'Encoding rules enforced while decoding: BER (permissive), DER, or CER.' },
            { path: 'asn1.file_header_length', label: 'File header bytes', type: 'INT', defaultValue: 0, description: 'Bytes to skip at the start of the file before the first record (e.g. 50 for Huawei-framed files). 0 = none.' },
            { path: 'asn1.record_header_length', label: 'Record header bytes', type: 'INT', defaultValue: 0, description: 'Bytes preceding each record’s TLV, skipped (e.g. 4 for Huawei-framed files). 0 = bare back-to-back TLVs. Records stay delimited by their own BER length.' },
            { path: 'asn1.max_records', label: 'Preview records', type: 'INT', defaultValue: 50, description: 'Records materialized into the preview tree (max 1000).' },
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
    const b = (req.body ?? {}) as { grammar?: Record<string, unknown>; sample_text?: string };
    const sample = String(b.sample_text ?? '');
    if (!sample.trim()) return error(400, "body must include 'sample_text' or 'sample_b64'");
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
        case 'delimited': return delimited(grammar, sample);
        case 'fixedwidth': return fixedwidth(grammar, sample);
        case 'json': return ndjson(sample);
        case 'text_regex': return textRegex(grammar, sample);
        case 'xml': return xmlTree(grammar, sample);
        // ASN.1 is in the catalog (the segments editor gates on its ingesterClass), but its input
        // is BINARY BER — a mock decoder would be a second ASN.1 implementation and a lie either
        // way. Refuse honestly: STRICTER than the server, never more lenient.
        case 'asn1': throw new Error('ASN.1 preview needs the real decoder — not available in mock mode');
        default: throw new Error(`unknown parser: ${id}`);
    }
}

const lines = (sample: string): string[] => sample.split(/\r?\n/).filter((l) => l.length > 0);
const sub = (grammar: Record<string, unknown>, key: string): Record<string, unknown> =>
    (grammar[key] ?? {}) as Record<string, unknown>;

function table(columns: string[], rows: Record<string, unknown>[], rejected = 0): ParserPreview {
    return { kind: 'table', columns, rows, rowCount: rows.length, rejectedRows: rejected };
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
    return table(header, rows);
}

function fixedwidth(grammar: Record<string, unknown>, sample: string): ParserPreview {
    const g = sub(grammar, 'fixedwidth');
    const fields = (g['fields'] ?? []) as { name: string; start: number; length: number }[];
    if (!fields.length) throw new Error('fixed width needs at least one field');
    const skip = sub(grammar, 'delimited')['has_header'] !== false ? 1 : 0;
    const rows = lines(sample).slice(skip).map((l) =>
        Object.fromEntries(fields.map((f) => [f.name, l.substring(f.start, f.start + f.length).trim()])),
    );
    return table(fields.map((f) => f.name), rows);
}

/** Mirrors the server's NDJSON path: json_valid filter (invalid lines REJECTED, never null-padded),
 *  then top-level keys in first-seen order become the columns. */
function ndjson(sample: string): ParserPreview {
    const keys: string[] = [];
    const records: Record<string, unknown>[] = [];
    let rejected = 0;
    for (const l of lines(sample)) {
        try {
            const v = JSON.parse(l) as Record<string, unknown>;
            for (const k of Object.keys(v)) if (!keys.includes(k)) keys.push(k);
            records.push(v);
        } catch {
            rejected++;
        }
    }
    if (records.length === 0) throw new Error('sample does not parse with this grammar: no valid JSON records');
    const rows = records.map((r) =>
        Object.fromEntries(keys.map((k) => {
            const v = r[k];
            // json_extract_string semantics: nested values land stringified, scalars as text.
            return [k, v === undefined || v === null ? null : typeof v === 'object' ? JSON.stringify(v) : String(v)];
        })),
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
        throw new Error(recordPath
            ? `no elements match record_element '${recordPath}'`
            : 'no record elements found under the document root');
    }
    const nodes = matches.slice(0, maxRecords).map(elementNode);
    return { kind: 'tree', recordCount: matches.length, nodes };
}

function elementNode(el: Element): ParserTreeNode {
    const children: ParserTreeNode[] = [];
    for (const a of Array.from(el.attributes)) children.push({ label: `@${a.localName}`, type: 'attr', value: a.value });
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

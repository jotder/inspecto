import { ParserTreeNode } from 'app/inspecto/api';
import { ParsingFrontend } from './parsing-attributes';

/**
 * Client-side helpers over the captured sample — pure functions, no HTTP:
 *
 * - {@link sniffFrontend} guesses the parsing frontend from the raw text (a SUGGESTION chip the
 *   builder applies with one click — never auto-applied).
 * - {@link jsonSampleToTree} renders the sample's own JSON records as a {@link ParserTreeNode}
 *   forest, making visible which keys are top-level (columns) and which are nested (the engine
 *   stringifies those into one column — flattening is not yet supported, BACKLOG §"flatten DSL").
 */

export interface FrontendSuggestion {
    frontend: ParsingFrontend;
    /** Human-readable evidence, shown on the suggestion chip ("Looks like …"). */
    reason: string;
    /** For `delimited`: the sniffed delimiter, prefilled on apply. */
    delimiter?: string;
}

const SNIFF_LINES = 20;
const DELIMITERS: { char: string; label: string }[] = [
    { char: ',', label: 'commas' },
    { char: '\t', label: 'tabs' },
    { char: '|', label: 'pipes' },
    { char: ';', label: 'semicolons' },
];

/** Guess the parsing frontend from raw sample text; null when nothing is confidently sniffable. */
export function sniffFrontend(text: string): FrontendSuggestion | null {
    const trimmed = text.trim();
    if (!trimmed) return null;

    // A whole-document JSON array of records.
    if (trimmed.startsWith('[')) {
        try {
            const doc = JSON.parse(trimmed);
            if (Array.isArray(doc)) return { frontend: 'json', reason: 'a JSON array document' };
        } catch {
            /* not a parsable array — fall through */
        }
    }

    const lines = trimmed
        .split(/\r?\n/)
        .map((l) => l.trim())
        .filter((l) => l.length > 0)
        .slice(0, SNIFF_LINES);
    if (lines.length === 0) return null;

    // NDJSON — every sampled line is its own JSON object.
    if (lines.every((l) => l.startsWith('{'))) {
        const allObjects = lines.every((l) => {
            try {
                const v = JSON.parse(l);
                return v !== null && typeof v === 'object' && !Array.isArray(v);
            } catch {
                return false;
            }
        });
        if (allObjects) return { frontend: 'json', reason: 'NDJSON — one object per line' };
    }

    // Delimiter sniff: a candidate wins when every sampled line splits into the SAME column
    // count (>1); the highest column count breaks ties between candidates.
    let best: { char: string; label: string; columns: number } | null = null;
    for (const { char, label } of DELIMITERS) {
        const counts = lines.map((l) => l.split(char).length);
        const columns = counts[0];
        if (columns > 1 && counts.every((c) => c === columns) && (!best || columns > best.columns)) {
            best = { char, label, columns };
        }
    }
    if (best) {
        return {
            frontend: 'delimited',
            reason: `${best.columns} columns split by ${best.label}`,
            delimiter: best.char,
        };
    }
    return null;
}

const MAX_TREE_RECORDS = 50;

/**
 * The sample's own JSON records as a tree forest (client-side — this is the builder's pasted
 * text, not a server parse). Accepts a JSON array document or NDJSON; null when nothing parses.
 */
export function jsonSampleToTree(text: string, maxRecords = MAX_TREE_RECORDS): ParserTreeNode[] | null {
    const trimmed = text.trim();
    if (!trimmed) return null;
    let records: unknown[] = [];
    if (trimmed.startsWith('[')) {
        try {
            const doc = JSON.parse(trimmed);
            if (Array.isArray(doc)) records = doc;
        } catch {
            /* fall through to NDJSON */
        }
    }
    if (records.length === 0) {
        for (const line of trimmed.split(/\r?\n/)) {
            const l = line.trim();
            if (!l) continue;
            try {
                records.push(JSON.parse(l));
            } catch {
                /* a non-JSON line simply doesn't contribute a record */
            }
            if (records.length >= maxRecords) break;
        }
    }
    if (records.length === 0) return null;
    return records.slice(0, maxRecords).map((r, i) => ({
        label: `record ${i + 1}`,
        type: typeLabel(r),
        ...(isContainer(r) ? { children: childNodes(r) } : { value: scalarText(r) }),
    }));
}

function isContainer(v: unknown): v is Record<string, unknown> | unknown[] {
    return v !== null && typeof v === 'object';
}

function typeLabel(v: unknown): string {
    if (v === null) return 'null';
    if (Array.isArray(v)) return 'array';
    return typeof v;
}

function scalarText(v: unknown): string {
    return v === null ? 'null' : String(v);
}

function childNodes(v: Record<string, unknown> | unknown[]): ParserTreeNode[] {
    const entries = Array.isArray(v) ? v.map((item, i) => [`[${i}]`, item] as const) : Object.entries(v);
    return entries.map(([label, value]) => ({
        label,
        type: typeLabel(value),
        ...(isContainer(value) ? { children: childNodes(value) } : { value: scalarText(value) }),
    }));
}

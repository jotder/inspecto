import type { ParserTreeNode } from 'app/inspecto/api';

/**
 * The segment model the Segments editor authors for an ingestable hierarchical parser
 * (`parsing.plugin`). One draft becomes one segment schema toon, and one segment becomes one Table.
 *
 * ⚠ `selector` is a DOTTED PATH into the decoded record — the contract
 * `com.gamma.ingester.Asn1RecordIngester` reads — not the positional index the text ingesters use.
 * A selector must name a LEAF: a container (sub-record, or a repeated field's list) resolves to
 * NULL rather than a stringified subtree, so `deriveSegments` never proposes one as a column.
 */
export interface SegmentColumnDraft {
    /** Output column name — must satisfy the engine's `^[A-Za-z_][A-Za-z0-9_]*$` identifier rule. */
    name: string;
    /** Dotted path into the decoded record, e.g. `party.number`. */
    selector: string;
    type: SegmentColumnType;
}

export interface SegmentDraft {
    /** Segment key — matched against the decoded record's own name by the ingester. */
    key: string;
    columns: SegmentColumnDraft[];
}

/** The honestly-castable set the Schema stage's type autodetection already speaks. */
export const SEGMENT_COLUMN_TYPES = ['VARCHAR', 'DOUBLE', 'DATE', 'TIMESTAMP'] as const;
export type SegmentColumnType = (typeof SEGMENT_COLUMN_TYPES)[number];

/** The derived column every segment gets: the ingester emits the segment key here. */
export const EVENT_TYPE_COLUMN = 'EVENT_TYPE';

/**
 * The engine validates every name interpolated into SQL DDL against this at config load
 * (`Identifiers.validateSchema`) — a hard fail, not a warning. The editor must therefore never
 * propose a name it would reject.
 */
export const IDENTIFIER_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

/**
 * A dotted selector to a legal column name: `party.number` → `PARTY_NUMBER`. Anything outside
 * `[A-Za-z0-9_]` collapses to `_`, and a leading digit gets an `_` prefix (a name may not start
 * with one).
 */
export function columnNameFor(selector: string): string {
    const cleaned = selector.replace(/[^A-Za-z0-9_]+/g, '_').replace(/^_+|_+$/g, '') || 'FIELD';
    const upper = cleaned.toUpperCase();
    return IDENTIFIER_RE.test(upper) ? upper : `_${upper}`;
}

/**
 * Propose one segment per distinct record type in a tree preview, with a column per LEAF path.
 *
 * <p>Top-level nodes are the decoded records and their labels are the record types — which is
 * exactly what the ingester matches segment keys against, so the grouping is not a heuristic. Each
 * type's columns come from the first record of that type; a later record with extra optional
 * fields will not add columns, which is why the result is a starting point the operator edits, not
 * a final answer.
 */
export function deriveSegments(nodes: readonly ParserTreeNode[] | null | undefined): SegmentDraft[] {
    const firstByKey = new Map<string, ParserTreeNode>();
    for (const node of nodes ?? []) {
        const key = (node.label ?? '').trim();
        if (key && !firstByKey.has(key)) firstByKey.set(key, node);
    }
    const out: SegmentDraft[] = [];
    for (const [key, node] of firstByKey) {
        const selectors = leafSelectors(node.children ?? [], '');
        if (selectors.length === 0) continue; // a record with no leaves has nothing to map
        out.push({
            key,
            columns: selectors.map((selector) => ({
                name: columnNameFor(selector),
                selector,
                type: 'VARCHAR' as SegmentColumnType,
            })),
        });
    }
    return out;
}

/** Depth-first leaf paths, deduped — repeated siblings share one selector. */
function leafSelectors(nodes: readonly ParserTreeNode[], prefix: string): string[] {
    const seen = new Set<string>();
    const out: string[] = [];
    const walk = (list: readonly ParserTreeNode[], at: string): void => {
        for (const n of list) {
            const label = (n.label ?? '').trim();
            if (!label) continue;
            const path = at ? `${at}.${label}` : label;
            if (n.children && n.children.length > 0) {
                walk(n.children, path);
            } else if (!seen.has(path)) {
                seen.add(path);
                out.push(path);
            }
        }
    };
    walk(nodes, prefix);
    return out;
}

/**
 * One segment draft as the schema toon the engine loads — the same shape the Schema stage writes,
 * so both guided editors persist through `ConfigService.write('schema', …)` identically.
 *
 * <p>Partitioning defaults to the derived `EVENT_TYPE` column: it is always emitted, it is always a
 * legal identifier, and it keeps rows out of the `year=1900` sentinel partition an empty
 * `partitions[]` would produce.
 */
export function schemaDraftFor(segment: SegmentDraft, schemaName: string): Record<string, unknown> {
    return {
        partitions: [{ column: 'event_type', source: EVENT_TYPE_COLUMN, type: 'VARCHAR' }],
        raw: {
            name: schemaName,
            format: 'CSV',
            fields: segment.columns.map((c) => ({ name: c.name, selector: c.selector, type: c.type })),
        },
        mapping: {
            canonicalName: schemaName,
            rawName: schemaName,
            rules: segment.columns.map((c) => ({ targetColumn: c.name, sourceExpression: c.name })),
        },
    };
}

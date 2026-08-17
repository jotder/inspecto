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
/**
 * The schema-toon NAME a stored segment path points at: `spaces/x/config/abcd_CALL.toon` → `abcd_CALL`.
 *
 * <p>Derived from the stored path rather than recomputed from the segment key, so a pipeline whose
 * segment paths were hand-authored (or written under a different space id than the one currently
 * selected) still re-hydrates. Returns `''` for anything that is not a `.toon` file reference —
 * the caller treats that as "not recoverable, re-derive".
 */
/**
 * The PORTABLE reference to a config the engine loads beside its pipeline — a bare `<name>.toon`.
 *
 * <p>Since unification W1b (2026-07-31) `schema_file` and `parsing.<asn1|plugin>.segments` values
 * resolve **config-relative first, working-directory second**, so a bare basename beside the
 * pipeline config is what makes a space tree relocatable / renamable / importable. Every UI writer
 * used to emit `spaces/<space>/config/<name>.toon` instead — reading portable refs worked, writing
 * them did not (W3). This is the one spelling of the written form.
 *
 * <p>⚠ Never COMPARE a stored ref against this. Configs written before this change carry the long
 * form and are still correct — compare by {@link schemaNameFromPath} instead, which reads both.
 */
export function portableConfigRef(name: string): string {
    return `${name}.toon`;
}

export function schemaNameFromPath(path: unknown): string {
    const s = String(path ?? '').trim();
    if (!s.toLowerCase().endsWith('.toon')) return '';
    return s.slice(0, -'.toon'.length).split(/[\\/]/).pop() ?? '';
}

/**
 * The `<pipeline>_<suffix>` schema name convention, sanitised to a legal identifier. Pass an empty
 * suffix for the bare `<pipeline>_` prefix that identifies the schemas a pipeline OWNS.
 *
 * ⚠ One spelling on purpose: a delete cascade decides what it may remove by testing this prefix
 * against names a writer produced, so the two must stay byte-identical. They were separate regexes
 * until 2026-08-17.
 */
export function companionSchemaName(pipeline: string, suffix: string): string {
    return `${pipeline}_${suffix}`.replace(/[^A-Za-z0-9_]+/g, '_');
}

/**
 * The `{segmentKey: schemaPath}` map a node's authored `parsing:` block declares, across every
 * hierarchical frontend that can carry one — `{}` when the node has none.
 *
 * <p>The shape lives here rather than at each reader because three surfaces walk it (the Parse
 * drawer's re-hydration, the delete cascade, and stream transfer), and the delete cascade is the one
 * where missing a frontend silently orphans schema files.
 */
export function segmentPathsOf(config: Record<string, unknown> | undefined): Record<string, string> {
    const parsing = config?.['parsing'];
    if (!parsing || typeof parsing !== 'object') return {};
    const out: Record<string, string> = {};
    for (const key of SEGMENTED_FRONTENDS) {
        const block = (parsing as Record<string, unknown>)[key] as Record<string, unknown> | undefined;
        const segments = block?.['segments'];
        if (!segments || typeof segments !== 'object' || Array.isArray(segments)) continue;
        for (const [segKey, path] of Object.entries(segments as Record<string, unknown>)) {
            if (typeof path === 'string') out[segKey] = path;
        }
    }
    return out;
}

/** The `parsing:` sub-blocks that can declare `segments:` — add a new hierarchical frontend here. */
const SEGMENTED_FRONTENDS = ['asn1', 'plugin'];

/**
 * Rebuild a segment draft from the schema toon it was written to — the inverse of
 * {@link schemaDraftFor}, reading back the `raw.fields[]` it wrote.
 *
 * <p>An unrecognized `type` falls back to `VARCHAR` rather than being trusted into the editor's
 * dropdown: the toon may have been hand-edited to a DuckDB type the editor cannot represent, and a
 * value outside {@link SEGMENT_COLUMN_TYPES} would leave the select showing blank and silently
 * rewrite the column on the next save.
 */
export function segmentDraftFrom(key: string, config: Record<string, unknown> | null | undefined): SegmentDraft {
    const raw = ((config ?? {})['raw'] ?? {}) as Record<string, unknown>;
    const fields = Array.isArray(raw['fields']) ? (raw['fields'] as Record<string, unknown>[]) : [];
    const columns: SegmentColumnDraft[] = [];
    for (const f of fields) {
        const name = String(f['name'] ?? '').trim();
        const selector = String(f['selector'] ?? '').trim();
        if (!name || !selector) continue; // a half-written row is not an editable column
        const declared = String(f['type'] ?? '').toUpperCase();
        columns.push({
            name,
            selector,
            type: (SEGMENT_COLUMN_TYPES as readonly string[]).includes(declared)
                ? (declared as SegmentColumnType)
                : 'VARCHAR',
        });
    }
    return { key, columns };
}

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

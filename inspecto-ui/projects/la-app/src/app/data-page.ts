import type { ComponentDef } from '@inspecto/core/api';
import type { EntityTypeGuide } from '@inspecto/link-analysis/api/entity-type-options';

/**
 * The **Data** page's pure half (LA-DATA-PAGE-1, plan `docs/superpower/la-data-arrival-plan.md` §8): a Dataset registry
 * entry read for the page, a column's Entity Type from its classification, and the entry a saved Mapping becomes.
 */

/**
 * A column's role as stored (`columns[].role`, LA-COLUMN-ROLES-1; one vocabulary with the Studio Dataset editor's
 * dimension / measure / temporal). The words the UI shows are {@link ROLE_LABELS}.
 */
export type ColumnRole = 'entity' | 'temporal' | 'measure' | 'dimension' | 'detail';

export const ROLE_LABELS: Record<ColumnRole, string> = {
    entity: 'Entity',
    temporal: 'Time',
    measure: 'Measure',
    dimension: 'Category',
    detail: 'Detail',
};

const ROLES = Object.keys(ROLE_LABELS) as ColumnRole[];

export interface DataColumn {
    name: string;
    type: string;
    classification: string | null;
    /** The stored role; null when the entry says none (then {@link roleOf} reads it from the rest of the entry). */
    role: ColumnRole | null;
    /** What a Measure counts in (USD, s, bytes); null when not said. */
    unit: string | null;
}

export interface LinkMapping {
    from: string;
    to: string;
    kind?: string | null;
    time?: string | null;
    amount?: string | null;
}

export interface DataEntry {
    id: string;
    description: string;
    /** `import` (Add data), `mapping` (a Mapping of another Dataset), or `other` (kept up to date by a Pipeline). */
    origin: 'import' | 'mapping' | 'other';
    /**
     * May be a Mapping's base here (LA-PIPELINE-MAPPINGS-1): an import, or a stored, plain Dataset - a `physicalRef`, not
     * computed when read, not an Entity List's store - that lists the columns the Mapping form offers.
     */
    mappable: boolean;
    kind: 'stream' | 'reference';
    basedOn: string | null;
    physicalRef: string;
    importPipeline: string | null;
    columns: DataColumn[];
    mapping: LinkMapping | null;
    eventDate: string | null;
    primaryEntityType: string | null;
}

/**
 * The Entity Types a column may hold: the id, its words, the classifications that mean it, and the reader guidance
 * (family, definition, example) the picker groups and explains them by.
 */
export interface TypeChoice extends EntityTypeGuide {
    id: string;
    label: string;
    classifications: string[];
}

const str = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v.trim() : null);

/** The id prefix of an Entity List's Parquet store (`EntityListSidecar.PREFIX`): a store, not data to read as links. */
const ENTITY_LIST_STORE = 'entity_list_';

/**
 * The columns a Mapping reads that its base no longer lists (a Pipeline's schema changed under it), in mapping order;
 * empty when nothing drifted or the base lists no columns to compare with.
 */
export function driftedColumns(m: DataEntry, base: DataEntry): string[] {
    if (!m.mapping || !base.columns.length) return [];
    const has = new Set(base.columns.map((c) => c.name.toLowerCase()));
    const read = [m.mapping.from, m.mapping.to, m.mapping.kind, m.mapping.time, m.mapping.amount];
    return read.filter((c): c is string => !!c && !has.has(c.toLowerCase()));
}

export function dataEntry(def: ComponentDef): DataEntry {
    const c = (def.content ?? {}) as Record<string, unknown>;
    const origin = c['origin'] === 'import' ? 'import' : c['origin'] === 'mapping' ? 'mapping' : 'other';
    const raw = c['defaultLinkMapping'] as Record<string, unknown> | undefined;
    const mapping =
        raw && str(raw['from']) && str(raw['to'])
            ? {
                  from: str(raw['from'])!,
                  to: str(raw['to'])!,
                  kind: str(raw['kind']),
                  time: str(raw['time']),
                  amount: str(raw['amount']),
              }
            : null;
    const columns = Array.isArray(c['columns'])
        ? (c['columns'] as Record<string, unknown>[])
              .filter((x) => str(x?.['name']))
              .map((x) => ({
                  name: str(x['name'])!,
                  type: str(x['type']) ?? 'string',
                  classification: str(x['classification']),
                  role: ROLES.find((r) => r === str(x['role'])?.toLowerCase()) ?? null,
                  unit: str(x['unit']),
              }))
        : [];
    // the server's base rule (DatasetEntryRules.validateMapping), plus what the form needs: two columns to pick from
    const computedWhenRead = ['sql', 'view', 'sourceName', 'calculated'].some((k) => c[k] != null);
    const mappable =
        origin === 'import' ||
        (origin === 'other' &&
            !!str(c['physicalRef']) &&
            !computedWhenRead &&
            !def.name.startsWith(ENTITY_LIST_STORE) &&
            columns.length >= 2);
    return {
        id: def.name,
        description: str(c['description']) ?? '',
        origin,
        mappable,
        kind: c['kind'] === 'reference' ? 'reference' : 'stream',
        basedOn: str(c['basedOn']),
        physicalRef: str(c['physicalRef']) ?? '',
        importPipeline: str(c['importPipeline']),
        columns,
        mapping,
        eventDate: str(c['eventDate']),
        primaryEntityType: str(c['primaryEntityType']),
    };
}

/** The Entity Type a column's classification means; null for a detail (no classification, or one no type claims). */
export function typeOf(classification: string | null, types: readonly TypeChoice[]): TypeChoice | null {
    if (!classification) return null;
    const k = classification.toUpperCase();
    return types.find((t) => t.classifications.some((x) => x.toUpperCase() === k)) ?? null;
}

/**
 * A column's role: the stored one, else (an entry written before roles) read from the rest of the entry - holds an
 * Entity Type → Entity; the link mapping's time or the event date → Time; its amount → Measure; its kind → Category;
 * anything else → Detail.
 */
export function roleOf(col: DataColumn, entry: DataEntry, types: readonly TypeChoice[]): ColumnRole {
    if (col.role) return col.role;
    const is = (n: string | null | undefined) => !!n && n.toLowerCase() === col.name.toLowerCase();
    if (typeOf(col.classification, types)) return 'entity';
    if (is(entry.mapping?.time) || is(entry.eventDate)) return 'temporal';
    if (is(entry.mapping?.amount)) return 'measure';
    if (is(entry.mapping?.kind)) return 'dimension';
    return 'detail';
}

/** A link mapping in words: `from_account → to_account · kind channel · when booked_at`. */
export function mappingLine(m: LinkMapping | null): string {
    if (!m) return 'No link mapping yet';
    const parts = [`${m.from} → ${m.to}`];
    if (m.kind) parts.push(`kind ${m.kind}`);
    if (m.time) parts.push(`when ${m.time}`);
    if (m.amount) parts.push(`amount ${m.amount}`);
    return parts.join(' · ');
}

/** What the Mapping form holds. Entity Types are ids ('' = a detail). */
export interface MappingForm {
    name: string;
    description: string;
    from: string;
    fromType: string;
    to: string;
    toType: string;
    kind: string;
    time: string;
    amount: string;
}

/** A Mapping name: the Dataset id rule (a lower-case letter, then letters, digits or _), unique among Datasets. */
export const MAPPING_NAME = /^[a-z][a-z0-9_]{0,62}$/;

/**
 * The Dataset entry a Mapping of `base` becomes: the base's own data (`physicalRef`) and columns, read its own way - the
 * From and To columns classified as the chosen Entity Types (a type's first classification, as Add data does), every
 * other column as the base has it.
 */
export function mappingEntry(base: DataEntry, f: MappingForm, types: readonly TypeChoice[]): Record<string, unknown> {
    const classify = (typeId: string): string | null => types.find((t) => t.id === typeId)?.classifications[0] ?? null;
    const columns = base.columns.map((c) => {
        const chosen = c.name === f.from ? f.fromType : c.name === f.to ? f.toType : undefined;
        const classification = chosen === undefined ? c.classification : chosen ? classify(chosen) : null;
        // a From / To re-classified here is an Entity column, or no longer one; every other column keeps its role
        const role = chosen === undefined ? c.role : classification ? 'entity' : c.role === 'entity' ? null : c.role;
        return {
            name: c.name,
            type: c.type,
            ...(classification ? { classification } : {}),
            ...(role ? { role } : {}),
            ...(role === 'measure' && c.unit ? { unit: c.unit } : {}),
        };
    });
    const mapping: Record<string, string> = { from: f.from, to: f.to };
    if (f.kind) mapping['kind'] = f.kind;
    if (f.time) mapping['time'] = f.time;
    if (f.amount) mapping['amount'] = f.amount;
    return {
        origin: 'mapping',
        basedOn: base.id,
        physicalRef: base.physicalRef,
        kind: base.kind,
        description:
            f.description.trim() || `A Mapping of ${base.id}: ${mappingLine(mapping as unknown as LinkMapping)}`,
        columns,
        defaultLinkMapping: mapping,
        ...(f.time ? { eventDate: f.time } : {}),
        ...(f.fromType ? { primaryEntityType: f.fromType } : {}),
    };
}

/** The form a Mapping (or the base's own mapping, for "New mapping from this") starts from. */
export function formOf(entry: DataEntry, types: readonly TypeChoice[], name = ''): MappingForm {
    const col = (n: string | null | undefined) => entry.columns.find((c) => c.name === n);
    const m = entry.mapping;
    return {
        name,
        description: entry.origin === 'mapping' ? entry.description : '',
        from: m?.from ?? '',
        fromType: typeOf(col(m?.from)?.classification ?? null, types)?.id ?? '',
        to: m?.to ?? '',
        toType: typeOf(col(m?.to)?.classification ?? null, types)?.id ?? '',
        kind: m?.kind ?? '',
        time: m?.time ?? '',
        amount: m?.amount ?? '',
    };
}

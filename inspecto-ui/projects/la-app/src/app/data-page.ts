import type { ComponentDef } from '@inspecto/core/api';
import { CARRIED_ROLES, ColumnRole, ROLE_LABELS, hintedRole } from '@inspecto/link-analysis/api/column-roles';
import type { EntityTypeGuide, RecordTypeGuide } from '@inspecto/link-analysis/api/entity-type-options';

export { ROLE_HINTS, ROLE_LABELS } from '@inspecto/link-analysis/api/column-roles';
export type { ColumnRole } from '@inspecto/link-analysis/api/column-roles';

/**
 * The **Data** page's pure half (LA-DATA-PAGE-1, plan `docs/superpower/la-data-arrival-plan.md` §8): a Dataset registry
 * entry read for the page, a column's Entity Type from its classification, and the entry a saved Mapping becomes.
 */

const ROLES = Object.keys(ROLE_LABELS) as ColumnRole[];

export interface DataColumn {
    name: string;
    type: string;
    classification: string | null;
    /** The stored role; null when the entry says none (then {@link roleOf} reads it from the rest of the entry). */
    role: ColumnRole | null;
    /** What a Measure counts in (USD, s, bytes); null when not said. */
    unit: string | null;
    /** False when a Time, Measure or Category column is kept off the links (LA-COLUMN-ROLES-1 P2); else absent. */
    carried?: false;
}

/** A link mapping: its ends, its kind, and the primary Time and Measure among the role columns (LA-COLUMN-ROLES-1 P1). */
export interface LinkMapping {
    from: string;
    to: string;
    kind?: string | null;
    primaryTime?: string | null;
    primaryMeasure?: string | null;
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
    /** `postgres` for a Postgres snapshot (BASIC-ONBOARD-1 P3); null for files. */
    importSource: string | null;
    columns: DataColumn[];
    mapping: LinkMapping | null;
    eventDate: string | null;
    primaryEntityType: string | null;
    /** What one row is: a record type id of the Space's packs (ENTITY-TYPE-CATALOG-1 P2); null when not said. */
    recordType: string | null;
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
    const read = [m.mapping.from, m.mapping.to, m.mapping.kind, m.mapping.primaryTime, m.mapping.primaryMeasure];
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
                  // `time` / `amount` are the old names, read for entries written before
                  primaryTime: str(raw['primaryTime']) ?? str(raw['time']),
                  primaryMeasure: str(raw['primaryMeasure']) ?? str(raw['amount']),
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
                  ...(x['carried'] === false ? { carried: false as const } : {}),
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
        importSource: str(c['importSource']),
        columns,
        mapping,
        eventDate: str(c['eventDate']),
        primaryEntityType: str(c['primaryEntityType']),
        recordType: str(c['recordType']),
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
 * Entity Type → Entity; the link mapping's primary Time or the event date → Time; its primary Measure → Measure; its kind
 * → Category; a date or timestamp column → Time (LA-COLUMN-ROLES-1 P1, so a Mapping can pick it); anything else → Detail.
 */
export function roleOf(col: DataColumn, entry: DataEntry, types: readonly TypeChoice[]): ColumnRole {
    if (col.role) return col.role;
    const is = (n: string | null | undefined) => !!n && n.toLowerCase() === col.name.toLowerCase();
    if (typeOf(col.classification, types)) return 'entity';
    if (is(entry.mapping?.primaryTime) || is(entry.eventDate)) return 'temporal';
    if (is(entry.mapping?.primaryMeasure)) return 'measure';
    if (is(entry.mapping?.kind)) return 'dimension';
    if (/^(date|timestamp)/i.test(col.type)) return 'temporal';
    return 'detail';
}

/** One role a Dataset's record type proposes for a column (ENTITY-TYPE-RECORD-ROLES-1). */
export interface RoleProposal {
    column: string;
    role: ColumnRole;
    /** The record type's hint that names the column. */
    hint: string;
}

/**
 * The roles a Dataset's record type usually gives its columns that the entry does not say yet: a column whose role is
 * unstated or Detail (Add data's "nothing said") and that holds no Entity Type, is none of the link mapping's columns,
 * and is named by one of the record type's hints its type can hold (`hintedRole`). A role the person set is never
 * proposed over.
 */
export function recordTypeProposals(
    entry: DataEntry,
    recordType: RecordTypeGuide | null | undefined,
    types: readonly TypeChoice[],
): RoleProposal[] {
    if (!recordType?.roleHints) return [];
    const m = entry.mapping;
    const mapped = new Set(
        [m?.from, m?.to, m?.kind, m?.primaryTime, m?.primaryMeasure, entry.eventDate]
            .filter((c): c is string => !!c)
            .map((c) => c.toLowerCase()),
    );
    const out: RoleProposal[] = [];
    for (const c of entry.columns) {
        if (mapped.has(c.name.toLowerCase()) || (c.role && c.role !== 'detail')) continue;
        if (roleOf(c, entry, types) === 'entity') continue;
        const hinted = hintedRole(c.name, c.type, recordType.roleHints);
        if (hinted && hinted.role !== roleOf(c, entry, types)) out.push({ column: c.name, ...hinted });
    }
    return out;
}

/** A link mapping in words: `from_account → to_account · kind channel · when booked_at`. */
export function mappingLine(m: LinkMapping | null): string {
    if (!m) return 'No link mapping yet';
    const parts = [`${m.from} → ${m.to}`];
    if (m.kind) parts.push(`kind ${m.kind}`);
    if (m.primaryTime) parts.push(`when ${m.primaryTime}`);
    if (m.primaryMeasure) parts.push(`measure ${m.primaryMeasure}`);
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
    primaryTime: string;
    primaryMeasure: string;
    /**
     * The Time, Measure and Category columns kept off this Mapping's links (`carried: false`, LA-COLUMN-ROLES-2): the
     * Mapping's own marks when editing it, its base's for a new one; any other column is carried.
     */
    notCarried: string[];
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
        // a From / To re-classified here is an Entity column, or no longer one; every other column keeps its role, and the
        // primaries take theirs when the base states none (an entry written before roles)
        const primary = c.name === f.primaryTime ? 'temporal' : c.name === f.primaryMeasure ? 'measure' : null;
        const role =
            chosen === undefined
                ? (c.role ?? primary)
                : classification
                  ? 'entity'
                  : c.role === 'entity'
                    ? null
                    : c.role;
        // LA-COLUMN-ROLES-2: whether it rides on this Mapping's links is the Mapping's own mark, not its base's
        // (an entry written before roles reads its role as the page shows it - a date column is a Time - and states it)
        const shown = role ?? (chosen === undefined ? roleOf(c, base, types) : null);
        const keptOff =
            !!shown &&
            CARRIED_ROLES.has(shown) &&
            f.notCarried.includes(c.name) &&
            c.name !== f.from &&
            c.name !== f.to;
        const written = keptOff ? shown : role;
        return {
            name: c.name,
            type: c.type,
            ...(classification ? { classification } : {}),
            ...(written ? { role: written } : {}),
            ...(written === 'measure' && c.unit ? { unit: c.unit } : {}),
            ...(keptOff ? { carried: false } : {}),
        };
    });
    const mapping: Record<string, string> = { from: f.from, to: f.to };
    if (f.kind) mapping['kind'] = f.kind;
    if (f.primaryTime) mapping['primaryTime'] = f.primaryTime;
    if (f.primaryMeasure) mapping['primaryMeasure'] = f.primaryMeasure;
    return {
        origin: 'mapping',
        basedOn: base.id,
        physicalRef: base.physicalRef,
        kind: base.kind,
        description:
            f.description.trim() || `A Mapping of ${base.id}: ${mappingLine(mapping as unknown as LinkMapping)}`,
        columns,
        defaultLinkMapping: mapping,
        ...(f.primaryTime ? { eventDate: f.primaryTime } : {}),
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
        primaryTime: m?.primaryTime ?? '',
        primaryMeasure: m?.primaryMeasure ?? '',
        notCarried: entry.columns.filter((c) => c.carried === false).map((c) => c.name),
    };
}

/**
 * The columns a Mapping of `base` may keep off its links (LA-COLUMN-ROLES-2): the base's Time, Measure and Category
 * columns, in column order, except the Mapping's From and To (an end is never carried).
 */
export function carriedChoices(
    base: DataEntry,
    f: Pick<MappingForm, 'from' | 'to'>,
    types: readonly TypeChoice[],
): string[] {
    return base.columns
        .filter((c) => c.name !== f.from && c.name !== f.to && CARRIED_ROLES.has(roleOf(c, base, types)))
        .map((c) => c.name);
}

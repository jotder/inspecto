import type { PickerOption } from '@inspecto/core/components/option-picker.component';
import { type EntityNormaliser, normalizeTypedKey } from '@inspecto/core/graph';
import type { EntityTypeSummary } from '@inspecto/link-analysis/api/entity-profile.service';
import type {
    EntityTypeConfig,
    LinkAnalysisLimits,
} from '@inspecto/link-analysis/link-analysis/link-analysis-settings.service';

/**
 * The framework-free half of the Entities page's *what is in your data* views (LA-ONBOARD-1 phase 5, UC-07): which
 * Entity Types occur in this Space's data, which Dataset columns look like identifiers, and the shape of a new Entity
 * Type. Everything is read from what two reads already return (`GET /inv/entity-types` bindings and the Dataset
 * registry `GET /components/dataset`), so the page asks no per-type question.
 */

/** One Dataset registry entry as the page needs it: its id, the optimistic-lock hash and the stored content. */
export interface DatasetEntry {
    id: string;
    contentHash: string;
    content: Record<string, unknown>;
}

/** The registry read (`GET /components/dataset`) as {@link DatasetEntry}s. */
export function datasetEntries(
    defs: readonly { name: string; contentHash?: string; content?: unknown }[],
): DatasetEntry[] {
    return defs.map((d) => ({
        id: d.name,
        contentHash: d.contentHash ?? '',
        content: d.content && typeof d.content === 'object' ? (d.content as Record<string, unknown>) : {},
    }));
}

/** One listed column of a Dataset entry. */
export interface DatasetColumn {
    name: string;
    classification: string | null;
}

/** The entry's `columns[]` (name + classification); empty when it lists none. */
export function datasetColumns(content: Record<string, unknown>): DatasetColumn[] {
    const cols = content['columns'];
    if (!Array.isArray(cols)) return [];
    const out: DatasetColumn[] = [];
    for (const c of cols) {
        if (!c || typeof c !== 'object') continue;
        const name = (c as Record<string, unknown>)['name'];
        if (typeof name !== 'string' || !name.trim()) continue;
        const cls = (c as Record<string, unknown>)['classification'];
        out.push({ name, classification: typeof cls === 'string' && cls.trim() ? cls.trim() : null });
    }
    return out;
}

/** The entry's `primaryEntityType` (the Entity Type its graph leads with), or null. */
export function primaryOf(content: Record<string, unknown>): string | null {
    const p = content['primaryEntityType'];
    return typeof p === 'string' && p.trim() ? p.trim() : null;
}

/** The two link ends of the entry's `defaultLinkMapping` (`from`, `to`), lower-cased; empty when it has none. */
export function linkEnds(content: Record<string, unknown>): { from: string | null; to: string | null } {
    const m = content['defaultLinkMapping'];
    if (!m || typeof m !== 'object') return { from: null, to: null };
    const s = (k: string) => {
        const v = (m as Record<string, unknown>)[k];
        return typeof v === 'string' && v.trim() ? v.trim().toLowerCase() : null;
    };
    return { from: s('from'), to: s('to') };
}

/** The Entity Type claiming `classification` (trimmed, case-insensitive: the server's binding rule), or null. */
export function typeForClassification<T extends { classifications: readonly string[] }>(
    classification: string | null,
    types: readonly T[],
): T | null {
    if (!classification) return null;
    const k = classification.trim().toLowerCase();
    return types.find((t) => t.classifications.some((c) => c.trim().toLowerCase() === k)) ?? null;
}

/** The Entity Types split by whether they occur in this Space's data. */
export interface PresenceSplit {
    /** Bound to at least one Dataset column; the primary ones (some Dataset leads with them) first. */
    present: EntityTypeSummary[];
    /** Bound nowhere: shown under "Not in your data". */
    absent: EntityTypeSummary[];
    /** Type id -> the Datasets that name it as their primary Entity Type. */
    primaryIn: Record<string, string[]>;
}

/**
 * Present = the server bound the type to a column of some Dataset (a column classified to it; a link end of a default
 * mapping binds only through its classification). A type a Dataset names as primary but no column holds is still
 * "not in your data": nothing would be drawn for it.
 */
export function splitByPresence(types: readonly EntityTypeSummary[], datasets: readonly DatasetEntry[]): PresenceSplit {
    const primaryIn: Record<string, string[]> = {};
    for (const d of datasets) {
        const p = primaryOf(d.content);
        if (p) (primaryIn[p] ??= []).push(d.id);
    }
    const present = types.filter((t) => t.bindings.length > 0);
    const absent = types.filter((t) => t.bindings.length === 0);
    // A stable partition: primary types first, each group in the server's order.
    const primary = present.filter((t) => primaryIn[t.id]);
    const rest = present.filter((t) => !primaryIn[t.id]);
    return { present: [...primary, ...rest], absent, primaryIn };
}

/** Why a column is listed on the Columns view. */
export type ColumnReason = 'classified' | 'link' | 'suggested' | 'other';

/** One row of the Columns view. */
export interface ColumnRow {
    dataset: string;
    column: string;
    /** The Entity Type the column holds now (its classification, claimed by a type in force), else null. */
    entityType: string | null;
    /** The stored classification; set and claimed by no type in force means "classified, but not an Entity Type". */
    classification: string | null;
    /** `from` / `to` when the column is a link end of the Dataset's default link mapping. */
    linkEnd: 'from' | 'to' | null;
    /** A type the column's NAME suggests, offered only while nothing is set. */
    suggestion: string | null;
    reason: ColumnReason;
}

/** The type a column NAME suggests: a word of the name is a type's id or one of its classifications (`from_account`). */
export function suggestType<T extends { id: string; classifications: readonly string[] }>(
    column: string,
    types: readonly T[],
): T | null {
    const words = column
        .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
        .toLowerCase()
        .split(/[^a-z0-9]+/)
        .filter(Boolean);
    if (!words.length) return null;
    const joined = `_${words.join('_')}_`;
    for (const t of types)
        for (const key of [t.id, ...t.classifications])
            if (key.trim() && joined.includes(`_${key.trim().toLowerCase()}_`)) return t;
    return null;
}

/** `id`, `*_id`, `*_ID`, `*Id`, `*ID`: a name that says it identifies something (any case; `paid` and `valid` do not). */
function isIdName(name: string): boolean {
    return /(^|[^a-z])id$/.test(name.toLowerCase()) || /[a-z0-9](Id|ID)$/.test(name);
}

/**
 * The columns that look like identifiers, per Dataset in registry order: classified, a link end of the default
 * mapping, or a name that suggests an Entity Type (or ends in "id"). `all` lists every column.
 */
export function columnRows(
    datasets: readonly DatasetEntry[],
    types: readonly EntityTypeSummary[],
    all = false,
): ColumnRow[] {
    const rows: ColumnRow[] = [];
    for (const d of datasets) {
        const ends = linkEnds(d.content);
        for (const c of datasetColumns(d.content)) {
            const lower = c.name.toLowerCase();
            const linkEnd = lower === ends.from ? 'from' : lower === ends.to ? 'to' : null;
            const type = typeForClassification(c.classification, types);
            const suggested = type || c.classification ? null : suggestType(c.name, types);
            const reason: ColumnReason = c.classification
                ? 'classified'
                : linkEnd
                  ? 'link'
                  : suggested || isIdName(c.name)
                    ? 'suggested'
                    : 'other';
            if (reason === 'other' && !all) continue;
            rows.push({
                dataset: d.id,
                column: c.name,
                entityType: type?.id ?? null,
                classification: c.classification,
                linkEnd,
                suggestion: suggested?.id ?? null,
                reason,
            });
        }
    }
    return rows;
}

/** The choices of "what this column holds": every type in force, then "not set" (clears the classification). */
export function entityTypeOptions(types: readonly { id: string; label: string }[], blank = 'Not set'): PickerOption[] {
    return [...types.map((t) => ({ value: t.id, label: t.label })), { value: '', label: blank }];
}

/** The "how values are compared" choices, in plain words, for the closed normaliser set. */
export const NORMALISER_OPTIONS: readonly PickerOption[] = [
    { value: 'upper-trim', label: 'Upper case, spaces trimmed', hint: 'ab 123 and AB 123 are the same value' },
    { value: 'digits', label: 'Digits only', hint: 'Everything but 0-9 is dropped: 35-2099 is 352099' },
    { value: 'e164', label: 'Phone number', hint: 'A leading + and the digits; 00 becomes +' },
    { value: 'default', label: 'Case ignored', hint: 'Lower case, spaces tidied, trailing punctuation dropped' },
];

/** The id a new Entity Type gets from its name: lower case, words joined by `_`, a letter first, at most 32 characters. */
export function entityTypeIdFor(name: string): string {
    let id = name
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '_')
        .replace(/^_+|_+$/g, '');
    if (id && !/^[a-z]/.test(id)) id = `t_${id}`;
    return id.slice(0, 32).replace(/_+$/, '');
}

/** What is wrong with a new Entity Type named `name` beside the types in force, or '' (the server's rules, said early). */
export function newEntityTypeProblem(name: string, inForce: readonly EntityTypeConfig[]): string {
    const label = name.trim();
    if (!label) return 'Give the Entity Type a name.';
    const id = entityTypeIdFor(label);
    if (!id) return 'Use at least one letter or digit in the name.';
    if (id === 'entity') return '"Entity" is reserved; choose another name.';
    if (inForce.some((t) => t.label.trim().toLowerCase() === label.toLowerCase()))
        return `There is already an Entity Type called "${label}".`;
    if (inForce.some((t) => t.id === id)) return `The id "${id}" is taken by another Entity Type; choose another name.`;
    const cls = id.toUpperCase();
    const owner = typeForClassification(cls, inForce);
    if (owner) return `"${owner.label}" already claims the classification ${cls}; choose another name.`;
    return '';
}

/** A new Entity Type: its classification is its id in upper case; not masked (masking is a Settings decision). */
export function newEntityType(name: string, normaliser: EntityNormaliser): EntityTypeConfig {
    const id = entityTypeIdFor(name);
    return { id, label: name.trim(), normaliser, masked: false, classifications: [id.toUpperCase()] };
}

/** How `example` is kept under `normaliser` (the key two spellings must share to be one Entity). */
export function exampleKey(example: string, normaliser: EntityNormaliser): string {
    return normalizeTypedKey(example, normaliser);
}

/**
 * The `PUT /settings/link-analysis` body that changes ONLY the Entity Types: the route replaces the document, keeps
 * every limit key the body leaves out, and clears any other key left out, so the non-limit keys go back exactly as
 * read (the same keys the Settings pane sends).
 */
export function settingsBodyWithTypes(
    served: LinkAnalysisLimits,
    entityTypes: readonly EntityTypeConfig[],
): Record<string, unknown> {
    const body: Record<string, unknown> = {
        maskingMode: served.maskingMode ?? null,
        entityTypes: entityTypes.map((t) => ({ ...t, classifications: [...t.classifications] })),
    };
    const enabled = served.index?.enabled;
    if (enabled === true || enabled === false) body['index'] = { enabled };
    const weights = served.graphRun?.propagatedRiskWeights;
    if (weights?.length) body['graphRun'] = { propagatedRiskWeights: [...weights] };
    const classes = served.embed?.refuseClassifications ?? [];
    const frames = served.embed?.frameAncestors ?? [];
    if (classes.length || frames.length)
        body['embed'] = {
            refuseClassifications: classes.length ? [...classes] : null,
            frameAncestors: frames.length ? [...frames] : null,
        };
    return body;
}

/** The one-line note beside a column: why it is listed, in plain words. */
export function columnNote(row: ColumnRow, types: readonly { id: string; label: string }[]): string {
    const label = (id: string | null) => types.find((t) => t.id === id)?.label ?? id ?? '';
    const parts: string[] = [];
    if (row.linkEnd === 'from') parts.push('Links start here');
    if (row.linkEnd === 'to') parts.push('Links end here');
    if (row.classification && !row.entityType)
        parts.push(`Classified ${row.classification}, which no Entity Type claims`);
    if (row.suggestion) parts.push(`Looks like ${label(row.suggestion)}`);
    return parts.join(' · ');
}

/** A change the Columns view made, for the result line once the Entity Types are read again. */
export type EntitiesChange =
    | { kind: 'column'; dataset: string; column: string; entityType: string | null; previous: string | null }
    | { kind: 'primary'; dataset: string; entityType: string | null }
    | { kind: 'type'; entityType: string };

/** The result of `change` as the re-read Entity Types state it ('' when there is nothing to say). */
export function changeResult(change: EntitiesChange | null, types: readonly EntityTypeSummary[]): string {
    if (!change) return '';
    const find = (id: string | null) => types.find((t) => t.id === id) ?? null;
    const where = (t: EntityTypeSummary) => {
        const cols = t.bindings.length;
        const sets = new Set(t.bindings.map((b) => b.dataset)).size;
        return cols
            ? `${t.label} is in ${cols} ${cols === 1 ? 'column' : 'columns'} of ${sets} ${sets === 1 ? 'Dataset' : 'Datasets'}.`
            : `${t.label} is not in your data.`;
    };
    switch (change.kind) {
        case 'column': {
            const now = find(change.entityType);
            const before = find(change.previous);
            const head = now
                ? `"${change.column}" in ${change.dataset} now holds ${now.label}.`
                : `"${change.column}" in ${change.dataset} no longer holds an Entity Type.`;
            const tails = [now, before && before.id !== now?.id ? before : null].filter(
                (t): t is EntityTypeSummary => !!t,
            );
            return [head, ...tails.map(where)].join(' ');
        }
        case 'primary': {
            const now = find(change.entityType);
            return now
                ? `${change.dataset} now leads with ${now.label}.`
                : `${change.dataset} has no primary Entity Type.`;
        }
        case 'type': {
            const now = find(change.entityType);
            return now
                ? `${now.label} was added. Set it on a column under Columns to bring it into your data.`
                : `The new Entity Type was saved.`;
        }
    }
}

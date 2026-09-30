import type { EntityIdMapping } from './graph-source';

/**
 * The ONE entity-identity key (decision D-S4, 2026-09-23): case-folded, internal whitespace collapsed,
 * trailing punctuation stripped, trimmed (`' Acme  Ltd.'` -> `acme ltd`). Every entity node id minted by
 * Link Analysis (`entity-projection.ts`) and by the geo bridge (`geo-analysis.ts` `coLocations` /
 * `coLocationGraph`) goes through this, so one entity gets one id from every path. It is NOT a display
 * string — callers keep a raw spelling as the label. `tools/check-split-identity-fixture.mjs` mirrors this
 * rule in plain Node; change both together.
 *
 * LA-17 step 2: an Entity Type names one of a CLOSED set of normalisers (`ENTITY_NORMALISERS`); `default` is
 * the rule above. The Java side (`EntityTypes`) must agree case-for-case — both run
 * `entity-normaliser-parity.fixture.json`. `tools/check-split-identity-fixture.mjs` mirrors only `default`.
 * Typed ids are `<type>:<key>` (D-M6, `typedEntityKey`); untyped ids keep `entity:<value>`.
 */
export type EntityNormaliser = 'default' | 'digits' | 'e164' | 'upper-trim';

export const ENTITY_NORMALISERS: readonly EntityNormaliser[] = ['default', 'digits', 'e164', 'upper-trim'];

export function normalizeEntityKey(value: string): string {
    return value
        .toLowerCase()
        .replace(/ς/g, 'σ') // final sigma -> sigma: Java and JS place Final_Sigma differently
        .replace(/\s+/g, ' ')
        .replace(/[\s.,;:]+$/, '')
        .trim();
}

/** The key under one of the closed-set normalisers; `default` is {@link normalizeEntityKey}. */
export function normalizeTypedKey(value: string, normaliser: EntityNormaliser): string {
    switch (normaliser) {
        case 'digits':
            return value.replace(/[^0-9]/g, '');
        case 'e164': {
            const t = value.trim();
            const d = t.replace(/[^0-9]/g, '');
            // A bare '+' (from '+N/A', '00', …) carries no number: empty, so it mints no junk hub.
            const r = t.startsWith('+') ? '+' + d : d.startsWith('00') ? '+' + d.slice(2) : d;
            return r === '+' ? '' : r;
        }
        case 'upper-trim':
            return value.replace(/\s+/g, ' ').trim().toUpperCase();
        case 'default':
            return normalizeEntityKey(value);
    }
}

/**
 * The Entity Type a projection column is typed by (LA-17 D-M6): its Dataset's `columns[].classification`
 * claimed by an in-force Entity Type. The SERVER resolves it and sends it with the projection answer
 * (`columnTypes` on `/inv/projection[/neighbors]`, `entityType`/`sourceType`/`targetType` on `/inv/projection/multi`
 * rows), so the SPA never guesses which columns are typed.
 */
export interface EntityTypeRef {
    id: string;
    normaliser: EntityNormaliser;
}

/** A typed entity id (D-M6): `<type>:<normalised key>`. */
export function typedEntityKey(type: string, value: string, normaliser: EntityNormaliser): string {
    return `${type}:${normalizeTypedKey(value, normaliser)}`;
}

/**
 * The UNTYPED Entity node id for a projected value. Type-scoped (`entity:<entityType>:<key>`) when a
 * multi-mapping merge supplies an `entityType`, so a `person` "Bob" stays distinct from an `account`
 * "Bob" (Phase C). Unscoped `entity:<key>` otherwise. The key is {@link normalizeEntityKey} of the raw
 * value (D-S4, 2026-09-23), so `ACME Ltd` and ` acme  ltd.` are ONE node; the raw spelling stays the label.
 * A column typed by an Entity Type mints through {@link endpointId} instead (D-M6).
 */
export function entityId(entityType: string | undefined, value: string): string {
    const key = normalizeEntityKey(value);
    return entityType ? `entity:${entityType}:${key}` : `entity:${key}`;
}

/**
 * The node id for a value under an optional column type: typed `<type>:<key>` (D-M6), else {@link entityId}.
 * `null` when the type's normaliser leaves an EMPTY key (`N/A` under `digits`): the value is treated like a blank
 * one -- no node, no edge -- exactly as the server refuses an empty key, so it never becomes one `msisdn:` super-node.
 */
export function typedOrEntityId(
    type: EntityTypeRef | undefined,
    entityType: string | undefined,
    value: string,
): string | null {
    if (!type) return entityId(entityType, value);
    return normalizeTypedKey(value, type.normaliser) ? typedEntityKey(type.id, value, type.normaliser) : null;
}

/**
 * THE mint for a value read from one endpoint column of a mapping (LA-17 D-M6): a typed column
 * (`sourceType` / `targetType`, server-resolved from the Dataset's classification) mints `<type>:<key>`
 * with that type's normaliser; an untyped column keeps {@link entityId}. Every projection path mints here.
 */
export function endpointId(p: EntityIdMapping | undefined, end: 'source' | 'target', value: string): string | null {
    return typedOrEntityId(end === 'source' ? p?.sourceType : p?.targetType, p?.entityType, value);
}

/**
 * Every id `value` could have under the given mappings, preferred end first. Used where a raw value arrives
 * WITHOUT its column (a path hop, a Working Set seed, a Geo key): the caller picks the candidate already drawn.
 */
export function entityIdCandidates(
    mappings: readonly (EntityIdMapping | undefined)[],
    value: string,
    prefer: 'source' | 'target' = 'source',
): string[] {
    const other = prefer === 'source' ? 'target' : 'source';
    const out = new Set<string>();
    for (const m of mappings.length ? mappings : [undefined]) {
        for (const id of [endpointId(m, prefer, value), endpointId(m, other, value)]) if (id) out.add(id);
    }
    return [...out];
}

/** The candidate id already in `has`, else the first (preferred) one. */
export function resolveEntityId(
    mappings: readonly (EntityIdMapping | undefined)[],
    value: string,
    has: (id: string) => boolean,
    prefer: 'source' | 'target' = 'source',
): string | null {
    const c = entityIdCandidates(mappings, value, prefer);
    return c.find(has) ?? c[0] ?? null;
}

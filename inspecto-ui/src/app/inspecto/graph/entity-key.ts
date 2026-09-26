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
            if (t.startsWith('+')) return '+' + d;
            if (d.startsWith('00')) return '+' + d.slice(2);
            return d;
        }
        case 'upper-trim':
            return value.replace(/\s+/g, ' ').trim().toUpperCase();
        case 'default':
            return normalizeEntityKey(value);
    }
}

/** A typed entity id (D-M6): `<type>:<normalised key>`. */
export function typedEntityKey(type: string, value: string, normaliser: EntityNormaliser): string {
    return `${type}:${normalizeTypedKey(value, normaliser)}`;
}

/**
 * The ONE entity-identity key (decision D-S4, 2026-09-23): case-folded, internal whitespace collapsed,
 * trailing punctuation stripped, trimmed (`' Acme  Ltd.'` -> `acme ltd`). Every entity node id minted by
 * Link Analysis (`entity-projection.ts`) and by the geo bridge (`geo-analysis.ts` `coLocations` /
 * `coLocationGraph`) goes through this, so one entity gets one id from every path. It is NOT a display
 * string — callers keep a raw spelling as the label. `tools/check-split-identity-fixture.mjs` mirrors this
 * rule in plain Node; change both together.
 */
export function normalizeEntityKey(value: string): string {
    return value
        .toLowerCase()
        .replace(/\s+/g, ' ')
        .replace(/[\s.,;:]+$/, '')
        .trim();
}

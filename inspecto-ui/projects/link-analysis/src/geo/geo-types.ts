/**
 * Shared geo data shapes (Geo Map Analysis) — the map-renderer contract, the geo analog of
 * `inspecto/graph`'s `G6GraphData`. Pure types, no Angular/MapLibre imports.
 * Design: docs/superpower/geo-map-analysis-plan.md.
 */

/** A located entity occurrence: one marker on the map. Coordinates are WGS84 degrees. */
export interface GeoPoint {
    id: string;
    lat: number;
    lon: number;
    /** Entity kind — drives icon/colour, mirrors graph node `kind`. */
    kind: string;
    label?: string;
    /**
     * The STABLE identity of the located entity, when the caller maps a key column (D-U3).
     *
     * 🔴 Distinct from both `id` and `label`, and neither is a substitute. `id` is `pt:<row index>` —
     * positional, regenerated every projection run, a decoy that looks like identity. `label` is a display
     * string, so it collides (`ACME Ltd` vs `acme ltd.`) and need not match a graph node's id. Geo ↔ Link
     * brushing needs a key that means the same thing on both canvases.
     *
     * ⚠ Absent when unmapped, and that is deliberate: an absent key is honest, while a fabricated one would
     * let a selection isolate the wrong nodes — a wrong answer wearing the shape of a finding.
     */
    key?: string;
    /** Event time (epoch millis) when the source maps a time field. */
    time?: number;
    /** Source-row attributes surfaced in the detail sheet. */
    attrs?: Record<string, unknown>;
}

/** A relationship between two located points, drawn as a route line. */
export interface GeoRoute {
    id: string;
    /** Endpoint point ids. */
    from: string;
    to: string;
    kind: string;
    label?: string;
    weight?: number;
    time?: number;
}

/** An investigator's annotation pinned to a coordinate (persisted with a saved Geo View). */
export interface GeoNote {
    id: string;
    lat: number;
    lon: number;
    text: string;
}

/** What a GeoSource query returns and the map view renders. */
export interface GeoData {
    points: GeoPoint[];
    routes: GeoRoute[];
    /** Set when a projection cap truncated the result (banner in the studio). */
    truncated?: boolean;
}

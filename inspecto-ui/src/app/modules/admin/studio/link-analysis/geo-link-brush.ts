import { Injectable, signal } from '@angular/core';
import { GeoPoint } from 'app/inspecto/geo';
import type { EntityIdMapping } from 'app/inspecto/graph';
import { entityIdCandidates } from './entity-projection';

/**
 * LA-22 — synchronised Geo ↔ Link brushing. A selection on one canvas is published here and the other
 * canvas highlights the matching elements.
 *
 * The join is the threaded entity key (D-U3): a {@link GeoPoint.key} becomes a graph node id ONLY through
 * {@link entityIdCandidates} over the run's id mappings — the same mint the projection uses — so a change to id
 * minting (D-S4 normalisation, D-M6 typed `<type>:<key>` ids) reaches the brush for free. A key records no column,
 * so every endpoint's candidate is tried against the drawn ids. ⛔ Never match on `GeoPoint.label` (a display string)
 * or `GeoPoint.id` (a positional row index); a point with no key never brushes.
 */
export type GeoLinkBrush =
    | { origin: 'geo'; keys: string[] }
    /** `mappings` are the Link run's id mappings (`undefined` = the unscoped, untyped single mapping). */
    | { origin: 'link'; nodeIds: string[]; mappings: (EntityIdMapping | undefined)[] };

/** Geo → Link: the graph node ids the given entity keys project to. */
export function nodeIdsForKeys(
    keys: Iterable<string>,
    nodeIds: ReadonlySet<string>,
    mappings: (EntityIdMapping | undefined)[],
): string[] {
    const hits = new Set<string>();
    for (const key of keys) {
        for (const id of entityIdCandidates(mappings, key)) if (nodeIds.has(id)) hits.add(id);
    }
    return [...hits];
}

/** Link → Geo: the ids of the points whose key projects to one of the selected node ids. */
export function pointIdsForNodes(
    points: GeoPoint[],
    nodeIds: ReadonlySet<string>,
    mappings: (EntityIdMapping | undefined)[],
): string[] {
    return points.filter((p) => p.key && nodeIdsForKeys([p.key], nodeIds, mappings).length).map((p) => p.id);
}

/** The shared brush, root-scoped so it survives navigating between the Geo Map and Link Analysis panes. */
@Injectable({ providedIn: 'root' })
export class GeoLinkBrushService {
    private readonly state = signal<GeoLinkBrush | null>(null);
    readonly brush = this.state.asReadonly();

    fromGeo(keys: string[]): void {
        this.state.set({ origin: 'geo', keys: [...new Set(keys.filter(Boolean))] });
    }

    fromLink(nodeIds: string[], mappings: (EntityIdMapping | undefined)[]): void {
        this.state.set({ origin: 'link', nodeIds, mappings });
    }

    clear(): void {
        this.state.set(null);
    }
}

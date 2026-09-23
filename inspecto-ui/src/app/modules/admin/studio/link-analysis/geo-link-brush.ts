import { Injectable, signal } from '@angular/core';
import { GeoPoint } from 'app/inspecto/geo';
import { entityId } from './entity-projection';

/**
 * LA-22 — synchronised Geo ↔ Link brushing. A selection on one canvas is published here and the other
 * canvas highlights the matching elements.
 *
 * The join is the threaded entity key (D-U3): a {@link GeoPoint.key} becomes a graph node id ONLY through
 * {@link entityId}, the same function the projection mints node ids with — so a change to id minting
 * (D-S4 normalisation) reaches the brush for free. ⛔ Never match on `GeoPoint.label` (a display string)
 * or `GeoPoint.id` (a positional row index); a point with no key never brushes.
 */
export type GeoLinkBrush =
    | { origin: 'geo'; keys: string[] }
    /** `entityTypes` are the Link query's mapping types (`undefined` = the unscoped single mapping). */
    | { origin: 'link'; nodeIds: string[]; entityTypes: (string | undefined)[] };

/** Geo → Link: the graph node ids the given entity keys project to. */
export function nodeIdsForKeys(
    keys: Iterable<string>,
    nodeIds: ReadonlySet<string>,
    entityTypes: (string | undefined)[],
): string[] {
    const hits = new Set<string>();
    for (const key of keys) {
        for (const t of entityTypes) {
            const id = entityId(t, key);
            if (nodeIds.has(id)) hits.add(id);
        }
    }
    return [...hits];
}

/** Link → Geo: the ids of the points whose key projects to one of the selected node ids. */
export function pointIdsForNodes(
    points: GeoPoint[],
    nodeIds: ReadonlySet<string>,
    entityTypes: (string | undefined)[],
): string[] {
    return points.filter((p) => p.key && nodeIdsForKeys([p.key], nodeIds, entityTypes).length).map((p) => p.id);
}

/** The shared brush, root-scoped so it survives navigating between the Geo Map and Link Analysis panes. */
@Injectable({ providedIn: 'root' })
export class GeoLinkBrushService {
    private readonly state = signal<GeoLinkBrush | null>(null);
    readonly brush = this.state.asReadonly();

    fromGeo(keys: string[]): void {
        this.state.set({ origin: 'geo', keys: [...new Set(keys.filter(Boolean))] });
    }

    fromLink(nodeIds: string[], entityTypes: (string | undefined)[]): void {
        this.state.set({ origin: 'link', nodeIds, entityTypes });
    }

    clear(): void {
        this.state.set(null);
    }
}

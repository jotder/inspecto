import type { ProjectionTriple } from '../api/inv.service';

/** One neighbour of the selected entity, folded over every link row between the two (LA-EXPAND-PICK-NEIGHBOURS-1). */
export interface NeighbourRow {
    /** The neighbour's id as the server returned it (an alias when the read was masked): hand it back unchanged. */
    id: string;
    /** Events across every link kind between the two entities, in either direction. */
    count: number;
    /** The link kinds seen, sorted; empty when the Investigation has no link-kind column. */
    kinds: string[];
}

/** How many neighbours "Add the strongest" picks. */
export const STRONGEST_NEIGHBOURS = 10;

/**
 * The neighbours of `value` from a `POST /inv/projection/neighbors` read, strongest first (ties by id). The route returns one
 * row per (source, target, kind); this folds them per neighbour. A self-link is no neighbour and is left out.
 */
export function rankNeighbours(rows: ProjectionTriple[], value: string): NeighbourRow[] {
    const byId = new Map<string, { count: number; kinds: Set<string> }>();
    for (const r of rows) {
        const other = r.source === value ? r.target : r.source;
        if (other === value) continue;
        const acc = byId.get(other) ?? { count: 0, kinds: new Set<string>() };
        acc.count += r.count;
        if (r.kind) acc.kinds.add(r.kind);
        byId.set(other, acc);
    }
    return [...byId.entries()]
        .map(([id, a]) => ({ id, count: a.count, kinds: [...a.kinds].sort() }))
        .sort((x, y) => y.count - x.count || x.id.localeCompare(y.id));
}

/** One neighbour of the selected entity, folded by the server over every link row between the two (LA-EXPAND-PICK-NEIGHBOURS-1). */
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

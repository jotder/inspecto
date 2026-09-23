import { G6GraphData, G6Node } from 'app/inspecto/graph';
import { CaseEntityMember } from 'app/inspecto/api';

/** At most this many members per Case opened from Link Analysis — the server's own cap (422 above it). */
export const MAX_CASE_MEMBERS = 100;

/** A graph node that can become a member of a new Case, and what it is sent as. */
export interface CaseMemberCandidate {
    nodeId: string;
    label: string;
    /** `Dataset · entity key`, or the Incident id — what the picker shows under the label. */
    detail: string;
    member: CaseEntityMember;
}

/**
 * **Which graph nodes can become Case members, and as what** (LA-CASE-CREATE-IN-PLACE-1, operator decision
 * 2026-09-23: *mint from the node*). A node qualifies when it is a real projected Entity:
 *  - a node that already references an Incident (`objectRef`, projected from an `incidentId` column) joins as
 *    that Incident — minting a second object for it would duplicate the record it points at;
 *  - any other Entity is minted, keyed by its node id (`entity:[<type>:]<normalised key>`, D-S4) plus the
 *    Dataset it was projected from — the identity the server reuses on a second mint.
 *
 * ⛔ Excluded, deliberately: stranded nodes (not in the current answer), super-nodes (a stand-in for many
 * Entities — minting one object for 200 accounts would be a lie), a node referencing a Case (a Case is merged,
 * never contained), non-Entity nodes, and an Entity with no knowable source Dataset (no identity to key on).
 *
 * The Dataset is the node's own provenance when the projection recorded it (LA-08; several Datasets merged
 * into one node are joined, sorted, so the identity is stable), else the Dataset the pane projected from.
 */
export function caseMemberCandidates(graph: G6GraphData, originDataset: string | undefined): CaseMemberCandidate[] {
    const out: CaseMemberCandidate[] = [];
    for (const n of graph.nodes) {
        const d = n.data;
        if (d.missing || d.superMembers?.length || d.kind !== 'entity') continue;
        if (d.objectRef) {
            if (d.objectRef.type === 'INCIDENT')
                out.push({
                    nodeId: n.id,
                    label: d.label,
                    detail: d.objectRef.id,
                    member: { objectId: d.objectRef.id },
                });
            continue;
        }
        if (!n.id.startsWith('entity:')) continue;
        const dataset = datasetOf(n, originDataset);
        if (!dataset) continue;
        out.push({
            nodeId: n.id,
            label: d.label,
            detail: `${dataset} · ${n.id.slice('entity:'.length)}`,
            member: { id: n.id, dataset, label: d.label },
        });
    }
    return out;
}

function datasetOf(n: G6Node, originDataset: string | undefined): string | undefined {
    const p = n.data.provenance?.filter((x) => !!x) ?? [];
    if (p.length) return [...new Set(p)].sort().join(', ');
    return originDataset || undefined;
}

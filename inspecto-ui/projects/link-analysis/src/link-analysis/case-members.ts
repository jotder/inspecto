import { G6GraphData, G6Node } from '@inspecto/core/graph';
import type { LaCaseEntityMember } from '@inspecto/link-analysis/la-host';

/** At most this many members per Case opened from Link Analysis — the server's own cap (422 above it). */
export const MAX_CASE_MEMBERS = 100;

/** A graph node that can become a member of a new Case, and what it is sent as. */
export interface CaseMemberCandidate {
    nodeId: string;
    label: string;
    /** `Dataset · entity key`, or the Incident id — what the picker shows under the label. */
    detail: string;
    member: LaCaseEntityMember;
}

/**
 * **Which graph nodes can become Case members, and as what** (LA-CASE-CREATE-IN-PLACE-1, operator decision
 * 2026-09-23: *mint from the node*). A node qualifies when it is a real projected Entity:
 *  - a node that already references an Incident (`objectRef`, projected from an `incidentId` column) joins as
 *    that Incident — minting a second object for it would duplicate the record it points at;
 *  - any other Entity is minted, keyed by its node id (`entity:<normalised key>`, or typed `<type>:<key>` — D-S4, D-M6) plus the
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
        if (d.missing || d.superMembers?.length || !isEntityNode(n)) continue;
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
        if (!ENTITY_NODE_ID.test(n.id) || MASKED_NODE_ID.test(n.id)) continue;
        const dataset = datasetOf(n, originDataset);
        if (!dataset) continue;
        out.push({
            nodeId: n.id,
            label: d.label,
            detail: `${dataset} · ${n.id.startsWith('entity:') ? n.id.slice('entity:'.length) : n.id}`,
            member: { id: n.id, dataset, label: d.label },
        });
    }
    return out;
}

/**
 * Why no node of `graph` can become a Case member, in the words the analyst can act on. Masking is the usual cause
 * (every Entity is a pseudonym the server refuses as a Case key), and naming the Dataset instead would send them
 * looking in the wrong place.
 */
export function noCaseMemberReason(graph: G6GraphData): string {
    const masked = graph.nodes.some((n) => isEntityNode(n) && MASKED_NODE_ID.test(n.id));
    return masked
        ? 'No node on this graph can become a Case member: its entities are masked, and a masked pseudonym is refused as a Case key. Ask for Reveal rights, or link an Investigation to a Case instead.'
        : 'No node on this graph can become a Case member: each must be an Entity with a known source Dataset.';
}

/** An Entity node id: untyped `entity:<key>` or typed `<type>:<key>` (D-M6) — the server's `EntityMember` rule. */
const ENTITY_NODE_ID = /^[a-z][a-z0-9_]{0,31}:./;

/**
 * A masked pseudonym (`masked:<hex>`, bare, after a type, or after `entity:<type>:` as a typed Investigation node mints
 * it): the server refuses it as a Case key (422), so it is never offered.
 */
const MASKED_NODE_ID = /^([a-z][a-z0-9_]{0,31}:){0,2}masked:/;

/** An Entity node: an untyped one (`kind` 'entity') or an Investigation's typed one (`kind` = its Entity Type). */
export function isEntityNode(n: G6Node): boolean {
    return n.data.kind === 'entity' || !!n.data.entityType;
}

function datasetOf(n: G6Node, originDataset: string | undefined): string | undefined {
    const p = n.data.provenance?.filter((x) => !!x) ?? [];
    if (p.length) return [...new Set(p)].sort().join(', ');
    return originDataset || undefined;
}

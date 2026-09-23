import { describe, expect, it } from 'vitest';
import { G6GraphData } from 'app/inspecto/graph';
import { caseMemberCandidates } from './case-members';

const G: G6GraphData = {
    nodes: [
        { id: 'entity:acme ltd', data: { label: 'ACME Ltd', kind: 'entity', spellings: ['ACME Ltd', 'acme ltd'] } },
        { id: 'entity:account:bob', data: { label: 'Bob', kind: 'entity', provenance: ['orders', 'invoices'] } },
        { id: 'entity:inc-7', data: { label: 'INC-7', kind: 'entity', objectRef: { id: 'INC-7', type: 'INCIDENT' } } },
        { id: 'entity:case-1', data: { label: 'CASE-1', kind: 'entity', objectRef: { id: 'CASE-1', type: 'CASE' } } },
        { id: 'entity:gone', data: { label: 'Gone', kind: 'entity', missing: true } },
        { id: 'super:1', data: { label: '200 accounts', kind: 'entity', superMembers: ['entity:a', 'entity:b'] } },
        { id: 'pipeline:p', data: { label: 'p', kind: 'pipeline' } },
    ],
    edges: [],
};

describe('caseMemberCandidates', () => {
    it('mints real Entities, joins a referenced Incident as itself, and excludes everything that is not one Entity', () => {
        const c = caseMemberCandidates(G, 'tx');
        expect(c.map((x) => x.nodeId)).toEqual(['entity:acme ltd', 'entity:account:bob', 'entity:inc-7']);
        // identity = the node id (type + D-S4 key) + the source Dataset; the label is the raw spelling
        expect(c[0].member).toEqual({ id: 'entity:acme ltd', dataset: 'tx', label: 'ACME Ltd' });
        // the node's own provenance wins over the pane's Dataset, merged Datasets joined sorted (stable identity)
        expect(c[1].member).toEqual({ id: 'entity:account:bob', dataset: 'invoices, orders', label: 'Bob' });
        // a node that already IS an Incident is not minted a second time
        expect(c[2].member).toEqual({ objectId: 'INC-7' });
    });

    it('offers no Entity it cannot key — no provenance and no Dataset on the pane', () => {
        expect(caseMemberCandidates(G, undefined).map((x) => x.nodeId)).toEqual(['entity:account:bob', 'entity:inc-7']);
    });
});

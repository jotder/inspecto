import { describe, expect, it } from 'vitest';
import { EntityProjection, G6GraphData } from '@inspecto/core/graph';
import { WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import { caseMemberCandidates, isEntityNode, noCaseMemberReason } from './case-members';
import { workingSetToGraph } from './investigation-state';

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

describe('caseMemberCandidates - typed Investigation nodes (ONB-LA-ENDPOINT-TYPE-1)', () => {
    // Attach to Case and Create Case (link-analysis-evidence.dialogs.ts) both list exactly these candidates.
    const ws = (entities: { id: string; type: string | null }[]): WorkingSet => ({
        entities: entities.map((e) => ({ ...e, hop: 0, seed: e.id, admittedBy: 1, hidden: false, kept: false })),
        links: [],
        excluded: [],
        hash: 'h',
    });
    const P: EntityProjection = { datasetId: 'calls', sourceCol: 'A', targetCol: 'B' };

    it('offers a typed Working Set entity (kind = its Entity Type) as a member', () => {
        const g = workingSetToGraph(ws([{ id: '99979100001', type: 'msisdn' }, { id: 'x', type: null }]), P);
        expect(g.nodes.find((n) => n.data.label === '99979100001')!.data.kind).toBe('msisdn');
        const c = caseMemberCandidates(g, 'calls');
        expect(c.map((m) => m.label).sort()).toEqual(['99979100001', 'x']);
    });

    it('never offers a typed masked alias, and says masking is why', () => {
        const g: G6GraphData = {
            nodes: [
                {
                    id: 'entity:msisdn:masked:0123456789abcdef',
                    data: { label: 'masked:0123456789abcdef', kind: 'msisdn', entityType: 'msisdn' },
                },
            ],
            edges: [],
        };
        expect(caseMemberCandidates(g, 'calls')).toEqual([]);
        expect(noCaseMemberReason(g)).toContain('masked');
    });

    it('still excludes non-Entity kinds', () => {
        expect(isEntityNode({ id: 'pipeline:p', data: { label: 'p', kind: 'pipeline' } })).toBe(false);
        expect(isEntityNode({ id: 'msisdn:1', data: { label: '1', kind: 'msisdn', entityType: 'msisdn' } })).toBe(true);
    });
});

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

    it('keys a typed Entity by its typed node id (LA-17 D-M6)', () => {
        const typed: G6GraphData = {
            nodes: [{ id: 'msisdn:+4478', data: { label: '0044 78', kind: 'entity' } }],
            edges: [],
        };
        expect(caseMemberCandidates(typed, 'calls')).toEqual([
            {
                nodeId: 'msisdn:+4478',
                label: '0044 78',
                detail: 'calls · msisdn:+4478',
                member: { id: 'msisdn:+4478', dataset: 'calls', label: '0044 78' },
            },
        ]);
    });

    it('offers no Entity it cannot key — no provenance and no Dataset on the pane', () => {
        expect(caseMemberCandidates(G, undefined).map((x) => x.nodeId)).toEqual(['entity:account:bob', 'entity:inc-7']);
    });

    it('offers no masked Entity — its node id is a pseudonym, which the server refuses as a Case key', () => {
        const masked: G6GraphData = {
            nodes: [
                { id: 'masked:0123456789abcdef', data: { label: 'masked', kind: 'entity' } },
                { id: 'msisdn:masked:0123456789abcdef', data: { label: 'masked', kind: 'entity' } },
            ],
            edges: [],
        };
        expect(caseMemberCandidates(masked, 'calls')).toEqual([]);
    });

    it('says masking, not a missing Dataset, when masked Entities are why nothing can become a member', () => {
        const masked: G6GraphData = {
            nodes: [{ id: 'masked:0123456789abcdef', data: { label: 'masked', kind: 'entity' } }],
            edges: [],
        };
        expect(noCaseMemberReason(masked)).toContain('masked');
        expect(noCaseMemberReason(masked)).not.toContain('known source Dataset');
        const other: G6GraphData = { nodes: [{ id: 'a', data: { label: 'a', kind: 'other' } }], edges: [] };
        expect(noCaseMemberReason(other)).toContain('known source Dataset');
    });
});

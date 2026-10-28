import { describe, expect, it } from 'vitest';
import type { G6GraphData } from '@inspecto/core/graph';
import { SUPER_NODE_KIND } from '@inspecto/link-analysis/graph/graph-analysis';
import { busiest, groups, route } from './discover';
import type { PackTerms } from './grow-exclude';

const T: PackTerms = { node: 'account', nodes: 'accounts', link: 'transfer', links: 'transfers' };

const node = (id: string, extra: Record<string, unknown> = {}) => ({ id, data: { label: id, kind: 'entity', ...extra } });
const edge = (s: string, t: string, at?: string) => ({
    id: `${s}->${t}`,
    source: s,
    target: t,
    data: { kind: 'transfer', ...(at ? { attrs: { booked: at } } : {}) },
});

/** A busy merchant every victim pays, and a tight ring of three mules that pass money round within a few days. */
const G: G6GraphData = {
    nodes: ['BIG', 'V1', 'V2', 'V3', 'V4', 'V5', 'V6', 'M1', 'M2', 'M3'].map((id) => node(id)),
    edges: [
        ...['V1', 'V2', 'V3', 'V4', 'V5', 'V6'].map((v) => edge(v, 'BIG')),
        edge('V1', 'M1'),
        edge('M1', 'M2', '2026-09-03'),
        edge('M2', 'M3', '2026-09-04'),
        edge('M3', 'M1', '2026-09-05'),
    ],
};

describe('Find something interesting - the readings (LA-ONBOARD-1 phase 7, UC-12)', () => {
    it('Who is busiest: ranked by links on the graph, each row a sentence in the pack words, the top one with the hint', () => {
        const r = busiest(G, T, 3);
        expect(r.heading).toBe('The 3 busiest accounts on this graph');
        expect(r.rows.map((x) => x.title)).toEqual(['BIG', 'M1', 'M2']); // ties rank by id (canonical-v1)
        expect(r.rows[0].reading).toBe('BIG takes part in 6 transfers, the most on this graph.');
        expect(r.rows[1].reading).toBe('M1 takes part in 3 transfers.');
        expect(r.rows[0].hint).toBe('BIG has 6 transfers. Add it to an exclusion list?');
        expect(r.rows[1].hint).toBeUndefined();
        expect(r.rows[0].edgeIds).toHaveLength(6);
        expect(r.rows[0].values).toEqual(['BIG']);
        // what a start from a row says it started from: that row, never the whole ranking
        expect(r.rows.map((x) => x.what)).toEqual(['BIG, the busiest account', 'M1, one of the busiest accounts', 'M2, one of the busiest accounts']);
    });

    it('no hint when the top one is not far busier than the next', () => {
        const flat: G6GraphData = { nodes: ['A', 'B', 'C'].map((id) => node(id)), edges: [edge('A', 'B'), edge('B', 'C'), edge('C', 'A')] };
        expect(busiest(flat, T).rows.every((x) => !x.hint)).toBe(true);
    });

    it('Who is busiest never ranks a super node, and says why when nothing has a link', () => {
        const g: G6GraphData = { nodes: [node('S', { kind: SUPER_NODE_KIND }), node('A')], edges: [edge('S', 'A')] };
        expect(busiest(g, T).rows.map((x) => x.title)).toEqual(['A']);
        expect(busiest({ nodes: [node('A')], edges: [] }, T)).toMatchObject({ rows: [], empty: 'No account on this graph has a transfer yet.' });
    });

    it('Which groups stick together: groups of three or more, with their links among them and the days they span', () => {
        const r = groups(G, T, 'booked');
        const ring = r.rows.find((x) => x.nodeIds.includes('M2'))!;
        // the victim who paid into the ring belongs with it; the merchant and its other payers are a group of their own
        expect([...ring.nodeIds].sort()).toEqual(['M1', 'M2', 'M3', 'V1']);
        expect(ring.reading).toMatch(/^Group \d: 4 accounts, 4 transfers among them within 2 days \(.+ and 1 more\)\.$/);
        expect([...ring.edgeIds].sort()).toEqual(['M1->M2', 'M2->M3', 'M3->M1', 'V1->M1']);
        expect(r.rows.find((x) => x.nodeIds.includes('BIG'))!.nodeIds).not.toContain('M1');
        // without a time attribute the reading says no days
        expect(groups(G, T).rows.find((x) => x.nodeIds.includes('M2'))!.reading).not.toContain('within');
    });

    it('Which groups stick together says so when no group of three exists', () => {
        const g: G6GraphData = { nodes: [node('A'), node('B')], edges: [edge('A', 'B')] };
        expect(groups(g, T)).toMatchObject({ rows: [], empty: 'No group of three or more accounts sticks together on this graph.' });
    });

    it('How does A reach B: following the links one way when it can, and saying so when it cannot', () => {
        const one = route(G, 'V1', 'M3', T);
        expect(one.rows[0].reading).toBe('V1 reaches M3 in 3 transfers: V1 → M1 → M2 → M3.');
        expect(one.rows[0].nodeIds).toEqual(['V1', 'M1', 'M2', 'M3']);
        const both = route(G, 'V2', 'V3', T);
        expect(both.rows[0].reading).toBe('V2 and V3 are linked through 2 transfers, not all in one direction: V2 – BIG – V3.');
        const none = route({ nodes: [node('A'), node('B')], edges: [] }, 'A', 'B', T);
        expect(none.empty).toBe('A and B are not linked on this graph. Expand… (G) from one of them may connect them.');
        expect(route(G, 'V1', 'V1', T).empty).toBe('Choose two different accounts on the graph.');
    });
});

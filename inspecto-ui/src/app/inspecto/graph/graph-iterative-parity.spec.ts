import { describe, expect, it } from 'vitest';
import type { G6GraphData } from './graph-types';
import {
    detectCommunities,
    eigenvectorCentrality,
    hits,
    katzCentrality,
    louvainCommunities,
    pageRank,
} from './graph-analysis';
import fixture from './graph-iterative-parity.fixture.json';

/**
 * D-S4 parity — the browser half, tranche A, iterative + communities slice. `graph-iterative-parity.fixture.json` is
 * ALSO run through the Java port by `GraphIterativeParityTest` (inspecto-la-graph). Scores are compared in rank order
 * to `fixture.epsilon`; communities as EXACT ordered (node, community) pairs (the community id is its smallest member,
 * and the Map's iteration order — members grouped by first-seen label — is part of the contract).
 */
type Case = { nodes: string[]; edges: string[][] };
const build = (c: Case): G6GraphData => ({
    nodes: c.nodes.map((id) => ({ id, data: { label: id, kind: 'entity' } })),
    edges: c.edges.map(([id, source, target]) => ({ id, source, target, data: { kind: 'link' } })),
});
type Pair = [string, number];
const GRAPHS = ['main', 'cliques', 'hitsGraph', 'edgeless', 'empty', 'louvainRand0', 'louvainRand296'] as const;
const graphs = fixture.graphs as Record<string, Case>;
// The fixture's `expected` block is heterogeneous JSON (a different shape per graph and per algorithm); each case below
// asserts the shape it reads, so a precise static type would only restate the fixture.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const expected = fixture.expected as unknown as Record<string, Record<string, any>>;
const eps = fixture.epsilon;

const expectScores = (actual: { id: string; score: number }[], want: Pair[]): void => {
    expect(actual.map((s) => s.id)).toEqual(want.map((w) => w[0]));
    actual.forEach((s, i) => expect(Math.abs(s.score - want[i][1])).toBeLessThanOrEqual(eps));
};
const pairs = (m: Map<string, string>): [string, string][] => [...m.entries()];

describe('iterative graph algorithms parity fixture (D-S4, tranche A)', () => {
    for (const name of GRAPHS) {
        const g = build(graphs[name]);
        const e = expected[name];
        if (e['pageRank']) it(`pageRank / ${name}`, () => expectScores(pageRank(g), e['pageRank']));
        if (e['eigenvector']) it(`eigenvector / ${name}`, () => expectScores(eigenvectorCentrality(g), e['eigenvector']));
        if (e['katz']) it(`katz / ${name}`, () => expectScores(katzCentrality(g), e['katz']));
        if (e['hits']) {
            it(`hits / ${name}`, () => {
                const r = hits(g);
                expectScores(r.hubs, e['hits'].hubs);
                expectScores(r.authorities, e['hits'].authorities);
            });
        }
        if (e['communities']) it(`detectCommunities / ${name}`, () => expect(pairs(detectCommunities(g))).toEqual(e['communities']));
        if (e['louvain']) it(`louvainCommunities / ${name}`, () => expect(pairs(louvainCommunities(g))).toEqual(e['louvain']));
    }

    describe('non-default parameters (main graph)', () => {
        const g = build(graphs['main']);
        const c = expected['mainCustom'];
        it('pageRank', () => expectScores(pageRank(g, { damping: c['pageRank'].damping, iterations: c['pageRank'].iterations }), c['pageRank'].scores));
        it('katz', () =>
            expectScores(
                katzCentrality(g, { alpha: c['katz'].alpha, beta: c['katz'].beta, iterations: c['katz'].iterations }),
                c['katz'].scores,
            ));
        it('eigenvector', () => expectScores(eigenvectorCentrality(g, c['eigenvector'].iterations), c['eigenvector'].scores));
        it('hits', () => {
            const r = hits(g, c['hits'].iterations);
            expectScores(r.hubs, c['hits'].hubs);
            expectScores(r.authorities, c['hits'].authorities);
        });
        it('detectCommunities', () =>
            expect(pairs(detectCommunities(g, c['communities'].maxIterations))).toEqual(c['communities'].pairs));
    });
});

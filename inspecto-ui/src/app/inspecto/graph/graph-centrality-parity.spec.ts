import { describe, expect, it } from 'vitest';
import type { G6GraphData } from './graph-types';
import { betweennessCentrality, closenessCentrality, jaccardSimilarity, linkPrediction } from './graph-analysis';
import fixture from './graph-centrality-parity.fixture.json';

/**
 * D-S4 parity — the browser half, single-pass float centrality lane. `graph-centrality-parity.fixture.json` is ALSO
 * run through the Java port by `GraphCentralityParityTest` (inspecto-la-graph); both assert the SAME hand-derived
 * `expected`, floats at `fixture.tolerance`.
 */
const graph: G6GraphData = {
    nodes: fixture.graph.nodes.map((id) => ({ id, data: { label: id, kind: 'entity' } })),
    edges: fixture.graph.edges.map(([id, source, target]) => ({ id, source, target, data: { kind: 'link' } })),
};
const DIGITS = 9; // toBeCloseTo(x, 9) is |diff| < 5e-10, inside the fixture's 1e-9

function expectScores(actual: { id: string; score: number }[], expected: (string | number)[][]): void {
    expect(actual.map((s) => s.id)).toEqual(expected.map((e) => e[0]));
    actual.forEach((s, i) => expect(s.score).toBeCloseTo(expected[i][1] as number, DIGITS));
}

describe('graph centrality parity fixture (D-S4, single-pass float lane)', () => {
    it('betweenness centrality', () => expectScores(betweennessCentrality(graph), fixture.expected.betweenness));
    it('closeness centrality', () => expectScores(closenessCentrality(graph), fixture.expected.closeness));

    for (const c of fixture.expected.jaccard) {
        it(`jaccard similarity of ${c.node}`, () => expectScores(jaccardSimilarity(graph, c.node), c.scores));
    }

    for (const c of fixture.expected.linkPrediction) {
        it(`link prediction (${c.method}, limit ${c.limit ?? 'default'})`, () => {
            const r = linkPrediction(graph, {
                method: c.method as 'common-neighbors' | 'adamic-adar',
                ...(c.limit === null ? {} : { limit: c.limit }),
            });
            expect(r.map((l) => [l.source, l.target])).toEqual(c.links.map((l) => [l[0], l[1]]));
            r.forEach((l, i) => expect(l.score).toBeCloseTo(c.links[i][2] as number, DIGITS));
        });
    }
});

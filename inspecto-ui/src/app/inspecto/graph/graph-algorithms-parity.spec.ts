import { describe, expect, it } from 'vitest';
import type { GraphDirection } from 'app/inspecto/api';
import type { G6GraphData } from './graph-types';
import {
    connectedComponents,
    degreeCentrality,
    kCore,
    neighborhood,
    shortestPath,
    triangleCount,
} from './graph-analysis';
import fixture from './graph-algorithms-parity.fixture.json';

/**
 * D-S4 parity — the browser half, tranche A slice 1. `graph-algorithms-parity.fixture.json` is ALSO run through the
 * Java port by `GraphAlgorithmsParityTest` (inspecto-la-graph); both assert the SAME hand-derived `expected`.
 */
const graph: G6GraphData = {
    nodes: fixture.graph.nodes.map((id) => ({ id, data: { label: id, kind: 'entity' } })),
    edges: fixture.graph.edges.map(([id, source, target]) => ({ id, source, target, data: { kind: 'link' } })),
};
const scores = (r: { id: string; score: number }[]): [string, number][] => r.map((s) => [s.id, s.score]);

describe('graph algorithms parity fixture (D-S4, tranche A slice 1)', () => {
    it('degree centrality', () => expect(scores(degreeCentrality(graph))).toEqual(fixture.expected.degree));
    it('connected components', () => expect(connectedComponents(graph)).toEqual(fixture.expected.components));
    it('k-core', () => expect(scores(kCore(graph))).toEqual(fixture.expected.kCore));
    it('triangle count', () => expect(scores(triangleCount(graph))).toEqual(fixture.expected.triangles));

    for (const c of fixture.expected.shortestPath) {
        it(`shortest path ${c.from}→${c.to} (${c.direction})`, () => {
            const r = shortestPath(graph, c.from, c.to, c.direction as GraphDirection);
            expect(r ? r.nodeIds : null).toEqual(c.nodeIds);
            expect(r ? r.edgeIds : null).toEqual(c.edgeIds);
        });
    }

    for (const c of fixture.expected.neighborhood) {
        it(`neighborhood of ${c.node}, ${c.hops} hop(s) (${c.direction})`, () => {
            const r = neighborhood(graph, c.node, c.hops, c.direction as GraphDirection);
            expect(r.nodes.map((n) => n.id)).toEqual(c.nodeIds);
            expect(r.edges.map((e) => e.id)).toEqual(c.edgeIds);
        });
    }
});

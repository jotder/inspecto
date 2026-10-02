import { describe, expect, it } from 'vitest';
import type { G6GraphData } from '@inspecto/core/graph/graph-types';
import { articulationPoints, bridges, cliques, descendants, findCycles, isForest } from './graph-analysis';
import fixture from './graph-structure-parity.fixture.json';

/**
 * D-S4 parity — the browser half, structure lane. `graph-structure-parity.fixture.json` is ALSO run through the
 * Java port by `GraphStructureParityTest` (inspecto-la-graph); both assert the SAME hand-derived `expected`.
 */
const toGraph = (g: { nodes: string[]; edges: string[][] }): G6GraphData => ({
    nodes: g.nodes.map((id) => ({ id, data: { label: id, kind: 'entity' } })),
    edges: g.edges.map(([id, source, target]) => ({ id, source, target, data: { kind: 'link' } })),
});
const graph = toGraph(fixture.graph);

describe('graph structure parity fixture (D-S4, structure lane)', () => {
    it('articulation points', () => expect(articulationPoints(graph)).toEqual(fixture.expected.articulationPoints));
    it('bridges', () => expect(bridges(graph)).toEqual(fixture.expected.bridges));

    for (const c of fixture.expected.isForest) {
        it(`isForest: ${c.name}`, () => {
            const g = 'graph' in c ? toGraph(c.graph as { nodes: string[]; edges: string[][] }) : graph;
            expect(isForest(g)).toBe(c.forest);
        });
    }

    for (const c of fixture.expected.descendants) {
        it(`descendants of ${c.root}`, () => expect([...descendants(graph, c.root)]).toEqual(c.ids));
    }

    for (const c of fixture.expected.cycles) {
        it(`findCycles: ${c.name}`, () => expect(findCycles(graph, c.opts)).toEqual(c.cycles));
    }

    const walk = fixture.expected.cyclesTwoInOneWalk;
    for (const c of walk.cases) {
        it(`findCycles (two cycles in one walk): ${c.name}`, () =>
            expect(findCycles(toGraph(walk.graph), c.opts)).toEqual(c.cycles));
    }

    for (const c of fixture.expected.cliques) {
        it(`cliques: ${c.name}`, () => expect(cliques(graph, c.opts)).toEqual(c.cliques));
    }
});

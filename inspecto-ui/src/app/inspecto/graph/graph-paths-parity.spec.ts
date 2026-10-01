import { describe, expect, it } from 'vitest';
import type { GraphDirection } from 'app/inspecto/api';
import type { G6GraphData } from './graph-types';
import {
    allPaths,
    edgeWeight,
    egoNetwork,
    maxFlow,
    maximumSpanningForest,
    weightedShortestPath,
} from './graph-analysis';
import fixture from './graph-paths-parity.fixture.json';

/**
 * D-S4 parity — the browser half, paths-and-flow lane. `graph-paths-parity.fixture.json` is ALSO run through the
 * Java port by `GraphPathsParityTest` (inspecto-la-graph); both assert the SAME hand-derived `expected`.
 */
type EdgeData = G6GraphData['edges'][number]['data'];
const edgeData = (kind: string, count: number | null): EdgeData => ({ kind, count }) as unknown as EdgeData;
const graph: G6GraphData = {
    nodes: fixture.graph.nodes.map((id) => ({ id, data: { label: id, kind: 'entity' } })),
    edges: fixture.graph.edges.map(([id, source, target, count, kind]) => ({
        id: id as string,
        source: source as string,
        target: target as string,
        data: edgeData(kind as string, count as number | null),
    })),
};

describe('graph paths parity fixture (D-S4, paths and flow)', () => {
    it('edge weight of every fixture edge', () => {
        const actual = graph.edges.map((e): [string, number] => [e.id, edgeWeight(e)]);
        expect(actual).toEqual(fixture.expected.edgeWeight);
    });

    for (const c of fixture.expected.edgeWeightExtra) {
        it(`edge weight of count ${c.count}, kind "${c.kind}"`, () => {
            const e = { id: 'x', source: 'a', target: 'b', data: edgeData(c.kind, c.count) };
            expect(edgeWeight(e)).toBe(c.weight);
        });
    }

    for (const c of fixture.expected.allPaths) {
        it(`all paths ${c.from}→${c.to} (${c.direction}, limit ${c.limit}, maxHops ${c.maxHops})`, () => {
            const r = allPaths(graph, c.from, c.to, {
                limit: c.limit,
                maxHops: c.maxHops,
                direction: c.direction as GraphDirection,
            });
            expect(r.map((p) => [p.nodeIds, p.edgeIds])).toEqual(c.paths);
        });
    }

    for (const c of fixture.expected.egoNetwork) {
        it(`ego network of ${c.node} (${c.direction})`, () => {
            const r = egoNetwork(graph, c.node, c.direction as GraphDirection);
            expect(r.nodes.map((n) => n.id)).toEqual(c.nodeIds);
            expect(r.edges.map((e) => e.id)).toEqual(c.edgeIds);
        });
    }

    for (const c of fixture.expected.weightedShortestPath) {
        it(`weighted shortest path ${c.from}→${c.to} (${c.direction})`, () => {
            const r = weightedShortestPath(graph, c.from, c.to, c.direction as GraphDirection);
            expect(r ? r.nodeIds : null).toEqual(c.nodeIds);
            expect(r ? r.edgeIds : null).toEqual(c.edgeIds);
        });
    }

    for (const c of fixture.expected.maxFlow) {
        it(`max flow ${c.from}→${c.to}`, () => {
            const r = maxFlow(graph, c.from, c.to);
            expect(r.value).toBe(c.value);
            expect(r.minCut.nodeIds).toEqual(c.nodeIds);
            expect(r.minCut.edgeIds).toEqual(c.edgeIds);
        });
    }

    it('maximum spanning forest', () => {
        const r = maximumSpanningForest(graph);
        expect(r.nodeIds).toEqual(fixture.expected.maximumSpanningForest.nodeIds);
        expect(r.edgeIds).toEqual(fixture.expected.maximumSpanningForest.edgeIds);
    });
});

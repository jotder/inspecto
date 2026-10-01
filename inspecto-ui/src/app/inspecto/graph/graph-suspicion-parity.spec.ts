import { describe, expect, it } from 'vitest';
import type { G6GraphData } from './graph-types';
import { suspicionScore } from './graph-analysis';
import fixture from './graph-suspicion-parity.fixture.json';

/**
 * D-S4 parity — the browser half, suspicion score. `graph-suspicion-parity.fixture.json` is ALSO run through the Java
 * port by `GraphSuspicionParityTest` (inspecto-la-graph); both assert the SAME `expected` (score + the five factors, in
 * rank order) at the fixture's `tolerance`.
 */
type FixtureGraph = { nodes: string[]; edges: string[][] };
const graphs = fixture.graphs as Record<string, FixtureGraph>;
const digits = Math.ceil(-Math.log10(fixture.tolerance)) - 1; // toBeCloseTo(x, d) is |Δ| < 10^-d / 2

function build(g: FixtureGraph): G6GraphData {
    return {
        nodes: g.nodes.map((id) => ({ id, data: { label: id, kind: 'entity' } })),
        edges: g.edges.map(([id, source, target]) => ({ id, source, target, data: { kind: 'link' } })),
    };
}

describe('suspicion score parity fixture (D-S4)', () => {
    for (const c of fixture.cases) {
        it(c.name, () => {
            const actual = suspicionScore(build(graphs[c.graph]), c.weights);
            expect(actual.map((s) => s.id)).toEqual(c.expected.map((e) => e[0]));
            actual.forEach((s, i) => {
                const [, score, factors] = c.expected[i] as [string, number, number[]];
                expect(s.score).toBeCloseTo(score, digits);
                expect(s.factors.degree).toBeCloseTo(factors[0], digits);
                expect(s.factors.betweenness).toBeCloseTo(factors[1], digits);
                expect(s.factors.pageRank).toBeCloseTo(factors[2], digits);
                expect(s.factors.core).toBeCloseTo(factors[3], digits);
                expect(s.factors.triangles).toBeCloseTo(factors[4], digits);
            });
        });
    }
});

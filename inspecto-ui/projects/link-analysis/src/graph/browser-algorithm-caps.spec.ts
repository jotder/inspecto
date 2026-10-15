import { afterEach, describe, expect, it } from 'vitest';
import { G6GraphData } from '@inspecto/core/graph/index';
import { matchBranchingPattern } from './branching-pattern-engine';
import {
    analysisNodeCapValue,
    betweennessCentrality,
    browserAlgorithmCapValue,
    cliques,
    closenessCentrality,
    degreeCentrality,
    eigenvectorCentrality,
    hits,
    katzCentrality,
    linkPrediction,
    louvainCommunities,
    maxFlow,
    selectionNodeCapValue,
    suspicionNodeCapValue,
} from './graph-analysis';
import { LA_LIMIT_FALLBACKS, LaLimitKey, configureLaLimits, resetLaLimits } from '@inspecto/link-analysis/link-analysis/la-limits';

/**
 * LA-CONFIG-LIMITS-1 CL-3 (operator, 2026-10-15): one browser cap PER ALGORITHM, `browser_algorithms.<id>`, replacing the
 * single shared cap for every algorithm the browser guards. Default 2 500, or the algorithm's measured ceiling where
 * lower. `analysis_node_cap` stays the general cap (the branching matcher, the selection algorithms' hand-over).
 */
function ring(n: number): G6GraphData {
    const nodes = Array.from({ length: n }, (_, i) => ({ id: `n${i}`, data: { label: `n${i}`, kind: 'entity' } }));
    const edges = Array.from({ length: n }, (_, i) => ({
        id: `e${i}`,
        source: `n${i}`,
        target: `n${(i + 1) % n}`,
        data: { kind: 'link' },
    }));
    return { nodes, edges } as G6GraphData;
}

/** Every guarded algorithm: its item, the name its refusal uses, and a run over `g`. */
const GUARDED: [LaLimitKey, string, (g: G6GraphData) => unknown][] = [
    ['browser_algorithms.betweennessCentrality', 'Betweenness', betweennessCentrality],
    ['browser_algorithms.closenessCentrality', 'Closeness', closenessCentrality],
    ['browser_algorithms.eigenvectorCentrality', 'Eigenvector centrality', eigenvectorCentrality],
    ['browser_algorithms.katzCentrality', 'Katz centrality', katzCentrality],
    ['browser_algorithms.hits', 'HITS', hits],
    ['browser_algorithms.cliques', 'Clique detection', cliques],
    ['browser_algorithms.maxFlow', 'Max-flow', (g) => maxFlow(g, 'n0', 'n1')], // vocab-allow: the max-flow algorithm id
    ['browser_algorithms.linkPrediction', 'Link prediction', linkPrediction],
    ['browser_algorithms.louvainCommunities', 'Community detection', louvainCommunities],
];

describe('per-algorithm browser caps (CL-3)', () => {
    afterEach(() => resetLaLimits());

    it('ships the decided defaults: 2 500, or the lower measured ceiling', () => {
        expect(LA_LIMIT_FALLBACKS.projection_node_cap).toBe(1000);
        expect(LA_LIMIT_FALLBACKS.analysis_node_cap).toBe(2500);
        expect(LA_LIMIT_FALLBACKS.suspicion_node_cap).toBe(1000);
        expect(browserAlgorithmCapValue('betweennessCentrality')).toBe(500);
        for (const a of ['closenessCentrality', 'eigenvectorCentrality', 'katzCentrality', 'hits', 'cliques', 'maxFlow', 'linkPrediction'] as const)
            expect(browserAlgorithmCapValue(a), a).toBe(1000);
        for (const a of ['louvainCommunities', 'detectCommunities', 'shortestPath', 'allPaths', 'findCycles', 'bridges'] as const)
            expect(browserAlgorithmCapValue(a), a).toBe(2500);
        expect('browser_algorithms.suspicionScore' in LA_LIMIT_FALLBACKS).toBe(false);
    });

    it.each(GUARDED)('%s: refuses above its own cap, naming the algorithm and the cap in force', (key, name, run) => {
        const g = ring(12);
        configureLaLimits({ [key]: 12 });
        expect(() => run(g)).not.toThrow(); // the probe that would otherwise succeed: AT the cap it runs
        configureLaLimits({ [key]: 11 });
        expect(() => run(g)).toThrowError(`${name} is capped at 11 nodes (graph has 12).`);
    });

    it.each(GUARDED)('%s: is its own cap - lowering every OTHER one, or the general cap, does not refuse it', (key, _name, run) => {
        const g = ring(12);
        const others = Object.fromEntries(GUARDED.filter(([k]) => k !== key).map(([k]) => [k, 2]));
        configureLaLimits({ ...others, analysis_node_cap: 2, suspicion_node_cap: 2 });
        expect(() => run(g)).not.toThrow();
    });

    it('the general cap still guards the branching matcher and is the selection threshold', () => {
        configureLaLimits({ analysis_node_cap: 5, 'browser_algorithms.closenessCentrality': 100 });
        expect(analysisNodeCapValue()).toBe(5);
        expect(selectionNodeCapValue()).toBe(5);
        expect(matchBranchingPattern(ring(6), [{ shape: 'chain', edgeKind: 'link' } as never]).refusal).toMatch(
            /capped at 5 nodes/,
        );
        expect(() => closenessCentrality(ring(6))).not.toThrow();
    });

    it('an algorithm the browser does not guard has no cap', () => {
        configureLaLimits({ analysis_node_cap: 1, suspicion_node_cap: 1 });
        expect(degreeCentrality(ring(30))).toHaveLength(30);
    });

    it('a nonsense per-algorithm value is refused and the previous one stands', () => {
        configureLaLimits({ 'browser_algorithms.hits': 40 });
        for (const bad of [0, -3, Number.NaN, Number.POSITIVE_INFINITY]) configureLaLimits({ 'browser_algorithms.hits': bad });
        expect(browserAlgorithmCapValue('hits')).toBe(40);
        expect(suspicionNodeCapValue()).toBe(1000);
    });
});

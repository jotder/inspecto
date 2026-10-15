import { afterEach, describe, expect, it } from 'vitest';
import {
    analysisNodeCapValue,
    betweennessCentrality,
    degreeCentrality,
    suspicionNodeCapValue,
    suspicionScore,
} from './graph-analysis';
import { G6GraphData } from '@inspecto/core/graph/index';
import { LA_LIMIT_FALLBACKS, configureLaLimits, resetLaLimits } from '@inspecto/link-analysis/link-analysis/la-limits';

/**
 * Suspicion score has its OWN cap, `suspicion_node_cap` (D-S3, 2026-09-22) - and since LA-CONFIG-LIMITS-1 CL-2
 * (operator, 2026-10-15) it is ONE value for the browser and the server, default 1 000: the browser refuses above it and
 * the server runs a larger one as a job (GraphRunRoutes reads the same key). Betweenness, which it blends, has its own
 * lower item (`browser_algorithms.betweennessCentrality`, 500) - and that one must never refuse a suspicion score.
 * Measured suspicion score end to end: 750 → 972 ms, 1 000 → 1 608 ms, 2 000 → 7 277 ms.
 */
function ring(n: number): G6GraphData {
    const nodes = Array.from({ length: n }, (_, i) => ({
        id: `n${i}`,
        data: { label: `n${i}`, kind: 'entity' },
    })) as G6GraphData['nodes'];
    const edges = Array.from({ length: n }, (_, i) => ({
        id: `e${i}`,
        source: `n${i}`,
        target: `n${(i + 1) % n}`,
        data: { kind: 'link' },
    })) as G6GraphData['edges'];
    return { nodes, edges };
}

describe('suspicion score has its own cap, shared with the server (D-S3, CL-2)', () => {
    afterEach(() => resetLaLimits());

    it('ships 1 000, below the general cap', () => {
        expect(LA_LIMIT_FALLBACKS.suspicion_node_cap).toBe(1000);
        expect(LA_LIMIT_FALLBACKS.suspicion_node_cap).toBeLessThan(LA_LIMIT_FALLBACKS.analysis_node_cap);
        expect(suspicionNodeCapValue()).toBe(LA_LIMIT_FALLBACKS.suspicion_node_cap);
    });

    // The point of the decision: a graph that is fine for the cheap algorithms is refused by this one.
    it('refuses above its own cap, and names it', () => {
        configureLaLimits({ analysis_node_cap: 2000, suspicion_node_cap: 100 });
        const g = ring(150);

        expect(() => degreeCentrality(g)).not.toThrow();
        expect(() => suspicionScore(g)).toThrow(/Suspicion scoring is capped at 100 nodes/);
    });

    it('runs when the graph is under its own cap', () => {
        const g = ring(40);

        expect(suspicionScore(g).length).toBe(40);
    });

    it('is settable independently of the general cap', () => {
        configureLaLimits({ suspicion_node_cap: 900 });

        expect(suspicionNodeCapValue()).toBe(900);
        expect(analysisNodeCapValue()).toBe(LA_LIMIT_FALLBACKS.analysis_node_cap);
    });

    // Fail closed: a cap of 0 or NaN would put EVERY graph over the limit and switch the tool off, which is worse
    // than ignoring the bad setting. This spec goes red if the guard is mutated away.
    it('ignores a nonsense cap and leaves the previous value standing', () => {
        configureLaLimits({ suspicion_node_cap: 900 });

        configureLaLimits({ suspicion_node_cap: 0 });
        expect(suspicionNodeCapValue()).toBe(900);

        configureLaLimits({ suspicion_node_cap: Number.NaN });
        expect(suspicionNodeCapValue()).toBe(900);

        configureLaLimits({ suspicion_node_cap: -5 });
        expect(suspicionNodeCapValue()).toBe(900);
    });

    it('resets to the shipped default, so one space cannot tune another', () => {
        configureLaLimits({ suspicion_node_cap: 900 });
        resetLaLimits();

        expect(suspicionNodeCapValue()).toBe(LA_LIMIT_FALLBACKS.suspicion_node_cap);
    });

    /**
     * 🔴 Since P2 betweenness has a LOWER cap (500) than suspicion score (1 000), yet suspicion score blends
     * betweenness. If it called the guarded `betweennessCentrality`, every suspicion score between the two caps would
     * be refused in betweenness's words - the Space's suspicion cap silently lowered to betweenness's.
     */
    it("betweenness's own lower cap never refuses a suspicion score the Space allows", () => {
        configureLaLimits({ 'browser_algorithms.betweennessCentrality': 10, suspicion_node_cap: 100 });
        const g = ring(40);

        expect(() => betweennessCentrality(g)).toThrow(/Betweenness is capped at 10 nodes/);
        expect(suspicionScore(g).length).toBe(40);
        expect(suspicionScore(g)[0].factors).toHaveProperty('betweenness');
    });

    it('the suspicion cap does not gate betweenness, which has its own item', () => {
        configureLaLimits({ suspicion_node_cap: 10, 'browser_algorithms.betweennessCentrality': 1000 });
        const g = ring(20);

        expect(() => betweennessCentrality(g)).not.toThrow();
        expect(() => suspicionScore(g)).toThrow(/capped at 10 nodes/);
    });
});

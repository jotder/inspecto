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
 * Decision D-S3 (operator, 2026-09-22): suspicion score gets its OWN, lower cap. The shared
 * `ANALYSIS_NODE_CAP` is sized for the other 26 algorithms, which stay under 60 ms at 2 000 nodes;
 * suspicion score takes ~7 s there because betweenness dominates and the growth is quadratic
 * (measured: 750 → 972 ms, 1 000 → 1 608 ms, 2 000 → 7 277 ms).
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

describe('suspicion score has its own cap (D-S3)', () => {
    afterEach(() => resetLaLimits());

    it('defaults lower than the shared analysis cap', () => {
        expect(LA_LIMIT_FALLBACKS.suspicion_node_cap).toBe(750);
        expect(LA_LIMIT_FALLBACKS.suspicion_node_cap).toBeLessThan(LA_LIMIT_FALLBACKS.analysis_node_cap);
        expect(suspicionNodeCapValue()).toBe(LA_LIMIT_FALLBACKS.suspicion_node_cap);
    });

    // The point of the decision: a graph that is fine for every other algorithm is refused by this one.
    it('refuses a graph the shared cap would admit, and names its own cap', () => {
        configureLaLimits({ analysis_node_cap: 2000, suspicion_node_cap: 100 });
        const g = ring(150);

        expect(() => degreeCentrality(g)).not.toThrow();
        expect(() => suspicionScore(g)).toThrow(/capped at 100 nodes/);
    });

    it('runs when the graph is under its own cap', () => {
        const g = ring(40);

        expect(suspicionScore(g).length).toBe(40);
    });

    it('is settable independently of the shared cap', () => {
        configureLaLimits({ suspicion_node_cap: 900 });

        expect(suspicionNodeCapValue()).toBe(900);
        expect(analysisNodeCapValue()).toBe(LA_LIMIT_FALLBACKS.analysis_node_cap);
    });

    // Fail closed, exactly as the other two caps do: a cap of 0 or NaN would put EVERY graph over the
    // limit and switch the tool off, which is worse than ignoring the bad setting. This spec goes red if
    // the guard is mutated away, because the bad value would then be applied.
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
     * 🔴 The cap belongs to BETWEENNESS, not to its caller. D-S3 set it on `suspicionScore` believing that
     * was the outlier; measured 2026-09-23, suspicion score is slow ONLY because it calls betweenness
     * (9 757 ms of a 9 359 ms blend at 2 000 nodes, every other component under 130 ms combined). While
     * betweenness sat behind the shared 2 000 cap, choosing "Betweenness" from the centrality list froze
     * the main thread for about ten seconds — the exact freeze D-S3 believed it had removed.
     */
    it('caps betweenness with the LOW ceiling, not the shared analysis cap', () => {
        configureLaLimits({ analysis_node_cap: 2000, suspicion_node_cap: 100 });
        const g = ring(150);

        expect(() => betweennessCentrality(g)).toThrow(/capped at 100 nodes/);
        // its cheap siblings stay on the shared cap and keep working at this size
        expect(() => degreeCentrality(g)).not.toThrow();
    });

    it('lets betweenness run under its own ceiling', () => {
        configureLaLimits({ suspicion_node_cap: 900 });

        expect(betweennessCentrality(ring(40)).length).toBe(40);
    });
});

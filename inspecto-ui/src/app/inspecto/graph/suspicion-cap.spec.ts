import { afterEach, describe, expect, it } from 'vitest';
import {
    ANALYSIS_NODE_CAP_DEFAULT,
    G6GraphData,
    SUSPICION_NODE_CAP_DEFAULT,
    analysisNodeCapValue,
    configureGraphLimits,
    degreeCentrality,
    resetGraphLimits,
    suspicionNodeCapValue,
    suspicionScore,
} from './index';

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
    afterEach(() => resetGraphLimits());

    it('defaults lower than the shared analysis cap', () => {
        expect(SUSPICION_NODE_CAP_DEFAULT).toBe(750);
        expect(SUSPICION_NODE_CAP_DEFAULT).toBeLessThan(ANALYSIS_NODE_CAP_DEFAULT);
        expect(suspicionNodeCapValue()).toBe(SUSPICION_NODE_CAP_DEFAULT);
    });

    // The point of the decision: a graph that is fine for every other algorithm is refused by this one.
    it('refuses a graph the shared cap would admit, and names its own cap', () => {
        configureGraphLimits({ analysisNodeCap: 2000, suspicionNodeCap: 100 });
        const g = ring(150);

        expect(() => degreeCentrality(g)).not.toThrow();
        expect(() => suspicionScore(g)).toThrow(/capped at 100 nodes/);
    });

    it('runs when the graph is under its own cap', () => {
        const g = ring(40);

        expect(suspicionScore(g).length).toBe(40);
    });

    it('is settable independently of the shared cap', () => {
        configureGraphLimits({ suspicionNodeCap: 900 });

        expect(suspicionNodeCapValue()).toBe(900);
        expect(analysisNodeCapValue()).toBe(ANALYSIS_NODE_CAP_DEFAULT);
    });

    // Fail closed, exactly as the other two caps do: a cap of 0 or NaN would put EVERY graph over the
    // limit and switch the tool off, which is worse than ignoring the bad setting. This spec goes red if
    // the guard is mutated away, because the bad value would then be applied.
    it('ignores a nonsense cap and leaves the previous value standing', () => {
        configureGraphLimits({ suspicionNodeCap: 900 });

        configureGraphLimits({ suspicionNodeCap: 0 });
        expect(suspicionNodeCapValue()).toBe(900);

        configureGraphLimits({ suspicionNodeCap: Number.NaN });
        expect(suspicionNodeCapValue()).toBe(900);

        configureGraphLimits({ suspicionNodeCap: -5 });
        expect(suspicionNodeCapValue()).toBe(900);
    });

    it('resets to the shipped default, so one space cannot tune another', () => {
        configureGraphLimits({ suspicionNodeCap: 900 });
        resetGraphLimits();

        expect(suspicionNodeCapValue()).toBe(SUSPICION_NODE_CAP_DEFAULT);
    });
});

import { afterEach, describe, expect, it } from 'vitest';
import type { G6GraphData } from './graph-types';
import { configureGraphLimits, matchPattern, resetGraphLimits } from './graph-analysis';
import { BranchStage, branchingNeedsTime, matchBranchingPattern, thresholdLabel } from './branching-pattern-engine';

/**
 * LA-14b — the branching matcher. Gate G-R5: "100 % of synthetic smurfing chains found; out-of-order
 * timestamps rejected". Fixtures are hand-built; no canvas.
 */

/** Structuring, as the plan and the demo corpus plant it: sub-threshold legs fan IN, then split, then re-converge. */
const STRUCTURING: BranchStage[] = [
    { shape: 'fan-in', minBranches: 5, threshold: { attr: 'AMOUNT', min: 900, max: 1000 }, windowHours: 24 },
    { shape: 'fan-out', minBranches: 2, afterPrevious: true, maxGapHours: 48 },
    { shape: 'fan-in', minBranches: 2, afterPrevious: true, maxGapHours: 48 },
];

type Leg = [source: string, target: string, amount: number, at: string];

function graph(legs: Leg[]): G6GraphData {
    const ids = [...new Set(legs.flatMap(([s, t]) => [s, t]))];
    return {
        nodes: ids.map((id) => ({ id, data: { label: id, kind: 'acct' } })),
        edges: legs.map(([source, target, amount, at], i) => ({
            id: `tx${i}`,
            source,
            target,
            data: { kind: 'pays', attrs: { AMOUNT: String(amount), AT: at } },
        })),
    } as G6GraphData;
}

const SMURFS = ['s1', 's2', 's3', 's4', 's5', 's6'];
const deposits: Leg[] = SMURFS.map((s, i) => [s, 'hub', 950 + i, `2026-09-01 1${i}:00:00`]);
const split: Leg[] = [
    ['hub', 'r1', 2800, '2026-09-01 20:00:00'],
    ['hub', 'r2', 2800, '2026-09-01 20:30:00'],
];
const converge: Leg[] = [
    ['r1', 'off', 2750, '2026-09-01 22:00:00'],
    ['r2', 'off', 2750, '2026-09-01 23:00:00'],
];
/** Ordinary small traffic that must never become part of the motif. */
const noise: Leg[] = [
    ['a1', 'a2', 40, '2026-09-01 09:00:00'],
    ['a2', 'a3', 1200, '2026-09-01 09:30:00'],
    ['s1', 'a1', 60, '2026-09-01 08:00:00'],
];

describe('matchBranchingPattern (LA-14b)', () => {
    afterEach(() => resetGraphLimits());

    it('finds a structuring motif: fan-in of sub-threshold legs, split, re-convergence', () => {
        const g = graph([...deposits, ...split, ...converge, ...noise]);
        const res = matchBranchingPattern(g, STRUCTURING, { timeAttr: 'AT' });
        expect(res.refusal).toBeUndefined();
        expect(res.matches).toHaveLength(1);
        const [m] = res.matches;
        expect(m.layers).toEqual([SMURFS, ['hub'], ['r1', 'r2'], ['off']]);
        // every deposit, both split legs and both converging legs — and none of the noise
        expect(m.edgeIds.sort()).toEqual(['tx0', 'tx1', 'tx2', 'tx3', 'tx4', 'tx5', 'tx6', 'tx7', 'tx8', 'tx9']);
        expect(m.nodeIds).not.toContain('a1');
    });

    it('does NOT find its linear-only twin, which the linear matcher does find', () => {
        // The same flow with one depositor and one relay: a chain, not a structure.
        const twin = graph([
            ['s1', 'hub', 950, '2026-09-01 10:00:00'],
            ['hub', 'r1', 950, '2026-09-01 20:00:00'],
            ['r1', 'off', 940, '2026-09-01 22:00:00'],
        ]);
        expect(matchBranchingPattern(twin, STRUCTURING, { timeAttr: 'AT' }).matches).toHaveLength(0);
        const chain = [{}, { direction: 'out' as const }, { direction: 'out' as const }, { direction: 'out' as const }];
        expect(matchPattern(twin, chain)).toHaveLength(1);
    });

    it('respects temporal order per branch: a relay forwarding BEFORE it was paid breaks the re-convergence', () => {
        const early: Leg[] = [
            ['r1', 'off', 2750, '2026-09-01 19:00:00'], // before hub → r1 at 20:00
            ['r2', 'off', 2750, '2026-09-01 23:00:00'],
        ];
        const g = graph([...deposits, ...split, ...early]);
        expect(matchBranchingPattern(g, STRUCTURING, { timeAttr: 'AT' }).matches).toHaveLength(0);
        // proof the shape itself matches: drop the ordering and it comes back
        const unordered = STRUCTURING.map((st) => ({ ...st, afterPrevious: false }));
        expect(matchBranchingPattern(g, unordered, { timeAttr: 'AT' }).matches).toHaveLength(1);
    });

    it('rejects a split made before the fan-in reached its minimum breadth', () => {
        // the collector wires out at 12:30 — only three depositors (10:00, 11:00, 12:00) had arrived
        const premature: Leg[] = [
            ['hub', 'r1', 2800, '2026-09-01 12:30:00'],
            ['hub', 'r2', 2800, '2026-09-01 12:45:00'],
            ['r1', 'off', 2750, '2026-09-01 22:00:00'],
            ['r2', 'off', 2750, '2026-09-01 23:00:00'],
        ];
        const g = graph([...deposits, ...premature]);
        expect(matchBranchingPattern(g, STRUCTURING, { timeAttr: 'AT' }).matches).toHaveLength(0);
    });

    it('honours the window: depositors spread over a week are not one fan-in', () => {
        const spread: Leg[] = SMURFS.map((s, i) => [s, 'hub', 950, `2026-09-0${i + 1} 10:00:00`]);
        const later: Leg[] = [
            ['hub', 'r1', 2800, '2026-09-07 20:00:00'],
            ['hub', 'r2', 2800, '2026-09-07 20:30:00'],
            ['r1', 'off', 2750, '2026-09-07 22:00:00'],
            ['r2', 'off', 2750, '2026-09-07 23:00:00'],
        ];
        expect(
            matchBranchingPattern(graph([...spread, ...later]), STRUCTURING, { timeAttr: 'AT' }).matches,
        ).toHaveLength(0);
    });

    it('keeps ordinary small payments out: many small senders under the band are not structuring', () => {
        const small: Leg[] = SMURFS.map((s, i) => [s, 'hub', 40 + i, `2026-09-01 1${i}:00:00`]);
        const g = graph([...small, ...split, ...converge, ['x', 'y', 950, '2026-09-01 10:00:00']]);
        expect(matchBranchingPattern(g, STRUCTURING, { timeAttr: 'AT' }).matches).toHaveLength(0);
    });

    it('REFUSES, never "no matches", when the view already filtered the sub-threshold legs away (§2.6 trap)', () => {
        // What `mule_large_transfers` (AMOUNT ≥ 5 000) leaves: only the large wires.
        const large = graph([
            ['hub', 'r1', 22000, '2026-09-01 20:00:00'],
            ['hub', 'r2', 22000, '2026-09-01 20:30:00'],
        ]);
        const res = matchBranchingPattern(large, STRUCTURING, { timeAttr: 'AT' });
        expect(res.matches).toHaveLength(0);
        expect(res.refusal).toContain('900 ≤ AMOUNT < 1000');
        expect(res.refusal).toContain('removed before it ran');
    });

    it('refuses when no link carries the threshold attribute', () => {
        const g = graph([...deposits]);
        g.edges.forEach((e) => delete e.data.attrs!['AMOUNT']);
        expect(matchBranchingPattern(g, STRUCTURING, { timeAttr: 'AT' }).refusal).toMatch(
            /No link carries a numeric AMOUNT/,
        );
    });

    it('fails closed on a temporal motif with no time column', () => {
        expect(branchingNeedsTime(STRUCTURING)).toBe(true);
        expect(branchingNeedsTime([{ shape: 'fan-in', minBranches: 3 }])).toBe(false);
        const g = graph([...deposits, ...split, ...converge]);
        const res = matchBranchingPattern(g, STRUCTURING);
        expect(res.matches).toHaveLength(0);
        expect(res.refusal).toMatch(/time column/);
    });

    it('refuses — does not throw — above the analysis node cap', () => {
        configureGraphLimits({ analysisNodeCap: 5 });
        const g = graph([...deposits, ...split, ...converge]);
        let res!: ReturnType<typeof matchBranchingPattern>;
        expect(() => (res = matchBranchingPattern(g, STRUCTURING, { timeAttr: 'AT' }))).not.toThrow();
        expect(res.matches).toHaveLength(0);
        expect(res.refusal).toMatch(/capped at 5 nodes/);
    });

    it('bounds the result: hitting the limit marks it truncated', () => {
        const two = graph([
            ...deposits,
            ...split,
            ...converge,
            ['r1', 'off2', 2750, '2026-09-01 22:10:00'],
            ['r2', 'off2', 2750, '2026-09-01 23:10:00'],
        ]);
        expect(matchBranchingPattern(two, STRUCTURING, { timeAttr: 'AT' }).matches).toHaveLength(2);
        const capped = matchBranchingPattern(two, STRUCTURING, { timeAttr: 'AT', limit: 1 });
        expect(capped.matches).toHaveLength(1);
        expect(capped.truncated).toBe(true);
    });

    it('supports a stage-0 fan-out (one source splitting to many)', () => {
        const g = graph([
            ['src', 'b1', 950, '2026-09-01 10:00:00'],
            ['src', 'b2', 950, '2026-09-01 10:05:00'],
            ['src', 'b3', 950, '2026-09-01 10:10:00'],
            ['b1', 'sink', 900, '2026-09-01 12:00:00'],
            ['b2', 'sink', 900, '2026-09-01 12:00:00'],
            ['b3', 'sink', 900, '2026-09-01 12:00:00'],
        ]);
        const res = matchBranchingPattern(
            g,
            [
                { shape: 'fan-out', minBranches: 3, threshold: { attr: 'AMOUNT', max: 1000 } },
                { shape: 'fan-in', minBranches: 3, afterPrevious: true },
            ],
            { timeAttr: 'AT' },
        );
        expect(res.matches.map((m) => m.layers)).toEqual([[['src'], ['b1', 'b2', 'b3'], ['sink']]]);
    });

    it('labels a threshold the way an analyst reads it', () => {
        expect(thresholdLabel({ attr: 'AMOUNT', min: 900, max: 1000 })).toBe('900 ≤ AMOUNT < 1000');
        expect(thresholdLabel({ attr: 'AMOUNT', max: 10000 })).toBe('AMOUNT < 10000');
    });
});

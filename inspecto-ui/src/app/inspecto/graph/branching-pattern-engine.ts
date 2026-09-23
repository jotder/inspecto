import type { G6Edge, G6GraphData } from './graph-types';
import type { GraphSelection } from './graph-analysis';
import { analysisNodeCapValue, baseEdgeKind, edgeTimeIndex, followsInTime } from './graph-analysis';

/**
 * **Branching pattern runtime (LA-14b)** — fan-out / fan-in motifs over the shared {@link G6GraphData} shape.
 *
 * `matchPattern` finds LINEAR motifs: one node per step, one edge per hop. That cannot express the shapes
 * money actually takes when it is being hidden — **structuring** (many sub-threshold deposits converging on
 * one collector, which then splits the total across several intermediaries that re-converge on one exit)
 * is a set of parallel legs at each stage, not a path. This file EXTENDS the linear matcher rather than
 * replacing it: linear packs still run through `matchPattern`, and this matcher reuses its temporal rule
 * ({@link followsInTime}), its time parse ({@link edgeTimeIndex}) and its kind folding ({@link baseEdgeKind}),
 * so the two can never disagree about when a link happened or what kind it is.
 *
 * <p><b>The model.</b> A motif is an ordered list of {@link BranchStage}s. The match carries a FRONTIER —
 * the nodes it has reached, each with the time it reached them.
 * <ul>
 *   <li><b>fan-in</b> at stage 0: every node with ≥ `minBranches` distinct counterparties sending it a
 *       qualifying leg is a candidate collector; the frontier becomes `{collector}`.</li>
 *   <li><b>fan-out</b> at stage 0: every node sending qualifying legs to ≥ `minBranches` distinct nodes is a
 *       candidate splitter; the frontier becomes those targets.</li>
 *   <li><b>fan-out</b> later: the frontier splits into ≥ `minBranches` distinct new nodes; they become the
 *       frontier.</li>
 *   <li><b>fan-in</b> later (re-convergence): ≥ `minBranches` distinct frontier members each send a leg to
 *       one common new node. Every such node is a separate match.</li>
 * </ul>
 * A leg into or out of a frontier node obeys LA-14a's rule against the time the match reached THAT node —
 * per branch, not per stage — so relay-01 forwarding before it was paid is rejected even when relay-02's
 * timing is fine. The time at which a fan-in "arrives" is the moment it reached its minimum breadth (the
 * `minBranches`-th distinct counterparty), which is what "collected, then moved on" means.
 *
 * <p>⛔ <b>Fails closed and says why</b>, the way LA-14a does — {@link BranchingResult.refusal} is set,
 * never an empty result that reads as "not present":
 * a temporal motif with no time column · a threshold on an attribute no link carries · a threshold that
 * NO link in the graph passes (the plan §2.6 filter trap: a view filtered to `AMOUNT ≥ 5 000` has already
 * removed every structuring leg before this runs, and "no matches" would declare the structuring absent) ·
 * a graph over the analysis cap. None of these throws: the cap is a refusal, not an exception.
 *
 * <p><b>Bounded.</b> Every leg examination counts against {@link BRANCHING_WORK_BUDGET}; on breach the
 * search stops and {@link BranchingResult.truncated} is set, so it can never hang the main thread.
 */

/**
 * A per-leg numeric range — the pack's VISIBLE threshold, e.g. `900 ≤ AMOUNT < 1000`. `min` is inclusive and
 * `max` exclusive, because a reporting threshold is crossed AT its value: a 1 000 deposit is reported, a
 * 999.99 one is not. Either bound may be absent. A band (both bounds) is what "just under the threshold"
 * means, and it is what keeps ordinary small payments — the long tail under any threshold — out of the motif.
 */
export interface LegThreshold {
    /** The edge attribute column (entity-projection `attrCols`), parsed with `Number`. */
    attr: string;
    min?: number;
    max?: number;
}

/** One stage of a branching motif. The temporal fields are LA-14a's, with the same meaning. */
export interface BranchStage {
    shape: 'fan-in' | 'fan-out';
    /** Minimum DISTINCT counterparties at this stage (senders for fan-in, receivers for fan-out). ≥ 1. */
    minBranches: number;
    /** The kind each leg must be (base kind); wildcard when absent. */
    edgeKind?: string;
    /** The kind of the node(s) this stage reaches (the collector for fan-in, each branch for fan-out). */
    nodeKind?: string;
    /** Each counted leg must pass this — legs that fail it are not part of the motif. */
    threshold?: LegThreshold;
    /** All counted legs of this stage fall within this many hours of each other (per collector/splitter). */
    windowHours?: number;
    /** Each leg is strictly after the match reached its tail node. Ignored on stage 0. */
    afterPrevious?: boolean;
    /** Upper bound on that gap, in hours. Only meaningful with {@link afterPrevious}. */
    maxGapHours?: number;
}

export interface BranchingOptions {
    /** The edge attribute holding the event time — the pane's time column, exactly as for `matchPattern`. */
    timeAttr?: string;
    /** Maximum matches returned (default 200). */
    limit?: number;
}

/** One match: every node and leg of the motif, plus the node LAYERS in stage order for a readable label. */
export interface BranchingMatch extends GraphSelection {
    /** e.g. `[[smurf…], [hub], [relay-01, relay-02], [offshore]]` for structuring. */
    layers: string[][];
}

export interface BranchingResult {
    matches: BranchingMatch[];
    /** Set when the motif could not be evaluated — the reason, in the analyst's words. Matches are empty. */
    refusal?: string;
    /** The work budget or the match limit was hit, so there may be more matches than returned. */
    truncated: boolean;
}

/** Ceiling on leg examinations per run. ~6 000 legs × a handful of stages is far below it. */
export const BRANCHING_WORK_BUDGET = 500_000;

/** Whether a motif needs a time column to mean anything — a window, or ordering after stage 0. */
export function branchingNeedsTime(stages: BranchStage[]): boolean {
    return stages.some((st, i) => (st.windowHours ?? 0) > 0 || (i > 0 && !!st.afterPrevious));
}

/** A threshold rendered the way the analyst reads it, e.g. `900 ≤ AMOUNT < 1000`. */
export function thresholdLabel(t: LegThreshold): string {
    return [t.min !== undefined ? `${t.min} ≤` : '', t.attr, t.max !== undefined ? `< ${t.max}` : '']
        .filter(Boolean)
        .join(' ');
}

function passes(t: LegThreshold, v: number): boolean {
    return (t.min === undefined || v >= t.min) && (t.max === undefined || v < t.max);
}

function numericAttr(e: G6Edge, attr: string): number | undefined {
    const raw = e.data.attrs?.[attr];
    if (raw == null || String(raw).trim() === '') return undefined;
    const v = Number(raw);
    return Number.isFinite(v) ? v : undefined;
}

/** A leg in the frame of the stage node it is grouped under: `other` is the counterparty. */
interface Leg {
    edgeId: string;
    other: string;
    t: number | undefined;
}

/**
 * The legs a stage counts for one collector/splitter, reduced to the EARLIEST window holding ≥ `min` distinct
 * counterparties (or all of them when there is no window). Returns null when the breadth is not reached.
 * `arrival` is the time the `min`-th distinct counterparty appeared — when the fan "completed".
 */
function selectWindow(
    legs: Leg[],
    min: number,
    windowHours: number | undefined,
): { legs: Leg[]; arrival: number | undefined } | null {
    const distinct = new Set(legs.map((l) => l.other));
    if (distinct.size < min) return null;
    const timed = legs.filter((l) => l.t !== undefined).sort((a, b) => a.t! - b.t!);
    const completion = (ordered: Leg[]): number | undefined => {
        const seen = new Set<string>();
        for (const l of ordered) {
            seen.add(l.other);
            if (seen.size >= min) return l.t;
        }
        return undefined;
    };
    if (!windowHours || windowHours <= 0) return { legs, arrival: completion(timed) };
    const span = windowHours * 3_600_000;
    for (let i = 0, j = 0; i < timed.length; i++) {
        while (j < timed.length && timed[j].t! - timed[i].t! <= span) j++;
        const win = timed.slice(i, j);
        if (new Set(win.map((l) => l.other)).size >= min) return { legs: win, arrival: completion(win) };
    }
    return null;
}

/** A fan-out's new frontier: each reached node with the EARLIEST time a counted leg reached it. */
function earliestPerNode(legs: Leg[]): Map<string, number | undefined> {
    const next = new Map<string, number | undefined>();
    for (const l of legs) {
        const prev = next.get(l.other);
        if (!next.has(l.other) || (l.t !== undefined && (prev === undefined || l.t < prev))) next.set(l.other, l.t);
    }
    return next;
}

/**
 * Find every match of a branching motif. See the file header for the model and the refusals.
 * Never mutates its input and never throws on a well-typed motif.
 */
export function matchBranchingPattern(
    g: G6GraphData,
    stages: BranchStage[],
    opts: BranchingOptions = {},
): BranchingResult {
    const { timeAttr, limit = 200 } = opts;
    const refuse = (refusal: string): BranchingResult => ({ matches: [], refusal, truncated: false });
    if (!stages.length) return refuse('This pattern has no stages.');
    const cap = analysisNodeCapValue();
    if (g.nodes.length > cap) {
        return refuse(`Branching patterns are capped at ${cap} nodes (graph has ${g.nodes.length}).`);
    }
    if (branchingNeedsTime(stages) && !timeAttr) {
        return refuse('This pattern has a time window or ordering — choose a time column in the Query panel first.');
    }

    const nodeKind = new Map(g.nodes.map((n) => [n.id, n.data.kind]));
    const kindOk = (id: string, k?: string): boolean => !k || nodeKind.get(id) === k;
    const edgeKind = new Map(g.edges.map((e) => [e.id, baseEdgeKind(e.data.kind)]));
    const edgeTime = edgeTimeIndex(g, timeAttr);

    // Per stage: the legs it may count, after kind + threshold. A threshold nothing passes is the §2.6 trap.
    const eligible: G6Edge[][] = [];
    for (const st of stages) {
        let legs = g.edges.filter((e) => e.source !== e.target && (!st.edgeKind || edgeKind.get(e.id) === st.edgeKind));
        if (st.threshold) {
            const th = st.threshold;
            const valued = legs.filter((e) => numericAttr(e, th.attr) !== undefined);
            if (!valued.length) {
                return refuse(
                    `No link carries a numeric ${th.attr}, so the threshold ${thresholdLabel(th)} cannot be evaluated — add ${th.attr} as a link attribute in the Query panel.`,
                );
            }
            legs = valued.filter((e) => passes(th, numericAttr(e, th.attr)!));
            if (!legs.length) {
                return refuse(
                    `No link in this graph passes ${thresholdLabel(th)}. If the view filters ${th.attr} (for example to ≥ 5 000), the legs this pattern looks for were removed before it ran — clear that filter rather than read this as "none found".`,
                );
            }
        }
        eligible.push(legs);
    }
    const index = eligible.map((legs) => {
        const out = new Map<string, Leg[]>();
        const inn = new Map<string, Leg[]>();
        for (const e of legs) {
            const t = edgeTime.get(e.id);
            if (!out.has(e.source)) out.set(e.source, []);
            out.get(e.source)!.push({ edgeId: e.id, other: e.target, t });
            if (!inn.has(e.target)) inn.set(e.target, []);
            inn.get(e.target)!.push({ edgeId: e.id, other: e.source, t });
        }
        return { out, in: inn };
    });

    let work = 0;
    let truncated = false;
    const spend = (n: number): boolean => {
        work += n;
        if (work > BRANCHING_WORK_BUDGET) truncated = true;
        return !truncated;
    };
    const matches: BranchingMatch[] = [];

    interface State {
        frontier: Map<string, number | undefined>;
        used: Set<string>;
        edges: string[];
        layers: string[][];
    }

    const extend = (stageIdx: number, s: State): void => {
        if (truncated || matches.length >= limit) return;
        if (stageIdx >= stages.length) {
            matches.push({ nodeIds: [...s.used], edgeIds: [...new Set(s.edges)], layers: s.layers });
            return;
        }
        const st = stages[stageIdx];
        const { out } = index[stageIdx];
        if (st.shape === 'fan-out') {
            // The frontier splits: legs out of every frontier node, each ordered against ITS arrival.
            const legs: Leg[] = [];
            for (const [f, at] of s.frontier) {
                const cand = out.get(f) ?? [];
                if (!spend(cand.length)) return;
                for (const l of cand) {
                    if (s.used.has(l.other) || !kindOk(l.other, st.nodeKind)) continue;
                    if (followsInTime(at, l.t, st)) legs.push(l);
                }
            }
            const win = selectWindow(legs, st.minBranches, st.windowHours);
            if (!win) return;
            const next = earliestPerNode(win.legs);
            extend(stageIdx + 1, {
                frontier: next,
                used: new Set([...s.used, ...next.keys()]),
                edges: [...s.edges, ...win.legs.map((l) => l.edgeId)],
                layers: [...s.layers, [...next.keys()]],
            });
            return;
        }
        // fan-in (re-convergence): candidate collectors are the new nodes frontier members send to.
        const byCollector = new Map<string, Leg[]>();
        for (const [f, at] of s.frontier) {
            const cand = out.get(f) ?? [];
            if (!spend(cand.length)) return;
            for (const l of cand) {
                if (s.used.has(l.other) || !kindOk(l.other, st.nodeKind) || !followsInTime(at, l.t, st)) continue;
                if (!byCollector.has(l.other)) byCollector.set(l.other, []);
                // Re-framed so `other` is the SENDER — breadth is counted over distinct frontier members.
                byCollector.get(l.other)!.push({ edgeId: l.edgeId, other: f, t: l.t });
            }
        }
        for (const [collector, legs] of byCollector) {
            if (truncated || matches.length >= limit) return;
            if (!spend(legs.length)) return;
            const win = selectWindow(legs, st.minBranches, st.windowHours);
            if (!win) continue;
            extend(stageIdx + 1, {
                frontier: new Map([[collector, win.arrival]]),
                used: new Set([...s.used, collector]),
                edges: [...s.edges, ...win.legs.map((l) => l.edgeId)],
                layers: [...s.layers, [collector]],
            });
        }
    };

    // Stage 0 anchors the motif: a collector (fan-in) or a splitter (fan-out), one candidate per node.
    const first = stages[0];
    for (const n of g.nodes) {
        if (truncated || matches.length >= limit) break;
        const legs = (first.shape === 'fan-in' ? index[0].in : index[0].out).get(n.id) ?? [];
        if (!spend(legs.length + 1)) break;
        if (first.shape === 'fan-in' && !kindOk(n.id, first.nodeKind)) continue;
        const usable = first.shape === 'fan-out' ? legs.filter((l) => kindOk(l.other, first.nodeKind)) : legs;
        const win = selectWindow(usable, first.minBranches, first.windowHours);
        if (!win) continue;
        const others = [...new Set(win.legs.map((l) => l.other))];
        const edges = win.legs.map((l) => l.edgeId);
        if (first.shape === 'fan-in') {
            extend(1, {
                frontier: new Map([[n.id, win.arrival]]),
                used: new Set([...others, n.id]),
                edges,
                layers: [others, [n.id]],
            });
        } else {
            extend(1, {
                frontier: earliestPerNode(win.legs),
                used: new Set([n.id, ...others]),
                edges,
                layers: [[n.id], others],
            });
        }
    }
    if (matches.length >= limit) truncated = true;
    return { matches, truncated };
}

import type { GraphDirection } from '@inspecto/core/api';
import { G6GraphData } from '@inspecto/core/graph';
import { BranchStage, LegThreshold } from '@inspecto/link-analysis/graph/branching-pattern-engine';
import { PatternStep, baseEdgeKind } from '@inspecto/link-analysis/graph/graph-analysis';

/**
 * Pattern-by-example (`LA-PRODUCT-NEXT-1` phase 5a): the selected entities and the links among them, read as a motif
 * the pattern builder can load and the analyst can loosen.
 * - a **path** (a chain, every entity on it at most two links) becomes linear {@link PatternStep}s;
 * - a **star** around one entity (every other entity linked to it alone) becomes branching {@link BranchStage}s.
 * Anything else is refused in the analyst's words, never guessed.
 */
export type DerivedPattern =
    | { kind: 'path'; steps: PatternStep[]; summary: string }
    | { kind: 'fan'; stages: BranchStage[]; summary: string }
    | { kind: 'refused'; reason: string };

/** The kind a node carries when nothing typed it — a wildcard, not a constraint. */
const UNTYPED = 'entity';

interface Pair {
    a: string;
    b: string;
    /** Every selected link between the two, whichever way it runs. */
    edges: G6GraphData['edges'];
}

const key = (a: string, b: string) => (a < b ? `${a}\u0000${b}` : `${b}\u0000${a}`);

/**
 * Derive a motif from `nodeIds` over `g`. `timeAttr` (the timeline column) adds the order the example happened in;
 * a fan's legs get a value band only when they carry exactly one numeric column besides the time.
 */
export function patternFromSelection(g: G6GraphData, nodeIds: readonly string[], timeAttr = ''): DerivedPattern {
    const ids = [...new Set(nodeIds)];
    if (ids.length < 2) return { kind: 'refused', reason: 'Select at least two linked entities.' };
    const inSel = new Set(ids);
    const pairs = new Map<string, Pair>();
    for (const e of g.edges) {
        if (!inSel.has(e.source) || !inSel.has(e.target) || e.source === e.target) continue;
        const k = key(e.source, e.target);
        const p = pairs.get(k) ?? { a: e.source, b: e.target, edges: [] };
        p.edges.push(e);
        pairs.set(k, p);
    }
    const nbrs = new Map<string, string[]>(ids.map((id) => [id, []]));
    for (const p of pairs.values()) {
        nbrs.get(p.a)!.push(p.b);
        nbrs.get(p.b)!.push(p.a);
    }
    if (!connected(ids, nbrs))
        return {
            kind: 'refused',
            reason: 'The selected entities are not all linked to each other. Select one chain or one star.',
        };
    const kindOf = new Map(g.nodes.map((n) => [n.id, n.data.kind]));
    const nodeKind = (id: string): string | undefined => {
        const k = kindOf.get(id);
        return k && k !== UNTYPED ? k : undefined;
    };
    const pair = (a: string, b: string) => pairs.get(key(a, b))!;
    const timeOf = (p: Pair): number | null => {
        if (!timeAttr) return null;
        const ts = p.edges.map((e) => Date.parse(e.data.attrs?.[timeAttr] ?? '')).filter((t) => Number.isFinite(t));
        return ts.length ? Math.min(...ts) : null;
    };

    const ends = ids.filter((id) => nbrs.get(id)!.length === 1);
    const isPath = pairs.size === ids.length - 1 && ids.every((id) => nbrs.get(id)!.length <= 2) && ends.length === 2;
    if (isPath) {
        const walk = (from: string): string[] => {
            const order = [from];
            while (order.length < ids.length) {
                const cur = order[order.length - 1];
                order.push(nbrs.get(cur)!.find((n) => !order.includes(n))!);
            }
            return order;
        };
        // Read the chain the way most of its links run (ties: the first end in selection order).
        const fwd = walk(ends[0]);
        const outs = (order: string[]) =>
            order.slice(1).filter((id, i) => directionOf(pair(order[i], id), order[i]) === 'out').length;
        const rev = [...fwd].reverse();
        const order = outs(rev) > outs(fwd) ? rev : fwd;
        const steps: PatternStep[] = [{ ...(nodeKind(order[0]) ? { nodeKind: nodeKind(order[0]) } : {}) }];
        let prevTime: number | null = null;
        for (let i = 1; i < order.length; i++) {
            const p = pair(order[i - 1], order[i]);
            const t = timeOf(p);
            const edgeKind = sharedKind(p.edges);
            steps.push({
                direction: directionOf(p, order[i - 1]),
                ...(edgeKind ? { edgeKind } : {}),
                ...(nodeKind(order[i]) ? { nodeKind: nodeKind(order[i]) } : {}),
                // Only the ORDER the example happened in; its exact gaps would fit this example alone.
                ...(i > 1 && prevTime != null && t != null && t > prevTime ? { afterPrevious: true } : {}),
            });
            prevTime = t;
        }
        const hops = order.length - 1;
        return { kind: 'path', steps, summary: `A chain of ${hops} ${hops === 1 ? 'hop' : 'hops'}.` };
    }

    // A star: one anchor every other entity links to, and to nothing else in the selection.
    const anchor = [...ids].sort((x, y) => nbrs.get(y)!.length - nbrs.get(x)!.length)[0];
    const leaves = ids.filter((id) => id !== anchor);
    if (!leaves.every((id) => nbrs.get(id)!.length === 1 && nbrs.get(id)![0] === anchor))
        return {
            kind: 'refused',
            reason: 'Select a chain (each entity linked to the next) or a star (many entities linked to one).',
        };
    const legs = leaves.map((leaf) => ({ leaf, p: pair(anchor, leaf) }));
    const inbound = legs.filter((l) => directionOf(l.p, l.leaf) === 'out');
    const outbound = legs.filter((l) => directionOf(l.p, anchor) === 'out');
    if (inbound.length + outbound.length !== legs.length)
        return {
            kind: 'refused',
            reason: 'Some selected links run both ways, so the star has no single direction. Select one-way links.',
        };
    const stage = (shape: BranchStage['shape'], group: typeof legs): BranchStage => {
        const edges = group.flatMap((l) => l.p.edges);
        const edgeKind = sharedKind(edges);
        const reached = shape === 'fan-in' ? nodeKind(anchor) : sharedNodeKind(group.map((l) => nodeKind(l.leaf)));
        const threshold = valueBand(edges, timeAttr);
        return {
            shape,
            minBranches: group.length,
            ...(edgeKind ? { edgeKind } : {}),
            ...(reached ? { nodeKind: reached } : {}),
            ...(threshold ? { threshold } : {}),
        };
    };
    const stages: BranchStage[] = [];
    if (inbound.length) stages.push(stage('fan-in', inbound));
    if (outbound.length) {
        const out = stage('fan-out', outbound);
        // Collected, then moved on: only when every outgoing leg came after every incoming one in the example.
        const lastIn = Math.max(...inbound.map((l) => timeOf(l.p) ?? Infinity));
        const firstOut = Math.min(...outbound.map((l) => timeOf(l.p) ?? -Infinity));
        if (inbound.length && Number.isFinite(lastIn) && Number.isFinite(firstOut) && firstOut > lastIn)
            out.afterPrevious = true;
        stages.push(out);
    }
    const parts = [
        inbound.length ? `${inbound.length} send to one` : '',
        outbound.length ? `one sends to ${outbound.length}` : '',
    ].filter(Boolean);
    return { kind: 'fan', stages, summary: `A star: ${parts.join(', then ')}.` };
}

function connected(ids: string[], nbrs: Map<string, string[]>): boolean {
    const seen = new Set([ids[0]]);
    const queue = [ids[0]];
    while (queue.length)
        for (const n of nbrs.get(queue.shift()!)!) {
            if (seen.has(n)) continue;
            seen.add(n);
            queue.push(n);
        }
    return seen.size === ids.length;
}

/** The direction of the pair's links seen from `from`: all leaving it `out`, all arriving `in`, a mix `both`. */
function directionOf(p: Pair, from: string): GraphDirection {
    const out = p.edges.every((e) => e.source === from);
    const inn = p.edges.every((e) => e.target === from);
    return out ? 'out' : inn ? 'in' : 'both';
}

/** The one base kind every link shares; none (a wildcard) when they differ or carry none. */
function sharedKind(edges: G6GraphData['edges']): string | undefined {
    const kinds = new Set(edges.map((e) => baseEdgeKind(e.data.kind)));
    const [only] = kinds;
    return kinds.size === 1 && only ? only : undefined;
}

function sharedNodeKind(kinds: (string | undefined)[]): string | undefined {
    const set = new Set(kinds);
    const [only] = set;
    return set.size === 1 ? only : undefined;
}

/**
 * A band around the legs' values, `[min, max)`: present only when every leg carries exactly one numeric column
 * other than the time (the amount, typically). Two candidates would be a guess, so there is no band then.
 */
function valueBand(edges: G6GraphData['edges'], timeAttr: string): LegThreshold | undefined {
    const numericCols = (e: G6GraphData['edges'][number]) =>
        Object.entries(e.data.attrs ?? {})
            .filter(([k, v]) => k !== timeAttr && v !== '' && v != null && Number.isFinite(Number(v)))
            .map(([k]) => k);
    const common = edges.map(numericCols).reduce((acc, cols) => acc.filter((c) => cols.includes(c)));
    if (common.length !== 1) return undefined;
    const attr = common[0];
    const values = edges.map((e) => Number(e.data.attrs![attr]));
    return { attr, min: Math.floor(Math.min(...values)), max: Math.floor(Math.max(...values)) + 1 };
}

import { describe, expect, it } from 'vitest';
import type { GraphDirection } from 'app/inspecto/api';
import type { G6GraphData } from './graph-types';
import { compareCanonicalV1, compareUnits, edgeWeight, kCore, linkPrediction, weightedShortestPath } from './graph-analysis';

/**
 * LA-GRAPH-QUADRATIC-1 — the three formerly O(N^2) algorithms (`kCore`, `weightedShortestPath`, `linkPrediction`) now
 * run on a bucket queue / binary heap / 2-hop candidate walk. This spec holds the OLD quadratic code as a private
 * reference and asserts, on many seeded random graphs, that the new output is IDENTICAL (`toEqual`: same order, same
 * floating-point score bits). Java twin: `GraphComplexityEquivalenceTest`. The random graphs deliberately carry ties,
 * parallel edges, self-loops, isolated nodes, several components and equal weights.
 */

// ---- the reference: the pre-fix code, verbatim in behaviour ------------------------------------------------

type Pair = [string, string];
const adjacencyRef = (g: G6GraphData) => {
    const out = new Map<string, Pair[]>();
    const inn = new Map<string, Pair[]>();
    for (const n of g.nodes) {
        out.set(n.id, []);
        inn.set(n.id, []);
    }
    for (const e of g.edges) {
        out.get(e.source)?.push([e.target, e.id]);
        inn.get(e.target)?.push([e.source, e.id]);
    }
    return { out, in: inn };
};

const undirectedRef = (g: G6GraphData): Map<string, Set<string>> => {
    const nb = new Map<string, Set<string>>(g.nodes.map((n) => [n.id, new Set<string>()]));
    for (const e of g.edges) {
        if (e.source === e.target) continue;
        nb.get(e.source)?.add(e.target);
        nb.get(e.target)?.add(e.source);
    }
    return nb;
};

const scoredRef = (g: G6GraphData, score: Map<string, number>) =>
    g.nodes
        .map((n) => ({ id: n.id, label: n.data.label, score: score.get(n.id) ?? 0 }))
        .sort((a, b) => b.score - a.score || compareCanonicalV1(a, b));

function kCoreRef(g: G6GraphData) {
    const nb = undirectedRef(g);
    const deg = new Map<string, number>([...nb].map(([id, set]) => [id, set.size]));
    const core = new Map<string, number>();
    const remaining = new Set(deg.keys());
    let k = 0;
    while (remaining.size) {
        let min: string | null = null;
        let minDeg = Infinity;
        for (const id of remaining) {
            if (deg.get(id)! < minDeg) {
                minDeg = deg.get(id)!;
                min = id;
            }
        }
        k = Math.max(k, minDeg);
        core.set(min!, k);
        remaining.delete(min!);
        for (const other of nb.get(min!) ?? []) {
            if (remaining.has(other)) deg.set(other, deg.get(other)! - 1);
        }
    }
    return scoredRef(g, core);
}

function weightedShortestPathRef(g: G6GraphData, fromId: string, toId: string, direction: GraphDirection) {
    if (fromId === toId) return { nodeIds: [fromId], edgeIds: [] as string[] };
    const adj = adjacencyRef(g);
    if (!adj.out.has(fromId) || !adj.out.has(toId)) return null;
    const neighbours = (id: string): Pair[] =>
        direction === 'out' ? (adj.out.get(id) ?? []) : direction === 'in' ? (adj.in.get(id) ?? []) : [...(adj.out.get(id) ?? []), ...(adj.in.get(id) ?? [])];
    const cost = new Map(g.edges.map((e) => [e.id, 1 / edgeWeight(e)]));
    const dist = new Map<string, number>([[fromId, 0]]);
    const prev = new Map<string, { node: string; edge: string }>();
    const visited = new Set<string>();
    for (;;) {
        let cur: string | null = null;
        let best = Infinity;
        for (const [id, d] of dist) {
            if (!visited.has(id) && d < best) {
                best = d;
                cur = id;
            }
        }
        if (cur === null || cur === toId) break;
        visited.add(cur);
        for (const [next, edgeId] of neighbours(cur)) {
            if (visited.has(next)) continue;
            const nd = best + (cost.get(edgeId) ?? 1);
            if (nd < (dist.get(next) ?? Infinity)) {
                dist.set(next, nd);
                prev.set(next, { node: cur, edge: edgeId });
            }
        }
    }
    if (!prev.has(toId)) return null;
    const nodeIds = [toId];
    const edgeIds: string[] = [];
    let at = toId;
    while (at !== fromId) {
        const p = prev.get(at)!;
        edgeIds.unshift(p.edge);
        nodeIds.unshift(p.node);
        at = p.node;
    }
    return { nodeIds, edgeIds };
}

function linkPredictionRef(g: G6GraphData, method: 'common-neighbors' | 'adamic-adar', limit: number) {
    const nb = undirectedRef(g);
    const label = new Map(g.nodes.map((n) => [n.id, n.data.label]));
    const ids = [...nb.keys()].sort();
    const out: { source: string; target: string; sourceLabel: string; targetLabel: string; score: number }[] = [];
    for (let i = 0; i < ids.length; i++) {
        const a = ids[i];
        const an = nb.get(a)!;
        for (let j = i + 1; j < ids.length; j++) {
            const b = ids[j];
            if (an.has(b)) continue;
            const bn = nb.get(b)!;
            let score = 0;
            for (const c of an) {
                if (!bn.has(c)) continue;
                const deg = nb.get(c)!.size;
                score += method === 'adamic-adar' ? (deg > 1 ? 1 / Math.log(deg) : 0) : 1;
            }
            if (score > 0) {
                out.push({ source: a, target: b, sourceLabel: label.get(a) ?? a, targetLabel: label.get(b) ?? b, score });
            }
        }
    }
    return out.sort((x, y) => y.score - x.score || compareUnits(x.source, y.source)).slice(0, limit);
}

// ---- seeded random graphs ----------------------------------------------------------------------------------

/** mulberry32: a tiny seeded PRNG, so a failure names a reproducible seed. */
function rng(seed: number): () => number {
    let a = seed >>> 0;
    return () => {
        a = (a + 0x6d2b79f5) >>> 0;
        let t = a;
        t = Math.imul(t ^ (t >>> 15), t | 1);
        t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

const PREFIXES = ['n', 'N', 'acc-', 'ACC-', 'x_', 'é'];

function randomGraph(seed: number): G6GraphData {
    const r = rng(seed);
    const pick = (n: number) => Math.floor(r() * n);
    const n = 1 + pick(40);
    const ids = new Set<string>();
    while (ids.size < n) ids.add(PREFIXES[pick(PREFIXES.length)] + pick(n * 2));
    const nodeIds = [...ids];
    const labels = ['L', 'M', 'N']; // few labels => label ties
    const nodes = nodeIds.map((id) => ({ id, data: { label: r() < 0.5 ? labels[pick(3)] : id, kind: 'entity' } }));
    const m = pick(n * 3 + 1);
    const counts: (number | undefined)[] = [undefined, undefined, 1, 1, 2, 3, 0.5, 0, -2];
    const edges = Array.from({ length: m }, (_, i) => {
        const s = nodeIds[pick(n)];
        const t = r() < 0.08 ? s : nodeIds[pick(n)]; // self-loops; repeats of a pair give parallel edges
        const count = counts[pick(counts.length)];
        const kind = r() < 0.2 ? 'calls · 4' : 'calls';
        return { id: r() < 0.05 && i > 0 ? `e${i - 1}` : `e${i}`, source: s, target: t, data: { kind, count } };
    });
    return { nodes, edges } as unknown as G6GraphData;
}

const SEEDS = Array.from({ length: 400 }, (_, i) => 1000 + i);

describe('LA-GRAPH-QUADRATIC-1 — the fast algorithms equal the quadratic reference', () => {
    it('kCore', () => {
        for (const seed of SEEDS) {
            const g = randomGraph(seed);
            expect(kCore(g), `seed ${seed}`).toEqual(kCoreRef(g));
        }
    });

    it('weightedShortestPath, every direction, present and absent endpoints', () => {
        const dirs: GraphDirection[] = ['out', 'in', 'both'];
        for (const seed of SEEDS) {
            const g = randomGraph(seed);
            const r = rng(seed * 31 + 7);
            const ids = g.nodes.map((n) => n.id);
            for (let q = 0; q < 6; q++) {
                const from = ids[Math.floor(r() * ids.length)];
                const to = r() < 0.1 ? 'missing' : ids[Math.floor(r() * ids.length)];
                for (const d of dirs) {
                    expect(weightedShortestPath(g, from, to, d), `seed ${seed} ${from}->${to} ${d}`).toEqual(
                        weightedShortestPathRef(g, from, to, d),
                    );
                }
            }
        }
    });

    it('linkPrediction, both methods, several limits', () => {
        for (const seed of SEEDS) {
            const g = randomGraph(seed);
            for (const method of ['common-neighbors', 'adamic-adar'] as const) {
                for (const limit of [0, 3, 20, 100000]) {
                    expect(linkPrediction(g, { method, limit }), `seed ${seed} ${method} ${limit}`).toEqual(
                        linkPredictionRef(g, method, limit),
                    );
                }
            }
        }
    });

    it('the random corpus actually exercises ties, loops, parallel edges and isolated nodes', () => {
        let loops = 0, parallel = 0, isolated = 0, tiedScores = 0;
        for (const seed of SEEDS) {
            const g = randomGraph(seed);
            const keys = new Set<string>();
            const touched = new Set<string>();
            for (const e of g.edges) {
                if (e.source === e.target) loops++;
                const k = [e.source, e.target].sort().join('|');
                if (keys.has(k)) parallel++;
                keys.add(k);
                touched.add(e.source);
                touched.add(e.target);
            }
            isolated += g.nodes.filter((n) => !touched.has(n.id)).length;
            const s = kCore(g).map((x) => x.score);
            tiedScores += s.length - new Set(s).size;
        }
        expect(loops).toBeGreaterThan(50);
        expect(parallel).toBeGreaterThan(200);
        expect(isolated).toBeGreaterThan(200);
        expect(tiedScores).toBeGreaterThan(1000);
    });
});

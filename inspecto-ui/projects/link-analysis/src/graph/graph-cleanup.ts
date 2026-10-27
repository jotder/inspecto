/**
 * LA-STUDIO-UI-REORG-1 §5.5 / §5.6 — the canvas Collapse fold and the four Clean-up tools, as pure functions over a
 * {@link G6GraphData}. Links are read UNDIRECTED (a path between seeds may run against a link's direction). Every tool
 * answers the ids it would take away; the host decides how they leave (a presentation hide, or recorded steps).
 */
import type { G6GraphData } from '@inspecto/core/graph/graph-types';

type Adjacency = Map<string, Set<string>>;

function adjacency(g: G6GraphData): Adjacency {
    const adj: Adjacency = new Map(g.nodes.map((n) => [n.id, new Set<string>()]));
    for (const e of g.edges) {
        if (e.source === e.target || !adj.has(e.source) || !adj.has(e.target)) continue;
        adj.get(e.source)!.add(e.target);
        adj.get(e.target)!.add(e.source);
    }
    return adj;
}

/** The ids of `g` that are not in `keep`, in node order. */
function complement(g: G6GraphData, keep: Set<string>): string[] {
    return g.nodes.filter((n) => !keep.has(n.id)).map((n) => n.id);
}

// ── Collapse (§5.5): visual only ──

/** The neighbours of `root` whose only link on the canvas is to `root`: what Collapse folds into its `+n` badge. */
export function foldableOf(g: G6GraphData, root: string): string[] {
    const adj = adjacency(g);
    return [...(adj.get(root) ?? [])].filter((n) => adj.get(n)!.size === 1);
}

/**
 * The canvas with each root's pendant neighbours folded away and the root carrying `data.folded` = how many it holds.
 * A root folded by another root stays drawn (it is never taken away), and a root with nothing to fold is left as is.
 */
export function foldBranches(g: G6GraphData, roots: string[]): G6GraphData {
    if (!roots.length) return g;
    const keep = new Set(roots);
    const count = new Map<string, number>();
    const hidden = new Set<string>();
    for (const r of roots) {
        const folded = foldableOf(g, r).filter((n) => !keep.has(n));
        if (!folded.length) continue;
        count.set(r, folded.length);
        for (const n of folded) hidden.add(n);
    }
    if (!hidden.size) return g;
    return {
        nodes: g.nodes
            .filter((n) => !hidden.has(n.id))
            .map((n) => (count.has(n.id) ? { ...n, data: { ...n.data, folded: count.get(n.id) } } : n)),
        edges: g.edges.filter((e) => !hidden.has(e.source) && !hidden.has(e.target)),
    };
}

// ── Clean-up tools (§5.6) ──

/** A path search's answer: the ids to take away, or `null` with `truncated` when it ran out of budget. */
export interface PathKeepResult {
    hide: string[] | null;
    truncated: boolean;
}

/** Simple-path steps a {@link keepPathsBetweenSeeds} may take before it gives up (the browser guard). */
export const PATH_STEP_BUDGET = 200_000;

/**
 * Keep paths between seeds: keep the seeds and every node on a SIMPLE path of at most `maxLen` links between two of
 * them; answer the rest. Fewer than two seeds on the canvas keeps only the seeds. Enumerates simple paths by a bounded
 * depth-first walk, so a dead end that merely sits near two seeds is not kept. Over `budget` steps it answers `null`.
 */
export function keepPathsBetweenSeeds(
    g: G6GraphData,
    seeds: string[],
    maxLen = 4,
    budget = PATH_STEP_BUDGET,
): PathKeepResult {
    const adj = adjacency(g);
    const seedSet = new Set(seeds.filter((s) => adj.has(s)));
    const keep = new Set(seedSet);
    let steps = 0;
    const path: string[] = [];
    const onPath = new Set<string>();
    const walk = (at: string, from: string): boolean => {
        if (++steps > budget) return false;
        if (at !== from && seedSet.has(at)) {
            for (const id of path) keep.add(id);
            return true; // a path ends at the first seed it meets; longer ones through it are pairs of their own
        }
        if (path.length - 1 >= maxLen) return true; // `path` holds nodes: links = length - 1
        for (const next of adj.get(at)!) {
            if (onPath.has(next)) continue;
            path.push(next);
            onPath.add(next);
            const ok = walk(next, from);
            path.pop();
            onPath.delete(next);
            if (!ok) return false;
        }
        return true;
    };
    for (const s of seedSet) {
        path.splice(0, path.length, s);
        onPath.clear();
        onPath.add(s);
        if (!walk(s, s)) return { hide: null, truncated: true };
    }
    return { hide: complement(g, keep), truncated: false };
}

/** Breadth-first hop counts from `start` over `adj`. */
function hops(adj: Adjacency, start: string): Map<string, number> {
    const d = new Map([[start, 0]]);
    const queue = [start];
    for (let i = 0; i < queue.length; i++) {
        const at = queue[i];
        for (const n of adj.get(at) ?? [])
            if (!d.has(n)) {
                d.set(n, d.get(at)! + 1);
                queue.push(n);
            }
    }
    return d;
}

/**
 * Highlight shortest paths: the nodes and links on EVERY shortest path between each pair of seeds (a node `v` is on
 * one iff `d(s,v) + d(v,t) = d(s,t)`). Nothing is removed; the host glows these and dims the rest.
 */
export function shortestPathsBetweenSeeds(g: G6GraphData, seeds: string[]): { nodeIds: string[]; edgeIds: string[] } {
    const adj = adjacency(g);
    const present = [...new Set(seeds.filter((s) => adj.has(s)))];
    const dist = new Map(present.map((s) => [s, hops(adj, s)]));
    const nodes = new Set<string>();
    const edges = new Set<string>();
    for (let i = 0; i < present.length; i++)
        for (let j = i + 1; j < present.length; j++) {
            const ds = dist.get(present[i])!;
            const dt = dist.get(present[j])!;
            const total = ds.get(present[j]);
            if (total === undefined) continue; // not connected
            for (const n of g.nodes) {
                const a = ds.get(n.id);
                const b = dt.get(n.id);
                if (a !== undefined && b !== undefined && a + b === total) nodes.add(n.id);
            }
            for (const e of g.edges) {
                const [u, w] = [e.source, e.target];
                const on = (x: string, y: string) =>
                    ds.get(x) !== undefined && dt.get(y) !== undefined && ds.get(x)! + 1 + dt.get(y)! === total;
                if (u !== w && (on(u, w) || on(w, u))) edges.add(e.id);
            }
        }
    return { nodeIds: g.nodes.filter((n) => nodes.has(n.id)).map((n) => n.id), edgeIds: [...edges] };
}

/**
 * Trim dangling leaves: repeatedly take away nodes with at most one link until none is left, sparing `spare` (seeds,
 * pinned and held nodes). A chain hanging off the graph goes whole; a cycle never does.
 */
export function trimDanglingLeaves(g: G6GraphData, spare: Iterable<string>): string[] {
    const adj = adjacency(g);
    const keep = new Set(spare);
    const gone = new Set<string>();
    const queue = [...adj.keys()].filter((id) => !keep.has(id) && adj.get(id)!.size <= 1);
    while (queue.length) {
        const id = queue.pop()!;
        if (gone.has(id)) continue;
        gone.add(id);
        for (const n of adj.get(id)!) {
            adj.get(n)!.delete(id);
            if (!keep.has(n) && !gone.has(n) && adj.get(n)!.size <= 1) queue.push(n);
        }
    }
    return g.nodes.filter((n) => gone.has(n.id)).map((n) => n.id);
}

/** Keep only selected: every node of `g` outside `selected`. */
export function keepOnly(g: G6GraphData, selected: string[]): string[] {
    return complement(g, new Set(selected));
}

/** Remove selected: the selected ids that are on the canvas. */
export function removeSelected(g: G6GraphData, selected: string[]): string[] {
    const sel = new Set(selected);
    return g.nodes.filter((n) => sel.has(n.id)).map((n) => n.id);
}

/** The effect line every tool states before it applies: "hides 612 of 921 entities". */
export function cleanupEffect(hidden: number, total: number): string {
    return `hides ${hidden.toLocaleString('en')} of ${total.toLocaleString('en')} ${total === 1 ? 'entity' : 'entities'}`;
}

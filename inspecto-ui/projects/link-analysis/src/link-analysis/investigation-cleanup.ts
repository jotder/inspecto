import type { WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import type { G6GraphData, G6Node } from '@inspecto/core/graph/graph-types';
import { keepOnly, keepPathsBetweenSeeds } from '@inspecto/link-analysis/graph/graph-cleanup';

/**
 * LA-INVESTIGATE-UX-1 — the Investigation tab's Clean up, as pure sets over the Working Set. Every action answers the RAW
 * ids it would take away, so the tab can state its count on the button and record it as ONE step (`hide`, or `exclude`
 * with its reason). Start entities (hop 0) and kept entities are never taken away; hidden ones are already gone.
 *
 * The Working Set carries link COUNTS, not link times, so "outside a time window" is not a clean-up here (a `window`
 * step changes what later expands read).
 */
export interface CleanupSets {
    /**
     * Entities with at most one link — ONE pass, unlike the broom's repeated trim: an Investigation grown from one start
     * entity is mostly a tree, which the repeated trim would take away whole. Press again to go one ring further.
     */
    leaves: string[];
    /** Entity types on the Working Set with the ids of each that may be taken away, largest first. */
    types: { type: string; ids: string[] }[];
}

/** The visible Working Set as a graph the shared clean-up functions read (ids are the raw values). */
export function workingSetGraph(ws: WorkingSet): G6GraphData {
    const shown = new Set(ws.entities.filter((e) => !e.hidden).map((e) => e.id));
    return {
        nodes: [...shown].map((id) => ({ id, data: { label: id, kind: 'entity' as G6Node['data']['kind'] } })),
        edges: ws.links
            .filter((l) => shown.has(l.source) && shown.has(l.target))
            .map((l, i) => ({ id: `l${i}`, source: l.source, target: l.target, data: { kind: l.kind ?? '' } })),
    };
}

/** Start entities and kept entities: no clean-up takes them away. */
export function sparedIds(ws: WorkingSet): Set<string> {
    return new Set(ws.entities.filter((e) => e.hop === 0 || e.kept).map((e) => e.id));
}

function spare(ids: string[], spared: Set<string>): string[] {
    return ids.filter((id) => !spared.has(id));
}

export function cleanupSets(ws: WorkingSet): CleanupSets {
    const spared = sparedIds(ws);
    const byType = new Map<string, string[]>();
    for (const e of ws.entities) {
        if (e.hidden || spared.has(e.id)) continue;
        const t = e.type ?? 'Untyped';
        byType.set(t, [...(byType.get(t) ?? []), e.id]);
    }
    return {
        leaves: oneLinkOrLess(ws, spared),
        types: [...byType]
            .map(([type, ids]) => ({ type, ids }))
            .sort((a, b) => b.ids.length - a.ids.length || a.type.localeCompare(b.type)),
    };
}

/** Entities whose links add up to fewer than `minEvents` events (on the visible Working Set). */
export function weaklyLinked(ws: WorkingSet, minEvents: number): string[] {
    const shown = new Set(ws.entities.filter((e) => !e.hidden).map((e) => e.id));
    const events = new Map<string, number>([...shown].map((id) => [id, 0]));
    for (const l of ws.links) {
        if (!shown.has(l.source) || !shown.has(l.target)) continue;
        events.set(l.source, events.get(l.source)! + l.count);
        if (l.target !== l.source) events.set(l.target, events.get(l.target)! + l.count);
    }
    return spare(
        [...events].filter(([, n]) => n < minEvents).map(([id]) => id),
        sparedIds(ws),
    );
}

function oneLinkOrLess(ws: WorkingSet, spared: Set<string>): string[] {
    const g = workingSetGraph(ws);
    const nb = new Map(g.nodes.map((n) => [n.id, new Set<string>()]));
    for (const e of g.edges) {
        if (e.source === e.target) continue;
        nb.get(e.source)!.add(e.target);
        nb.get(e.target)!.add(e.source);
    }
    return g.nodes.filter((n) => !spared.has(n.id) && nb.get(n.id)!.size <= 1).map((n) => n.id);
}

/** Shrink: the visible neighbours of `ids` whose only links are to `ids` (what the expand of those brought in). */
export function leafNeighbours(ws: WorkingSet, ids: string[]): string[] {
    const from = new Set(ids);
    const adj = new Map<string, Set<string>>();
    for (const l of workingSetGraph(ws).edges) {
        if (l.source === l.target) continue;
        adj.set(l.source, (adj.get(l.source) ?? new Set()).add(l.target));
        adj.set(l.target, (adj.get(l.target) ?? new Set()).add(l.source));
    }
    const out = [...adj].filter(([id, nb]) => !from.has(id) && [...nb].every((n) => from.has(n))).map(([id]) => id);
    return spare(out, sparedIds(ws));
}

/** The last `n` distinct exclusion reasons of this Investigation, newest first: one-press choices in Exclude…. */
export function recentExcludeReasons(ws: WorkingSet | null, n = 3): string[] {
    const out: string[] = [];
    for (const x of [...(ws?.excluded ?? [])].sort((a, b) => b.step - a.step)) {
        const r = x.reason?.trim();
        if (r && !out.includes(r)) out.push(r);
        if (out.length === n) break;
    }
    return out;
}

/** Keep only the selection: every other visible entity. */
export function keepOnlySelected(ws: WorkingSet, selected: string[]): string[] {
    return spare(keepOnly(workingSetGraph(ws), selected), sparedIds(ws));
}

/**
 * Keep the paths between two picked entities: every visible entity off a simple path of at most `maxLen` links between
 * them. Null when the walk ran out of its step budget (the graph is too dense to answer in the browser).
 */
export function offPathsBetween(ws: WorkingSet, a: string, b: string, maxLen: number): string[] | null {
    const r = keepPathsBetweenSeeds(workingSetGraph(ws), [a, b], maxLen);
    return r.hide && spare(r.hide, sparedIds(ws));
}

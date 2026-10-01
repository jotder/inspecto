import { G6GraphData, NodeScore, PredictedLink, SuspicionScore, endpointId, EntityIdMapping } from 'app/inspecto/graph';
import {
    GraphBudgetView,
    GraphListCut,
    GraphPredictedLinkView,
    GraphRunResult,
    GraphRunView,
    GraphScoreView,
    GraphSelectionView,
    GraphSuspicionView,
    WorkingSet,
} from 'app/inspecto/api';
import { rawIdsOf } from './investigation-state';

/**
 * LA separation D-4 step 7 - putting a SERVER Graph Run's answer onto the canvas.
 *
 * A server result names nodes by the (possibly masked) entity ids the Working Set carries and edges by the D-U9 wire
 * id (`linkId`); the canvas names nodes by `endpointId` keys and edges `sid->tid:kind`. {@link buildServerIdMap}
 * translates, and the `to*` functions below turn each result shape into exactly the value the browser's own
 * algorithm returns - so the toolbox applies a server answer through the same code as a local one. Results arrive
 * ALREADY MASKED to match what the canvas displays; nothing here unmasks.
 */
export interface ServerIdMap {
    /** The canvas node for a server node id, or undefined when the canvas does not draw it. */
    node(serverId: string): string | undefined;
    /** The canvas edge for a server `linkId`, or undefined when the canvas does not draw it. */
    edge(linkId: string): string | undefined;
    /** The id a server request names this canvas node by (its first raw spelling). */
    serverNode(canvasId: string): string | undefined;
}

/**
 * Build the translation from the Working Set the canvas was drawn from. A node answers to its canvas id and every
 * raw spelling it folded; an edge is the `sid->tid:kind` id {@link projectTriples} mints for the link, found by
 * running the SAME `endpointId` mint over the link's endpoints - only kept when the canvas really has that edge.
 */
export function buildServerIdMap(
    ws: WorkingSet,
    projection: EntityIdMapping | undefined,
    canvas: G6GraphData,
): ServerIdMap {
    const nodes = new Map<string, string>();
    const serverNodes = new Map<string, string>();
    for (const n of canvas.nodes) {
        nodes.set(n.id, n.id);
        const raws = rawIdsOf(n);
        for (const raw of raws) nodes.set(raw, n.id);
        if (raws.length) serverNodes.set(n.id, raws[0]);
    }
    const canvasEdges = new Set(canvas.edges.map((e) => e.id));
    const edges = new Map<string, string>();
    for (const l of ws.links) {
        if (!l.linkId) continue;
        const sid = endpointId(projection, 'source', String(l.source ?? '').trim());
        const tid = endpointId(projection, 'target', String(l.target ?? '').trim());
        if (!sid || !tid) continue;
        const id = `${sid}->${tid}:${l.kind ?? 'link'}`;
        if (canvasEdges.has(id)) edges.set(l.linkId, id);
    }
    return {
        node: (id) => nodes.get(id),
        edge: (id) => edges.get(id),
        serverNode: (id) => serverNodes.get(id),
    };
}

/** A selection on canvas ids; ids the canvas does not draw are dropped (it is a view of the same Working Set, possibly filtered). */
export function toSelection(sel: GraphSelectionView, map: ServerIdMap): { nodeIds: string[]; edgeIds: string[] } {
    return {
        nodeIds: sel.nodeIds.map((id) => map.node(id)).filter((id): id is string => !!id),
        edgeIds: sel.edgeIds.map((id) => map.edge(id)).filter((id): id is string => !!id),
    };
}

/** Ranked scores, keeping the server's order, on canvas ids. */
export function toNodeScores(scores: GraphScoreView[], map: ServerIdMap): NodeScore[] {
    const out: NodeScore[] = [];
    for (const s of scores) {
        const id = map.node(s.id);
        if (id) out.push({ id, label: s.label, score: s.score });
    }
    return out;
}

export function toSuspicionScores(scores: GraphSuspicionView[], map: ServerIdMap): SuspicionScore[] {
    const out: SuspicionScore[] = [];
    for (const s of scores) {
        const id = map.node(s.id);
        if (id) out.push({ id, label: s.label, score: s.score, factors: s.factors });
    }
    return out;
}

/** node -> community, in the server's pair order. A community id naming a member node (label propagation) is mapped too. */
export function toCommunityMap(pairs: { id: string; community: string }[], map: ServerIdMap): Map<string, string> {
    const out = new Map<string, string>();
    for (const p of pairs) {
        const id = map.node(p.id);
        if (id) out.set(id, map.node(p.community) ?? p.community);
    }
    return out;
}

export function toGroups(groups: string[][], map: ServerIdMap): string[][] {
    return groups
        .map((g) => g.map((id) => map.node(id)).filter((id): id is string => !!id))
        .filter((g) => g.length > 0);
}

export function toPredictedLinks(links: GraphPredictedLinkView[], map: ServerIdMap): PredictedLink[] {
    const out: PredictedLink[] = [];
    for (const l of links) {
        const source = map.node(l.source);
        const target = map.node(l.target);
        if (source && target) out.push({ ...l, source, target });
    }
    return out;
}

/**
 * What an over-budget run offers as its ONE next step. The server's ceilings decide whether raising the budget is
 * allowed at all: raising is offered only when the number the run needed fits under the ceiling; otherwise the only
 * honest advice is to shrink the Working Set or pick a cheaper algorithm.
 */
export type BudgetNextAction =
    | { kind: 'raise'; budget: Partial<GraphBudgetView>; label: string }
    | { kind: 'narrow'; text: string };

export function budgetNextAction(run: GraphRunView, ceilings: GraphBudgetView | null): BudgetNextAction {
    const narrow: BudgetNextAction = {
        kind: 'narrow',
        text: 'Filter the Working Set to fewer entities or links, or pick a cheaper algorithm.',
    };
    if (!ceilings) return narrow;
    switch (run.exceeded) {
        case 'NODES':
            return run.consumed.nodes <= ceilings.maxNodes
                ? {
                      kind: 'raise',
                      budget: { maxNodes: run.consumed.nodes },
                      label: `Run again with a budget of ${run.consumed.nodes} nodes`,
                  }
                : narrow;
        case 'EDGES':
            return run.consumed.edges <= ceilings.maxEdges
                ? {
                      kind: 'raise',
                      budget: { maxEdges: run.consumed.edges },
                      label: `Run again with a budget of ${run.consumed.edges} links`,
                  }
                : narrow;
        case 'TIMEOUT':
            return run.budget.timeoutMs < ceilings.timeoutMs
                ? {
                      kind: 'raise',
                      budget: { timeoutMs: ceilings.timeoutMs },
                      label: `Run again with a ${ceilings.timeoutMs} ms deadline`,
                  }
                : narrow;
        default:
            return narrow;
    }
}

/**
 * What a COMPLETED server result names that the canvas does not draw (LA-GRAPH-RUN-HIDDEN-NODES-1). The `to*`
 * functions above drop such an id - never invent one - so without this a result over a Working Set the canvas draws
 * only part of would look complete. Counts are of DISTINCT ids, nodes and edges apart.
 */
export interface DroppedCounts {
    nodes: { dropped: number; total: number };
    edges: { dropped: number; total: number };
}

export function countDropped(r: GraphRunResult, map: ServerIdMap): DroppedCounts {
    const nodes = new Set<string>();
    const edges = new Set<string>();
    const sel = (s: GraphSelectionView | null | undefined): void => {
        s?.nodeIds.forEach((id) => nodes.add(id));
        s?.edgeIds.forEach((id) => edges.add(id));
    };
    (r.scores ?? []).forEach((s) => nodes.add(s.id));
    (r.hubs ?? []).forEach((s) => nodes.add(s.id));
    (r.authorities ?? []).forEach((s) => nodes.add(s.id));
    sel(r.selection);
    (r.selections ?? []).forEach(sel);
    sel(r.minCut);
    (r.groups ?? []).forEach((g) => g.forEach((id) => nodes.add(id)));
    (r.communities ?? []).forEach((p) => nodes.add(p.id)); // the pair's `community` may be a label, not a node
    (r.ids ?? []).forEach((id) => (r.algorithm === 'bridges' ? edges : nodes).add(id));
    (r.links ?? []).forEach((l) => {
        nodes.add(l.source);
        nodes.add(l.target);
    });
    (r.nodes ?? []).forEach((n) => nodes.add(n.id));
    (r.edges ?? []).forEach((e) => edges.add(e.id));
    const missing = (ids: Set<string>, has: (id: string) => string | undefined) =>
        [...ids].filter((id) => !has(id)).length;
    return {
        nodes: { dropped: missing(nodes, map.node), total: nodes.size },
        edges: { dropped: missing(edges, map.edge), total: edges.size },
    };
}

/** The sentence the toolbox shows when part of a server result has no place on the canvas; '' when all of it is drawn. */
export function droppedNotice(c: DroppedCounts): string {
    const parts: string[] = [];
    if (c.nodes.dropped) parts.push(`${c.nodes.dropped} of ${c.nodes.total} result nodes`);
    if (c.edges.dropped) parts.push(`${c.edges.dropped} of ${c.edges.total} result links`);
    return parts.length
        ? `${parts.join(' and ')} are not drawn on the canvas right now, so the result shown here leaves them out.`
        : '';
}

/** What a list key of `GraphResultJson.lists` holds, in the analyst's words. */
const LIST_NOUN: Record<string, string> = {
    scores: 'scores',
    hubs: 'hubs',
    authorities: 'authorities',
    groups: 'groups',
    ids: 'results',
    communities: 'community assignments',
    links: 'predicted links',
    selections: 'selections',
    nodes: 'nodes',
    edges: 'links',
};

function cutPhrase(key: string, c: GraphListCut): string {
    const n = (v: number) => v.toLocaleString('en-US');
    // `groups[0]`, `selections[2].nodeIds`, `selection.edgeIds`, `minCut.nodeIds`: a list nested inside another.
    const nested = /^(\w+?)(?:\[(\d+)\])?(?:\.(nodeIds|edgeIds))?$/.exec(key);
    const base = nested?.[1] ?? key;
    const index = nested?.[2] === undefined ? null : Number(nested[2]) + 1;
    const part = nested?.[3] === 'nodeIds' ? 'nodes' : nested?.[3] === 'edgeIds' ? 'links' : null;
    // Fewer than the limit came back: the rest were left out because they depend on something cut (a link whose node was cut).
    const shown =
        c.returned < c.limit ? `${n(c.returned)} of ${n(c.total)}` : `the first ${n(c.returned)} of ${n(c.total)}`;
    if (part) {
        const owner =
            index === null
                ? `the ${base === 'minCut' ? 'minimum cut' : base}`
                : `${base === 'groups' ? 'group' : 'selection'} ${index}`;
        return `${shown} ${part} of ${owner}`;
    }
    if (index !== null) return `${shown} members of ${base === 'groups' ? 'group' : 'item'} ${index}`;
    const tail = key === 'edges' && c.returned < c.limit ? ' (links whose nodes were cut are left out)' : '';
    return `${shown} ${LIST_NOUN[key] ?? key}${tail}`;
}

/**
 * Never a silent cap (the SPA half): the sentence for a COMPLETED server result the server cut at `graph_run.max_result_items`,
 * built from `lists` (what was kept, of how many, against which limit); '' when nothing was cut.
 */
export function truncationNotice(r: GraphRunResult): string {
    if (!r.truncated) return '';
    const cuts = Object.entries(r.lists ?? {}).filter(([, c]) => c.truncated);
    if (!cuts.length)
        return 'The server cut this result at its cap; raise graph_run.max_result_items in Settings to see more.';
    const limit = Math.max(...cuts.map(([, c]) => c.limit));
    const parts = cuts.map(([k, c]) => cutPhrase(k, c)).join('; ');
    return `Showing ${parts} (the server cap is ${limit.toLocaleString('en-US')}; raise graph_run.max_result_items in Settings).`;
}

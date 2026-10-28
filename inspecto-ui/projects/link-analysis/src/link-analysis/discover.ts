import type { G6GraphData, G6Node } from '@inspecto/core/graph';
import {
    SUPER_NODE_KIND,
    degreeCentrality,
    louvainCommunities,
    shortestPath,
} from '@inspecto/link-analysis/graph/graph-analysis';
import type { PackTerms } from './grow-exclude';
import { rawIdsOf } from './investigation-state';
import { someValues } from './start-from';

/**
 * LA-ONBOARD-1 phase 7, UC-12 **Find something interesting** - the pure half: three questions a beginner can ask of the
 * graph on screen, answered with the existing browser algorithms (degree centrality, Louvain communities, shortest path),
 * each result row read out in one sentence in the pack's words. Never a number without a sentence. No Angular, no HTTP.
 */

/** One answer row: what it is, its one-line reading, and what *Show on graph*, *Start from these* and *Add to a list* use. */
export interface FoundRow {
    key: string;
    title: string;
    reading: string;
    nodeIds: string[];
    edgeIds: string[];
    /** The entities' raw values (a masked node gives its alias, written as is - the same values Exclude → Add to a list sends). */
    values: string[];
    /** UC-10's help, on the busiest row only: "X has N links. Add it to an exclusion list?". */
    hint?: string;
    /** What a start from this row starts from, in words: "MERCHANT-BIG, the busiest account". */
    what: string;
}

export interface FoundResult {
    /** What was asked, in a sentence (also what *Start from these* says it started from). */
    heading: string;
    rows: FoundRow[];
    /** Why there is nothing to show; '' when there are rows. */
    empty: string;
}

const fmt = (n: number): string => n.toLocaleString('en');
const count = (n: number, one: string, many: string): string => `${fmt(n)} ${n === 1 ? one : many}`;

/** Real entities only: an aggregated super node is not something to start from or put on a list. */
const entities = (g: G6GraphData): G6Node[] => g.nodes.filter((n) => n.data.kind !== SUPER_NODE_KIND);

function valuesOf(nodes: readonly G6Node[]): string[] {
    return [...new Set(nodes.map((n) => rawIdsOf(n)[0]).filter((v): v is string => !!v))];
}

/** *Who is busiest*: the entities with the most links on this graph (degree centrality). */
export function busiest(g: G6GraphData, t: PackTerms, limit = 5): FoundResult {
    const byId = new Map(entities(g).map((n) => [n.id, n]));
    const ranked = degreeCentrality(g)
        .filter((s) => byId.has(s.id) && s.score > 0)
        .slice(0, limit);
    if (!ranked.length)
        return { heading: `The busiest ${t.nodes}`, rows: [], empty: `No ${t.node} on this graph has a ${t.link} yet.` };
    const rows = ranked.map((s, i): FoundRow => {
        const node = byId.get(s.id)!;
        const edgeIds = g.edges.filter((e) => e.source === s.id || e.target === s.id).map((e) => e.id);
        return {
            key: s.id,
            title: s.label,
            reading:
                `${s.label} takes part in ${count(s.score, t.link, t.links)}` +
                (i === 0 ? ', the most on this graph.' : '.'),
            nodeIds: [s.id],
            edgeIds,
            values: valuesOf([node]),
            what: i === 0 ? `${s.label}, the busiest ${t.node}` : `${s.label}, one of the busiest ${t.nodes}`,
        };
    });
    // The hint (UC-10): a top entity far busier than the next is usually a hub to leave out, not a lead.
    const [top, next] = ranked;
    if (top.score >= 5 && (!next || top.score >= 2 * next.score))
        rows[0].hint = `${top.label} has ${count(top.score, t.link, t.links)}. Add it to an exclusion list?`;
    return {
        heading: `The ${count(rows.length, `busiest ${t.node}`, `busiest ${t.nodes}`)} on this graph`,
        rows,
        empty: '',
    };
}

/** The days between the first and the last time a group's links carry, or null when they carry no readable time. */
function daysSpanned(edges: G6GraphData['edges'], timeAttr: string): number | null {
    if (!timeAttr) return null;
    const ms = edges
        .map((e) => Date.parse(String(e.data.attrs?.[timeAttr] ?? '')))
        .filter((v) => Number.isFinite(v));
    if (ms.length < 2) return null;
    return Math.max(1, Math.ceil((Math.max(...ms) - Math.min(...ms)) / 86_400_000));
}

/** *Which groups stick together*: communities of at least three entities, largest first (Louvain). */
export function groups(g: G6GraphData, t: PackTerms, timeAttr = '', limit = 5): FoundResult {
    const heading = `Groups of ${t.nodes} that link mostly among themselves`;
    let byNode: Map<string, string>;
    try {
        byNode = louvainCommunities(g);
    } catch (err) {
        return { heading, rows: [], empty: err instanceof Error ? err.message : 'The groups could not be found.' };
    }
    const real = new Set(entities(g).map((n) => n.id));
    const members = new Map<string, string[]>();
    for (const [id, c] of byNode) {
        if (!real.has(id)) continue;
        members.set(c, [...(members.get(c) ?? []), id]);
    }
    const found = [...members.values()]
        .filter((m) => m.length >= 3)
        .map((m) => {
            const inside = new Set(m);
            const edges = g.edges.filter((e) => inside.has(e.source) && inside.has(e.target));
            return { m, edges };
        })
        .sort((a, b) => b.m.length - a.m.length || b.edges.length - a.edges.length || (a.m[0] < b.m[0] ? -1 : 1))
        .slice(0, limit);
    if (!found.length)
        return { heading, rows: [], empty: `No group of three or more ${t.nodes} sticks together on this graph.` };
    const nodeOf = new Map(g.nodes.map((n) => [n.id, n]));
    return {
        heading,
        rows: found.map(({ m, edges }, i): FoundRow => {
            const nodes = m.map((id) => nodeOf.get(id)!).filter(Boolean);
            const days = daysSpanned(edges, timeAttr);
            return {
                key: `group-${i + 1}`,
                title: `Group ${i + 1}`,
                reading:
                    `Group ${i + 1}: ${count(m.length, t.node, t.nodes)}, ${count(edges.length, t.link, t.links)} among them` +
                    (days !== null ? ` within ${count(days, 'day', 'days')}` : '') +
                    ` (${someValues(nodes.map((n) => n.data.label), 3)}).`,
                nodeIds: m,
                edgeIds: edges.map((e) => e.id),
                values: valuesOf(nodes),
                what: `group ${i + 1} of ${t.nodes} that stick together`,
            };
        }),
        empty: '',
    };
}

/**
 * *How does A reach B*: the shortest chain of links from A to B following their direction, else in either direction
 * (said so). A and B are node ids on the graph.
 */
export function route(g: G6GraphData, from: string, to: string, t: PackTerms): FoundResult {
    const nodeOf = new Map(g.nodes.map((n) => [n.id, n]));
    const label = (id: string) => nodeOf.get(id)?.data.label ?? id;
    const heading = `How ${label(from)} reaches ${label(to)}`;
    if (!nodeOf.has(from) || !nodeOf.has(to) || from === to)
        return { heading, rows: [], empty: `Choose two different ${t.nodes} on the graph.` };
    const directed = shortestPath(g, from, to, 'out');
    const path = directed ?? shortestPath(g, from, to, 'both');
    if (!path)
        return {
            heading,
            rows: [],
            empty: `${label(from)} and ${label(to)} are not linked on this graph. Expand… (G) from one of them may connect them.`,
        };
    const hops = path.edgeIds.length;
    const chain = path.nodeIds.map(label).join(directed ? ' → ' : ' – ');
    const reading = directed
        ? `${label(from)} reaches ${label(to)} in ${count(hops, t.link, t.links)}: ${chain}.`
        : `${label(from)} and ${label(to)} are linked through ${count(hops, t.link, t.links)}, not all in one direction: ${chain}.`;
    return {
        heading,
        rows: [
            {
                key: 'route',
                title: `${label(from)} to ${label(to)}`,
                reading,
                nodeIds: path.nodeIds,
                edgeIds: path.edgeIds,
                values: valuesOf(path.nodeIds.map((id) => nodeOf.get(id)!)),
                what: `the ${t.nodes} on the way from ${label(from)} to ${label(to)}`,
            },
        ],
        empty: '',
    };
}

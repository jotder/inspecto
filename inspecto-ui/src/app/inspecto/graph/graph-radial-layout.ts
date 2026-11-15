// LA-RADIAL-LAYOUT-SQUASH-1: the Radial layout, one radial per connected component, packed side by side.
// G6's built-in `radial` (stress majorization round ONE focus node) drew the Audit "Reached by many" answer as a wedge:
// its focus is the FIRST node in the data, not the hub; nodes on one ring are held apart by its fixed `linkDistance`
// (50) however large the ring, so 24 neighbours on a 480 px ring took a 34° fan; its overlap pass assumed 10 px nodes;
// and a node of another component was put on one more ring with made-up distances, flattening that component.
// This layout is pure and runs in O(nodes + links): each component is a breadth-first tree round its best-connected
// node, every node owning a share of the circle in proportion to the leaves below it, and every ring wide enough that
// no two nodes on it come closer than `nodeSize + nodeSpacing`. Links that close a cycle are drawn but do not place a node.
import { BaseLayout, ExtensionCategory, register } from '@antv/g6';
import type { GraphData } from '@antv/g6';

export interface RadialComponentsOptions {
    /** The distance from one ring to the next, at the least. */
    unitRadius: number;
    /** The drawn node size (diameter). */
    nodeSize: number;
    /** The free space kept between two nodes. */
    nodeSpacing: number;
}

export interface RadialLink {
    source: string | number;
    target: string | number;
}

type Point = { x: number; y: number };

/** One component laid out round its own centre (0, 0). */
interface PlacedComponent {
    members: number[];
    pos: Map<number, Point>;
    /** The radius of the circle that holds every node of the component, the nodes' own size included. */
    radius: number;
}

/**
 * The position of every node: one radial per connected component, the largest first, packed in rows round (0, 0).
 * Unknown link ends, self-links and repeated links are ignored. Deterministic: the same input gives the same picture.
 */
export function radialComponentsLayout(
    nodeIds: readonly string[],
    links: readonly RadialLink[],
    opts: RadialComponentsOptions,
): Map<string, Point> {
    const index = new Map(nodeIds.map((id, i) => [id, i]));
    const adj: number[][] = nodeIds.map(() => []);
    const seen = new Set<string>();
    for (const l of links) {
        const a = index.get(String(l.source));
        const b = index.get(String(l.target));
        if (a === undefined || b === undefined || a === b) continue;
        const key = a < b ? `${a} ${b}` : `${b} ${a}`;
        if (seen.has(key)) continue;
        seen.add(key);
        adj[a].push(b);
        adj[b].push(a);
    }
    const minSep = opts.nodeSize + opts.nodeSpacing;
    const placed = components(adj).map((members) =>
        placeComponent(members, adj, opts.unitRadius, minSep, opts.nodeSize),
    );
    // The largest component first (the one an analyst came for), then the order of the data.
    placed.sort((p, q) => q.members.length - p.members.length || p.members[0] - q.members[0]);
    const out = new Map<string, Point>();
    const gap = Math.max(2 * minSep, opts.unitRadius / 2);
    for (const { component, cx, cy } of packRows(placed, gap)) {
        for (const [i, p] of component.pos) out.set(nodeIds[i], { x: cx + p.x, y: cy + p.y });
    }
    return out;
}

/** The connected components, each listed in breadth-first order from its first node in the data. */
function components(adj: readonly number[][]): number[][] {
    const done = new Uint8Array(adj.length);
    const out: number[][] = [];
    for (let s = 0; s < adj.length; s++) {
        if (done[s]) continue;
        done[s] = 1;
        const members = [s];
        for (let h = 0; h < members.length; h++) {
            for (const v of adj[members[h]]) {
                if (!done[v]) {
                    done[v] = 1;
                    members.push(v);
                }
            }
        }
        out.push(members);
    }
    return out;
}

function placeComponent(
    members: readonly number[],
    adj: readonly number[][],
    unitRadius: number,
    minSep: number,
    nodeSize: number,
): PlacedComponent {
    // The centre is the best-connected node (the data's order breaks a tie).
    let focus = members[0];
    for (const m of members)
        if (adj[m].length > adj[focus].length || (adj[m].length === adj[focus].length && m < focus)) focus = m;

    // A breadth-first tree from the focus: depth = ring.
    const depth = new Map<number, number>([[focus, 0]]);
    const children = new Map<number, number[]>();
    const order = [focus];
    for (let h = 0; h < order.length; h++) {
        const u = order[h];
        const kids: number[] = [];
        for (const v of adj[u]) {
            if (!depth.has(v)) {
                depth.set(v, depth.get(u)! + 1);
                kids.push(v);
                order.push(v);
            }
        }
        children.set(u, kids);
    }

    // Every node owns a share of the circle in proportion to the leaves below it, so a hub's neighbours go all round.
    const leaves = new Map<number, number>();
    for (let h = order.length - 1; h >= 0; h--) {
        const kids = children.get(order[h])!;
        leaves.set(order[h], kids.length ? kids.reduce((s, c) => s + leaves.get(c)!, 0) : 1);
    }
    const angle = new Map<number, number>([[focus, 0]]);
    const wedge = new Map<number, [number, number]>([[focus, [-Math.PI / 2, (3 * Math.PI) / 2]]]);
    for (const u of order) {
        const [from, to] = wedge.get(u)!;
        const kids = children.get(u)!;
        const total = leaves.get(u)!;
        let start = from;
        for (const c of kids) {
            const span = ((to - from) * leaves.get(c)!) / total;
            wedge.set(c, [start, start + span]);
            angle.set(c, start + span / 2);
            start += span;
        }
    }

    // Ring radii: at least `unitRadius` apart and `minSep` apart, and wide enough that neighbours on a ring do not touch.
    const rings: number[][] = [];
    for (const u of order) (rings[depth.get(u)!] ??= []).push(angle.get(u)!);
    const radii = [0];
    for (let d = 1; d < rings.length; d++) {
        const a = [...rings[d]].sort((p, q) => p - q);
        let minGap = Infinity;
        for (let i = 0; i < a.length; i++) {
            const next = i + 1 < a.length ? a[i + 1] : a[0] + 2 * Math.PI;
            minGap = Math.min(minGap, next - a[i]);
        }
        const fit = a.length > 1 ? minSep / (2 * Math.sin(Math.min(minGap, Math.PI) / 2)) : 0;
        radii.push(Math.max(d * unitRadius, radii[d - 1] + minSep, fit));
    }

    const pos = new Map<number, Point>();
    for (const u of order) {
        const r = radii[depth.get(u)!];
        const t = angle.get(u)!;
        pos.set(u, { x: r * Math.cos(t), y: r * Math.sin(t) });
    }
    return { members: [...members], pos, radius: radii[radii.length - 1] + nodeSize / 2 };
}

/** Rows of components, each in a square of its own diameter, `gap` apart; the whole centred on (0, 0). */
function packRows(
    placed: readonly PlacedComponent[],
    gap: number,
): { component: PlacedComponent; cx: number; cy: number }[] {
    if (!placed.length) return [];
    const area = placed.reduce((s, p) => s + (2 * p.radius + gap) ** 2, 0);
    const rowWidth = Math.max(2 * placed[0].radius, Math.sqrt(area));
    const cells: { component: PlacedComponent; cx: number; cy: number }[] = [];
    let x = 0;
    let y = 0;
    let rowHeight = 0;
    let width = 0;
    for (const p of placed) {
        const d = 2 * p.radius;
        if (x > 0 && x + d > rowWidth) {
            y += rowHeight + gap;
            x = 0;
            rowHeight = 0;
        }
        cells.push({ component: p, cx: x + p.radius, cy: y + p.radius });
        width = Math.max(width, x + d);
        x += d + gap;
        rowHeight = Math.max(rowHeight, d);
    }
    const height = y + rowHeight;
    return cells.map((c) => ({ ...c, cx: c.cx - width / 2, cy: c.cy - height / 2 }));
}

/** The G6 layout over {@link radialComponentsLayout}; its options are those of {@link RadialComponentsOptions}. */
export class ComponentRadialLayout extends BaseLayout {
    id = 'component-radial';

    async execute(model: GraphData): Promise<GraphData> {
        const o = this.options as Partial<RadialComponentsOptions> & { center?: [number, number] };
        const ids = (model.nodes ?? []).map((n) => String(n.id));
        const pos = radialComponentsLayout(ids, model.edges ?? [], {
            unitRadius: o.unitRadius ?? 120,
            nodeSize: typeof o.nodeSize === 'number' ? o.nodeSize : 32,
            nodeSpacing: o.nodeSpacing ?? 0,
        });
        const [cx, cy] = o.center ?? [0, 0];
        return {
            nodes: ids.map((id) => ({ id, style: { x: cx + pos.get(id)!.x, y: cy + pos.get(id)!.y, z: 0 } })),
            edges: [],
        } as GraphData;
    }
}

let registered = false;

/** Register `component-radial` with G6 once. */
export function registerRadialLayout(): void {
    if (registered) return;
    registered = true;
    register(ExtensionCategory.LAYOUT, 'component-radial', ComponentRadialLayout as never);
}

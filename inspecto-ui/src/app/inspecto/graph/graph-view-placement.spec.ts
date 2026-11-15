import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { G6GraphData } from './catalog-graph';
import {
    GRAPH_DEFAULT_LAYOUT_SPACING,
    GRAPH_DEFAULT_NODE_SIZE,
    GraphViewComponent,
    placeBesideNeighbours,
    placementGap,
} from './graph-view.component';

/**
 * LA-FULL-CYCLE-TEST-1 phase 6, finding 17: a drawn graph stays readable through an Expand and a Save. The 17-node
 * fixture is the tested case: a start entity with a ring of 14 (as Radial draws it), then an Expand that adds two.
 * G6 cannot run in jsdom, so the live graph is a stub that remembers where each node was drawn.
 */
type Pt = [number, number];
const NODE = GRAPH_DEFAULT_NODE_SIZE; // 20 px: the icon tile is 1.44 × 1.06 of it
const BOX: Pt = [Math.round(NODE * 1.44), Math.round(NODE * 1.06)];
const GAP = placementGap(NODE, GRAPH_DEFAULT_LAYOUT_SPACING);

/** The start entity `s` at the centre, `r0`…`r13` on a ring round it, each linked to `s`: 15 nodes. */
function starPositions(): Record<string, Pt> {
    const at: Record<string, Pt> = { s: [0, 0] };
    for (let i = 0; i < 14; i++) {
        const a = (i / 14) * 2 * Math.PI;
        at[`r${i}`] = [200 * Math.cos(a), 200 * Math.sin(a)];
    }
    return at;
}
const STAR_EDGES: [string, string][] = Array.from({ length: 14 }, (_, i) => ['s', `r${i}`]);

function graph(ids: string[], edges: [string, string][]): G6GraphData {
    return {
        nodes: ids.map((id) => ({ id, data: { label: id, kind: 'entity' } })) as G6GraphData['nodes'],
        edges: edges.map(([source, target]) => ({
            id: `${source}-${target}`,
            source,
            target,
            data: { kind: 'calls' },
        })) as unknown as G6GraphData['edges'],
    };
}

/** Every pair of node boxes (the icon tile, centred on the node) that overlap; [] = none. */
function overlaps(at: Record<string, Pt>): string[] {
    const ids = Object.keys(at);
    const out: string[] = [];
    for (let i = 0; i < ids.length; i++)
        for (let j = i + 1; j < ids.length; j++) {
            const [a, b] = [at[ids[i]], at[ids[j]]];
            if (Math.abs(a[0] - b[0]) < BOX[0] && Math.abs(a[1] - b[1]) < BOX[1]) out.push(`${ids[i]}/${ids[j]}`);
        }
    return out;
}

function segmentDistance(p: Pt, a: Pt, b: Pt): number {
    const [dx, dy] = [b[0] - a[0], b[1] - a[1]];
    const t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)));
    return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
}

type Internals = {
    ready: boolean;
    graph: unknown;
    data: G6GraphData | null;
    display: GraphViewComponent['display'];
    positionHints: GraphViewComponent['positionHints'];
    snapshotKeys(): void;
    ngOnChanges(changes: Record<string, unknown>): void;
};

/** The view over a live stub graph that keeps each node where `setData` put it (a G6 draw that runs no layout). */
function harness(data: G6GraphData, positions: Record<string, Pt>) {
    TestBed.configureTestingModule({
        imports: [GraphViewComponent],
        providers: [{ provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
    });
    const comp = TestBed.createComponent(GraphViewComponent).componentInstance as unknown as Internals;
    vi.spyOn(comp as unknown as { create(): void }, 'create').mockImplementation(() => undefined);
    const drawn: Record<string, Pt> = { ...positions };
    const stub = {
        setData: vi.fn((d: { nodes: { id: string; style?: { x?: number; y?: number } }[] }) => {
            for (const k of Object.keys(drawn)) delete drawn[k];
            for (const n of d.nodes)
                if (typeof n.style?.x === 'number' && typeof n.style?.y === 'number')
                    drawn[n.id] = [n.style.x, n.style.y];
        }),
        getPlugins: () => [],
        draw: vi.fn(() => Promise.resolve()),
        render: vi.fn(() => Promise.resolve()),
        destroy: vi.fn(),
        getElementPosition: vi.fn((id: string) => {
            const p = drawn[id];
            if (!p) throw new Error('not rendered');
            return p;
        }),
    };
    comp.ready = true;
    comp.graph = stub;
    comp.display = { nodeSize: NODE, layoutSpacing: GRAPH_DEFAULT_LAYOUT_SPACING } as GraphViewComponent['display'];
    comp.data = data;
    comp.snapshotKeys();
    const view = comp as unknown as GraphViewComponent;
    return { comp, stub, drawn, view };
}

describe('graph placement after an Expand and a Save (LA-FULL-CYCLE-TEST-1 phase 6)', () => {
    const starIds = ['s', ...Array.from({ length: 14 }, (_, i) => `r${i}`)];

    it('the 17-node fixture: an Expand from a ring entity places its 2 new nodes outside the star, overlapping nothing', () => {
        const { comp, stub, drawn } = harness(graph(starIds, STAR_EDGES), starPositions());
        comp.data = graph([...starIds, 'x1', 'x2'], [...STAR_EDGES, ['r0', 'x1'], ['r0', 'x2']]);
        comp.ngOnChanges({ data: {} });
        expect(stub.render).not.toHaveBeenCalled(); // no layout: every drawn node kept its place
        expect(Object.keys(drawn)).toHaveLength(17);
        expect(overlaps(drawn)).toEqual([]);
        for (const id of starIds) expect(drawn[id]).toEqual(starPositions()[id]);
        // outside the star: further from its centre than the ring, and on none of its links
        for (const id of ['x1', 'x2']) {
            expect(Math.hypot(...drawn[id])).toBeGreaterThan(200);
            for (const [a, b] of STAR_EDGES)
                expect(segmentDistance(drawn[id], drawn[a], drawn[b])).toBeGreaterThanOrEqual(GAP * 0.4);
        }
    });

    it('an Expand from the start entity itself puts the new nodes beyond its ring, not between its links', () => {
        const { comp, drawn } = harness(graph(starIds, STAR_EDGES), starPositions());
        comp.data = graph([...starIds, 'y1', 'y2'], [...STAR_EDGES, ['s', 'y1'], ['s', 'y2']]);
        comp.ngOnChanges({ data: {} });
        expect(overlaps(drawn)).toEqual([]);
        for (const id of ['y1', 'y2']) {
            expect(Math.hypot(...drawn[id])).toBeGreaterThanOrEqual(200 + GAP - 1e-6);
            for (const [a, b] of STAR_EDGES)
                expect(segmentDistance(drawn[id], drawn[a], drawn[b])).toBeGreaterThanOrEqual(GAP * 0.4);
        }
    });

    it('after Save the saved Investigation (its own ids) is drawn where the analyst left it: no layout, no overlap', () => {
        const before = { ...starPositions() };
        const { comp, stub, drawn } = harness(graph(starIds, STAR_EDGES), before);
        comp.data = graph([...starIds, 'x1', 'x2'], [...STAR_EDGES, ['r0', 'x1'], ['r0', 'x2']]);
        comp.ngOnChanges({ data: {} });
        const expanded = { ...drawn };
        // Save replays the steps under the Investigation's own ids: the start first, then the Expand
        const inv = (id: string) => `inv:${id}`;
        const all = [...starIds, 'x1', 'x2'];
        comp.positionHints = Object.fromEntries(all.map((id) => [inv(id), expanded[id]]));
        comp.data = graph([inv('s')], []);
        comp.ngOnChanges({ data: {} });
        expect(drawn[inv('s')]).toEqual(expanded['s']);
        comp.data = graph(
            all.map(inv),
            [...STAR_EDGES, ['r0', 'x1'], ['r0', 'x2']].map(([a, b]) => [inv(a), inv(b)] as [string, string]),
        );
        comp.ngOnChanges({ data: {} });
        expect(stub.render).not.toHaveBeenCalled(); // not one layout run through the whole Save
        expect(Object.keys(drawn)).toHaveLength(17);
        for (const id of all) expect(drawn[inv(id)]).toEqual(expanded[id]);
        expect(overlaps(drawn)).toEqual([]);
    });

    it('nodePositions reports where each drawn node is, leaving out one not drawn yet', () => {
        const { view } = harness(graph(['a', 'b'], []), { a: [1, 2] });
        expect(view.nodePositions()).toEqual({ a: [1, 2] });
    });

    it('siblings fan out: ten new nodes on one ring entity keep the gap from each other and from the star', () => {
        const nodes: Record<string, unknown>[] = Object.entries(starPositions()).map(([id, [x, y]]) => ({
            id,
            style: { x, y },
        }));
        const fresh = Array.from({ length: 10 }, (_, i) => `n${i}`);
        for (const id of fresh) nodes.push({ id });
        const edges = [...STAR_EDGES, ...fresh.map((id) => ['r3', id])].map(([source, target]) => ({ source, target }));
        expect(placeBesideNeighbours(nodes, edges, new Set(fresh), GAP)).toBe(true);
        const at = Object.fromEntries(
            nodes.map((n) => {
                const s = n['style'] as { x: number; y: number };
                return [n['id'] as string, [s.x, s.y] as Pt];
            }),
        );
        const ids = Object.keys(at);
        for (let i = 0; i < ids.length; i++)
            for (let j = i + 1; j < ids.length; j++)
                expect(Math.hypot(at[ids[i]][0] - at[ids[j]][0], at[ids[i]][1] - at[ids[j]][1])).toBeGreaterThanOrEqual(
                    GAP - 1e-6,
                );
    });

    it('on an evenly spaced Grid (no spot clears the link rules) it stays fast and puts the node beside the graph', () => {
        // a 13 x 13 lattice at 100 px with the anchor in the middle: every own link from it passes a lattice node
        const nodes: Record<string, unknown>[] = [];
        for (let i = 0; i < 13; i++)
            for (let j = 0; j < 13; j++) nodes.push({ id: `g${i}-${j}`, style: { x: i * 100, y: j * 100 } });
        const fresh = ['n0', 'n1', 'n2'];
        for (const id of fresh) nodes.push({ id });
        const edges = fresh.map((id) => ({ source: 'g6-6', target: id }));
        const t0 = performance.now();
        expect(placeBesideNeighbours(nodes, edges, new Set(fresh), GAP)).toBe(true);
        expect(performance.now() - t0).toBeLessThan(1000);
        const placed = nodes.slice(-3).map((n) => n['style'] as { x: number; y: number });
        for (const p of placed) {
            // beside the 1,200 px drawing, never thrown far off it
            expect(Math.hypot(p.x - 600, p.y - 600)).toBeLessThan(600 * Math.SQRT2 + 3 * GAP);
            for (const q of nodes.slice(0, -3).map((n) => n['style'] as { x: number; y: number }))
                expect(Math.hypot(p.x - q.x, p.y - q.y)).toBeGreaterThanOrEqual(GAP - 1e-6);
        }
    });
});

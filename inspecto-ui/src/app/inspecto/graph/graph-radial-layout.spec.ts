// LA-RADIAL-LAYOUT-SQUASH-1 (Basic UI test D-5): the Radial layout drew the Audit "Reached by many" answer (27 nodes, two
// components) as a ~40° wedge of overlapping neighbours round V-001 and flattened the other component. These specs
// measure the angular spread and the closest pair, on the pure layout and through the REAL G6 5.1.1 layout controller.
import '@antv/g6';
import { getExtension } from '@antv/g6';
import { LayoutController } from '@antv/g6/esm/runtime/layout';
import { describe, expect, it } from 'vitest';
import { ComponentRadialLayout, radialComponentsLayout, registerRadialLayout } from './graph-radial-layout';
import { layoutConfig } from './graph-view.component';

type Pos = Map<string, { x: number; y: number }>;
type Link = { source: string; target: string };

/** The D-5 graph: V-001 with 24 neighbours, and a second component of two. `hubAt` = where V-001 sits in the data. */
function reachedByMany(hubAt = 0): { ids: string[]; links: Link[] } {
    const leaves = Array.from({ length: 24 }, (_, i) => `A-${String(i + 1).padStart(3, '0')}`);
    const ids = [...leaves];
    ids.splice(hubAt, 0, 'V-001');
    ids.push('V-117', 'A-900');
    const links = [...leaves.map((l) => ({ source: l, target: 'V-001' })), { source: 'A-900', target: 'V-117' }];
    return { ids, links };
}

/** How much of the circle round `centre` the `around` nodes cover: 360° less the widest empty gap. */
function spreadDeg(pos: Pos, centre: string, around: readonly string[]): number {
    const c = pos.get(centre)!;
    const a = around.map((id) => Math.atan2(pos.get(id)!.y - c.y, pos.get(id)!.x - c.x)).sort((p, q) => p - q);
    let widest = 0;
    for (let i = 0; i < a.length; i++)
        widest = Math.max(widest, (i + 1 < a.length ? a[i + 1] : a[0] + 2 * Math.PI) - a[i]);
    return 360 - (widest * 180) / Math.PI;
}

/** The distance between the two closest nodes. */
function closestPair(pos: Pos): number {
    const p = [...pos.values()];
    let min = Infinity;
    for (let i = 0; i < p.length; i++)
        for (let j = i + 1; j < p.length; j++) min = Math.min(min, Math.hypot(p[i].x - p[j].x, p[i].y - p[j].y));
    return min;
}

const dist = (pos: Pos, a: string, b: string): number =>
    Math.hypot(pos.get(a)!.x - pos.get(b)!.x, pos.get(a)!.y - pos.get(b)!.y);

/** Link Analysis' own start: Small nodes (20 px), Wide spacing (4). */
const LA = layoutConfig('radial', { nodeSize: 20, spacing: 4 }) as {
    unitRadius: number;
    nodeSize: number;
    nodeSpacing: number;
};
const MIN_SEP = LA.nodeSize + LA.nodeSpacing;

describe('radialComponentsLayout (LA-RADIAL-LAYOUT-SQUASH-1)', () => {
    for (const hubAt of [0, 12, 24]) {
        it(`spreads a hub's 24 neighbours round the full circle, one ring, no overlap (V-001 at ${hubAt} in the data)`, () => {
            const { ids, links } = reachedByMany(hubAt);
            const pos = radialComponentsLayout(ids, links, LA);
            const leaves = ids.filter((id) => id.startsWith('A-0'));
            // 24 evenly round the hub leave a widest gap of 15°: 345° covered (G6's radial covered 34°).
            expect(spreadDeg(pos, 'V-001', leaves)).toBeCloseTo(345, 6);
            const r = leaves.map((l) => dist(pos, 'V-001', l));
            expect(Math.max(...r) - Math.min(...r)).toBeLessThan(1e-6);
            expect(closestPair(pos)).toBeGreaterThanOrEqual(MIN_SEP - 1e-6);
        });
    }

    it('widens a crowded ring rather than overlap its nodes (Compact spacing: 24 on a 120 px ring would touch)', () => {
        const compact = layoutConfig('radial', { nodeSize: 20 }) as typeof LA;
        const { ids, links } = reachedByMany();
        const pos = radialComponentsLayout(ids, links, compact);
        expect(closestPair(pos)).toBeGreaterThanOrEqual(compact.nodeSize + compact.nodeSpacing - 1e-6);
        expect(dist(pos, 'V-001', 'A-001')).toBeGreaterThan(compact.unitRadius);
    });

    it('lays the second component out on its own, beside the first and not inside its ring', () => {
        const { ids, links } = reachedByMany();
        const pos = radialComponentsLayout(ids, links, LA);
        const ring = dist(pos, 'V-001', 'A-001');
        // its own pair keeps one ring apart, as a link in the first component does
        expect(dist(pos, 'V-117', 'A-900')).toBeCloseTo(LA.unitRadius, 6);
        // and both its nodes sit clear of the first component's disc (ring + one node)
        for (const id of ['V-117', 'A-900']) expect(dist(pos, 'V-001', id)).toBeGreaterThan(ring + LA.nodeSize);
    });

    it('keeps every pair apart on a deeper graph with cycles, and the rings grow outwards', () => {
        // a hub with 6 branches, each a chain of 3 with 4 leaves at its end, and a cross-link between two branches
        const ids = ['h'];
        const links: Link[] = [];
        for (let b = 0; b < 6; b++) {
            ids.push(`b${b}-1`, `b${b}-2`, `b${b}-3`);
            links.push({ source: 'h', target: `b${b}-1` }, { source: `b${b}-1`, target: `b${b}-2` });
            links.push({ source: `b${b}-2`, target: `b${b}-3` });
            for (let l = 0; l < 4; l++) {
                ids.push(`b${b}-L${l}`);
                links.push({ source: `b${b}-3`, target: `b${b}-L${l}` });
            }
        }
        links.push({ source: 'b0-2', target: 'b1-2' });
        for (const opts of [LA, layoutConfig('radial') as typeof LA]) {
            const pos = radialComponentsLayout(ids, links, opts);
            expect(closestPair(pos)).toBeGreaterThanOrEqual(opts.nodeSize + opts.nodeSpacing - 1e-6);
            const ring = (id: string): number => dist(pos, 'h', id);
            expect(ring('b0-1')).toBeLessThan(ring('b0-2'));
            expect(ring('b0-2')).toBeLessThan(ring('b0-3'));
            expect(ring('b0-3')).toBeLessThan(ring('b0-L0'));
            expect(spreadDeg(pos, 'h', ['b0-L0', 'b1-L0', 'b2-L0', 'b3-L0', 'b4-L0', 'b5-L0'])).toBeGreaterThan(290);
        }
    });

    it('packs many components and lone nodes with no overlap, the same picture every time', () => {
        const ids: string[] = [];
        const links: Link[] = [];
        for (let c = 0; c < 5; c++) {
            ids.push(`c${c}`);
            for (let l = 0; l <= c * 3; l++) {
                ids.push(`c${c}-${l}`);
                links.push({ source: `c${c}`, target: `c${c}-${l}` });
            }
        }
        for (let s = 0; s < 12; s++) ids.push(`lone${s}`);
        // a link to an unknown node, a self-link and a repeated link are ignored
        links.push(
            { source: 'c1', target: 'nobody' },
            { source: 'c2', target: 'c2' },
            { source: 'c0', target: 'c0-0' },
        );
        const pos = radialComponentsLayout(ids, links, LA);
        expect(pos.size).toBe(ids.length);
        expect(closestPair(pos)).toBeGreaterThanOrEqual(MIN_SEP - 1e-6);
        expect(radialComponentsLayout(ids, links, LA)).toEqual(pos);
    });

    it('handles an empty graph and a single node', () => {
        expect(radialComponentsLayout([], [], LA).size).toBe(0);
        expect(radialComponentsLayout(['a'], [], LA).get('a')).toEqual({ x: 0, y: 0 });
    });
});

describe('component-radial through the real G6 layout controller', () => {
    /** G6's LayoutController over a minimal fake context: the same option merge and execute call a live graph makes. */
    async function simulate(
        ids: readonly string[],
        links: readonly Link[],
        layout: Record<string, unknown>,
    ): Promise<Pos> {
        registerRadialLayout();
        const data = {
            nodes: ids.map((id) => ({ id, style: {} })),
            edges: links.map((l, i) => ({ id: `e${i}`, ...l })),
            combos: [],
        };
        const controller = new LayoutController({
            options: { layout },
            model: { getData: () => data, getAncestorsData: () => [] },
            element: { getElement: () => ({}), getElementComputedStyle: () => ({ size: 20 }) },
            viewport: { getCanvasSize: () => [1000, 700] },
        } as never);
        const result = await controller.simulate();
        const pos: Pos = new Map();
        for (const n of result.nodes ?? []) {
            const s = n.style as { x: number; y: number };
            pos.set(String(n.id), { x: s.x, y: s.y });
        }
        return pos;
    }

    it('registers the layout G6 resolves for Radial', () => {
        registerRadialLayout();
        expect(getExtension('layout', String(LA['type' as never]))).toBe(ComponentRadialLayout);
    });

    it('draws the D-5 graph centred on the canvas, the hub neighbours all round it, none overlapping', async () => {
        const { ids, links } = reachedByMany(5);
        const pos = await simulate(ids, links, layoutConfig('radial', { nodeSize: 20, spacing: 4 }));
        expect(pos.size).toBe(27);
        const leaves = ids.filter((id) => id.startsWith('A-0'));
        expect(spreadDeg(pos, 'V-001', leaves)).toBeGreaterThan(300);
        expect(closestPair(pos)).toBeGreaterThanOrEqual(MIN_SEP - 1e-6);
        expect(dist(pos, 'V-117', 'A-900')).toBeGreaterThanOrEqual(MIN_SEP);
        // the whole picture is centred on the canvas centre G6 hands every layout
        const xs = [...pos.values()].map((p) => p.x);
        expect((Math.min(...xs) + Math.max(...xs)) / 2).toBeCloseTo(500, 0);
    });
});

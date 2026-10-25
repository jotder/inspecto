// N2 round 3: a data change that removes a hull member while hulls are on. The graph slice throws on an id no longer in the data,
// as G6 5.1.1 does, and render() emits AFTER_RENDER; the REAL Hull / BubbleSets / PluginController are driven.
import '@antv/g6';
import { GraphEvent } from '@antv/g6';
import { PluginController } from '@antv/g6/esm/runtime/plugin';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { GraphViewComponent } from './graph-view.component';

const POS: Record<string, [number, number]> = { a: [0, 0], b: [100, 0], c: [0, 100], d: [200, 200], e: [300, 0] };
const gd = (ids: string[]) => ({ nodes: ids.map((id) => ({ id, data: { kind: 'x' } })), edges: [] }) as never;

function rig(initial: string[]) {
    const present = new Set(initial);
    const renders: Promise<unknown>[] = [];
    const handlers = new Map<string, Set<(e: unknown) => void>>();
    const emit = (ev: string) => [...(handlers.get(ev) ?? [])].forEach((fn) => fn({}));
    const need = (id: string) => {
        if (!present.has(id)) throw new Error('Node not found for id: ' + id);
    };
    const graph = {
        on: (ev: string, fn: (e: unknown) => void) => {
            if (!handlers.has(ev)) handlers.set(ev, new Set());
            handlers.get(ev)!.add(fn);
        },
        off: (ev: string, fn: (e: unknown) => void) => handlers.get(ev)?.delete(fn),
        getNodeData: (id: string) => (need(id), { id, style: { x: POS[id][0], y: POS[id][1] } }),
        getElementRenderBounds: (id: string) => (
            need(id),
            { min: [POS[id][0] - 16, POS[id][1] - 16], max: [POS[id][0] + 16, POS[id][1] + 16], halfExtents: [16, 16] }
        ),
        getElementType: (id: string) => {
            if (!present.has(id)) throw new Error('Unknown element type of id: ' + id);
            return 'node';
        },
    };
    const options: { plugins: Record<string, unknown>[] } = { plugins: [] };
    const controller = new PluginController({ graph, canvas: { appendChild: vi.fn() }, options } as never);
    const live = {
        getPlugins: () => options.plugins,
        setPlugins: (u: (p: Record<string, unknown>[]) => Record<string, unknown>[]) => {
            options.plugins = u(options.plugins).map(({ labelText: _l, ...r }) => r);
            controller.setPlugins(options.plugins as never);
        },
        getPluginInstance: (k: string) => controller.getPluginInstance(k),
        getOptions: () => ({ plugins: options.plugins }),
        setData: (d: { nodes: { id: string }[] }) => {
            present.clear();
            d.nodes.forEach((n) => present.add(String(n.id)));
        },
        render: () => {
            const p = (async () => emit(GraphEvent.AFTER_RENDER))();
            renders.push(p);
            return p;
        },
        draw: async () => undefined,
        getElementPosition: (id: string) => (need(id), POS[id]),
        setElementState: async () => undefined,
        fitView: async () => undefined,
        destroy: () => controller.destroy(),
    };
    return { live, controller, emit, renders };
}

function component(initial: string[]) {
    TestBed.configureTestingModule({
        imports: [GraphViewComponent],
        providers: [{ provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
    });
    const comp = TestBed.createComponent(GraphViewComponent).componentInstance as unknown as {
        ready: boolean;
        graph: unknown;
        data: unknown;
        plugins: GraphViewComponent['plugins'];
        snapshotKeys(): void;
        ngOnChanges(c: Record<string, unknown>): void;
    };
    vi.spyOn(comp as unknown as { create(): void }, 'create').mockImplementation(() => undefined);
    const r = rig(initial);
    comp.ready = true;
    comp.graph = r.live;
    comp.data = gd(initial);
    return { comp, ...r };
}
const flush = async () => {
    for (let i = 0; i < 6; i++) await Promise.resolve();
};
const d = (c: PluginController, k: string) =>
    (c.getPluginInstance(k) as unknown as { shape?: { style: { d: unknown } } })?.shape?.style.d;

describe('N2: hulls on + data change that removes a member and adds a node (super-node aggregation shape)', () => {
    it('hull: the shape follows the new members once the data is painted', async () => {
        const { comp, controller, renders } = component(['a', 'b', 'c']);
        comp.plugins = { hulls: null };
        comp.snapshotKeys();
        comp.plugins = { hulls: new Map([['0', ['a', 'b', 'c']]]) };
        comp.ngOnChanges({ plugins: {} });
        const before = d(controller, 'hull-0');
        expect(before).toBeDefined();

        comp.data = gd(['a', 'b', 'd']); // c aggregated away, d added -> render()
        comp.plugins = { hulls: new Map([['0', ['a', 'b', 'd']]]) };
        comp.ngOnChanges({ data: {}, plugins: {} });
        await flush();
        await new Promise((r) => setTimeout(r, 0));
        const settled = await Promise.allSettled(renders);
        expect(settled.map((x) => (x.status === 'rejected' ? String(x.reason) : 'ok'))).toEqual(['ok']);
        expect(d(controller, 'hull-0')).not.toEqual(before);
    });

    it('bubble sets: the shape follows the new members once the data is painted (removal only, draw path)', async () => {
        const { comp, controller } = component(['a', 'b', 'c']);
        comp.plugins = { hulls: null, bubbleSets: true };
        comp.snapshotKeys();
        comp.plugins = { hulls: new Map([['0', ['a', 'b', 'c']]]), bubbleSets: true };
        comp.ngOnChanges({ plugins: {} });
        const before = d(controller, 'bubble-sets-0');
        expect(before).toBeDefined();
        comp.data = gd(['a', 'b', 'e']);
        comp.plugins = { hulls: new Map([['0', ['a', 'b', 'e']]]), bubbleSets: true };
        comp.ngOnChanges({ data: {}, plugins: {} });
        await flush();
        expect(d(controller, 'bubble-sets-0')).not.toEqual(before);
    });
});

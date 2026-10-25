// N2 round 2: the community shapes driven through the REAL G6 5.1.1 PluginController and the REAL Hull / BubbleSets
// classes (a minimal fake graph context only), because a stub `setPlugins` cannot show what G6 does with a plugin.
import '@antv/g6';
import { GraphEvent } from '@antv/g6';
import { Hull } from '@antv/g6/esm/plugins/hull';
import { BubbleSets } from '@antv/g6/esm/plugins/bubble-sets';
import { PluginController } from '@antv/g6/esm/runtime/plugin';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { GraphViewComponent, buildPluginList, swapCommunityShapes } from './graph-view.component';

const POS: Record<string, [number, number]> = { a: [0, 0], b: [100, 0], c: [0, 100], d: [200, 200] };

function realG6() {
    const handlers = new Map<string, Set<(e: unknown) => void>>();
    const render = vi.fn();
    const graph = {
        on: (ev: string, fn: (e: unknown) => void) => {
            if (!handlers.has(ev)) handlers.set(ev, new Set());
            handlers.get(ev)!.add(fn);
        },
        off: (ev: string, fn: (e: unknown) => void) => handlers.get(ev)?.delete(fn),
        getNodeData: (id: string) => ({ id, style: { x: POS[id][0], y: POS[id][1] } }),
        getElementRenderBounds: (id: string) => ({
            min: [POS[id][0] - 16, POS[id][1] - 16],
            max: [POS[id][0] + 16, POS[id][1] + 16],
            halfExtents: [16, 16],
        }),
        getElementType: () => 'node',
        render,
    };
    const canvas = { appendChild: vi.fn() };
    const options: { plugins: Record<string, unknown>[] } = { plugins: [] };
    const controller = new PluginController({ graph, canvas, options } as never);
    /** The slice of `Graph` the helper uses, wired exactly as G6's `Graph.setPlugins` wires it. */
    const live = {
        getPlugins: () => options.plugins,
        setPlugins: (u: (p: Record<string, unknown>[]) => Record<string, unknown>[]) => {
            // jsdom has no 2D canvas, so G cannot measure a label: the label is dropped, the geometry is what is driven.
            options.plugins = u(options.plugins).map(({ labelText: _label, ...rest }) => rest);
            controller.setPlugins(options.plugins as never);
        },
        getPluginInstance: (key: string) => controller.getPluginInstance(key),
        getOptions: () => ({ plugins: options.plugins }),
    };
    const count = (ev: string) => handlers.get(ev)?.size ?? 0;
    return { live, controller, canvas, render, count, graph };
}

const shapes = (m: [string, string[]][], bubbleSets = false) =>
    buildPluginList({ hulls: new Map(m), bubbleSets }, ['red', 'blue']);

const shapeOf = (c: PluginController, key: string) =>
    (c.getPluginInstance(key) as unknown as { shape?: { style: { d: unknown } } }).shape;

describe('N2: community shapes on the real G6 plugin classes', () => {
    it('(7) pin: the real classes still expose the draw handlers the helper relies on', () => {
        const { controller, live, graph } = realG6();
        swapCommunityShapes(live as never, shapes([['0', ['a', 'b']]]));
        expect(typeof (controller.getPluginInstance('hull-0') as unknown as { drawHull: unknown }).drawHull).toBe(
            'function',
        );
        expect(Hull.prototype.destroy).toBeTypeOf('function');
        const bs = new BubbleSets({ graph } as never, { members: [] } as never);
        expect(typeof (bs as unknown as { drawBubbleSets: unknown }).drawBubbleSets).toBe('function');
    });

    it('(1) hulls ON draws the shape at once, with no render', () => {
        const { controller, live, canvas, render } = realG6();
        swapCommunityShapes(live as never, shapes([['0', ['a', 'b', 'c']]]));
        expect(shapeOf(controller, 'hull-0')).toBeDefined();
        expect(canvas.appendChild).toHaveBeenCalledTimes(1);
        expect(render).not.toHaveBeenCalled();
    });

    it('(2) ON then straight OFF does not throw, and destroying the controller afterwards does not either', () => {
        const { controller, live, count } = realG6();
        swapCommunityShapes(live as never, shapes([['0', ['a', 'b']]]));
        expect(() => swapCommunityShapes(live as never, [])).not.toThrow();
        expect(() => controller.destroy()).not.toThrow();
        expect(count(GraphEvent.AFTER_RENDER)).toBe(0);
    });

    it('(2b) OFF of a shape never drawn (graph not rendered yet) does not throw either', () => {
        const { controller, live } = realG6();
        live.setPlugins(() => shapes([['0', ['a', 'b']]]) as never); // created only: no draw happened
        expect(shapeOf(controller, 'hull-0')).toBeUndefined();
        expect(() => swapCommunityShapes(live as never, [])).not.toThrow();
        expect(() => controller.destroy()).not.toThrow();
    });

    it('(3) the same key with other members redraws the path: hulls follow the data', () => {
        const { controller, live } = realG6();
        swapCommunityShapes(live as never, shapes([['0', ['a', 'b', 'c']]]));
        const before = shapeOf(controller, 'hull-0')!.style.d;
        swapCommunityShapes(live as never, shapes([['0', ['b', 'c', 'd']]]));
        expect(shapeOf(controller, 'hull-0')!.style.d).not.toEqual(before);
    });

    it('(4) 20 ON/OFF cycles leave no listeners behind', () => {
        const { live, count } = realG6();
        const on = shapes([
            ['0', ['a', 'b']],
            ['1', ['c', 'd']],
        ]);
        for (let i = 0; i < 20; i++) {
            swapCommunityShapes(live as never, on);
            swapCommunityShapes(live as never, []);
        }
        expect(count(GraphEvent.AFTER_RENDER)).toBe(0);
        expect(count(GraphEvent.AFTER_ELEMENT_UPDATE)).toBe(0);
    });

    it('(5) bubble sets: drawn at once, follow the members, 20 cycles clean', () => {
        const { controller, live, count } = realG6();
        swapCommunityShapes(live as never, shapes([['0', ['a', 'b', 'c']]], true));
        expect(shapeOf(controller, 'bubble-sets-0')).toBeDefined();
        const before = shapeOf(controller, 'bubble-sets-0')!.style.d;
        swapCommunityShapes(live as never, shapes([['0', ['b', 'c', 'd']]], true));
        expect(shapeOf(controller, 'bubble-sets-0')!.style.d).not.toEqual(before);
        for (let i = 0; i < 20; i++) {
            swapCommunityShapes(live as never, []);
            swapCommunityShapes(live as never, shapes([['0', ['a', 'b']]], true));
        }
        swapCommunityShapes(live as never, []);
        expect(count(GraphEvent.AFTER_RENDER)).toBe(0);
        expect(count(GraphEvent.AFTER_ELEMENT_UPDATE)).toBe(0);
    });

    it('the component: ON then OFF through ngOnChanges, then destroyGraph still nulls the graph', () => {
        TestBed.configureTestingModule({
            imports: [GraphViewComponent],
            providers: [{ provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
        });
        const comp = TestBed.createComponent(GraphViewComponent).componentInstance as unknown as {
            ready: boolean;
            graph: unknown;
            plugins: GraphViewComponent['plugins'];
            snapshotKeys(): void;
            destroyGraph(): void;
            ngOnChanges(c: Record<string, unknown>): void;
        };
        const create = vi.spyOn(comp as unknown as { create(): void }, 'create').mockImplementation(() => undefined);
        const { live, controller, render } = realG6();
        comp.ready = true;
        comp.graph = { ...live, destroy: () => controller.destroy(), render };
        comp.plugins = { hulls: null };
        comp.snapshotKeys();
        comp.plugins = { hulls: new Map([['0', ['a', 'b']]]) };
        comp.ngOnChanges({ plugins: {} });
        expect(shapeOf(controller, 'hull-0')).toBeDefined();
        comp.plugins = { hulls: null };
        expect(() => comp.ngOnChanges({ plugins: {} })).not.toThrow();
        comp.destroyGraph();
        expect(comp.graph).toBeNull();
        expect(create).not.toHaveBeenCalled();
        expect(render).not.toHaveBeenCalled();
    });
});

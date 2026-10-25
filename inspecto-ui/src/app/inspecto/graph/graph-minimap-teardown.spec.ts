import { Minimap } from '@antv/g6';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GraphViewComponent, silenceMinimap } from './graph-view.component';

/**
 * N12: G6 5.1.1 `Minimap` debounces `renderMinimap` (plugins/minimap/index.js:117, no cancel) and its `destroy()`
 * (via `BaseExtension.destroy`, registry/extension/index.js:80) blanks `this.context`. A render still pending then
 * throws `Cannot read properties of undefined (reading 'getData')` (index.js:149). This spec instantiates the REAL
 * class with a minimal fake context (no canvas is touched on the paths exercised).
 */
describe('minimap teardown (N12)', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => {
        vi.useRealTimers();
        vi.restoreAllMocks();
    });

    function realMinimap() {
        const context = {
            graph: { on: vi.fn(), off: vi.fn() },
            model: { getData: () => ({ nodes: [], edges: [], combos: [] }) },
        };
        const minimap = new Minimap(context as never, { type: 'minimap', delay: 128 } as never);
        return minimap as unknown as { onRender(): void; destroy(): void };
    }
    const fakeGraph = (minimap: unknown) =>
        ({
            getOptions: () => ({ plugins: [{ type: 'minimap', key: 'minimap' }] }),
            getPluginInstance: () => minimap,
        }) as never;

    it('REPRODUCTION: a render pending at destroy throws once the debounce fires', () => {
        const m = realMinimap();
        m.onRender();
        m.destroy();
        expect(() => vi.advanceTimersByTime(500)).toThrow(/getData/);
    });

    it('silenceMinimap before destroy turns the pending render into a no-op', () => {
        const m = realMinimap();
        m.onRender();
        silenceMinimap(fakeGraph(m));
        m.destroy();
        expect(() => vi.advanceTimersByTime(500)).not.toThrow();
    });

    it('while the graph is alive the minimap still renders', () => {
        const proto = Minimap.prototype as unknown as { renderMinimap(): void; renderMask(): void };
        const render = vi.spyOn(proto, 'renderMinimap').mockImplementation(() => undefined);
        vi.spyOn(proto, 'renderMask').mockImplementation(() => undefined);
        const m = realMinimap();
        m.onRender();
        vi.advanceTimersByTime(500);
        expect(render).toHaveBeenCalledTimes(1);
    });

    it('does nothing (and does not look the plugin up) when the graph has no minimap', () => {
        const getPluginInstance = vi.fn();
        silenceMinimap({ getOptions: () => ({ plugins: [] }), getPluginInstance } as never);
        expect(getPluginInstance).not.toHaveBeenCalled();
    });

    it('create() over an existing graph silences the old minimap before destroying it', () => {
        const order: string[] = [];
        const old = {
            getOptions: () => ({ plugins: [{ type: 'minimap', key: 'minimap' }] }),
            getPluginInstance: () => {
                order.push('silence');
                return {};
            },
            destroy: () => order.push('destroy'),
        };
        const comp = Object.create(GraphViewComponent.prototype) as unknown as {
            graph: unknown;
            destroyGraph(): void;
        };
        comp.graph = old;
        comp.destroyGraph.call({
            graph: old,
            cancelPendingClick: () => undefined,
            dropPendingBrush: () => undefined,
            hostEl: undefined,
        });
        expect(order).toEqual(['silence', 'destroy']);
    });
});

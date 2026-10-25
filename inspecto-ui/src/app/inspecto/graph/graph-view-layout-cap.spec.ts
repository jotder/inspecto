import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { G6GraphData } from './catalog-graph';
import { CAPPED_LAYOUT_FALLBACK, GraphDisplayOptions, GraphViewComponent, cappedLayout } from './graph-view.component';

/** Every `new Graph(options)` the component makes, in order: the layout is the one that would reach G6. */
const built = vi.hoisted(() => [] as { layout: { type: string } }[]);

// G6 cannot mount in jsdom: only `new Graph(options)` matters here, every other export is an inert enum-like stand-in.
vi.mock('@antv/g6', () => {
    class FakeGraph {
        constructor(options: { layout: { type: string } }) {
            built.push(options);
            return new Proxy(this, { get: (t, p) => (p in t ? Reflect.get(t, p) : () => Promise.resolve()) });
        }
    }
    const events = new Proxy({}, { get: (_t, k) => String(k) });
    return { Graph: FakeGraph, CanvasEvent: events, CommonEvent: events, EdgeEvent: events, NodeEvent: events };
});

const graphOf = (n: number): G6GraphData =>
    ({
        nodes: Array.from({ length: n }, (_, i) => ({ id: `n${i}`, data: { kind: 'sim', label: `n${i}` } })),
        edges: Array.from({ length: n - 1 }, (_, i) => ({
            id: `e${i}`,
            source: `n${i}`,
            target: `n${i + 1}`,
            data: {},
        })),
    }) as unknown as G6GraphData;

const display = (caps: GraphDisplayOptions['layoutCaps']): GraphDisplayOptions => ({
    nodeLabels: true,
    edgeLabels: true,
    nodeColors: {},
    edgeColors: {},
    nodeShapes: {},
    edgePatterns: {},
    edgeSizes: {},
    layoutCaps: caps,
});

function host(n: number, layout: string, caps: GraphDisplayOptions['layoutCaps']) {
    TestBed.configureTestingModule({
        imports: [GraphViewComponent],
        providers: [{ provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
    });
    const fixture = TestBed.createComponent(GraphViewComponent);
    fixture.componentRef.setInput('data', graphOf(n));
    fixture.componentRef.setInput('layout', layout);
    fixture.componentRef.setInput('display', display(caps));
    fixture.detectChanges();
    return fixture;
}

describe('the layout node cap, at the one place a layout reaches G6 (N1)', () => {
    beforeEach(() => (built.length = 0));

    it('cappedLayout: the pick at or under its cap, the fallback over it, any pick with no cap', () => {
        expect(cappedLayout('radial', 100, { radial: 100 })).toBe('radial');
        expect(cappedLayout('radial', 101, { radial: 100 })).toBe(CAPPED_LAYOUT_FALLBACK);
        expect(cappedLayout('mds', 301, { mds: 300, radial: 100 })).toBe(CAPPED_LAYOUT_FALLBACK);
        expect(cappedLayout('force', 5000, { radial: 100 })).toBe('force');
        expect(cappedLayout('radial', 5000, undefined)).toBe('radial');
        expect(cappedLayout(null, 5000, { radial: 100 })).toBeNull();
        expect(CAPPED_LAYOUT_FALLBACK).toBe('concentric');
    });

    it('a Radial graph over the cap is built with Concentric, never Radial', () => {
        host(101, 'radial', { radial: 100 });
        expect(built.map((o) => o.layout.type)).toEqual(['concentric']);
    });

    it('an Information density graph over its cap is built with Concentric', () => {
        host(301, 'mds', { mds: 300, radial: 100 });
        expect(built.map((o) => o.layout.type)).toEqual(['concentric']);
    });

    it('at the cap the layout is unchanged', () => {
        host(100, 'radial', { radial: 100 });
        expect(built.map((o) => o.layout.type)).toEqual(['radial']);
    });

    it('a graph that GROWS past the cap is rebuilt with the fallback, not re-laid-out as Radial', () => {
        const fixture = host(100, 'radial', { radial: 100 });
        fixture.componentRef.setInput('data', graphOf(101));
        fixture.detectChanges();
        expect(built.map((o) => o.layout.type)).toEqual(['radial', 'concentric']);
    });
});

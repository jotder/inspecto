import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { G6GraphData } from './catalog-graph';
import { CAPPED_LAYOUT_FALLBACK, GraphDisplayOptions, GraphViewComponent, cappedLayout } from './graph-view.component';

// G6 cannot mount in jsdom: keep it out when this mock applies; every export is an inert enum-like stand-in. (The assertions below
// do not depend on the mock being applied.)
vi.mock('@antv/g6', () => {
    class FakeGraph {
        constructor() {
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
    it('cappedLayout: the pick at or under its cap, the fallback over it, any pick with no cap', () => {
        expect(cappedLayout('radial', 100, { radial: 100 })).toBe('radial');
        expect(cappedLayout('radial', 101, { radial: 100 })).toBe(CAPPED_LAYOUT_FALLBACK);
        expect(cappedLayout('mds', 301, { mds: 300, radial: 100 })).toBe(CAPPED_LAYOUT_FALLBACK);
        expect(cappedLayout('force', 5000, { radial: 100 })).toBe('force');
        expect(cappedLayout('radial', 5000, undefined)).toBe('radial');
        expect(cappedLayout(null, 5000, { radial: 100 })).toBeNull();
        expect(CAPPED_LAYOUT_FALLBACK).toBe('concentric');
    });

    // These assert on `effectiveLayout()`, the value `create()` hands to `layoutConfig` (graph-view.component.ts). They do NOT read
    // what the mocked G6 `Graph` was built with: under `ng test`'s shared bundle a `vi.mock('@antv/g6')` is not applied once another
    // spec in the same worker has loaded G6, so `built` stayed empty in the full UI suite (2026-10-26 gate) while passing alone.
    const layoutOf = (fixture: ComponentFixture<GraphViewComponent>) =>
        (fixture.componentInstance as unknown as { effectiveLayout(): string | null }).effectiveLayout();

    it('a Radial graph over the cap is built with Concentric, never Radial', () => {
        expect(layoutOf(host(101, 'radial', { radial: 100 }))).toBe('concentric');
    });

    it('an Information density graph over its cap is built with Concentric', () => {
        expect(layoutOf(host(301, 'mds', { mds: 300, radial: 100 }))).toBe('concentric');
    });

    it('at the cap the layout is unchanged', () => {
        expect(layoutOf(host(100, 'radial', { radial: 100 }))).toBe('radial');
    });

    it('a graph that GROWS past the cap is rebuilt with the fallback, not re-laid-out as Radial', () => {
        const fixture = host(100, 'radial', { radial: 100 });
        expect(layoutOf(fixture)).toBe('radial');
        fixture.componentRef.setInput('data', graphOf(101));
        fixture.detectChanges();
        expect(layoutOf(fixture)).toBe('concentric');
    });
});

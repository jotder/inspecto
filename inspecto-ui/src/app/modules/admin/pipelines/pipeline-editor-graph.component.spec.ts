import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineEditorGraphComponent } from './pipeline-editor-graph.component';
import { loadPipelineLayout } from './pipeline-layout';

/** G6 can't instantiate in jsdom (per the angular-ui skill) and this host mounts the canvas
 *  unconditionally in ngAfterViewInit — so `rebuild()` is stubbed out before the first
 *  detectChanges and only the canvas-free host shell (drop target, keyboard, aria) is tested. */
function create() {
    TestBed.configureTestingModule({
        imports: [PipelineEditorGraphComponent],
        providers: [{ provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
    });
    const fixture = TestBed.createComponent(PipelineEditorGraphComponent);
    vi.spyOn(fixture.componentInstance as unknown as { rebuild(): void }, 'rebuild').mockImplementation(
        () => undefined,
    );
    fixture.detectChanges();
    return fixture;
}

describe('PipelineEditorGraphComponent', () => {
    it('emits deleteKey on Delete and dropAdd from a palette drop', () => {
        const fixture = create();
        const c = fixture.componentInstance;

        const deleted = vi.fn();
        c.deleteKey.subscribe(deleted);
        c.onKeydown(new KeyboardEvent('keydown', { key: 'Delete' }));
        expect(deleted).toHaveBeenCalledTimes(1);

        const dropped = vi.fn();
        c.dropAdd.subscribe(dropped);
        // jsdom has no DataTransfer/DragEvent constructors — a shaped stand-in is enough here.
        const drop = {
            preventDefault: () => undefined,
            dataTransfer: { getData: (t: string) => (t === 'text/flow-node-type' ? 'collector' : '') },
            clientX: 10,
            clientY: 20,
        } as unknown as DragEvent;
        c.onDrop(drop);
        expect(dropped).toHaveBeenCalledWith(expect.objectContaining({ type: 'collector' }));
    });

    it('persists every node position for the Pipeline, and Auto-arrange forgets them', () => {
        localStorage.clear();
        const fixture = create();
        const c = fixture.componentInstance;
        c.graphKey = 'orders';
        const pos: Record<string, [number, number]> = { a: [10, 20], b: [30, 40] };
        (c as unknown as { graph: unknown }).graph = {
            getNodeData: () => [{ id: 'a' }, { id: 'b' }],
            getElementPosition: (id: string) => [...pos[id], 0],
            destroy: vi.fn(),
        };
        c.persistLayout();
        expect(loadPipelineLayout('orders')).toEqual(pos);

        c.resetLayout();
        expect(loadPipelineLayout('orders')).toBeNull();
    });

    it('renders the canvas host (empty, no graph mounted) with no a11y violations', async () => {
        const fixture = create();
        const host = (fixture.nativeElement as HTMLElement).querySelector('[role="application"]');
        expect(host?.getAttribute('aria-label')).toContain('Pipeline editor canvas');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('observes the canvas box so the side docks resize the graph, and disconnects on destroy', () => {
        // jsdom has no ResizeObserver; stub one so the AfterViewInit wiring runs (the component guards
        // on `typeof ResizeObserver !== 'undefined'`).
        const observe = vi.fn();
        const disconnect = vi.fn();
        vi.stubGlobal(
            'ResizeObserver',
            class {
                observe = observe;
                disconnect = disconnect;
                unobserve = vi.fn();
            },
        );

        const fixture = create();
        expect(observe).toHaveBeenCalledTimes(1);
        fixture.destroy();
        expect(disconnect).toHaveBeenCalledTimes(1);
        vi.unstubAllGlobals();
    });

    /** The canvas says how to connect: plain drag MOVES a Step, Shift+drag draws the edge. */
    it("the canvas's accessible name states the Shift+drag connect gesture, not a plain drag", () => {
        const host = (create().nativeElement as HTMLElement).querySelector('[role="application"]');
        const label = host?.getAttribute('aria-label') ?? '';
        expect(label).toContain('Shift+drag from one Step to another to connect');
        expect(label).not.toContain('drag Step-to-Step to connect');
    });

    /**
     * CANVAS-CLIPPED-AT-1024 (driven 2026-09-25): with both docks open the acquisition and sink Steps sat
     * off either edge — resizing never re-fit, and G6 clamps a fit to the 0.75 wheel floor. G6 cannot run
     * in jsdom, so the fit logic is driven against a shaped stand-in.
     */
    describe('fit to view', () => {
        /** A stand-in graph whose drawn content spans canvas x ∈ [0, contentW] in a `viewW`-wide box. */
        function fakeGraph(viewW: number, contentW: number) {
            let zoom = 1;
            let range: [number, number] = [0.75, 3];
            const g = {
                resize: vi.fn(),
                getSize: () => [viewW, 400],
                getCanvas: () => ({ getBounds: () => ({ min: [0, 0, 0], max: [contentW, 50, 0] }) }),
                getViewportByCanvas: ([x, y]: number[]) => [x * zoom, y * zoom],
                setZoomRange: vi.fn((r: [number, number]) => (range = r)),
                getZoomRange: () => range,
                // G6 clamps the fitted zoom to the CURRENT range — the trap being fixed.
                fitView: vi.fn(async () => {
                    zoom = Math.max(range[0], Math.min(range[1], viewW / contentW));
                }),
                getZoom: () => zoom,
                destroy: vi.fn(),
            };
            return g;
        }

        it('a resize that leaves the graph overflowing re-fits it — below the wheel floor when it must', async () => {
            const c = create().componentInstance;
            const g = fakeGraph(200, 600); // needs 0.33 to be seen whole
            (c as unknown as { graph: unknown }).graph = g;
            c.onHostResize();
            await Promise.resolve();
            await Promise.resolve();
            expect(g.resize).toHaveBeenCalled();
            expect(g.fitView).toHaveBeenCalledTimes(1);
            expect(g.getZoom()).toBeCloseTo(200 / 600); // whole graph visible, not clamped at 0.75
            // The wheel floor comes back — at the fitted zoom, so zooming out still reaches the whole graph.
            expect(g.getZoomRange()).toEqual([200 / 600, 3]);
        });

        it('a resize that still fits keeps the author’s zoom and pan', async () => {
            const c = create().componentInstance;
            const g = fakeGraph(900, 600);
            (c as unknown as { graph: unknown }).graph = g;
            c.onHostResize();
            await Promise.resolve();
            expect(g.resize).toHaveBeenCalled();
            expect(g.fitView).not.toHaveBeenCalled();
        });

        it('an explicit fit restores the 0.75 wheel floor when the graph fits above it', async () => {
            const c = create().componentInstance;
            const g = fakeGraph(900, 600);
            (c as unknown as { graph: unknown }).graph = g;
            await c.fitToView();
            expect(g.fitView).toHaveBeenCalledTimes(1);
            expect(g.getZoomRange()).toEqual([0.75, 3]);
        });
    });
});

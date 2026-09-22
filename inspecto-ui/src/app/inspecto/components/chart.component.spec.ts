import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoChartComponent } from './chart.component';

// Chart.js cannot paint in jsdom; the fixture keeps `data` null so rebuild() never instantiates a
// Chart — the a11y contract under test is the canvas wrapper (role="img" + alt text), not the pixels.
function create() {
    TestBed.configureTestingModule({
        imports: [InspectoChartComponent],
        providers: [
            provideNoopAnimations(),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    const fixture = TestBed.createComponent(InspectoChartComponent);
    fixture.componentRef.setInput('type', 'bar');
    fixture.detectChanges();
    return fixture;
}

describe('InspectoChartComponent', () => {
    it('exposes the canvas as role="img" with a data-derived text alternative', () => {
        const fixture = create();
        // ⚠ setInput, NOT a raw field write. The old direct assignment avoided ngOnChanges (the rebuild
        // wants a real canvas), but it also marks nothing dirty — fine under CD.Default, invisible under
        // OnPush, which this component now uses. setInput is what every real caller does and it marks the
        // view; the rebuild it triggers is already guarded for jsdom.
        fixture.componentRef.setInput('data', { labels: ['a', 'b'], datasets: [{ data: [1, 2] }] });
        fixture.detectChanges();

        // ⚠ Re-query: the canvas lives behind an `@if`, so it is a NEW element once there is data.
        const canvas = (fixture.nativeElement as HTMLElement).querySelector('canvas')!;
        expect(canvas.getAttribute('role')).toBe('img');
        expect(canvas.getAttribute('aria-label')).toBe('bar chart. a: 1, b: 2.');
    });

    it('renders the shared empty state instead of an empty axis when there is nothing to plot', () => {
        const fixture = create();
        const el = fixture.nativeElement as HTMLElement;

        // 🔴 No data at all. Chart.js would happily draw a labelled 0-to-1 axis with a legend here, which
        // reads as "measured, and the answer is zero" when the truth is "nothing has run yet".
        expect(el.querySelector('canvas')).toBeNull();
        expect(el.querySelector('inspecto-empty-state')).not.toBeNull();

        // labels but every dataset empty — still nothing to plot
        fixture.componentRef.setInput('data', { labels: ['a'], datasets: [{ data: [] }] });
        fixture.detectChanges();
        expect(el.querySelector('canvas')).toBeNull();
    });

    it('still draws a series of explicit zeros — that is a measurement, not an absence', () => {
        const fixture = create();
        fixture.componentRef.setInput('data', { labels: ['ok', 'failed'], datasets: [{ data: [0, 0] }] });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('canvas')).not.toBeNull();
        expect(el.querySelector('inspecto-empty-state')).toBeNull();
    });

    it("honours the host's verdict that the series means nothing yet", () => {
        const fixture = create();
        fixture.componentRef.setInput('data', { labels: ['p50'], datasets: [{ data: [0] }] });
        fixture.componentRef.setInput('empty', true);
        fixture.componentRef.setInput('emptyMessage', 'No Consignment has completed yet.');
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('canvas')).toBeNull();
        expect(el.querySelector('inspecto-empty-state')?.textContent).toContain('No Consignment has completed yet.');
    });

    it('observes the host box for container resizes and disconnects on destroy', () => {
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
        // Observes the HOST element (not the <canvas>) to avoid a maintainAspectRatio feedback loop.
        expect(observe).toHaveBeenCalledWith(fixture.nativeElement);
        fixture.destroy();
        expect(disconnect).toHaveBeenCalled();
        vi.unstubAllGlobals();
    });

    it('has no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

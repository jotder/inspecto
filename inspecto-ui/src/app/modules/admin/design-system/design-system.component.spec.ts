import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { afterEach, describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { GammaConfigService } from '@gamma/services/config';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DesignSystemComponent } from './design-system.component';

async function create() {
    TestBed.configureTestingModule({
        imports: [DesignSystemComponent],
        providers: [
            provideNoopAnimations(),
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            {
                provide: ToastrService,
                useValue: {
                    success: () => undefined,
                    error: () => undefined,
                    warning: () => undefined,
                    info: () => undefined,
                },
            },
            // the embedded map host tracks the colour scheme
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // the embedded data-table has a @defer block
    const fixture = TestBed.createComponent(DesignSystemComponent);
    fixture.detectChanges();
    return fixture;
}

describe('DesignSystemComponent', () => {
    afterEach(() => document.querySelector('.cdk-overlay-container')?.remove());

    it('renders the gallery: heading plus one section per shared pattern', async () => {
        const fixture = await create();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('h1')?.textContent).toContain('Design System');
        const sections = Array.from(el.querySelectorAll('section h2')).map((h) => h.textContent?.trim());
        expect(sections[0]).toBe('Foundations');
        expect(sections).toContain('Status badge');
        expect(sections).toContain('Data grid');
        expect(sections).toContain('Data table');
        expect(sections).toContain('Resizable dialog');
        expect(sections).toContain('Chart theme');
    });

    it('the Dashboard tile section shows a KPI, a chart, a loading and an empty tile side by side', async () => {
        const fixture = await create();
        const el = fixture.nativeElement as HTMLElement;
        const heading = Array.from(el.querySelectorAll('section > div > h2')).find(
            (h) => h.textContent?.trim() === 'Dashboard tile',
        );
        const tiles = heading!.closest('section')!.querySelectorAll('inspecto-tile-card');
        expect(tiles.length).toBe(4);
        expect(tiles[0].querySelector('inspecto-kpi')).toBeTruthy();
        expect(tiles[1].querySelector('inspecto-chart')).toBeTruthy();
        expect(tiles[2].querySelector('[data-testid="tile-skeleton"]')).toBeTruthy();
        expect(tiles[3].querySelector('[data-testid="tile-empty"]')).toBeTruthy();
    });

    it('the resizable-dialog demo opens with the shared chrome (grip + maximize button)', async () => {
        const fixture = await create();
        fixture.componentInstance.openResizeDemo();
        TestBed.tick(); // the dialog renders in the overlay, outside the fixture's tree
        const pane = document.querySelector('.cdk-overlay-pane') as HTMLElement;
        expect(pane.classList.contains('inspecto-dialog-resizable')).toBe(true);
        expect(pane.querySelector('.inspecto-dialog-resize-grip')).toBeTruthy();
        expect(pane.querySelector('button[aria-label="Full screen"]')).toBeTruthy();
    });

    // ⚠ 30s, not the 15s default: this is the suite's single heaviest assertion — axe over the WHOLE
    // gallery, every shared pattern mounted at once — and it was sitting close enough to the default
    // that adding four unrelated specs elsewhere in the suite tipped it over (2026-08-29). A timeout
    // here says nothing about a11y, so the honest fix is headroom rather than a smaller scan.
    it('has no a11y violations', async () => {
        const fixture = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    }, 30_000);
});

import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ComponentsService, DbBrowserService, EventsService, LensService } from '@inspecto/core/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from '@inspecto/core/grid';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisToolboxComponent } from './link-analysis-toolbox.component';
import { LinkAnalysisValueMeasuresComponent } from './link-analysis-value-measures.component';

function make(withMapping = true) {
    TestBed.configureTestingModule({
        imports: [LinkAnalysisToolboxComponent],
        providers: [
            provideNoopAnimations(),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            { provide: ComponentsService, useValue: { list: () => of([]) } },
            { provide: InvService, useValue: { valueMeasures: vi.fn(), boundAlertRules: () => of({ rules: [] }) } },
            { provide: LensService, useValue: { canAuthorAlertRules: () => true } },
            { provide: EventsService, useValue: { search: () => of([]) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            { provide: DbBrowserService, useValue: { table: () => of({ columns: [] }) } },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisToolboxComponent);
    fixture.componentRef.setInput('side', 'server'); // Value Measures runs on the server: the Server tab
    if (withMapping)
        fixture.componentRef.setInput('indexMappings', [
            { dataset: 'transfers', sourceCol: 'payer', targetCol: 'payee', kindCol: 'kind', label: 'transfers' },
        ]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const header = () =>
        Array.from(el.querySelectorAll<HTMLButtonElement>('section > button')).find((b) =>
            (b.textContent ?? '').includes('Value Measures'),
        );
    return { fixture, el, header };
}

describe('Toolbox - Value Measures entry', () => {
    it('lists a labelled Value Measures entry with its one-line description, keyboard operable (a11y)', async () => {
        const { fixture, el, header } = make();
        const h = header();
        expect(h).toBeTruthy();
        expect(h!.tagName).toBe('BUTTON');
        expect(h!.textContent).toContain('Find entities by how money moves through them');
        expect(h!.getAttribute('aria-expanded')).toBe('false');
        h!.focus();
        h!.click(); // a native button: Enter/Space dispatch this same click
        fixture.detectChanges();
        expect(h!.getAttribute('aria-expanded')).toBe('true');
        expect(el.querySelector('#' + h!.getAttribute('aria-controls'))).toBeTruthy();
        expect(el.querySelector('#la-value-measures-heading')?.textContent).toContain('Value Measures');
        await expectNoA11yViolations(el);
    });

    it('opens the Value Measures panel pre-selecting the Query panel Dataset and columns', () => {
        const { fixture, header } = make();
        header()!.click();
        fixture.detectChanges();
        const vm = fixture.debugElement.query((d) => d.componentInstance instanceof LinkAnalysisValueMeasuresComponent)
            .componentInstance as LinkAnalysisValueMeasuresComponent;
        expect(vm.form().form.value).toMatchObject({
            dataset: 'transfers',
            sourceCol: 'payer',
            targetCol: 'payee',
            linkKindCol: 'kind',
        });
    });

    it('opens with no mapping too (nothing to pre-select)', () => {
        const { fixture, header } = make(false);
        header()!.click();
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('#la-value-measures-heading')).toBeTruthy();
    });
});

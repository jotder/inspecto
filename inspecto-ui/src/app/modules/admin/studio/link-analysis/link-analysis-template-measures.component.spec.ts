import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService, InvestigationMeasures } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LinkAnalysisTemplateMeasuresComponent } from './link-analysis-template-measures.component';
import { InstantiateTemplateDialog, WatchMeasureDialog } from './link-analysis-template.dialogs';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisTemplateMeasuresComponent],
    template: `<inspecto-link-analysis-template-measures
        investigationId="inv-1"
        (instantiated)="got = $event"
    ></inspecto-link-analysis-template-measures>`,
})
class Host {
    got: unknown = null;
}

const MEASURES: InvestigationMeasures = {
    id: 'inv-1',
    head: { step: 3, workingSetHash: 'sha256:h' },
    measures: [
        { name: 'entities', relation: 'entities', measure: 'count', value: 12 },
        { name: 'maxHop', relation: 'entities', measure: 'max(hop)', value: null },
    ],
    byKind: [],
    key: 'k',
    cached: false,
};

async function create(measures: () => unknown = () => of(MEASURES)) {
    const open = vi.fn(() => ({ afterClosed: () => of({ id: 'inv-2', header: { title: 'T' } }) }));
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: { investigationMeasures: vi.fn(measures) } },
            { provide: MatDialog, useValue: { open } },
        ],
    });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    const c = f.debugElement.children[0].componentInstance as LinkAnalysisTemplateMeasuresComponent;
    return { f, c, el: f.nativeElement as HTMLElement, open };
}

describe('LinkAnalysisTemplateMeasuresComponent (LA-23)', () => {
    it('renders the Measures strip with values, an em dash for an absent value, and the head step', async () => {
        const { el } = await create();
        const strip = el.querySelector('[aria-label="Measures strip"]')!.textContent!;
        expect(strip).toContain('entities');
        expect(strip).toContain('12');
        expect(strip).toContain('—');
        expect(el.textContent).toContain('At step 3');
        await expectNoA11yViolations(el);
    });

    it('Watch opens the Alert Rule dialog for that Measure; Instantiate emits the new Investigation', async () => {
        const { f, c, open } = await create();
        c.watch(MEASURES.measures[0]);
        expect(open).toHaveBeenCalledWith(
            WatchMeasureDialog,
            expect.objectContaining({ data: { investigationId: 'inv-1', measure: MEASURES.measures[0] } }),
        );
        c.instantiate();
        expect(open).toHaveBeenLastCalledWith(InstantiateTemplateDialog, expect.anything());
        expect((f.componentInstance as Host).got).toEqual({ id: 'inv-2', header: { title: 'T' } });
    });

    it('explains a 503 in place as info, never a toast', async () => {
        const { c, el } = await create(() =>
            throwError(() => new HttpErrorResponse({ status: 503, error: { error: 'no write root' } })),
        );
        expect(c.unavailable()).toBe(true);
        expect(el.textContent).toContain('not available here');
        await expectNoA11yViolations(el);
    });
});

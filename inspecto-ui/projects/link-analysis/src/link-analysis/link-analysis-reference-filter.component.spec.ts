import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penByLabel, penEdit } from '@inspecto/core/testing/pen-field';
import { LinkAnalysisReferenceFilterComponent, ReferenceFilter } from './link-analysis-reference-filter.component';
import { NodeReference } from './reference-details';

const STORES: NodeReference = {
    dataset: 'stores',
    keyCol: 'store_id',
    name: 'Stores',
    entityType: 'store',
    columns: ['region', 'manager'],
};

@Component({
    standalone: true,
    imports: [LinkAnalysisReferenceFilterComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-la-reference-filter
        [references]="refs"
        [active]="active()"
        (apply)="applied.push($event)"
        (clear)="cleared = cleared + 1"
    />`,
})
class HostComponent {
    refs: NodeReference[] = [STORES];
    readonly active = signal(false);
    readonly applied: ReferenceFilter[] = [];
    cleared = 0;
}

describe('LinkAnalysisReferenceFilterComponent (ONBOARDING-FIRST-RUN-1 C6a step 4)', () => {
    it('keeps the nodes whose first detail contains the text; Show all once a filter is in force', async () => {
        TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain('Details from Stores');
        const apply = el.querySelector('[data-testid="reference-filter-apply"]') as HTMLButtonElement;
        expect(apply.disabled).toBe(true); // nothing typed yet
        penEdit(fixture, penByLabel(el, 'Contains')!, ' North ');
        fixture.detectChanges();
        // Enter applies (once: the field cancels the key, so the form is not also submitted)
        expect(fixture.componentInstance.applied).toEqual([{ reference: STORES, column: 'region', text: 'North' }]);
        expect(apply.disabled).toBe(false);
        apply.click();
        expect(fixture.componentInstance.applied.length).toBe(2);
        expect(el.querySelector('[data-testid="reference-filter-clear"]')).toBeNull();
        fixture.componentInstance.active.set(true);
        fixture.detectChanges();
        (el.querySelector('[data-testid="reference-filter-clear"]') as HTMLButtonElement).click();
        expect(fixture.componentInstance.cleared).toBe(1);
        await expectNoA11yViolations(el);
    });
});

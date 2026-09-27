import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ComponentsService, DecisionRulesService, JobsService, LensService, RunsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { buildRequirement, decideRequirement, deliverRequirement, Requirement } from 'app/inspecto/requirement';
import { RequirementDecisionDialog, RequirementDecisionResult } from './requirement-decision.dialog';

function create(requirement = buildRequirement('Daily churn KPI', 'kpi', 'Track churn.')) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [RequirementDecisionDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: requirement },
            { provide: MatDialogRef, useValue: ref },
            // The "Delivered via" picker's cross-kind suggestion sources.
            {
                provide: ComponentsService,
                useValue: { list: (kind: string) => of(kind === 'dashboard' ? [{ name: 'churn_kpi' }] : []) },
            },
            { provide: RunsService, useValue: { list: () => of([{ name: 'cdr_ingest' }]) } },
            { provide: JobsService, useValue: { list: () => of([]) } },
            { provide: DecisionRulesService, useValue: { list: () => of([]) } },
        ],
    });
    const fixture = TestBed.createComponent(RequirementDecisionDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('RequirementDecisionDialog', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('shows Accept/Reject for a submitted requirement in the default (Builder) lens', () => {
        const { fixture, ref } = create();
        const el = fixture.nativeElement as HTMLElement;
        Array.from(el.querySelectorAll('button'))
            .find((b) => b.textContent?.includes('Accept'))
            ?.click();
        const result = ref.close.mock.calls[0][0] as RequirementDecisionResult;
        expect(result).toEqual({ action: 'decide', accept: true, note: undefined });
    });

    it('shows Mark delivered for an accepted requirement', () => {
        const { fixture, ref } = create(decideRequirement(buildRequirement('x', 'kpi', 'y'), true));
        const el = fixture.nativeElement as HTMLElement;
        Array.from(el.querySelectorAll('button'))
            .find((b) => b.textContent?.includes('Mark delivered'))
            ?.click();
        expect(ref.close).toHaveBeenCalledWith({ action: 'deliver', note: undefined });
    });

    it('suggests cross-kind component refs on focus and delivers the picked/typed value', async () => {
        const { fixture, c, ref } = create(decideRequirement(buildRequirement('x', 'kpi', 'y'), true));
        c.loadOptions();
        await new Promise((r) => setTimeout(r, 0)); // flush the loader's allSettled chain
        expect(c.filteredOptions().map((o) => o.value)).toEqual(['dashboard/churn_kpi', 'pipeline/cdr_ingest']);

        c.note.setValue('dash'); // typing narrows; the value is never constrained to the list
        expect(c.filteredOptions().map((o) => o.value)).toEqual(['dashboard/churn_kpi']);

        c.note.setValue('dashboard/churn_kpi');
        Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
            .find((b) => b.textContent?.includes('Mark delivered'))
            ?.click();
        expect(ref.close).toHaveBeenCalledWith({ action: 'deliver', note: 'dashboard/churn_kpi' });
    });

    it('hides decision inputs in the Business (read-only) lens', () => {
        const { fixture } = create();
        TestBed.inject(LensService).selectLens('business');
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Accept'))).toBe(false);
    });

    describe('Create KPI (ASSURE-KPI-DEFINITIONS-1)', () => {
        const delivered = (kind: Requirement['kind'] = 'kpi') =>
            deliverRequirement(decideRequirement(buildRequirement('Refund exposure', kind, 'y'), true));
        const createKpiButton = (el: HTMLElement) =>
            Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Create KPI');

        it('offers Create KPI on a delivered kpi requirement and closes with that action', async () => {
            const { fixture, ref } = create(delivered());
            const button = createKpiButton(fixture.nativeElement);
            expect(button).toBeTruthy();
            await expectNoA11yViolations(fixture.nativeElement);
            button!.click();
            expect(ref.close).toHaveBeenCalledWith({ action: 'createKpi' });
        });

        it('is hidden without canAuthorWorkbench (the Business lens)', () => {
            const { fixture } = create(delivered());
            TestBed.inject(LensService).selectLens('business');
            fixture.detectChanges();
            expect(createKpiButton(fixture.nativeElement)).toBeUndefined();
        });

        it('is hidden for a delivered requirement of another kind', () => {
            const { fixture } = create(delivered('report'));
            expect(createKpiButton(fixture.nativeElement)).toBeUndefined();
        });

        it('is hidden for a kpi requirement not yet delivered', () => {
            const { fixture } = create(decideRequirement(buildRequirement('x', 'kpi', 'y'), true));
            expect(createKpiButton(fixture.nativeElement)).toBeUndefined();
        });

        it('while a KPI from it is held for approval, explains that instead of offering Create KPI', async () => {
            const { fixture } = create({ ...delivered(), kpiPending: true } as Requirement);
            const el = fixture.nativeElement as HTMLElement;
            expect(createKpiButton(el)).toBeUndefined();
            expect(el.querySelector('[data-testid="kpi-pending"]')?.textContent).toContain('waiting for approval');
            await expectNoA11yViolations(el);
        });

        it('shows the created KPI id instead once the requirement has one', () => {
            const { fixture } = create({ ...delivered(), kpi: 'refund_exposure' });
            const el = fixture.nativeElement as HTMLElement;
            expect(createKpiButton(el)).toBeUndefined();
            expect(el.textContent).toContain('refund_exposure');
        });
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

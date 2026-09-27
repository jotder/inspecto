import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService, DbBrowserService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { buildRequirement, RequirementsService } from 'app/inspecto/requirement';
import { KPI_DEFINITION_ATTRIBUTES } from '../kpi-reports/kpi-definition-attributes';
import { RequirementKpiDialog } from './requirement-kpi.dialog';

const REQ = buildRequirement('Refund exposure', 'kpi', 'y');
const BODY = { dataset: 'orders', measure: 'sum(amount)', timeField: 'order_date', grain: 'month' };

function create(answer: () => Observable<unknown>) {
    const ref = { close: vi.fn(), disableClose: false, backdropClick: () => of(), keydownEvents: () => of() };
    const createKpi = vi.fn(answer);
    const toastr = { error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [RequirementKpiDialog],
        providers: [
            provideNoopAnimations(),
            { provide: RequirementsService, useValue: { createKpi } },
            { provide: ComponentsService, useValue: { list: vi.fn(() => of([])) } },
            { provide: DbBrowserService, useValue: {} },
            { provide: MatDialogRef, useValue: ref },
            { provide: MAT_DIALOG_DATA, useValue: REQ },
            { provide: InspectoConfirmService, useValue: {} },
            { provide: ToastrService, useValue: toastr },
        ],
    });
    const fixture = TestBed.createComponent(RequirementKpiDialog);
    fixture.detectChanges();
    return { fixture, ref, createKpi, toastr, c: fixture.componentInstance };
}

const refusal = (status: number) =>
    new HttpErrorResponse({ status, error: { error: { message: "kpi dataset 'orders' does not exist" } } });

describe('RequirementKpiDialog (ASSURE-KPI-DEFINITIONS-1)', () => {
    it("asks only the Measure and the period — the KPI editor's own specs — and never calls without them", async () => {
        const { fixture, c, createKpi } = create(() => of({}));
        expect(c.attributes.map((a) => a.key)).toEqual(['dataset', 'measure', 'timeField', 'grain']);
        expect(c.attributes.every((a) => KPI_DEFINITION_ATTRIBUTES.includes(a))).toBe(true);
        c.create();
        expect(createKpi).not.toHaveBeenCalled();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it("calls createKpi and closes with the server's answer", () => {
        const { c, ref, createKpi } = create(() => of({ name: REQ.id }));
        c.schemaForm.form.patchValue(BODY);
        c.create();
        expect(createKpi).toHaveBeenCalledWith(REQ.id, BODY);
        expect(ref.close).toHaveBeenCalledWith({ name: REQ.id });
    });

    it('shows a 422 refusal in place and stays open with the answers kept', async () => {
        const { fixture, c, ref, toastr } = create(() => throwError(() => refusal(422)));
        c.schemaForm.form.patchValue(BODY);
        c.create();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('inspecto-alert')?.textContent).toContain("kpi dataset 'orders' does not exist");
        expect(ref.close).not.toHaveBeenCalled();
        expect(toastr.error).not.toHaveBeenCalled();
        expect(c.schemaForm.value()['measure']).toBe('sum(amount)');
        await expectNoA11yViolations(el);
    });

    it('toasts any other failure via apiErrorMessage, still open', () => {
        const { c, ref, toastr } = create(() => throwError(() => refusal(403)));
        c.schemaForm.form.patchValue(BODY);
        c.create();
        expect(toastr.error).toHaveBeenCalledWith("kpi dataset 'orders' does not exist");
        expect(ref.close).not.toHaveBeenCalled();
    });
});

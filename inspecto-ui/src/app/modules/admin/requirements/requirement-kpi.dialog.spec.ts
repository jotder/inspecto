import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService, DbBrowserService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { buildRequirement } from 'app/inspecto/requirement';
import { RequirementKpiDialog } from './requirement-kpi.dialog';

function create() {
    const ref = { close: vi.fn(), disableClose: false, backdropClick: () => of(), keydownEvents: () => of() };
    TestBed.configureTestingModule({
        imports: [RequirementKpiDialog],
        providers: [
            provideNoopAnimations(),
            { provide: ComponentsService, useValue: { list: vi.fn(() => of([])) } },
            { provide: DbBrowserService, useValue: {} },
            { provide: MatDialogRef, useValue: ref },
            { provide: MAT_DIALOG_DATA, useValue: buildRequirement('Refund exposure', 'kpi', 'y') },
            { provide: InspectoConfirmService, useValue: {} },
        ],
    });
    const fixture = TestBed.createComponent(RequirementKpiDialog);
    fixture.detectChanges();
    return { fixture, ref, c: fixture.componentInstance };
}

describe('RequirementKpiDialog (ASSURE-KPI-DEFINITIONS-1)', () => {
    it('asks only the Measure and the period, and does not close until they are given', async () => {
        const { fixture, c, ref } = create();
        expect(c.attributes.map((a) => a.key)).toEqual(['dataset', 'measure', 'timeField', 'grain']);
        c.create();
        expect(ref.close).not.toHaveBeenCalled();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('closes with the createKpi body', () => {
        const { c, ref } = create();
        c.schemaForm.form.patchValue({ dataset: 'orders', measure: 'sum(amount)', timeField: 'order_date' });
        c.create();
        expect(ref.close).toHaveBeenCalledWith({
            dataset: 'orders',
            measure: 'sum(amount)',
            timeField: 'order_date',
            grain: 'month',
        });
    });
});

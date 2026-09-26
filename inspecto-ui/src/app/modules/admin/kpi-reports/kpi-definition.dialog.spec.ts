import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentDef, ComponentsService, DbBrowserService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { KpiDefinitionDialog } from './kpi-definition.dialog';

const EXISTING: ComponentDef = {
    type: 'kpi',
    name: 'refunds',
    ref: 'kpi/refunds',
    contentHash: 'h1',
    content: {
        title: 'Refunds',
        dataset: 'orders',
        measure: 'sum(amount)',
        timeField: 'order_date',
        grain: 'month',
        direction: 'down',
        bands: { green: 5, amber: 8 },
        format: { style: 'currency', currency: 'SAR' },
        owner: 'alice',
    },
};

function create(existing: ComponentDef | undefined, result: 'ok' | 'refused') {
    const api = {
        list: vi.fn(() => of([])),
        create: vi.fn(() =>
            result === 'ok'
                ? of(EXISTING)
                : throwError(() => ({ status: 422, error: { error: { message: "kpi dataset 'x' does not exist" } } })),
        ),
        update: vi.fn(() => of(EXISTING)),
    };
    const ref = { close: vi.fn(), disableClose: false, backdropClick: () => of(), keydownEvents: () => of() };
    TestBed.configureTestingModule({
        imports: [KpiDefinitionDialog],
        providers: [
            provideNoopAnimations(),
            { provide: ComponentsService, useValue: api },
            { provide: DbBrowserService, useValue: {} },
            { provide: MatDialogRef, useValue: ref },
            { provide: MAT_DIALOG_DATA, useValue: { existing } },
            { provide: InspectoConfirmService, useValue: {} },
            { provide: ToastrService, useValue: { error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(KpiDefinitionDialog);
    fixture.detectChanges();
    return { fixture, api, ref, c: fixture.componentInstance };
}

describe('KpiDefinitionDialog (ASSURE-KPI-DEFINITIONS-1)', () => {
    it('edits in place: keys the form does not ask ride through, with If-Match', () => {
        const { c, api, ref } = create(EXISTING, 'ok');
        c.save();
        const [type, id, body, opts] = api.update.mock.calls[0] as unknown as [
            string,
            string,
            Record<string, unknown>,
            { ifMatch: string },
        ];
        expect([type, id, opts.ifMatch]).toEqual(['kpi', 'refunds', 'h1']);
        expect(body['format']).toEqual({ style: 'currency', currency: 'SAR' });
        expect(body['owner']).toBe('alice');
        expect(body['bands']).toEqual({ green: 5, amber: 8 });
        expect(ref.close).toHaveBeenCalledWith(EXISTING);
    });

    it("shows the server's refusal in place and keeps the dialog open", async () => {
        const { c, fixture, ref } = create(undefined, 'refused');
        c.schemaForm.form.patchValue({ title: 'X', dataset: 'x', measure: 'count', timeField: 'd' });
        c.save();
        fixture.detectChanges();
        expect(c.refusal()).toBeTruthy();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Not saved');
        expect(ref.close).not.toHaveBeenCalled();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

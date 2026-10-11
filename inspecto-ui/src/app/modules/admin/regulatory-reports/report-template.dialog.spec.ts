import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { RegulatoryReportsService, ReportTemplate } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ReportTemplateDialog, ReportTemplateDialogData, templateBody } from './report-template.dialog';

const TEMPLATE: ReportTemplate = {
    id: 'sample-sar',
    title: 'Sample',
    description: null,
    format: 'xml',
    rootElement: 'sar',
    origin: 'built-in',
    delivery: { kind: 'file-drop', dir: 'out/sar' },
    inputs: ['narrative'],
    fields: [{ name: 'narrative', source: 'input.narrative', required: true, maxLength: 20000 }],
};

function create(data: ReportTemplateDialogData = {}, saveTemplate = vi.fn(() => of({ ...TEMPLATE, created: true }))) {
    const ref = { close: vi.fn(), keydownEvents: () => of(), backdropClick: () => of(), disableClose: false };
    TestBed.configureTestingModule({
        imports: [ReportTemplateDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: RegulatoryReportsService, useValue: { saveTemplate } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(async () => true) } },
        ],
    });
    const fixture = TestBed.createComponent(ReportTemplateDialog);
    fixture.detectChanges();
    return { fixture, ref, saveTemplate };
}

describe('ReportTemplateDialog', () => {
    it('creates from a starter, saving under the typed id', async () => {
        const { fixture, ref, saveTemplate } = create();
        const c = fixture.componentInstance;
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('mat-error')).toBeNull();
        c.save(); // id empty
        fixture.detectChanges();
        expect(saveTemplate).not.toHaveBeenCalled();
        expect(el.querySelector('.mat-mdc-form-field-subscript-wrapper mat-error')?.textContent).toContain('id');
        c.form.controls.id.setValue('my-tpl');
        c.save();
        expect(saveTemplate).toHaveBeenCalledWith('my-tpl', expect.objectContaining({ format: 'json' }));
        expect(ref.close).toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });

    it('refuses text that is not a JSON object, before any request', () => {
        const { fixture, saveTemplate } = create();
        const c = fixture.componentInstance;
        c.form.controls.id.setValue('my-tpl');
        c.form.controls.body.setValue('{ nope');
        c.save();
        expect(saveTemplate).not.toHaveBeenCalled();
        expect(c.form.controls.body.getError('json')).toContain('Not valid JSON');
    });

    it('edits a built-in as a Space override: the id is fixed and the body is the template without its id', () => {
        const { fixture, saveTemplate } = create({ template: TEMPLATE });
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.form.controls.id.disabled).toBe(true);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('built-in template');
        expect(JSON.parse(c.form.controls.body.value)).toEqual(templateBody(TEMPLATE));
        c.save();
        expect(saveTemplate).toHaveBeenCalledWith('sample-sar', templateBody(TEMPLATE));
    });

    it('shows the server 422 verbatim and stays open', () => {
        const bad = new HttpErrorResponse({
            status: 422,
            error: { error: { message: 'report template my-tpl is invalid: format must be one of csv, json, xml' } },
        });
        const { fixture, ref } = create(
            {},
            vi.fn(() => throwError(() => bad)),
        );
        const c = fixture.componentInstance;
        c.form.controls.id.setValue('my-tpl');
        c.save();
        fixture.detectChanges();
        expect(ref.close).not.toHaveBeenCalled();
        expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent).toContain(
            'format must be one of',
        );
    });
});

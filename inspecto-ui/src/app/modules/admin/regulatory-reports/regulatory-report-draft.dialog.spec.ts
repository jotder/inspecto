import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ObjectsService, RegulatoryReportsService, ReportTemplate } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RegulatoryReportDraftData, RegulatoryReportDraftDialog } from './regulatory-report-draft.dialog';

const TEMPLATE: ReportTemplate = {
    id: 'sample-sar',
    title: 'Sample suspicious activity report',
    description: 'Illustrative only',
    format: 'xml',
    origin: 'built-in',
    delivery: { kind: 'file-drop', dir: 'regulatory-submissions/sample-sar' },
    inputs: ['reportingEntity', 'narrative', 'activityPeriod'],
    fields: [
        { name: 'reportingEntity', source: 'input.reportingEntity', required: true, maxLength: 10 },
        { name: 'narrative', source: 'input.narrative', required: true, maxLength: 20000 },
        { name: 'activityPeriod', source: 'input.activityPeriod', required: false, maxLength: 0 },
    ],
};

function create(
    draft = vi.fn(() => of({ id: 'rr-1', status: 'draft' })),
    data: Partial<RegulatoryReportDraftData> = {},
) {
    const ref = { close: vi.fn(), keydownEvents: () => of(), backdropClick: () => of(), disableClose: false };
    const objects = {
        list: vi.fn(() =>
            of([
                { id: 'CASE-1', objectType: 'CASE', title: 'Structuring suspected', status: 'OPEN' },
                { id: 'INC-1', objectType: 'INCIDENT', title: 'Cash deposits', status: 'IDENTIFIED' },
            ]),
        ),
    };
    TestBed.configureTestingModule({
        imports: [RegulatoryReportDraftDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: MAT_DIALOG_DATA, useValue: { templates: [TEMPLATE], ...data } },
            { provide: RegulatoryReportsService, useValue: { draft } },
            { provide: ObjectsService, useValue: objects },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(async () => true) } },
        ],
    });
    const fixture = TestBed.createComponent(RegulatoryReportDraftDialog);
    fixture.detectChanges();
    return { fixture, ref, draft, objects };
}

describe('RegulatoryReportDraftDialog', () => {
    it('asks exactly the inputs the template reads, with its required and length rules', async () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        expect(c.inputKeys()).toEqual(['reportingEntity', 'narrative', 'activityPeriod']);
        const labels = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('mat-label')).map((l) =>
            (l.textContent ?? '').trim(),
        );
        expect(labels).toContain('Reporting entity');
        expect(labels).toContain('Activity period');
        expect(c.inputControl('narrative').hasError('required')).toBe(true);
        expect(c.inputControl('activityPeriod').valid).toBe(true);
        c.inputControl('reportingEntity').setValue('x'.repeat(11));
        expect(c.inputControl('reportingEntity').hasError('maxlength')).toBe(true);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('offers only Cases when raising from a Case, only Incidents when raising from an Incident', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        expect(c.subjectOptions().map((o) => o.value)).toEqual(['CASE-1']);
        c.setKind('incident');
        expect(c.subjectOptions().map((o) => o.value)).toEqual(['INC-1']);
    });

    it('refuses an incomplete draft on screen and sends nothing', () => {
        const { fixture, draft } = create();
        const alerts0 = (fixture.nativeElement as HTMLElement).querySelectorAll('[role="alert"]');
        expect(alerts0.length, 'no error before a submit attempt').toBe(0);
        fixture.componentInstance.save();
        fixture.detectChanges();
        expect(draft).not.toHaveBeenCalled();
        const alerts = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('[role="alert"]')).map(
            (a) => a.textContent ?? '',
        );
        expect(alerts.some((t) => t.includes('Pick the Case'))).toBe(true);
    });

    it('drafts with exactly one subject and only the inputs typed, then closes with the draft', () => {
        const { fixture, draft, ref } = create(undefined, { subjectKind: 'case', subjectId: 'CASE-1' });
        const c = fixture.componentInstance;
        c.inputControl('reportingEntity').setValue('Bank');
        c.inputControl('narrative').setValue('Cash split');
        c.save();
        expect(draft).toHaveBeenCalledWith({
            template: 'sample-sar',
            caseId: 'CASE-1',
            inputs: { reportingEntity: 'Bank', narrative: 'Cash split' },
            reason: undefined,
        });
        expect(ref.close).toHaveBeenCalledWith({ id: 'rr-1', status: 'draft' });
    });

    it("shows the server's render refusal in place and stays open", () => {
        const draft = vi.fn(() =>
            throwError(
                () =>
                    new HttpErrorResponse({
                        status: 422,
                        error: { error: { message: "the report does not render: required field 'subjectTitle'" } },
                    }),
            ),
        );
        const { fixture, ref } = create(draft, { subjectKind: 'case', subjectId: 'CASE-1' });
        const c = fixture.componentInstance;
        c.inputControl('reportingEntity').setValue('Bank');
        c.inputControl('narrative').setValue('Cash split');
        c.save();
        fixture.detectChanges();
        expect(ref.close).not.toHaveBeenCalled();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('does not render');
    });
});

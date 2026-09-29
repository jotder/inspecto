import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import { JobsService, JobUpsert } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ScheduleExportData, ScheduleExportDialog } from './schedule-export.dialog';
import { SCHEDULE_EXPORT_ATTRIBUTES } from './schedule-export-attributes';

function create(data: ScheduleExportData, jobs: object = {}) {
    TestBed.configureTestingModule({
        imports: [ScheduleExportDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: JobsService, useValue: jobs },
            { provide: ToastrService, useValue: {} },
        ],
    });
    const fixture = TestBed.createComponent(ScheduleExportDialog);
    fixture.detectChanges();
    return fixture;
}

describe('ScheduleExportDialog', () => {
    it('create mode blocks a duplicate id inline and has no a11y violations', async () => {
        const fixture = create({
            dashboardId: 'cdr_overview',
            dashboardName: 'CDR Overview',
            existingNames: ['daily_cdr_export'],
        });
        const name = fixture.componentInstance.schemaForm.form.get('name')!;
        name.setValue('daily_cdr_export');
        expect(name.hasError('duplicate')).toBe(true);
        name.setValue('weekly_export');
        expect(name.hasError('duplicate')).toBe(false);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('edit mode locks the id and prefills format/cron/recipients from the job params', () => {
        const fixture = create({
            dashboardId: 'cdr_overview',
            dashboardName: 'CDR Overview',
            job: {
                name: 'daily_cdr_export',
                type: 'report',
                cron: '0 0 6 * * *',
                onPipeline: null,
                enabled: true,
                params: { dashboardId: 'cdr_overview', format: 'pdf', recipients: ['ops@x.com', 'fin@x.com'] },
            },
        });
        const c = fixture.componentInstance;
        expect(c.schemaForm.form.get('name')!.disabled).toBe(true);
        expect(c.schemaForm.form.get('format')!.value).toBe('pdf');
        expect(c.schemaForm.form.get('recipients')!.value).toBe('ops@x.com, fin@x.com');
    });

    it('offers xlsx and sends attach + recipients in the shape the report Job reads', () => {
        let sent: JobUpsert | undefined;
        const fixture = create(
            { dashboardId: 'cdr_overview', dashboardName: 'CDR Overview' },
            { create: (b: JobUpsert) => ((sent = b), of(b)) },
        );
        const c = fixture.componentInstance;
        const format = SCHEDULE_EXPORT_ATTRIBUTES.find((a) => a.key === 'format')!;
        expect(format.options!.map((o) => o.value)).toContain('xlsx');
        c.schemaForm.form.patchValue({
            name: 'weekly_xlsx',
            format: 'xlsx',
            recipients: 'ops@x.com, fin@x.com',
            attach: true,
        });
        c.save();
        expect(sent!.params).toMatchObject({ format: 'xlsx', recipients: 'ops@x.com,fin@x.com', attach: 'true' });
    });

    it('edit mode prefills attach and a comma-string recipients param', () => {
        const fixture = create({
            dashboardId: 'cdr_overview',
            dashboardName: 'CDR Overview',
            job: {
                name: 'daily_cdr_export',
                type: 'report',
                cron: null,
                onPipeline: null,
                enabled: true,
                params: { format: 'xlsx', recipients: 'ops@x.com,fin@x.com', attach: 'true' },
            },
        });
        const f = fixture.componentInstance.schemaForm.form;
        expect(f.get('recipients')!.value).toBe('ops@x.com, fin@x.com');
        expect(f.get('attach')!.value).toBe(true);
    });
});

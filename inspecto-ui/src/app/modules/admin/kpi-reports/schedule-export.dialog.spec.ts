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
            job: {
                name: 'daily_cdr_export',
                type: 'report',
                cron: '0 0 6 * * *',
                onPipeline: null,
                enabled: true,
                params: {
                    scope: 'dataset',
                    dataset: 'cdr_daily',
                    format: 'xlsx',
                    recipients: ['ops@x.com', 'fin@x.com'],
                },
            },
        });
        const c = fixture.componentInstance;
        expect(c.schemaForm.form.get('name')!.disabled).toBe(true);
        expect(c.schemaForm.form.get('format')!.value).toBe('xlsx');
        expect(c.schemaForm.form.get('dataset')!.value).toBe('cdr_daily');
        expect(c.schemaForm.form.get('recipients')!.value).toBe('ops@x.com, fin@x.com');
    });

    // Fails on the old dialog, which sent {reportKind:'dashboard', dashboardId, attach} and no scope/dataset/out_dir
    // — a payload ReportJob ignores (it delivered nothing).
    it('sends the dataset-scope payload ReportJob delivers: scope, dataset, out_dir exports/<id>, csv|xlsx only', () => {
        let sent: JobUpsert | undefined;
        const fixture = create({}, { create: (b: JobUpsert) => ((sent = b), of(b)) });
        const c = fixture.componentInstance;
        const format = SCHEDULE_EXPORT_ATTRIBUTES.find((a) => a.key === 'format')!;
        expect(format.options!.map((o) => o.value)).toEqual(['csv', 'xlsx']);
        expect(SCHEDULE_EXPORT_ATTRIBUTES.map((a) => a.key)).not.toContain('attach');
        c.schemaForm.form.patchValue({
            name: 'weekly_xlsx',
            dataset: 'cdr_daily',
            format: 'xlsx',
            recipients: 'ops@x.com, fin@x.com',
        });
        c.save();
        expect(sent!.params).toEqual({
            scope: 'dataset',
            dataset: 'cdr_daily',
            format: 'xlsx',
            out_dir: 'exports/weekly_xlsx',
            recipients: 'ops@x.com,fin@x.com',
        });
    });

    it('refuses to save without a Dataset', () => {
        let called = false;
        const fixture = create({}, { create: () => ((called = true), of({})) });
        fixture.componentInstance.schemaForm.form.patchValue({ name: 'x_export', dataset: '' });
        fixture.componentInstance.save();
        expect(called).toBe(false);
    });

    it('edit mode prefills a comma-string recipients param', () => {
        const fixture = create({
            job: {
                name: 'daily_cdr_export',
                type: 'report',
                cron: null,
                onPipeline: null,
                enabled: true,
                params: { scope: 'dataset', dataset: 'cdr_daily', format: 'xlsx', recipients: 'ops@x.com,fin@x.com' },
            },
        });
        expect(fixture.componentInstance.schemaForm.form.get('recipients')!.value).toBe('ops@x.com, fin@x.com');
    });
});

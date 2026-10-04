import { AfterViewInit, ChangeDetectionStrategy, Component, inject, signal, ViewChild } from '@angular/core';
import { AbstractControl, ValidatorFn } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, JobDetail, JobsService, JobUpsert } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { datasetOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { SCHEDULE_EXPORT_ATTRIBUTES } from './schedule-export-attributes';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

/** Dialog input: an existing schedule to edit (absent ⇒ create). */
export interface ScheduleExportData {
    /** An existing report-job ⇒ edit; absent ⇒ create. */
    job?: JobDetail;
    /** Ids already in use — on create the schedule id rejects a duplicate inline (product-wide rule). */
    existingNames?: string[];
}
export interface ScheduleExportResult {
    saved?: JobDetail;
}

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) =>
        set.has(
            String(c.value ?? '')
                .trim()
                .toLowerCase(),
        )
            ? { duplicate: true }
            : null;
}

/** A stored `recipients` param: a comma string (current) or a string[] (schedules saved before 2026-09-29). */
function recipientsText(raw: unknown): string {
    if (Array.isArray(raw)) return raw.join(', ');
    return typeof raw === 'string'
        ? raw
              .split(',')
              .map((r) => r.trim())
              .filter(Boolean)
              .join(', ')
        : '';
}

/**
 * Schedule a Dataset export — no new entity: a scheduled export IS a Job with
 * `type: 'report'` and `params: {scope: 'dataset', dataset, format, out_dir, recipients}`. `out_dir` is the
 * fixed per-Space convention `exports/<schedule id>` (the id is the Job name: immutable, the storage key),
 * resolved by the server under the Space config root through the path jail — the operator never types a path.
 * `ReportJob` has no dashboard scope, so a Dashboard export cannot be scheduled yet. The existing scheduler
 * (cron/manual, run history, live-tail) is reused as-is. SchemaForm-driven, mirrors
 * {@link JobFormDialog}'s shape.
 */
@Component({
    selector: 'app-schedule-export-dialog',
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, InspectoAlertComponent, InspectoSchemaFormComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>
            {{ isEdit ? 'Edit scheduled export "' + data.job!.name + '"' : 'Schedule a Dataset export' }}
        </h2>
        <mat-dialog-content>
            @if (writesDisabled()) {
                <inspecto-alert variant="warning" title="Writes are disabled">
                    The server is running read-only — the schedule was not saved.
                </inspecto-alert>
            }
            <inspecto-schema-form
                [specs]="attributes"
                [initial]="initialValue"
                [optionLoaders]="optionLoaders"
                (submitted)="save()"
            ></inspecto-schema-form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button [disabled]="saving()" (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" [disabled]="saving()" (click)="save()">
                {{ isEdit ? 'Save' : 'Schedule' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class ScheduleExportDialog implements AfterViewInit {
    private api = inject(JobsService);
    private ref = inject(MatDialogRef<ScheduleExportDialog, ScheduleExportResult>);
    private confirm = inject(InspectoConfirmService);

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(this.ref, () => this.schemaForm?.isDirty() ?? false, this.confirm);
    private toastr = inject(ToastrService);
    readonly data = inject<ScheduleExportData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    readonly isEdit = !!this.data.job;
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly attributes = SCHEDULE_EXPORT_ATTRIBUTES;
    readonly optionLoaders = { dataset: datasetOptionLoader() };

    readonly initialValue: Record<string, unknown> | undefined = this.data.job
        ? {
              name: this.data.job.name,
              dataset: String(this.data.job.params?.['dataset'] ?? ''),
              format: (this.data.job.params?.['format'] as string) ?? 'csv',
              scheduleMode: this.data.job.cron ? 'cron' : 'manual',
              cron: this.data.job.cron ?? '0 0 6 * * *',
              recipients: recipientsText(this.data.job.params?.['recipients']),
              enabled: this.data.job.enabled,
          }
        : undefined;

    ngAfterViewInit(): void {
        const nameCtrl = this.schemaForm.form.get('name');
        if (this.isEdit) {
            // The id is immutable once created (it is the storage key).
            nameCtrl?.disable({ emitEvent: false });
        } else if (this.data.existingNames?.length) {
            // Block a duplicate id inline rather than relying on the server 409 (product-wide form rule).
            nameCtrl?.addValidators(uniqueNameValidator(this.data.existingNames));
            nameCtrl?.updateValueAndValidity({ emitEvent: false });
        }
    }

    save(): void {
        if (!this.schemaForm.validate()) return;
        const v = this.schemaForm.value() as {
            name?: string;
            dataset: string;
            format: 'csv' | 'xlsx';
            scheduleMode: 'cron' | 'manual';
            cron?: string;
            recipients?: string;
            enabled?: boolean;
        };
        const recipients = String(v.recipients ?? '')
            .split(',')
            .map((r) => r.trim())
            .filter(Boolean);
        const name = this.isEdit ? this.data.job!.name : String(v.name ?? '').trim();
        const body: JobUpsert = {
            name,
            type: 'report',
            cron: v.scheduleMode === 'cron' ? String(v.cron ?? '').trim() : null,
            onPipeline: null,
            enabled: v.enabled !== false,
            // The report Job reads `recipients` as ONE comma-separated string; `out_dir` makes it deliver a file.
            params: {
                scope: 'dataset',
                dataset: String(v.dataset ?? '').trim(),
                format: v.format,
                out_dir: 'exports/' + name,
                recipients: recipients.join(','),
            },
        };
        this.saving.set(true);
        const call = this.isEdit ? this.api.update(body.name, body) : this.api.create(body);
        call.subscribe({
            next: (saved) => this.ref.close({ saved }),
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                else this.toastr.error(apiErrorMessage(e, 'Could not save the scheduled export.'));
            },
        });
    }
}

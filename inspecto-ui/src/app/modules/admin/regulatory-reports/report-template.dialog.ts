import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
    AbstractControl,
    FormControl,
    FormGroup,
    ReactiveFormsModule,
    ValidationErrors,
    Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { apiErrorMessage, RegulatoryReportsService, ReportTemplate } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

/** What the pane hands the dialog: the template to edit (a built-in becomes a Space override), or none to create. */
export interface ReportTemplateDialogData {
    template?: ReportTemplate;
}

const STARTER = {
    title: 'My regulatory report',
    format: 'json',
    delivery: { kind: 'file-drop', dir: 'regulatory-submissions/my-report' },
    fields: [
        { name: 'reportId', source: 'report.id', required: true, maxLength: 64 },
        { name: 'subjectTitle', source: 'subject.title', required: true, maxLength: 400 },
        { name: 'narrative', source: 'input.narrative', required: true, maxLength: 20000 },
    ],
};

/** The editable body of a template: everything the server's document takes except its id (the path names that). */
export function templateBody(t: ReportTemplate): Record<string, unknown> {
    const body: Record<string, unknown> = { title: t.title };
    if (t.description) body['description'] = t.description;
    body['format'] = t.format;
    if (t.rootElement) body['rootElement'] = t.rootElement;
    body['delivery'] = t.delivery;
    body['fields'] = t.fields;
    return body;
}

function jsonObject(c: AbstractControl<string>): ValidationErrors | null {
    try {
        const v: unknown = JSON.parse(c.value);
        return v !== null && typeof v === 'object' && !Array.isArray(v)
            ? null
            : { json: 'The template must be a JSON object.' };
    } catch (e) {
        return { json: `Not valid JSON: ${(e as Error).message}` };
    }
}

/**
 * Author a **Report Template** — `PUT /regulatory-reports/templates/{id}`. The id (create only) and the template as
 * JSON; the server validates it (every problem named, 422) and jails the drop directory (403) — its words are shown
 * verbatim and nothing is saved. Editing a built-in saves a Space template of the same id, which wins over it.
 */
@Component({
    selector: 'app-report-template-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ data.template ? 'Edit Report Template' : 'New Report Template' }}</h2>
        <mat-dialog-content class="flex flex-col gap-3 pt-2" style="min-width: 36rem">
            <form [formGroup]="form" class="flex flex-col gap-3" (ngSubmit)="save()">
                @if (data.template?.origin === 'built-in') {
                    <inspecto-alert variant="info"
                        >This is a built-in template. Saving creates this Space's own template with the same id, which
                        replaces it here.</inspecto-alert
                    >
                }
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Template id</mat-label>
                    <input matInput formControlName="id" autocomplete="off" />
                    @if (form.controls.id.hasError('required')) {
                        <mat-error>An id is required.</mat-error>
                    } @else if (form.controls.id.hasError('pattern')) {
                        <mat-error>Lower-case letters, digits and hyphens, 2 to 64 characters.</mat-error>
                    }
                </mat-form-field>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Template (JSON)</mat-label>
                    <textarea matInput rows="16" class="font-mono" spellcheck="false" formControlName="body"></textarea>
                    @if (form.controls.body.hasError('required')) {
                        <mat-error>The template is required.</mat-error>
                    } @else if (form.controls.body.hasError('json')) {
                        <mat-error>{{ form.controls.body.getError('json') }}</mat-error>
                    }
                </mat-form-field>
            </form>
            @if (error()) {
                <inspecto-alert variant="error" title="Not saved">{{ error() }}</inspecto-alert>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="saving()" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class ReportTemplateDialog {
    private readonly api = inject(RegulatoryReportsService);
    private readonly ref = inject(MatDialogRef<ReportTemplateDialog, ReportTemplate>);
    private readonly confirm = inject(InspectoConfirmService);
    readonly data = inject<ReportTemplateDialogData>(MAT_DIALOG_DATA);

    readonly form = new FormGroup({
        id: new FormControl(
            { value: this.data.template?.id ?? '', disabled: !!this.data.template },
            { nonNullable: true, validators: [Validators.required, Validators.pattern(/^[a-z0-9][a-z0-9-]{1,63}$/)] },
        ),
        body: new FormControl(
            JSON.stringify(this.data.template ? templateBody(this.data.template) : STARTER, null, 2),
            { nonNullable: true, validators: [Validators.required, jsonObject] },
        ),
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);
    readonly saving = signal(false);
    readonly error = signal<string | null>(null);

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const { id, body } = this.form.getRawValue();
        this.saving.set(true);
        this.error.set(null);
        this.api.saveTemplate(id, JSON.parse(body) as Record<string, unknown>).subscribe({
            next: (t) => this.ref.close(t),
            error: (e) => {
                this.saving.set(false);
                // The server's own words: every template problem, or the jail's refusal.
                this.error.set(apiErrorMessage(e, 'The template could not be saved'));
            },
        });
    }
}

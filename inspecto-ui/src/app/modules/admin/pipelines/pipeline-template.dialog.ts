import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { InspectoSampleValueDirective } from 'app/inspecto/components/sample-value.directive';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';

export interface PipelineTemplateData {
    /** The pipeline being copied — shown so the operator can see what the template is based on. */
    source: string;
    /** Pipeline ids already registered; a duplicate is blocked inline rather than via the server 409. */
    existingNames: string[];
}

export interface PipelineTemplateResultData {
    id: string;
    displayName?: string;
}

/**
 * Save-as-template dialog — names the copy and explains what a template is before it is created.
 *
 * <p>The explanation is not decoration: "save as" normally means "a copy that does what the original
 * does", and here it deliberately does NOT. The operator needs to know before they click that the copy
 * reads its own inbox and writes its own output, and that it will not run until they clear the template
 * flag — otherwise they will point it at production data expecting the copy to be inert.
 */
@Component({
    selector: 'app-pipeline-template-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        PenFieldComponent,
        InspectoAlertComponent,
        InspectoDialogResizeDirective,
        InspectoSampleValueDirective,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title inspectoDialogResize>Save '{{ data.source }}' as a template</h2>
        <mat-dialog-content>
            <inspecto-alert variant="info" title="A template is not runnable">
                The copy keeps this pipeline's shape — parsing, schema, output format and settings — but collects from
                its own folder and writes to its own tables, so it can never touch '{{ data.source }}'. It stays
                unrunnable until you clear the template flag.
            </inspecto-alert>
            <form [formGroup]="form" (ngSubmit)="save()" class="mt-4 flex flex-col gap-3">
                <inspecto-pen-field
                    cdkFocusInitial
                    label="Template id"
                    [required]="true"
                    [mono]="true"
                    placeholder="e.g. orders_eu"
                    help="Lowercase letters, digits and underscores. This is the permanent identity."
                    formControlName="id"
                    [error]="idError()"
                ></inspecto-pen-field>
                <inspecto-pen-field
                    label="Display name (optional)"
                    placeholder="e.g. Orders (EU)"
                    help="Shown in lists. Defaults to the id."
                    formControlName="displayName"
                ></inspecto-pen-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()">Create template</button>
        </mat-dialog-actions>
    `,
})
export class PipelineTemplateDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<PipelineTemplateDialog, PipelineTemplateResultData>);
    private confirm = inject(InspectoConfirmService);
    readonly data = inject<PipelineTemplateData>(MAT_DIALOG_DATA);

    readonly form = this.fb.group({
        id: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[a-z0-9][a-z0-9_]*$/),
                uniqueNameValidator(() => this.data.existingNames),
            ],
        ],
        displayName: [''],
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    /** The id row's error lines: shown once the row was left or Create template was pressed. */
    idError(): string {
        const c = this.form.controls.id;
        if (!c.touched) return '';
        const lines: string[] = [];
        if (c.hasError('required')) lines.push('An id is required.');
        if (c.hasError('pattern'))
            lines.push('Lowercase letters, digits and underscores only; start with a letter or digit.');
        if (c.hasError('duplicate')) lines.push('A pipeline with this id already exists.');
        return lines.join(' ');
    }

    save(): void {
        // Normalise BEFORE validating, so the pattern judges the value we actually send. Otherwise a
        // pasted id with a stray space fails the lowercase-letters rule, and the error names a problem
        // the operator cannot see in the field.
        this.form.controls.id.setValue(String(this.form.controls.id.value ?? '').trim());
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const displayName = String(this.form.controls.displayName.value ?? '').trim() || undefined;
        this.ref.close({ id: String(this.form.controls.id.value ?? ''), displayName });
    }
}

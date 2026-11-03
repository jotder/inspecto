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

export interface PipelineChangeIdData {
    /** The pipeline's current identity — the thing this migration moves. */
    id: string;
    /** Its current display name, shown so the operator recognises which pipeline this is. */
    displayName: string;
    /** Pipeline ids already registered; a duplicate is blocked inline rather than via the server 409. */
    existingNames: string[];
}

export interface PipelineChangeIdResultData {
    newId: string;
}

/**
 * Change-id dialog — the FULL identity migration (T3), distinct from {@link PipelineRenameDialog}'s
 * display-only relabel. Every artifact keyed by the old id (config file, commit log, audit CSVs, the
 * DuckDB status mirror, the acquisition ledger) moves to the new one — real risk the label rename
 * carries none of, so this asks the operator to type the current id before it enables Change id,
 * the same typed-confirmation shape {@link InspectoConfirmService} uses for destructive actions.
 */
@Component({
    selector: 'app-pipeline-change-id-dialog',
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
        <h2 mat-dialog-title inspectoDialogResize>Change pipeline id</h2>
        <mat-dialog-content>
            <inspecto-alert variant="warning" title="This moves the identity itself">
                Unlike Rename&hellip; (which only changes the label), this moves the config file, run history, dedup
                ledger and data catalog to the new id. The pipeline must be inactive and not currently running;
                dependent configs (enrichment/job triggers, targets, dataset references) are rewritten on a best-effort
                basis.
            </inspecto-alert>
            <form [formGroup]="form" (ngSubmit)="save()" class="mt-4 flex flex-col gap-3">
                <inspecto-pen-field
                    cdkFocusInitial
                    label="New id"
                    [required]="true"
                    [mono]="true"
                    placeholder="e.g. retail_orders_eu"
                    help="Lowercase letters, digits and underscores. This is the permanent identity."
                    formControlName="newId"
                    [error]="newIdError()"
                ></inspecto-pen-field>
                <inspecto-pen-field
                    [label]="confirmLabel"
                    [required]="true"
                    [mono]="true"
                    formControlName="confirmId"
                    [error]="confirmIdError()"
                ></inspecto-pen-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="warn" [disabled]="!confirmed()" (click)="save()">
                Change id
            </button>
        </mat-dialog-actions>
    `,
})
export class PipelineChangeIdDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<PipelineChangeIdDialog, PipelineChangeIdResultData>);
    private confirm = inject(InspectoConfirmService);
    readonly data = inject<PipelineChangeIdData>(MAT_DIALOG_DATA);

    readonly form = this.fb.group({
        newId: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[a-z0-9][a-z0-9_]*$/),
                uniqueNameValidator(() => this.data.existingNames),
            ],
        ],
        confirmId: ['', [Validators.required, this.matchesCurrentId()]],
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    readonly confirmLabel = `Type "${this.data.id}" to confirm`;

    /** The new id row's error lines: shown once the row was left or Change id was pressed. */
    newIdError(): string {
        const c = this.form.controls.newId;
        if (!c.touched) return '';
        const lines: string[] = [];
        if (c.hasError('required')) lines.push('A new id is required.');
        if (c.hasError('pattern'))
            lines.push('Lowercase letters, digits and underscores only; start with a letter or digit.');
        if (c.hasError('duplicate')) lines.push('A pipeline with this id already exists.');
        return lines.join(' ');
    }

    /** The confirm row's error line: shown once the row was left or Change id was pressed. */
    confirmIdError(): string {
        const c = this.form.controls.confirmId;
        return c.touched && c.hasError('mismatch') ? "Doesn't match the current id." : '';
    }

    private matchesCurrentId() {
        return (control: { value: unknown }) =>
            String(control.value ?? '') === this.data.id ? null : { mismatch: true };
    }

    confirmed(): boolean {
        return String(this.form.controls.confirmId.value ?? '') === this.data.id;
    }

    save(): void {
        // Normalise BEFORE validating (see PipelineTemplateDialog) so the pattern judges the value sent.
        this.form.controls.newId.setValue(String(this.form.controls.newId.value ?? '').trim());
        if (this.form.invalid || !this.confirmed()) {
            this.form.markAllAsTouched();
            return;
        }
        this.ref.close({ newId: String(this.form.controls.newId.value ?? '') });
    }
}

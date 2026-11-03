import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';

export interface WidgetSaveData {
    suggestedId: string;
    /** True when editing — the id is fixed and the field is read-only. */
    lockId: boolean;
    description?: string;
    /** Ids already in use — on create the id control rejects a duplicate inline (product-wide rule). */
    existingNames?: string[];
}

export interface WidgetSaveResult {
    name: string;
    description?: string;
}

/**
 * Save-as-widget dialog — names the widget (+ a description for the library gallery) and closes with the
 * result (or undefined on cancel).
 *
 * ⚠ **No tags field, deliberately (D7 (c), 2026-07-27).** A widget's chips are a projection of the central
 * tag assignment store, so tags are applied with the Tags button on the library card. A comma-separated
 * field here would be a SECOND writer that resurrects config-only tags on every save — and it minted
 * unregistered names, which is exactly what the shared vocabulary exists to prevent.
 */
@Component({
    selector: 'app-widget-save-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, PenFieldComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Save widget</h2>
        <mat-dialog-content>
            <form [formGroup]="form" (ngSubmit)="save()" class="flex flex-col gap-3">
                <inspecto-pen-field
                    cdkFocusInitial
                    label="Widget id"
                    [required]="true"
                    [readOnly]="data.lockId"
                    placeholder="e.g. duration_by_tariff"
                    formControlName="name"
                    [error]="nameError()"
                ></inspecto-pen-field>
                <inspecto-pen-field label="Description" formControlName="description"></inspecto-pen-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class WidgetSaveDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<WidgetSaveDialog, WidgetSaveResult>);
    private confirm = inject(InspectoConfirmService);

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);
    readonly data = inject<WidgetSaveData>(MAT_DIALOG_DATA);

    readonly form = this.fb.group({
        name: [
            this.data.suggestedId,
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                ...(this.data.lockId ? [] : [uniqueNameValidator(() => this.data.existingNames ?? [])]),
            ],
        ],
        description: [this.data.description ?? ''],
    });

    /** The id row's error line: shown once the row was left or Save was pressed. */
    nameError(): string {
        const c = this.form.controls.name;
        if (!c.touched) return '';
        if (c.hasError('pattern')) return 'Letters, digits, dot, dash, underscore; start alphanumeric.';
        if (c.hasError('duplicate')) return 'A widget with this id already exists.';
        return '';
    }

    save(): void {
        const ctrl = this.form.controls.name;
        const name = String(ctrl.value ?? '').trim();
        if (!name || ctrl.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const description = String(this.form.controls.description.value ?? '').trim() || undefined;
        this.ref.close({ name, description });
    }
}

import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface WidgetSaveData {
    suggestedId: string;
    /** True when editing — the id is fixed and the field is read-only. */
    lockId: boolean;
    description?: string;
    /** Ids already in use — on create the id control rejects a duplicate inline (product-wide rule). */
    existingNames?: string[];
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
    imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Save widget</h2>
        <mat-dialog-content>
            <form [formGroup]="form" (ngSubmit)="save()" class="flex flex-col gap-3">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Widget id</mat-label>
                    <input
                        matInput
                        formControlName="name"
                        [readonly]="data.lockId"
                        placeholder="e.g. duration_by_tariff"
                        cdkFocusInitial
                    />
                    @if (form.controls.name.hasError('pattern')) {
                        <mat-error>Letters, digits, dot, dash, underscore; start alphanumeric.</mat-error>
                    }
                    @if (form.controls.name.hasError('duplicate')) {
                        <mat-error>A widget with this id already exists.</mat-error>
                    }
                </mat-form-field>
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Description</mat-label>
                    <input matInput formControlName="description" />
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close>Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class WidgetSaveDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<WidgetSaveDialog, WidgetSaveResult>);
    readonly data = inject<WidgetSaveData>(MAT_DIALOG_DATA);

    readonly form = this.fb.group({
        name: [
            this.data.suggestedId,
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                ...(this.data.lockId ? [] : [uniqueNameValidator(this.data.existingNames ?? [])]),
            ],
        ],
        description: [this.data.description ?? ''],
    });

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

import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { INCIDENT_TAXONOMY, joinCategory, splitCategory } from './incident-taxonomy';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { InspectoOptionPickerComponent, pickerOptions } from 'app/inspecto/components/option-picker.component';

/**
 * 3-layer categorization picker (cascading L1 → L2 → L3 selects over {@link INCIDENT_TAXONOMY}).
 * Used by Accept (Identified → Diagnosing requires a category) and available for re-categorizing.
 * Closes with the joined category path, or null on cancel.
 */
@Component({
    selector: 'app-categorize-dialog',
    standalone: true,
    imports: [InspectoOptionPickerComponent, ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Categorize incident</h2>
        <mat-dialog-content class="flex flex-col gap-3 pt-2">
            @if (data.hint) {
                <p class="text-secondary text-sm">{{ data.hint }}</p>
            }
            <form [formGroup]="form" class="flex flex-col gap-3">
                <div>
                    <inspecto-option-picker
                        label="Category"
                        [options]="opts(l1Options)"
                        formControlName="l1"
                        (ngModelChange)="onL1()"
                    />
                    @if (form.controls.l1.hasError('required') && form.controls.l1.touched) {
                        <p class="text-warn m-0 text-xs" role="alert">Category is required.</p>
                    }
                </div>
                <div>
                    <inspecto-option-picker
                        label="Subcategory"
                        [options]="opts(l2Options())"
                        formControlName="l2"
                        (ngModelChange)="onL2()"
                    />
                    @if (form.controls.l2.hasError('required') && form.controls.l2.touched) {
                        <p class="text-warn m-0 text-xs" role="alert">Subcategory is required.</p>
                    }
                </div>
                <div>
                    <inspecto-option-picker label="Detail" [options]="opts(l3Options())" formControlName="l3" />
                    @if (form.controls.l3.hasError('required') && form.controls.l3.touched) {
                        <p class="text-warn m-0 text-xs" role="alert">Detail is required.</p>
                    }
                </div>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" (click)="apply()">Apply</button>
        </mat-dialog-actions>
    `,
})
export class CategorizeDialog {
    private ref = inject(MatDialogRef<CategorizeDialog>);
    private confirm = inject(InspectoConfirmService);

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);
    private fb = inject(FormBuilder);
    readonly data = inject<{ current?: string; hint?: string }>(MAT_DIALOG_DATA);

    readonly l1Options = Object.keys(INCIDENT_TAXONOMY);
    readonly opts = pickerOptions;

    readonly form = this.fb.group({
        l1: ['', Validators.required],
        l2: ['', Validators.required],
        l3: ['', Validators.required],
    });

    constructor() {
        const [l1, l2, l3] = splitCategory(this.data.current ?? '');
        if (l1 && INCIDENT_TAXONOMY[l1]) {
            this.form.patchValue({ l1 });
            if (l2 && INCIDENT_TAXONOMY[l1][l2]) {
                this.form.patchValue({ l2 });
                if (l3 && INCIDENT_TAXONOMY[l1][l2].includes(l3)) this.form.patchValue({ l3 });
            }
        }
    }

    l2Options(): string[] {
        const l1 = this.form.controls.l1.value ?? '';
        return l1 && INCIDENT_TAXONOMY[l1] ? Object.keys(INCIDENT_TAXONOMY[l1]) : [];
    }

    l3Options(): string[] {
        const l1 = this.form.controls.l1.value ?? '';
        const l2 = this.form.controls.l2.value ?? '';
        return l1 && l2 && INCIDENT_TAXONOMY[l1]?.[l2] ? INCIDENT_TAXONOMY[l1][l2] : [];
    }

    onL1(): void {
        this.form.patchValue({ l2: '', l3: '' });
    }

    onL2(): void {
        this.form.patchValue({ l3: '' });
    }

    apply(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        this.ref.close(joinCategory(v.l1!, v.l2!, v.l3!));
    }
}

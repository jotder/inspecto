import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { RequirementKind } from 'app/inspecto/requirement';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { InspectoOptionPickerComponent } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';

export interface RequirementFormResult {
    title: string;
    kind: RequirementKind;
    description: string;
    targetComponent?: string;
}

const KINDS: { value: RequirementKind; label: string }[] = [
    { value: 'kpi', label: 'KPI' },
    { value: 'report', label: 'Report' },
    { value: 'reconciliation', label: 'Reconciliation' },
    { value: 'rule', label: 'Decision rule' },
];

/** Submit a new Requirement — ask-the-minimum: title, kind, and a free-text description. No id is
 *  shown (nothing else references a requirement by id, so there's nothing to name). */
@Component({
    selector: 'app-requirement-form-dialog',
    standalone: true,
    imports: [
        InspectoOptionPickerComponent,
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        PenFieldComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>New requirement</h2>
        <mat-dialog-content>
            <form [formGroup]="form" class="flex flex-col gap-3">
                <inspecto-pen-field
                    cdkFocusInitial
                    label="Title"
                    [required]="true"
                    placeholder="e.g. daily churn KPI by region"
                    formControlName="title"
                    [error]="form.controls.title.touched && form.controls.title.hasError('required') ? 'Title is required' : ''"
                ></inspecto-pen-field>
                <inspecto-option-picker class="w-full" label="Kind" [options]="kinds" formControlName="kind" />
                <inspecto-pen-field
                    label="Description"
                    [required]="true"
                    [multiline]="true"
                    placeholder="What do you need, and why?"
                    formControlName="description"
                    [error]="
                        form.controls.description.touched && form.controls.description.hasError('required')
                            ? 'A description is required'
                            : ''
                    "
                ></inspecto-pen-field>
                <inspecto-pen-field
                    label="Target Component / Pipeline (optional)"
                    placeholder="e.g. pipeline/orders_etl or dataset/mart_sales"
                    formControlName="targetComponent"
                ></inspecto-pen-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="submit()">Submit</button>
        </mat-dialog-actions>
    `,
})
export class RequirementFormDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<RequirementFormDialog, RequirementFormResult>);
    private confirm = inject(InspectoConfirmService);

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    readonly kinds = KINDS;
    readonly form = this.fb.group({
        title: ['', Validators.required],
        kind: this.fb.nonNullable.control<RequirementKind>('kpi'),
        description: ['', Validators.required],
        targetComponent: [''],
    });

    submit(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        this.ref.close({
            title: v.title.trim(),
            kind: v.kind,
            description: v.description.trim(),
            targetComponent: v.targetComponent?.trim() || undefined,
        });
    }
}

import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

/** What the Breaks page hands the dialog: the Break's label and the assignee to start from. */
export interface ReconAssignData {
    label: string;
    assignee: string;
}

/** The server's cap (`ReconRoutes.MAX_ASSIGNEE`) — a user or team name, not a sentence. */
export const MAX_ASSIGNEE = 200;

/**
 * Assign one Reconciliation Break (`ASSURE-BREAK-LIFECYCLE-1`): asks only the assignee, pre-filled with the
 * current assignee or the signed-in user. Closes with the trimmed name; the page writes it through
 * `POST /recon/{id}/breaks/status` (status `assigned`, `canOperateRuns`).
 */
@Component({
    selector: 'app-recon-assign-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Assign break</h2>
        <form [formGroup]="form" (ngSubmit)="save()">
            <mat-dialog-content>
                <p class="text-secondary mb-4 text-sm">{{ data.label }}</p>
                <mat-form-field class="w-full">
                    <mat-label>Assignee</mat-label>
                    <input matInput formControlName="assignee" cdkFocusInitial />
                    @if (form.controls.assignee.hasError('required')) {
                        <mat-error>Name who owns this break.</mat-error>
                    }
                    @if (form.controls.assignee.hasError('maxlength')) {
                        <mat-error>At most {{ max }} characters.</mat-error>
                    }
                </mat-form-field>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button mat-button type="button" (click)="requestClose()">Cancel</button>
                <button mat-flat-button color="primary" type="submit">Assign</button>
            </mat-dialog-actions>
        </form>
    `,
})
export class ReconAssignDialog {
    private ref = inject(MatDialogRef<ReconAssignDialog, string>);
    private confirm = inject(InspectoConfirmService);
    readonly data = inject<ReconAssignData>(MAT_DIALOG_DATA);
    readonly max = MAX_ASSIGNEE;

    readonly form = inject(FormBuilder).nonNullable.group({
        assignee: [this.data.assignee, [Validators.required, Validators.maxLength(MAX_ASSIGNEE)]],
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    save(): void {
        const assignee = this.form.controls.assignee.value.trim();
        if (!assignee) this.form.controls.assignee.setValue('');
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.ref.close(assignee);
    }
}

import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

/** Reprocess-batch prompt — returns the entered batch id, or undefined on cancel. */
@Component({
    selector: 'app-reprocess-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Reprocess batch</h2>
        <mat-dialog-content>
            <p class="mb-3">
                Reprocess a committed batch of <b>{{ data.pipeline }}</b
                >.
            </p>
            <mat-form-field class="w-full">
                <mat-label>Batch id</mat-label>
                <input
                    matInput
                    [formControl]="batchId"
                    placeholder="batch id"
                    required
                    (keyup.enter)="submit()"
                    cdkFocusInitial
                />
                @if (batchId.hasError('required') && batchId.touched) {
                    <mat-error>Batch id is required.</mat-error>
                }
            </mat-form-field>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" (click)="submit()">Reprocess</button>
        </mat-dialog-actions>
    `,
})
export class ReprocessDialog {
    readonly data = inject<{ pipeline: string }>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<ReprocessDialog, string>);
    private confirm = inject(InspectoConfirmService);

    /** Guarded close: Esc / backdrop / Cancel confirm before discarding input. */
    readonly requestClose = guardDirtyClose(this.ref, () => this.batchId.dirty, this.confirm);

    readonly batchId = new FormControl('', {
        nonNullable: true,
        validators: Validators.required,
    });

    submit(): void {
        const value = this.batchId.value.trim();
        if (!value) {
            this.batchId.markAsTouched();
            return;
        }
        this.ref.close(value);
    }
}

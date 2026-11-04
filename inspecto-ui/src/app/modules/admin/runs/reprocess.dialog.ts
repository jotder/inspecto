import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

/** Reprocess-batch prompt — returns the entered batch id, or undefined on cancel. */
@Component({
    selector: 'app-reprocess-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, PenFieldComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Reprocess batch</h2>
        <mat-dialog-content>
            <p class="mb-3">
                Reprocess a committed batch of <b>{{ data.pipeline }}</b
                >.
            </p>
            <inspecto-pen-field
                cdkFocusInitial
                label="Batch id"
                [required]="true"
                [mono]="true"
                placeholder="batch id"
                [formControl]="batchId"
                [error]="batchId.hasError('required') && batchId.touched ? 'Batch id is required.' : ''"
                (entered)="submit()"
            ></inspecto-pen-field>
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

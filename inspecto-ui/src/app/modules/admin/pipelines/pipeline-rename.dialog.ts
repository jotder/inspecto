import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

export interface PipelineRenameData {
    /** The pipeline's identity — unchanged by this dialog, and shown so that is obvious. */
    id: string;
    /** Its current display name (the id when it has never been relabelled). */
    displayName: string;
}

export interface PipelineRenameResultData {
    name: string;
}

/**
 * Rename dialog — changes a pipeline's DISPLAY name only.
 *
 * <p>It says so plainly, because the honest scope is the surprising part: the identity keeps addressing
 * the pipeline everywhere (its config file, run history, dedup ledger and Catalog Stream), so an operator
 * who renames "orders" to "Retail Orders" will still see `orders` in run URLs and audit trails. Hiding
 * that would make the feature feel broken; stating it makes it a deliberate, safe relabel.
 */
@Component({
    selector: 'app-pipeline-rename-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoDialogResizeDirective,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title inspectoDialogResize>Rename pipeline</h2>
        <mat-dialog-content>
            <inspecto-alert variant="info" title="The identity stays '{{ data.id }}'">
                Only the label changes. Run history, the dedup ledger and the data catalog keep using
                '{{ data.id }}', so nothing is re-processed and no history is lost.
            </inspecto-alert>
            <form [formGroup]="form" (ngSubmit)="save()" class="mt-4 flex flex-col gap-3">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Display name</mat-label>
                    <input matInput formControlName="name" placeholder="e.g. Retail Orders (EU)" cdkFocusInitial />
                    @if (form.controls.name.hasError('required')) {
                        <mat-error>A display name is required.</mat-error>
                    }
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()">Rename</button>
        </mat-dialog-actions>
    `,
})
export class PipelineRenameDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<PipelineRenameDialog, PipelineRenameResultData>);
    private confirm = inject(InspectoConfirmService);
    readonly data = inject<PipelineRenameData>(MAT_DIALOG_DATA);

    readonly form = this.fb.group({
        name: [this.data.displayName, [Validators.required]],
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    save(): void {
        const name = String(this.form.controls.name.value ?? '').trim();
        if (!name) {
            this.form.controls.name.setErrors({ required: true });
            this.form.markAllAsTouched();
            return;
        }
        this.ref.close({ name });
    }
}

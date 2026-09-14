import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { uniqueNameValidator } from 'app/inspecto/investigation';

export interface MaterializeDatasetData {
    /** The dataset being materialized — the source, and the one value `target` may not equal. */
    source: string;
    /** Existing dataset ids, for the inline duplicate guard on a NEW target. */
    existingNames: string[];
}

/** A component id: starts alphanumeric, then alphanumeric / dot / dash / underscore (mirrors the backend `SAFE_ID`). */
const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

/**
 * "Materialize dataset" — asks for the one thing the action needs now: the **target** id to write the
 * snapshot to. Everything else (`measures`, `group_by`, `limit`) has a server-side default and is
 * deliberately not asked; a scheduled rollup that needs them is authored as a `task: materialize` job.
 *
 * <p>⚠ The duplicate guard here is a **warning, not a refusal**: unlike a create, materializing over an
 * existing target is the normal refresh case — the snapshot is replaced in place. So an existing id is
 * offered with a hint rather than blocked, and only a target equal to the SOURCE is refused, because the
 * server refuses that too (a materialize never writes over its own input).
 */
@Component({
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Materialize dataset</h2>
        <mat-dialog-content class="flex w-96 max-w-full flex-col gap-4">
            <div class="text-secondary text-sm">
                Writes <strong>{{ data.source }}</strong> to a Parquet snapshot and registers it as a dataset of its
                own, so charts read the summary instead of re-reading the detail.
            </div>
            <form [formGroup]="form" class="flex flex-col gap-2" (ngSubmit)="submit()">
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Target dataset id</mat-label>
                    <input matInput formControlName="target" cdkFocusInitial placeholder="e.g. orders_by_region" />
                    @if (form.controls.target.hasError('required')) {
                        <mat-error>A target is required.</mat-error>
                    } @else if (form.controls.target.hasError('pattern')) {
                        <mat-error>Letters, digits, “.”, “-”, “_” only; must start alphanumeric.</mat-error>
                    } @else if (form.controls.target.hasError('sameAsSource')) {
                        <mat-error>The target must differ from the source dataset.</mat-error>
                    }
                </mat-form-field>
                @if (form.controls.target.hasError('duplicate')) {
                    <p class="text-secondary text-xs" role="status">
                        “{{ form.controls.target.value }}” already exists — its snapshot will be replaced.
                    </p>
                }
            </form>
            <div class="text-secondary text-xs">
                The run happens in the background; you'll get its run id once it starts.
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" (click)="submit()">Materialize</button>
        </mat-dialog-actions>
    `,
})
export class MaterializeDatasetDialog {
    readonly data = inject<MaterializeDatasetData>(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<MaterializeDatasetDialog>);
    private confirm = inject(InspectoConfirmService);
    private fb = inject(FormBuilder);

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    readonly form = this.fb.nonNullable.group({
        target: ['', [Validators.required, Validators.pattern(SAFE_ID)]],
    });

    constructor() {
        // Mirrors the server's own refusal so the operator is told here rather than by a 422.
        this.form.controls.target.addValidators((c): ValidationErrors | null =>
            String(c.value ?? '').trim() === this.data.source ? { sameAsSource: true } : null,
        );
        // Advisory only — see the class doc. `uniqueNameValidator` sets `duplicate`, which this dialog
        // renders as a hint and does NOT treat as invalid.
        this.form.controls.target.addValidators(uniqueNameValidator(() => this.data.existingNames));
    }

    /** Valid enough to submit: `duplicate` is deliberately not disqualifying. */
    private submittable(): boolean {
        const e = this.form.controls.target.errors ?? {};
        return Object.keys(e).every((k) => k === 'duplicate');
    }

    submit(): void {
        if (!this.submittable()) {
            this.form.markAllAsTouched();
            return;
        }
        this.ref.close(this.form.controls.target.value.trim());
    }
}

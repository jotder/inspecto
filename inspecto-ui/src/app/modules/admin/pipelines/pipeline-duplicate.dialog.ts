import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

export interface PipelineDuplicateData {
    /** The pipeline being copied — shown so the scope is obvious. */
    sourceId: string;
    /** Pipeline names already in use — the name control rejects a duplicate inline. */
    existingNames: string[];
}

export interface PipelineDuplicateResultData {
    name: string;
}

/** Mirrors onboarding-create's uniqueNameValidator (⛔ a feature may not import a feature). */
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

/**
 * Duplicate dialog — asks only the one thing the copy needs: its new name. The caller composes the
 * proven stream-bundle retarget path (export → plan under this name → import), so everything else —
 * directories, satellite identities, the inactive-draft posture — is re-derived, never asked.
 */
@Component({
    selector: 'app-pipeline-duplicate-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Duplicate pipeline</h2>
        <mat-dialog-content>
            <inspecto-alert variant="info" title="Copies the saved configuration">
                '{{ data.sourceId }}' and its schema, segment schemas and enrichment are copied under the new name.
                The copy lands as an inactive draft with its own directories — activate it when you are ready.
            </inspecto-alert>
            <form [formGroup]="form" (ngSubmit)="save()" class="mt-4 flex flex-col gap-3">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>New pipeline name</mat-label>
                    <input matInput formControlName="name" cdkFocusInitial />
                    @if (form.controls.name.hasError('required')) {
                        <mat-error>A name is required.</mat-error>
                    }
                    @if (form.controls.name.hasError('duplicate')) {
                        <mat-error>A pipeline with this name already exists.</mat-error>
                    }
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()">Duplicate</button>
        </mat-dialog-actions>
    `,
})
export class PipelineDuplicateDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<PipelineDuplicateDialog, PipelineDuplicateResultData>);
    private confirm = inject(InspectoConfirmService);
    readonly data = inject<PipelineDuplicateData>(MAT_DIALOG_DATA);

    readonly form = this.fb.group({
        name: [
            `${this.data.sourceId}_copy`,
            [Validators.required, uniqueNameValidator(this.data.existingNames ?? [])],
        ],
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    save(): void {
        const name = String(this.form.controls.name.value ?? '').trim();
        if (!name || this.form.invalid) {
            if (!name) this.form.controls.name.setErrors({ required: true });
            this.form.markAllAsTouched();
            return;
        }
        this.ref.close({ name });
    }
}

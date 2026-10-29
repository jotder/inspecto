import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { laLimit } from './la-limits';

export interface SaveInvestigationDialogData {
    /** What the fields open with: the last edits of a cancelled Save, else the start's default Name and Purpose. */
    name: string;
    purpose: string;
    /** How many unsaved steps Save records. */
    steps: number;
    /** Called on every edit, so a cancelled Save opens with the edits next time. */
    keep: (name: string, purpose: string) => void;
}

export interface SaveInvestigationDialogResult {
    name: string;
    purpose: string;
}

const NOT_BLANK = /\S/;

/**
 * LA-UNSAVED-INVESTIGATION-1 — **Save** of an unsaved Investigation: the Name and the Purpose, pre-filled. It only ASKS:
 * it closes with the two values and the screen creates the Investigation and records the steps. The edits are handed to
 * `keep` as they are typed, so closing it (Cancel, Esc) loses nothing and needs no discard question.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, PenFieldComponent],
    template: `
        <h2 mat-dialog-title>Save the Investigation</h2>
        <form [formGroup]="form" (ngSubmit)="submit()">
            <mat-dialog-content class="flex flex-col gap-3 text-sm">
                <p class="text-secondary m-0" data-testid="la-save-what">
                    Creates the Investigation and records the {{ data.steps === 1 ? 'step' : data.steps + ' steps' }} taken
                    so far, in order, with your name.
                </p>
                <inspecto-pen-field
                    data-testid="la-save-name"
                    label="Name"
                    formControlName="name"
                    [maxlength]="200"
                    [required]="true"
                    [error]="error('name')"
                />
                <div>
                    <inspecto-pen-field
                        data-testid="la-save-purpose"
                        label="Purpose"
                        formControlName="purpose"
                        [multiline]="true"
                        [required]="true"
                        [maxlength]="purposeMax"
                        [error]="error('purpose')"
                    />
                    <p class="text-secondary m-0 text-xs">
                        Why you are investigating (stated reason or legal basis). Shown in the Dossier.
                    </p>
                </div>
            </mat-dialog-content>
            <mat-dialog-actions align="end" class="gap-2">
                <button mat-button type="button" (click)="ref.close()">Cancel</button>
                <button mat-flat-button color="primary" type="submit" data-testid="la-save-go">Save</button>
            </mat-dialog-actions>
        </form>
    `,
})
export class LinkAnalysisSaveInvestigationDialog {
    readonly data = inject<SaveInvestigationDialogData>(MAT_DIALOG_DATA);
    readonly ref = inject<MatDialogRef<LinkAnalysisSaveInvestigationDialog, SaveInvestigationDialogResult>>(MatDialogRef);
    /** The longest purpose the Space accepts (`input.purpose_length`, the server's own limit). */
    readonly purposeMax = laLimit('input.purpose_length');
    readonly form = new FormGroup({
        name: new FormControl(this.data.name, {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern(NOT_BLANK), Validators.maxLength(200)],
        }),
        purpose: new FormControl(this.data.purpose, {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern(NOT_BLANK), Validators.maxLength(this.purposeMax)],
        }),
    });
    /** Save was pressed: a missing field says so. */
    private readonly tried = signal(false);

    constructor() {
        this.form.valueChanges
            .pipe(takeUntilDestroyed())
            .subscribe(() => this.data.keep(this.form.controls.name.value, this.form.controls.purpose.value));
    }

    error(field: 'name' | 'purpose'): string {
        const c = this.form.controls[field];
        if (c.hasError('maxlength')) return `At most ${field === 'name' ? 200 : this.purposeMax} characters.`;
        if (this.tried() && c.invalid) return field === 'name' ? 'A name is required.' : 'A purpose is required.';
        return '';
    }

    submit(): void {
        this.tried.set(true);
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.ref.close({ name: this.form.controls.name.value.trim(), purpose: this.form.controls.purpose.value.trim() });
    }
}

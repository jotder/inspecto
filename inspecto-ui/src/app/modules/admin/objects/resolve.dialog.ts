import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InspectoOptionPickerComponent } from 'app/inspecto/components/option-picker.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { DISPOSITIONS } from './incident-disposition';

/** What the dialog was opened for. `askDisposition` is set for Incidents, which cannot resolve without one. */
export interface ResolveDialogData {
    count: number;
    label: string;
    askDisposition?: boolean;
}

/** The dialog's result — `disposition` only when it was asked for. */
export interface ResolveResult {
    comment: string;
    disposition?: string;
}

/**
 * Resolve dialog — the state change to Resolved requires a resolution comment (GLOSSARY §9); the comment is
 * appended to each selected object's thread before the `resolve` transition. For an Incident it also asks the
 * **Disposition** (WS-10): the server refuses an Incident's resolve without one (422), so it is asked here,
 * once, for every selected Incident. Closes with a {@link ResolveResult}, or null on cancel.
 */
@Component({
    selector: 'app-resolve-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoOptionPickerComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Resolve {{ data.count }} {{ data.label }}{{ data.count === 1 ? '' : 's' }}</h2>
        <mat-dialog-content class="pt-2">
            <form [formGroup]="form" class="flex flex-col gap-3">
                @if (data.askDisposition) {
                    <div>
                        <inspecto-option-picker
                            label="Disposition"
                            formControlName="disposition"
                            placeholder="Choose the outcome"
                            [options]="dispositions"
                        />
                        <!-- The picker cannot show its own error on submit (it only reads its own touched
                             state), so the host renders the line — the angular-ui option-picker rule. -->
                        @if (form.controls.disposition.hasError('required') && form.controls.disposition.touched) {
                            <p class="text-warn m-0 text-xs" role="alert">A Disposition is required to resolve.</p>
                        }
                    </div>
                }
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Resolution</mat-label>
                    <textarea
                        matInput
                        rows="4"
                        formControlName="comment"
                        placeholder="what was done, and why the problem is considered fixed"
                        required
                        cdkFocusInitial
                    ></textarea>
                    @if (form.controls.comment.hasError('required') && form.controls.comment.touched) {
                        <mat-error>A resolution comment is required.</mat-error>
                    }
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" (click)="apply()">Resolve</button>
        </mat-dialog-actions>
    `,
})
export class ResolveDialog {
    private ref = inject(MatDialogRef<ResolveDialog>);
    private confirm = inject(InspectoConfirmService);

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);
    private fb = inject(FormBuilder);
    readonly data = inject<ResolveDialogData>(MAT_DIALOG_DATA);
    readonly dispositions = [...DISPOSITIONS];

    readonly form = this.fb.group({
        comment: ['', Validators.required],
        disposition: ['', this.data.askDisposition ? Validators.required : []],
    });

    apply(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const { comment, disposition } = this.form.getRawValue();
        this.ref.close(
            this.data.askDisposition ? { comment: comment!, disposition: disposition! } : { comment: comment! },
        );
    }
}

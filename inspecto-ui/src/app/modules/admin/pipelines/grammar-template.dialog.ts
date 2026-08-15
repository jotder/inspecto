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
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';

export interface GrammarTemplateData {
    /** The node whose Grammar is being saved — shown so the operator sees what the template copies. */
    source: string;
    /** Grammar ids already registered; a duplicate is blocked inline rather than via the server 409. */
    existingNames: string[];
    /** The suggested id, derived from the Grammar's frontend. */
    suggested: string;
}

export interface GrammarTemplateResultData {
    id: string;
}

/**
 * Save-as-template dialog for a Grammar — names the copy and states the one thing that is easy to
 * assume wrongly: the template is a **copy**, not a live reference.
 *
 * <p>The explanation earns its place because this behaviour was deliberately REVERSED: until
 * 2026-08-15 saving a reusable Grammar MOVED the block off the node and bound it via
 * `use: grammar/<id>`, so a later template edit reached back into every pipeline using it. An
 * operator carrying that expectation would edit the template and wait for a change that never comes.
 * See `docs/archived-documents/plans-archive/grammar-templates-not-bindings-plan.md`.
 */
@Component({
    selector: 'app-grammar-template-dialog',
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
        <h2 mat-dialog-title inspectoDialogResize>Save '{{ data.source }}' Grammar as a template</h2>
        <mat-dialog-content>
            <inspecto-alert variant="info" title="A template is a copy, not a link">
                '{{ data.source }}' keeps its own Grammar and is unaffected by later edits to this template — and
                editing the template will not change any pipeline started from it.
            </inspecto-alert>
            <form [formGroup]="form" (ngSubmit)="save()" class="mt-4 flex flex-col gap-3">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Template id</mat-label>
                    <input matInput formControlName="id" placeholder="e.g. pipe_delimited" cdkFocusInitial />
                    <mat-hint>Lowercase letters, digits and underscores. This is the permanent identity.</mat-hint>
                    @if (form.controls.id.hasError('required')) {
                        <mat-error>An id is required.</mat-error>
                    }
                    @if (form.controls.id.hasError('pattern')) {
                        <mat-error>Lowercase letters, digits and underscores only; start with a letter or digit.</mat-error>
                    }
                    @if (form.controls.id.hasError('duplicate')) {
                        <mat-error>A Grammar template with this id already exists.</mat-error>
                    }
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()">Save template</button>
        </mat-dialog-actions>
    `,
})
export class GrammarTemplateDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<GrammarTemplateDialog, GrammarTemplateResultData>);
    private confirm = inject(InspectoConfirmService);
    readonly data = inject<GrammarTemplateData>(MAT_DIALOG_DATA);

    readonly form = this.fb.group({
        id: [
            this.data.suggested ?? '',
            [
                Validators.required,
                Validators.pattern(/^[a-z0-9][a-z0-9_]*$/),
                uniqueNameValidator(() => this.data.existingNames),
            ],
        ],
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    save(): void {
        // Normalise BEFORE validating (the PipelineTemplateDialog precedent) so the pattern judges
        // the value actually sent.
        this.form.controls.id.setValue(String(this.form.controls.id.value ?? '').trim());
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.ref.close({ id: String(this.form.controls.id.value ?? '') });
    }
}

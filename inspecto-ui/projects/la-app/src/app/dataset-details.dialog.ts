import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { switchMap } from 'rxjs';
import { ComponentsService, STALE_WRITE_MESSAGE, apiErrorMessage, isStaleVersionError } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { guardDirtyClose } from '@inspecto/core/dialog-dirty-guard';

export interface DatasetDetailsData {
    /** The Dataset's id: fixed, it is what Mappings, views and Pipelines refer to. */
    id: string;
    /** The name shown for it ('' = none stated, the id is shown). */
    name: string;
    description: string;
}

export type DatasetDetailsResult = Pick<DatasetDetailsData, 'name' | 'description'>;

const NAME_MAX = 120;
const DESCRIPTION_MAX = 1000;

/**
 * LA-DATA-DETAILS-1 (operator 2026-11-10): rename a Dataset and say what it is. Only the shown name changes - stored as
 * `title`, because the server stamps `name` with the id on every write - so nothing that refers to the Dataset breaks. Saves itself - reads the stored Dataset, writes it back with the two
 * keys changed and every other key untouched, against the version it read (a change made meanwhile is refused, not
 * overwritten) - and closes with the saved values.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, InspectoAlertComponent, PenFieldComponent],
    template: `
        <h2 mat-dialog-title>Dataset details</h2>
        <mat-dialog-content class="flex flex-col gap-3 text-sm">
            <form [formGroup]="form" (ngSubmit)="save()" class="flex flex-col gap-3">
                <inspecto-pen-field
                    label="Name"
                    formControlName="name"
                    [maxlength]="NAME_MAX"
                    [required]="true"
                    [placeholder]="data.id"
                    help="The name shown on the Data page and in pickers"
                    [error]="nameError()"
                    cdkFocusInitial
                    data-testid="dataset-details-name"
                ></inspecto-pen-field>
                <inspecto-pen-field
                    label="Description"
                    formControlName="description"
                    [multiline]="true"
                    [maxlength]="DESCRIPTION_MAX"
                    placeholder="What its rows are, where they come from, what to watch for"
                    data-testid="dataset-details-description"
                ></inspecto-pen-field>
                <div class="flex flex-col gap-0.5">
                    <span class="text-secondary text-xs">Id</span>
                    <span class="font-mono text-sm" data-testid="dataset-details-id">{{ data.id }}</span>
                    <span class="text-secondary text-xs">
                        Fixed: Mappings, saved views and Pipelines refer to the Dataset by its id, so renaming changes
                        only what is shown.
                    </span>
                </div>
            </form>
            @if (error()) {
                <inspecto-alert variant="error">{{ error() }}</inspecto-alert>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button
                mat-flat-button
                color="primary"
                type="button"
                [disabled]="saving()"
                (click)="save()"
                data-testid="dataset-details-save"
            >
                Save
            </button>
        </mat-dialog-actions>
    `,
})
export class DatasetDetailsDialog {
    protected readonly data = inject<DatasetDetailsData>(MAT_DIALOG_DATA);
    private readonly ref = inject<MatDialogRef<DatasetDetailsDialog, DatasetDetailsResult>>(MatDialogRef);
    private readonly components = inject(ComponentsService);
    protected readonly NAME_MAX = NAME_MAX;
    protected readonly DESCRIPTION_MAX = DESCRIPTION_MAX;

    readonly form = new FormGroup({
        name: new FormControl(this.data.name || this.data.id, {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern(/\S/), Validators.maxLength(NAME_MAX)],
        }),
        description: new FormControl(this.data.description, {
            nonNullable: true,
            validators: [Validators.maxLength(DESCRIPTION_MAX)],
        }),
    });
    readonly saving = signal(false);
    readonly error = signal('');
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, inject(InspectoConfirmService));

    protected nameError(): string {
        const c = this.form.controls.name;
        return c.touched && c.invalid ? 'Give the Dataset a name.' : '';
    }

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const name = this.form.controls.name.value.trim();
        const description = this.form.controls.description.value.trim();
        this.saving.set(true);
        this.error.set('');
        this.components
            .get('dataset', this.data.id)
            .pipe(
                switchMap((def) =>
                    this.components.update(
                        'dataset',
                        this.data.id,
                        { ...def.content, title: name, description },
                        def.contentHash ? { ifMatch: def.contentHash } : undefined,
                    ),
                ),
            )
            .subscribe({
                next: () => {
                    this.saving.set(false);
                    this.form.markAsPristine();
                    this.ref.close({ name, description });
                },
                error: (e) => {
                    this.saving.set(false);
                    this.error.set(
                        isStaleVersionError(e)
                            ? STALE_WRITE_MESSAGE
                            : apiErrorMessage(e, 'The details could not be saved.'),
                    );
                },
            });
    }
}

import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { guardDirtyClose } from '@inspecto/core/dialog-dirty-guard';
import { GRAPH_LAYOUTS } from '@inspecto/core/graph/graph-layouts';
import { viewDataset } from './link-analysis-open.dialog';
import { LinkAnalysisView } from './link-analysis.service';

export interface ViewDetailsDialogData {
    view: LinkAnalysisView;
    /** Why this view cannot be changed here (`view-access.ts`); '' = it can. */
    editBlock: string;
    /** Opens the view's version history (the File ▸ Open dialog's). */
    history: (view: LinkAnalysisView) => void;
}

/** What the dialog closes with: the new Name and Description, only when Save was pressed. */
export interface ViewDetailsDialogResult {
    name: string;
    description: string;
}

const NOT_BLANK = /\S/;
export const VIEW_NAME_MAX = 120;
export const VIEW_DESCRIPTION_MAX = 2000;

/**
 * LA-VIEW-DETAILS-1 (operator 2026-11-10) — the open saved view, from its name in the page header: what it is (Name,
 * Description, Dataset, layout, industry pack, Investigations, id) and, where the caller may change it, a Rename and a new
 * Description in one Save. It only ASKS: it closes with the two values and the page writes them (the same update of the
 * same id the Open dialog's Rename makes), so the open graph and the rest of the view are untouched.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatIconModule, PenFieldComponent],
    template: `
        <h2 mat-dialog-title>Saved view</h2>
        <form [formGroup]="form" (ngSubmit)="submit()">
            <mat-dialog-content class="flex flex-col gap-3 text-sm">
                @if (data.editBlock) {
                    <p class="text-secondary m-0" data-testid="la-view-details-locked">{{ data.editBlock }}</p>
                    <div class="flex flex-col gap-1">
                        <span class="text-secondary text-xs">Name</span>
                        <span class="font-semibold" data-testid="la-view-details-name-text">{{ data.view.name }}</span>
                    </div>
                    <div class="flex flex-col gap-1">
                        <span class="text-secondary text-xs">Description</span>
                        <p class="m-0 whitespace-pre-line" data-testid="la-view-details-description-text">
                            {{ data.view.description || 'No description.' }}
                        </p>
                    </div>
                } @else {
                    <inspecto-pen-field
                        data-testid="la-view-details-name"
                        label="Name"
                        formControlName="name"
                        [maxlength]="nameMax"
                        [required]="true"
                        [error]="error('name')"
                    />
                    <inspecto-pen-field
                        data-testid="la-view-details-description"
                        label="Description"
                        formControlName="description"
                        [multiline]="true"
                        [maxlength]="descriptionMax"
                        placeholder="What this view shows, and why it was saved"
                        help="Shown when someone opens or hovers this view."
                        [error]="error('description')"
                    />
                }
                <dl class="m-0 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1" data-testid="la-view-details-facts">
                    <dt class="text-secondary">Dataset</dt>
                    <dd class="m-0">{{ dataset || '—' }}</dd>
                    <dt class="text-secondary">Layout</dt>
                    <dd class="m-0">{{ layout }}</dd>
                    <dt class="text-secondary">Industry pack</dt>
                    <dd class="m-0">{{ data.view.profile || 'Generic' }}</dd>
                    <dt class="text-secondary">Investigations</dt>
                    <dd class="m-0">{{ data.view.investigations?.length ?? 0 }}</dd>
                    <dt class="text-secondary">Id</dt>
                    <dd class="m-0 font-mono text-xs">{{ data.view.id }}</dd>
                </dl>
                <p class="text-secondary m-0 text-xs">
                    Renaming keeps the id, so links to this view keep working. The graph on screen is not saved here:
                    use File ▸ Save view for that.
                </p>
            </mat-dialog-content>
            <mat-dialog-actions class="gap-2">
                <button mat-button type="button" (click)="showHistory()" data-testid="la-view-details-history">
                    <mat-icon svgIcon="heroicons_outline:clock"></mat-icon>
                    Version history
                </button>
                <span class="flex-auto"></span>
                @if (data.editBlock) {
                    <button mat-flat-button color="primary" type="button" (click)="ref.close()">Close</button>
                } @else {
                    <button mat-button type="button" (click)="requestClose()">Cancel</button>
                    <button mat-flat-button color="primary" type="submit" data-testid="la-view-details-save">
                        Save
                    </button>
                }
            </mat-dialog-actions>
        </form>
    `,
})
export class LinkAnalysisViewDetailsDialog {
    readonly data = inject<ViewDetailsDialogData>(MAT_DIALOG_DATA);
    readonly ref = inject<MatDialogRef<LinkAnalysisViewDetailsDialog, ViewDetailsDialogResult>>(MatDialogRef);
    readonly nameMax = VIEW_NAME_MAX;
    readonly descriptionMax = VIEW_DESCRIPTION_MAX;
    readonly dataset = viewDataset(this.data.view);
    readonly layout = GRAPH_LAYOUTS.find((l) => l.id === this.data.view.layout)?.label ?? 'Layered';
    readonly form = new FormGroup({
        name: new FormControl(this.data.view.name, {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern(NOT_BLANK), Validators.maxLength(VIEW_NAME_MAX)],
        }),
        description: new FormControl(this.data.view.description ?? '', {
            nonNullable: true,
            validators: [Validators.maxLength(VIEW_DESCRIPTION_MAX)],
        }),
    });
    /** Esc, the backdrop and Cancel ask before dropping an edit. */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, inject(InspectoConfirmService));
    private readonly tried = signal(false);

    error(field: 'name' | 'description'): string {
        const c = this.form.controls[field];
        if (c.hasError('maxlength'))
            return `At most ${field === 'name' ? this.nameMax : this.descriptionMax} characters.`;
        if (this.tried() && c.invalid) return 'A name is required.';
        return '';
    }

    showHistory(): void {
        this.data.history(this.data.view);
    }

    submit(): void {
        this.tried.set(true);
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const name = this.form.controls.name.value.trim();
        const description = this.form.controls.description.value.trim();
        if (name === this.data.view.name && description === (this.data.view.description ?? '')) {
            this.ref.close();
            return;
        }
        this.ref.close({ name, description });
    }
}

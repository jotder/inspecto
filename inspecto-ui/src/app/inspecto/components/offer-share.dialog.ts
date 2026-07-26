import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ExchangeKind } from 'app/inspecto/api';

export interface OfferShareData {
    kind: ExchangeKind;
    /** The owner space id (the active space). */
    owner: string;
    /** The component id being offered. */
    item: string;
}

/** The owner's answer — what to publish in the shareable catalog. */
export interface OfferShareResult {
    description: string;
}

/**
 * "Offer for sharing" — the owner side of the Exchange. Lists a Dataset, Widget or saved View in the
 * shareable catalog so other spaces can request access. Asks only for an optional catalog description; the
 * item, owner and kind travel in the dialog data. A derived kind's datasets must already be offered
 * (the backend 409s otherwise) — surfaced by the caller as a toast, not pre-validated here.
 */
@Component({
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Offer for sharing</h2>
        <mat-dialog-content class="flex w-96 max-w-full flex-col gap-4">
            <div class="text-secondary text-sm">
                List {{ label }} <strong>{{ data.item }}</strong> in the shareable catalog. Other spaces
                can then request access; nothing is shared until you approve a request.
                @if (data.kind !== 'dataset') {
                    <span class="mt-1 block">
                        Every dataset this {{ label }} reads must already be offered — offer those first if
                        they aren't.
                    </span>
                }
                @if (data.kind === 'link-analysis-view') {
                    <span class="mt-1 block">
                        A saved view is shared <strong>live</strong>: it holds no rows of its own, so
                        consumers always read current data through its datasets' grants.
                    </span>
                }
            </div>
            <form [formGroup]="form">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Description (optional)</mat-label>
                    <textarea
                        matInput
                        formControlName="description"
                        rows="3"
                        placeholder="What this {{ label }} contains — shown to spaces browsing the catalog"
                        cdkFocusInitial
                    ></textarea>
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Cancel</button>
            <button mat-flat-button color="primary" (click)="submit()">Offer</button>
        </mat-dialog-actions>
    `,
})
export class OfferShareDialog {
    readonly data = inject<OfferShareData>(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<OfferShareDialog>);

    /** Reader-facing name for the kind — the wire value `link-analysis-view` is not prose. */
    readonly label = this.data.kind === 'link-analysis-view' ? 'saved view' : this.data.kind;

    readonly form = inject(FormBuilder).nonNullable.group({ description: [''] });

    submit(): void {
        this.ref.close(this.form.getRawValue() satisfies OfferShareResult);
    }
}

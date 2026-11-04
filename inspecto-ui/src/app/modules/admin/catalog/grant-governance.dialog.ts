import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ExchangeGrant } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';

export interface GrantGovernanceData {
    field: 'pin' | 'expiry';
    grant: ExchangeGrant;
}

/**
 * The result: the new value, or `null` to clear. For `pin` it's a snapshot version string; for
 * `expiry` it's epoch millis. `undefined` (dialog dismissed) means no change.
 */
export type GrantGovernanceResult = string | number | null;

/**
 * A compact grant-governance editor (S3) — sets or clears one optional field on a grant: a consumer's
 * snapshot version **pin**, or an owner's **expiry**. Rendered as a version text box or a date input by
 * {@link GrantGovernanceData.field}; the "Clear" action closes with `null` to remove the value.
 */
@Component({
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, PenFieldComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ isPin ? 'Pin snapshot version' : 'Set grant expiry' }}</h2>
        <mat-dialog-content class="flex w-80 max-w-full flex-col gap-3">
            <div class="text-secondary text-sm">
                {{ data.grant.kind }} <strong>{{ data.grant.owner }}/{{ data.grant.item }}</strong>
                @if (isPin) {
                    — pin the consumer to a fixed snapshot version, or clear to track the latest.
                } @else {
                    — the grant is automatically revoked after this time; clear for no expiry.
                }
            </div>
            <form [formGroup]="form">
                @if (isPin) {
                    <inspecto-pen-field
                        label="Version"
                        placeholder="e.g. v3"
                        formControlName="pin"
                        cdkFocusInitial
                    ></inspecto-pen-field>
                } @else {
                    <inspecto-pen-field
                        label="Expires at"
                        type="datetime-local"
                        formControlName="expiry"
                        cdkFocusInitial
                    ></inspecto-pen-field>
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-button (click)="clear()">Clear</button>
            <button mat-flat-button color="primary" (click)="submit()">Save</button>
        </mat-dialog-actions>
    `,
})
export class GrantGovernanceDialog {
    readonly data = inject<GrantGovernanceData>(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<GrantGovernanceDialog>);
    private confirm = inject(InspectoConfirmService);

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    readonly isPin = this.data.field === 'pin';

    readonly form = inject(FormBuilder).nonNullable.group({
        pin: [this.data.grant.pin ?? ''],
        expiry: [this.toLocalInput(this.data.grant.expiresAt)],
    });

    submit(): void {
        if (this.isPin) {
            const v = this.form.controls.pin.value.trim();
            this.ref.close(v ? v : null);
        } else {
            const v = this.form.controls.expiry.value;
            this.ref.close(v ? new Date(v).getTime() : null);
        }
    }

    clear(): void {
        this.ref.close(null);
    }

    /** Epoch millis → the `datetime-local` control's local `YYYY-MM-DDTHH:mm` string (empty when unset). */
    private toLocalInput(epoch: number | null): string {
        if (!epoch) return '';
        const d = new Date(epoch);
        const pad = (n: number): string => String(n).padStart(2, '0');
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }
}

import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Dashboard } from '../dashboards/dashboard-types';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';

export interface AddToDashboardData {
    widgetId: string;
    /** Existing dashboards — the pick list, and the duplicate-id check for "new". */
    dashboards: Dashboard[];
}

/** Either `existingId` (place on that dashboard) or `newName` (create one, then place) — never both. */
export interface AddToDashboardResult {
    existingId?: string;
    newName?: string;
}

/** Sentinel target = "create a new dashboard" (the default — the name field appears). A string, not
 *  `null`, because mat-select renders a null selection as blank; can't collide with a real dashboard id
 *  (ids must start alphanumeric). */
const NEW_DASHBOARD = '__new__';

/**
 * Add-to-dashboard dialog (Viz Library) — picks an existing dashboard, or names a new one (duplicate ids
 * blocked inline, product-wide rule), and closes with the {@link AddToDashboardResult} (or undefined on
 * cancel). The caller performs the actual tile append/create.
 */
@Component({
    selector: 'app-add-to-dashboard-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Add “{{ data.widgetId }}” to a dashboard</h2>
        <mat-dialog-content>
            <form [formGroup]="form" (ngSubmit)="add()" class="flex flex-col gap-3">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Dashboard</mat-label>
                    <mat-select formControlName="target" cdkFocusInitial>
                        <mat-option [value]="NEW">New dashboard…</mat-option>
                        @for (d of data.dashboards; track d.id) {
                            <mat-option [value]="d.id"
                                >{{ d.name }}
                                <span class="text-secondary">({{ d.tiles.length }} tiles)</span></mat-option
                            >
                        }
                    </mat-select>
                </mat-form-field>
                @if (form.controls.target.value === NEW) {
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>New dashboard id</mat-label>
                        <input matInput formControlName="name" placeholder="e.g. fraud_overview" />
                        @if (form.controls.name.hasError('pattern')) {
                            <mat-error>Letters, digits, dot, dash, underscore; start alphanumeric.</mat-error>
                        }
                        @if (form.controls.name.hasError('duplicate')) {
                            <mat-error>A dashboard with this id already exists.</mat-error>
                        }
                    </mat-form-field>
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="add()">Add</button>
        </mat-dialog-actions>
    `,
})
export class AddToDashboardDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<AddToDashboardDialog, AddToDashboardResult>);
    private confirm = inject(InspectoConfirmService);

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);
    readonly data = inject<AddToDashboardData>(MAT_DIALOG_DATA);
    readonly NEW = NEW_DASHBOARD;

    readonly form = this.fb.group({
        target: [NEW_DASHBOARD],
        name: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                uniqueNameValidator(() => this.data.dashboards.map((d) => d.id)),
            ],
        ],
    });

    add(): void {
        const target = this.form.controls.target.value;
        if (target && target !== NEW_DASHBOARD) {
            this.ref.close({ existingId: target });
            return;
        }
        const nameCtrl = this.form.controls.name;
        const name = String(nameCtrl.value ?? '').trim();
        if (!name || nameCtrl.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.ref.close({ newName: name });
    }
}

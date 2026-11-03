import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { Dashboard } from '../dashboards/dashboard-types';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';

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
        InspectoOptionPickerComponent,
        PenFieldComponent,
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Add “{{ data.widgetId }}” to a dashboard</h2>
        <mat-dialog-content>
            <form [formGroup]="form" (ngSubmit)="add()" class="flex flex-col gap-3">
                <inspecto-option-picker
                    class="w-full"
                    label="Dashboard"
                    [options]="targetOptions"
                    formControlName="target"
                />
                @if (form.controls.target.value === NEW) {
                    <inspecto-pen-field
                        label="New dashboard id"
                        [required]="true"
                        placeholder="e.g. fraud_overview"
                        formControlName="name"
                        [error]="nameError()"
                    ></inspecto-pen-field>
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
    readonly targetOptions: PickerOption[] = [
        { value: NEW_DASHBOARD, label: 'New dashboard…' },
        ...this.data.dashboards.map((d) => ({ value: d.id, label: d.name, hint: `${d.tiles.length} tiles` })),
    ];

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

    /** The new id row's error line: shown once the row was left or Add was pressed. */
    nameError(): string {
        const c = this.form.controls.name;
        if (!c.touched) return '';
        if (c.hasError('pattern')) return 'Letters, digits, dot, dash, underscore; start alphanumeric.';
        if (c.hasError('duplicate')) return 'A dashboard with this id already exists.';
        return '';
    }

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

import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

import { RoleDef } from 'app/inspecto/api';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

export interface RoleFormData {
    /** Absent = create a new role; present = edit (the name is immutable — it is the IdP claim key). */
    role?: RoleDef;
    /** The full capability vocabulary offered as checkboxes. */
    vocabulary: string[];
    /** Existing role names (create-mode duplicate guard). */
    existingNames: string[];
}

/**
 * Create/edit one role definition (Settings ▸ Access ▸ Roles, RBAC R5): the role name (an IdP
 * claim value — lowercased, immutable once created), its capability grants as a checkbox list over
 * the route-gate vocabulary, and optional SEC-7d data scopes. Closes with the edited {@link RoleDef}
 * (the caller persists); Cancel/Esc/backdrop go through the dirty guard.
 */
@Component({
    selector: 'app-role-form-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, MatCheckboxModule, MatDialogModule, PenFieldComponent],
    template: `
        <h2 mat-dialog-title>{{ data.role ? 'Edit role "' + data.role.name + '"' : 'New role' }}</h2>
        <form (ngSubmit)="save()" [formGroup]="form">
            <mat-dialog-content class="flex w-100 max-w-full flex-col gap-4">
                @if (!data.role) {
                    <inspecto-pen-field
                        label="Role name"
                        [required]="true"
                        [mono]="true"
                        placeholder="e.g. fraud-analyst"
                        help="The IdP role claim value — lowercase; assignment stays in your identity provider."
                        formControlName="name"
                        [error]="nameError()"
                    ></inspecto-pen-field>
                }

                <fieldset class="flex flex-col gap-1">
                    <legend class="text-secondary mb-1 text-sm font-medium">Capabilities</legend>
                    @for (cap of data.vocabulary; track cap) {
                        <mat-checkbox [checked]="selected.has(cap)" (change)="toggle(cap, $event.checked)">
                            {{ cap }}
                        </mat-checkbox>
                    }
                </fieldset>

                <inspecto-pen-field
                    label="Data scopes"
                    ariaLabel="Data scopes, comma-separated"
                    placeholder="e.g. fraud, billing"
                    help="Comma-separated case types this role may see (SEC-7d); empty = unscoped."
                    formControlName="dataScopes"
                ></inspecto-pen-field>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button mat-button type="button" (click)="requestClose()">Cancel</button>
                <button mat-flat-button color="primary" type="submit">Save</button>
            </mat-dialog-actions>
        </form>
    `,
})
export class RoleFormDialog {
    private readonly fb = inject(FormBuilder);
    private readonly ref = inject(MatDialogRef<RoleFormDialog, RoleDef | undefined>);
    private readonly confirm = inject(InspectoConfirmService);
    readonly data = inject<RoleFormData>(MAT_DIALOG_DATA);

    /** Working capability set — checkbox list state (order comes from the vocabulary). */
    readonly selected = new Set<string>(this.data.role?.capabilities ?? []);
    private capsDirty = false;

    readonly form = this.fb.nonNullable.group({
        name: [
            this.data.role?.name ?? '',
            this.data.role
                ? []
                : [
                      Validators.required,
                      Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                      (c: { value: string }) =>
                          this.data.existingNames.includes(String(c.value).trim().toLowerCase())
                              ? { duplicate: true }
                              : null,
                  ],
        ],
        dataScopes: [(this.data.role?.dataScopes ?? []).join(', ')],
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty || this.capsDirty, this.confirm);

    /** The Role name row's error line: shown once the row was left or Save was pressed. */
    nameError(): string {
        const c = this.form.controls.name;
        if (!c.touched) return '';
        if (c.hasError('required')) return 'A role name is required.';
        if (c.hasError('pattern')) return "Letters, digits, '.', '_' and '-' only.";
        if (c.hasError('duplicate')) return 'A role with this name already exists.';
        return '';
    }

    toggle(cap: string, checked: boolean): void {
        if (checked) this.selected.add(cap);
        else this.selected.delete(cap);
        this.capsDirty = true;
    }

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        const scopes = v.dataScopes
            .split(',')
            .map((s) => s.trim().toLowerCase())
            .filter(Boolean);
        this.ref.close({
            name: (this.data.role?.name ?? v.name).trim().toLowerCase(),
            // Keep vocabulary order so the saved list reads like the checkbox list.
            capabilities: this.data.vocabulary.filter((c) => this.selected.has(c)),
            ...(scopes.length ? { dataScopes: scopes } : {}),
        });
    }
}

import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import { EntityListDetail, EntityListPurpose, InvService } from 'app/inspecto/api';
import { EntityTypeConfig } from 'app/inspecto/api/link-analysis-settings.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { entityListErrorMessage } from './investigation-state';
import { isUnavailable } from './link-analysis-template.dialogs';

/** D-P10's four purposes, in the analyst's words. */
export const ENTITY_LIST_PURPOSES: readonly { value: EntityListPurpose; label: string; hint: string }[] = [
    { value: 'allow', label: 'Allow', hint: 'Entities known to be legitimate' },
    { value: 'block', label: 'Block', hint: 'Entities to block' },
    { value: 'watch', label: 'Watch', hint: 'Entities to keep an eye on' },
    { value: 'exclusion', label: 'Exclusion', hint: 'Entities to leave out of an analysis' },
];

const NOT_BLANK = /\S/;

// ── Create an Entity List ────────────────────────────────────────────────────────────────────────────

export interface CreateEntityListData {
    /** The Space's `entityTypesInForce` — a list's Entity Type must be one of them. */
    entityTypes: EntityTypeConfig[];
}

/**
 * LA-17 **New Entity List** — `POST /inv/entity-lists`. The id is left to the server to mint (ask the minimum);
 * a 409/422 stays in the dialog so the analyst can correct it. Closes with the created list.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
    ],
    template: `
        <h2 mat-dialog-title>New Entity List</h2>
        <mat-dialog-content class="flex flex-col gap-3 text-sm">
            <p class="m-0 text-xs">
                A named set of Entity keys in this Space. Every change is recorded with who, when and why, and the
                history is kept.
            </p>
            @if (!typeOptions.length) {
                <inspecto-alert variant="warning" title="No Entity Types">
                    No Entity Types are in force in this Space, so a list cannot be typed. Check the Link Analysis
                    settings.
                </inspecto-alert>
            }
            <form class="flex flex-col gap-2" [formGroup]="form" (ngSubmit)="save()">
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Title</mat-label>
                    <input matInput formControlName="title" />
                    @if (form.controls.title.hasError('required') || form.controls.title.hasError('pattern')) {
                        <mat-error>A title is required.</mat-error>
                    } @else if (form.controls.title.hasError('maxlength')) {
                        <mat-error>At most 200 characters.</mat-error>
                    }
                </mat-form-field>
                <inspecto-option-picker
                    label="Purpose"
                    formControlName="purpose"
                    [options]="purposeOptions"
                ></inspecto-option-picker>
                @if (submitted() && form.controls.purpose.invalid) {
                    <p class="text-warn m-0 text-xs" role="alert">Choose a purpose.</p>
                }
                <inspecto-option-picker
                    label="Entity Type"
                    formControlName="entityType"
                    [options]="typeOptions"
                ></inspecto-option-picker>
                @if (submitted() && form.controls.entityType.invalid) {
                    <p class="text-warn m-0 text-xs" role="alert">Choose the Entity Type its members are.</p>
                }
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Reason</mat-label>
                    <input matInput formControlName="reason" />
                    <mat-hint>Recorded with the change.</mat-hint>
                    @if (form.controls.reason.hasError('required') || form.controls.reason.hasError('pattern')) {
                        <mat-error>A reason is required — every change to a list is accounted for.</mat-error>
                    } @else if (form.controls.reason.hasError('maxlength')) {
                        <mat-error>At most 1000 characters.</mat-error>
                    }
                </mat-form-field>
            </form>
            @if (error()) {
                <inspecto-alert [variant]="unavailable() ? 'info' : 'error'" title="List not created">
                    {{ error() }}
                </inspecto-alert>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="busy()" (click)="save()">Create list</button>
        </mat-dialog-actions>
    `,
})
export class CreateEntityListDialog {
    readonly data = inject<CreateEntityListData>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<CreateEntityListDialog, EntityListDetail | undefined>);
    private inv = inject(InvService);
    private confirm = inject(InspectoConfirmService);

    readonly purposeOptions: PickerOption[] = ENTITY_LIST_PURPOSES.map((p) => ({ ...p }));
    readonly typeOptions: PickerOption[] = this.data.entityTypes.map((t) => ({
        value: t.id,
        label: t.label,
        hint: t.id,
    }));
    readonly form = new FormGroup({
        title: new FormControl('', {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern(NOT_BLANK), Validators.maxLength(200)],
        }),
        purpose: new FormControl<EntityListPurpose | null>(null, { validators: [Validators.required] }),
        entityType: new FormControl<string | null>(null, { validators: [Validators.required] }),
        reason: new FormControl('', {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern(NOT_BLANK), Validators.maxLength(1000)],
        }),
    });
    /** The pickers cannot show their own error on submit (angular-ui §4) — the dialog renders it once this is set. */
    readonly submitted = signal(false);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly unavailable = signal(false);
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    async save(): Promise<void> {
        if (this.busy()) return;
        this.submitted.set(true);
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const { title, purpose, entityType, reason } = this.form.getRawValue();
        this.busy.set(true);
        this.error.set('');
        this.unavailable.set(false);
        try {
            const list = await firstValueFrom(
                this.inv.createEntityList({
                    title: title.trim(),
                    purpose: purpose!,
                    entityType: entityType!,
                    reason: reason.trim(),
                }),
            );
            this.ref.close(list);
        } catch (err) {
            this.unavailable.set(isUnavailable(err));
            this.error.set(entityListErrorMessage(err, 'Could not create the Entity List.'));
        } finally {
            this.busy.set(false);
        }
    }
}

// ── Ask for a reason (add selection · retire · exclude by list) ─────────────────────────────────────

export interface EntityListReasonData {
    title: string;
    /** What will happen, in the analyst's words. */
    message: string;
    confirmLabel: string;
    /** A retire: the confirm button is the warn colour — the dialog IS the confirmation. */
    destructive?: boolean;
    /** The server's bound for this reason (1 000 for list writes; an exclusion's 200). */
    maxLength: number;
}

/**
 * LA-17 — the one required-reason prompt the Entity List actions share. It only ASKS: it closes with the trimmed
 * reason and the host makes the call, so a refusal is shown where the result is (the section, or the panel's
 * step error for `excludeBy`).
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
    template: `
        <h2 mat-dialog-title>{{ data.title }}</h2>
        <mat-dialog-content class="flex flex-col gap-3 text-sm">
            <p class="m-0">{{ data.message }}</p>
            <form [formGroup]="form" (ngSubmit)="submit()">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Reason</mat-label>
                    <input matInput formControlName="reason" />
                    @if (form.controls.reason.hasError('required') || form.controls.reason.hasError('pattern')) {
                        <mat-error>A reason is required — without one the change cannot be challenged.</mat-error>
                    } @else if (form.controls.reason.hasError('maxlength')) {
                        <mat-error>At most {{ data.maxLength }} characters.</mat-error>
                    }
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-flat-button [color]="data.destructive ? 'warn' : 'primary'" (click)="submit()">
                {{ data.confirmLabel }}
            </button>
        </mat-dialog-actions>
    `,
})
export class EntityListReasonDialog {
    readonly data = inject<EntityListReasonData>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<EntityListReasonDialog, string | undefined>);
    private confirm = inject(InspectoConfirmService);

    readonly form = new FormGroup({
        reason: new FormControl('', {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern(NOT_BLANK), Validators.maxLength(this.data.maxLength)],
        }),
    });
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    submit(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.ref.close(this.form.controls.reason.value.trim());
    }
}

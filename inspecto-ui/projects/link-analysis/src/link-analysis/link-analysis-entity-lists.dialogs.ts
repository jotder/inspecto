import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import {
    EntityListDetail,
    EntityListHeld,
    EntityListMembersRequest,
    EntityListMembersResult,
    EntityListPurpose,
    EntityListSummary,
    InvService,
} from '@inspecto/link-analysis/api/inv.service';
import { StatusBadgeComponent } from '@inspecto/core/components/status-badge.component';
import { expiryInstant, parseEntityListEntries } from './entity-list-entries';
import { EntityTypeConfig } from './link-analysis-settings.service';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { guardDirtyClose } from '@inspecto/core/dialog-dirty-guard';
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
 * LA-17 **New Entity List** — `POST /entity-lists`. The id is left to the server to mint (ask the minimum);
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

// ── Edit entries: exact keys, ranges / CIDR, expiry (ASSURE-ENTITY-LISTS-RESIDUALS-1 (2)) ───────────────

export interface EntityListEntriesData {
    list: EntityListSummary;
    /** The Entity Type's label, for the normalisation hint. */
    typeLabel: string;
}

/** What the dialog closes with: the server's answer, so the host reports applied vs held. */
export type EntityListEntriesResult = EntityListMembersResult | EntityListHeld;

/**
 * Add or remove entries on one list — exact keys, prefixes, same-length ranges and CIDR blocks, one per line
 * (`parseEntityListEntries`) — with an optional expiry on an add. It reads the list first and shows its current
 * range entries and expiring entries as served (masked per `maskingMode`). A 422/409 stays in the dialog; a
 * governed change closes with the `202` Pending Change body.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        StatusBadgeComponent,
    ],
    template: `
        <h2 mat-dialog-title>Edit entries — {{ data.list.title }}</h2>
        <mat-dialog-content class="flex flex-col gap-3 text-sm">
            @if (detail(); as d) {
                <section aria-label="Current range and expiring entries" class="flex flex-col gap-1 text-xs">
                    <p class="text-secondary m-0">
                        {{ d.size }} {{ d.size === 1 ? 'member' : 'members' }} · {{ (d.ranges ?? []).length }} range
                        {{ (d.ranges ?? []).length === 1 ? 'entry' : 'entries' }}
                    </p>
                    @if ((d.ranges ?? []).length) {
                        <ul class="m-0 list-none p-0 font-mono" aria-label="Range entries">
                            @for (r of d.ranges; track r) {
                                <li>{{ r }}</li>
                            }
                        </ul>
                    }
                    @if ((d.expiring ?? []).length) {
                        <ul class="m-0 list-none p-0" aria-label="Expiring entries">
                            @for (e of d.expiring; track e.entry + e.kind) {
                                <li class="flex items-center gap-1">
                                    <span class="font-mono">{{ e.entry }}</span>
                                    <span class="text-secondary">until {{ e.expiresAt }}</span>
                                    @if (e.expired) {
                                        <inspecto-status-badge value="expired" label="Expired"></inspecto-status-badge>
                                    }
                                </li>
                            }
                        </ul>
                    }
                </section>
            }
            <form class="flex flex-col gap-2" [formGroup]="form" (ngSubmit)="save()">
                <mat-button-toggle-group formControlName="mode" aria-label="Add or remove entries">
                    <mat-button-toggle value="add">Add</mat-button-toggle>
                    <mat-button-toggle value="remove">Remove</mat-button-toggle>
                </mat-button-toggle-group>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Entries, one per line</mat-label>
                    <textarea matInput rows="6" formControlName="entries"></textarea>
                    <mat-hint>
                        A key · a prefix like +4478* · a range like 447800..447899 (same length) · a CIDR block like
                        10.1.0.0/16. Keys and bounds are normalised as {{ data.typeLabel }}.
                    </mat-hint>
                    @if (form.controls.entries.hasError('required') || form.controls.entries.hasError('pattern')) {
                        <mat-error>Enter at least one entry.</mat-error>
                    }
                </mat-form-field>
                @if (parseErrors().length) {
                    <ul class="text-warn m-0 pl-4 text-xs" role="alert" aria-label="Refused lines">
                        @for (e of parseErrors(); track e) {
                            <li>{{ e }}</li>
                        }
                    </ul>
                }
                @if (form.controls.mode.value === 'add') {
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Expires (optional)</mat-label>
                        <input matInput type="datetime-local" formControlName="expires" />
                        <mat-hint>Blank keeps the entries. An expiring add never shortens a permanent entry.</mat-hint>
                    </mat-form-field>
                    @if (expiryError()) {
                        <p class="text-warn m-0 text-xs" role="alert">{{ expiryError() }}</p>
                    }
                }
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Reason</mat-label>
                    <input matInput formControlName="reason" />
                    @if (form.controls.reason.hasError('required') || form.controls.reason.hasError('pattern')) {
                        <mat-error>A reason is required — every change to a list is accounted for.</mat-error>
                    } @else if (form.controls.reason.hasError('maxlength')) {
                        <mat-error>At most 1000 characters.</mat-error>
                    }
                </mat-form-field>
            </form>
            @if (error()) {
                <inspecto-alert variant="error" title="Entries not changed">{{ error() }}</inspecto-alert>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="busy()" (click)="save()">
                {{ form.controls.mode.value === 'add' ? 'Add entries' : 'Remove entries' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class EntityListEntriesDialog {
    readonly data = inject<EntityListEntriesData>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<EntityListEntriesDialog, EntityListEntriesResult | undefined>);
    private inv = inject(InvService);
    private confirm = inject(InspectoConfirmService);

    readonly form = new FormGroup({
        mode: new FormControl<'add' | 'remove'>('add', { nonNullable: true }),
        entries: new FormControl('', {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern(NOT_BLANK)],
        }),
        expires: new FormControl('', { nonNullable: true }),
        reason: new FormControl('', {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern(NOT_BLANK), Validators.maxLength(1000)],
        }),
    });
    readonly detail = signal<EntityListDetail | null>(null);
    readonly parseErrors = signal<string[]>([]);
    readonly expiryError = signal('');
    readonly busy = signal(false);
    readonly error = signal('');
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    constructor() {
        // Best effort: the current entries are context, not a precondition of the edit.
        this.inv.getEntityList(this.data.list.id).subscribe({
            next: (d) => this.detail.set(d),
            error: () => undefined,
        });
    }

    async save(): Promise<void> {
        if (this.busy()) return;
        const v = this.form.getRawValue();
        const parsed = parseEntityListEntries(v.entries);
        this.parseErrors.set(parsed.errors);
        const expiry = v.mode === 'add' ? expiryInstant(v.expires) : {};
        this.expiryError.set(expiry.error ?? '');
        if (this.form.invalid || parsed.errors.length || expiry.error) {
            this.form.markAllAsTouched();
            return;
        }
        const reason = v.reason.trim();
        const req: EntityListMembersRequest =
            v.mode === 'add'
                ? { add: parsed.keys, addRanges: parsed.ranges, reason }
                : { remove: parsed.keys, removeRanges: parsed.ranges, reason };
        if (expiry.iso) req.expiresAt = expiry.iso;
        this.busy.set(true);
        this.error.set('');
        try {
            this.ref.close(await firstValueFrom(this.inv.changeEntityListMembers(this.data.list.id, req)));
        } catch (err) {
            this.error.set(entityListErrorMessage(err, 'Could not change the Entity List.'));
        } finally {
            this.busy.set(false);
        }
    }
}

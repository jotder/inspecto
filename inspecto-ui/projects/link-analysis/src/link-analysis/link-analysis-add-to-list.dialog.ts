import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { guardDirtyClose } from '@inspecto/core/dialog-dirty-guard';
import {
    EntityListPurpose,
    EntityListSummary,
    InvService,
    isHeldListChange,
} from '@inspecto/link-analysis/api/inv.service';
import type { PackTerms } from './grow-exclude';
import { entityListErrorMessage } from './investigation-state';
import { ENTITY_LIST_PURPOSES } from './link-analysis-entity-lists.dialogs';

export interface AddToListData {
    /** The entities' labels, to say what is being added. */
    labels: string[];
    /** Their raw values (a masked node gives its alias, written as is, as Exclude → Add to a list does). */
    values: string[];
    /** The Entity Type they are; null = only an existing list can take them. */
    entityType: string | null;
    /** The Space's Entity Lists (retired ones are not offered). */
    lists: EntityListSummary[];
    /** The purpose a new list starts with (the busiest hint asks for an exclusion list). */
    purpose?: EntityListPurpose;
    terms: PackTerms;
}

/** What was written: the list, its purpose, and the sentence to show. */
export interface AddToListResult {
    listId: string;
    title: string;
    purpose: EntityListPurpose;
    message: string;
}

const NEW_LIST = '__new__';
const NOT_BLANK = /\S/;

/**
 * LA-ONBOARD-1 phase 7, UC-10 **Add to a list** from a *Find something interesting* result: pick one of the Space's
 * lists of the same Entity Type, or create one with a purpose in plain words, then add the entities with a reason. The
 * dialog makes the calls (`POST /entity-lists`, `POST /entity-lists/{id}/members`) and closes with what it wrote; a
 * refusal stays in the dialog.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
    ],
    template: `
        <h2 mat-dialog-title>
            Add {{ data.values.length.toLocaleString('en') }}
            {{ data.values.length === 1 ? data.terms.node : data.terms.nodes }} to a list
        </h2>
        <mat-dialog-content class="flex flex-col gap-3 text-sm">
            <p class="m-0 font-mono text-xs" data-testid="la-add-list-what">
                {{ data.labels.slice(0, 5).join(', ') }}{{ data.labels.length > 5 ? ', …' : '' }}
            </p>
            <form [formGroup]="form" (ngSubmit)="submit()" class="flex flex-col gap-3">
                <inspecto-option-picker
                    label="List"
                    [options]="listOptions()"
                    formControlName="listId"
                    data-testid="la-add-list-pick"
                ></inspecto-option-picker>
                @if (listMissing()) {
                    <p class="text-warn m-0 text-xs" role="alert">Choose a list, or create a new one.</p>
                }
                @if (form.controls.listId.value === NEW_LIST) {
                    <inspecto-pen-field
                        label="New list name"
                        formControlName="newTitle"
                        [maxlength]="200"
                        [error]="titleError()"
                    ></inspecto-pen-field>
                    <inspecto-option-picker
                        label="What the list is for"
                        [options]="purposeOptions"
                        formControlName="purpose"
                    ></inspecto-option-picker>
                }
                <inspecto-pen-field
                    label="Reason"
                    formControlName="reason"
                    [maxlength]="1000"
                    [error]="reasonError()"
                ></inspecto-pen-field>
            </form>
            @if (error()) {
                <inspecto-alert variant="error" title="Not added">{{ error() }}</inspecto-alert>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button
                mat-flat-button
                color="primary"
                type="button"
                data-testid="la-add-list-go"
                [disabled]="busy()"
                (click)="submit()"
            >
                Add to the list
            </button>
        </mat-dialog-actions>
    `,
})
export class LinkAnalysisAddToListDialog {
    readonly data = inject<AddToListData>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<LinkAnalysisAddToListDialog, AddToListResult | undefined>);
    private confirm = inject(InspectoConfirmService);
    private inv = inject(InvService);
    readonly NEW_LIST = NEW_LIST;

    /** Plain words for a purpose (UC-10 step 2). */
    readonly purposeOptions: PickerOption[] = ENTITY_LIST_PURPOSES.map((p) => ({
        value: p.value,
        label: PURPOSE_WORDS[p.value],
        hint: p.hint,
    }));
    private readonly targets = this.data.lists.filter(
        (l) => !l.retired && (!this.data.entityType || l.entityType === this.data.entityType),
    );
    readonly listOptions = computed<PickerOption[]>(() => [
        ...this.targets.map((l) => ({
            value: l.id,
            label: l.title,
            hint: `${PURPOSE_WORDS[l.purpose] ?? l.purpose} · ${l.size.toLocaleString('en')} ${l.size === 1 ? 'member' : 'members'}`,
        })),
        ...(this.data.entityType ? [{ value: NEW_LIST, label: 'Create a new list…', hint: 'A list you keep' }] : []),
    ]);

    readonly form = new FormGroup({
        listId: new FormControl<string | null>(
            this.targets.length ? null : this.data.entityType ? NEW_LIST : null,
        ),
        newTitle: new FormControl('', { nonNullable: true }),
        purpose: new FormControl<EntityListPurpose>(this.data.purpose ?? 'watch', { nonNullable: true }),
        reason: new FormControl('Found with Find something interesting', {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern(NOT_BLANK), Validators.maxLength(1000)],
        }),
    });
    private readonly submitted = signal(false);
    private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });
    readonly busy = signal(false);
    readonly error = signal('');

    readonly listMissing = computed(() => this.submitted() && !this.value().listId);
    readonly titleError = computed(() =>
        this.submitted() && this.value().listId === NEW_LIST && !NOT_BLANK.test(this.value().newTitle ?? '')
            ? 'Name the new list.'
            : '',
    );
    readonly reasonError = computed(() =>
        this.submitted() && !NOT_BLANK.test(this.value().reason ?? '')
            ? 'A reason is required - every change to a list is accounted for.'
            : '',
    );
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    async submit(): Promise<void> {
        if (this.busy()) return;
        this.submitted.set(true);
        this.form.markAllAsTouched();
        const v = this.form.getRawValue();
        if (!v.listId || this.form.controls.reason.invalid) return;
        if (v.listId === NEW_LIST && !NOT_BLANK.test(v.newTitle)) return;
        const reason = v.reason.trim();
        this.busy.set(true);
        this.error.set('');
        try {
            let list: { id: string; title: string; purpose: EntityListPurpose };
            if (v.listId === NEW_LIST) {
                const made = await firstValueFrom(
                    this.inv.createEntityList({
                        title: v.newTitle.trim(),
                        purpose: v.purpose,
                        entityType: this.data.entityType!,
                        reason,
                    }),
                );
                list = made;
            } else {
                list = this.targets.find((l) => l.id === v.listId)!;
            }
            const r = await firstValueFrom(this.inv.changeEntityListMembers(list.id, { add: this.data.values, reason }));
            const message = isHeldListChange(r)
                ? `Waiting for approval: adding to “${list.title}” needs a second person to approve it.`
                : r.changed
                  ? `${r.changed.toLocaleString('en')} added to “${list.title}” - it now has ${r.size.toLocaleString('en')}.`
                  : `Nothing changed - “${list.title}” already holds ${this.data.values.length === 1 ? 'it' : 'them'}.`;
            this.ref.close({ listId: list.id, title: list.title, purpose: list.purpose, message });
        } catch (err) {
            this.error.set(entityListErrorMessage(err, 'The list could not be changed.'));
        } finally {
            this.busy.set(false);
        }
    }
}

/** A list's purpose in plain words (UC-10): *Watch these* · *Never follow these* · *Known good* · *Block*. */
export const PURPOSE_WORDS: Record<EntityListPurpose, string> = {
    watch: 'Watch these',
    exclusion: 'Never follow these (exclusion)',
    allow: 'Known good',
    block: 'Block',
};

import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { guardDirtyClose } from '@inspecto/core/dialog-dirty-guard';
import type { ExclusionListSummary } from '@inspecto/link-analysis/api/inv.service';
import { PackTerms, listTargets } from './grow-exclude';

export interface ExcludeDialogData {
    count: number;
    /** The first few labels, to say what is being excluded. */
    labels: string[];
    mode: 'investigation' | 'basic';
    terms: PackTerms;
    /** null = the lists cannot be read here (an older server): "Add to a list" says so. */
    lists: ExclusionListSummary[] | null;
    /** The Entity Type of the selected items; null = they cannot go on a list. */
    entityType: string | null;
    /** `canManageIncidents`: analyst lists. */
    canManageLists: boolean;
    /** `canManageExclusionCategories`: admin (category) lists too. */
    canManageCategories: boolean;
    /** Why "From this Investigation only" cannot run (no write capability); empty = it can. */
    hereWhyNot: string;
}

export type ExcludeDialogResult =
    | { kind: 'here'; reason: string }
    | { kind: 'list'; listId?: string; newTitle?: string; reason: string };

const NEW_LIST = '__new__';
const NOT_BLANK = /\S/;

/**
 * LA-GROW-EXCLUDE-1 §3.3 — **Exclude…** (right-click a node or a selection, or X): two choices, one primary action.
 * *From this Investigation only* is one `exclude` op (Basic: hide from this graph); *Add to a list…* picks or creates an
 * analyst exclusion list (a category list only with the capability), so the items stay out in every later step. It only
 * ASKS: it closes with the choice and the screen acts.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatRadioModule,
        MatTooltipModule,
        InspectoOptionPickerComponent,
        PenFieldComponent,
    ],
    template: `
        <h2 mat-dialog-title [matTooltip]="'Exclusion: the items leave the graph and their links go with them'">
            Exclude {{ data.count.toLocaleString('en') }} {{ data.count === 1 ? data.terms.node : data.terms.nodes }}
        </h2>
        <mat-dialog-content class="flex flex-col gap-3 text-sm">
            <p class="m-0 font-mono text-xs" data-testid="la-exclude-what">
                {{ data.labels.slice(0, 5).join(', ') }}{{ data.count > 5 ? ', …' : '' }}
            </p>
            <form [formGroup]="form" (ngSubmit)="submit()" class="flex flex-col gap-3">
                <mat-radio-group formControlName="kind" class="flex flex-col gap-1" aria-label="How to exclude">
                    <mat-radio-button value="here" [disabled]="!!data.hereWhyNot" data-testid="la-exclude-here">
                        {{ data.mode === 'investigation' ? 'From this Investigation only' : 'Hide from this graph' }}
                    </mat-radio-button>
                    <p class="text-secondary m-0 ml-8 text-xs">
                        {{
                            data.hereWhyNot ||
                                (data.mode === 'investigation'
                                    ? 'One recorded step (exclude), with your reason. Undo reverts it.'
                                    : 'Only on this screen, not recorded. Undo brings them back.')
                        }}
                    </p>
                    <mat-radio-button value="list" [disabled]="!!listWhyNot()" data-testid="la-exclude-list">
                        Add to a list…
                    </mat-radio-button>
                    <p class="text-secondary m-0 ml-8 text-xs" data-testid="la-exclude-list-hint">
                        {{
                            listWhyNot() ||
                                (data.mode === 'investigation'
                                    ? 'They are excluded now and in every later step (exclusion list).'
                                    : 'Hidden here, and left out wherever the list is used (exclusion list).')
                        }}
                    </p>
                </mat-radio-group>

                @if (kind() === 'list') {
                    <inspecto-option-picker
                        label="List"
                        [options]="listOptions()"
                        formControlName="listId"
                        data-testid="la-exclude-pick"
                    ></inspecto-option-picker>
                    @if (listMissing()) {
                        <p class="text-warn m-0 text-xs" role="alert">Choose a list, or create a new one.</p>
                    }
                    @if (adminOnly(); as n) {
                        <p class="text-secondary m-0 text-xs" data-testid="la-exclude-admin-only">
                            {{ n }} category {{ n === 1 ? 'list is' : 'lists are' }} kept by an administrator and not offered here.
                        </p>
                    }
                    @if (form.controls.listId.value === NEW_LIST) {
                        <inspecto-pen-field
                            label="New list name"
                            formControlName="newTitle"
                            [maxlength]="200"
                            placeholder="e.g. Call centres"
                            [error]="titleError()"
                        ></inspecto-pen-field>
                    }
                }
                @if (needsReason()) {
                    <inspecto-pen-field label="Reason" formControlName="reason" [maxlength]="200" [error]="reasonError()"></inspecto-pen-field>
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" type="button" data-testid="la-exclude-go" (click)="submit()">Exclude</button>
        </mat-dialog-actions>
    `,
})
export class LinkAnalysisExcludeDialog {
    readonly data = inject<ExcludeDialogData>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<LinkAnalysisExcludeDialog, ExcludeDialogResult | undefined>);
    private confirm = inject(InspectoConfirmService);
    readonly NEW_LIST = NEW_LIST;

    private readonly targets = listTargets(this.data.lists, this.data.entityType, this.data.canManageCategories);

    /** Why "Add to a list…" is not possible here; empty = it is. */
    readonly listWhyNot = signal(
        !this.data.canManageLists
            ? 'Adding to a list needs the Incident management capability.'
            : this.data.lists === null
              ? 'Exclusion lists cannot be read on this server.'
              : !this.data.entityType && !this.targets.length
                ? `These ${this.data.terms.nodes} have no Entity Type, so they cannot go on a list.`
                : '',
    );

    readonly form = new FormGroup({
        kind: new FormControl<'here' | 'list'>(this.data.hereWhyNot ? 'list' : 'here', { nonNullable: true }),
        listId: new FormControl<string | null>(null),
        newTitle: new FormControl('', { nonNullable: true }),
        reason: new FormControl(
            this.data.mode === 'investigation' ? 'Not relevant to this Investigation' : 'Not relevant to this analysis',
            { nonNullable: true, validators: [Validators.required, Validators.pattern(NOT_BLANK), Validators.maxLength(200)] },
        ),
    });
    readonly kind = toSignal(this.form.controls.kind.valueChanges, { initialValue: this.form.controls.kind.value });
    private readonly submitted = signal(false);
    private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

    /** A local hide (Basic) records nothing, so it asks no reason; a recorded step and a list write do. */
    readonly needsReason = computed(() => this.data.mode === 'investigation' || this.kind() === 'list');
    readonly listOptions = computed<PickerOption[]>(() => [
        ...this.targets
            .filter((x) => !x.whyNot)
            .map((x) => ({
                value: x.list.id,
                label: x.list.title,
                hint: `${x.list.ownerKind === 'admin' ? 'Category list (administrator)' : 'Analyst list'} · ${x.list.size.toLocaleString('en')} members`,
            })),
        ...(this.data.entityType ? [{ value: NEW_LIST, label: 'Create a new list…', hint: 'An analyst list you keep' }] : []),
    ]);
    readonly adminOnly = computed(() => this.targets.filter((x) => x.whyNot).length);
    readonly listMissing = computed(() => this.submitted() && this.value().kind === 'list' && !this.value().listId);
    readonly titleError = computed(() =>
        this.submitted() && this.value().listId === NEW_LIST && !NOT_BLANK.test(this.value().newTitle ?? '')
            ? 'Name the new list.'
            : '',
    );
    readonly reasonError = computed(() =>
        this.submitted() && !NOT_BLANK.test(this.value().reason ?? '')
            ? 'A reason is required — without one the change cannot be challenged.'
            : '',
    );
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    submit(): void {
        this.submitted.set(true);
        this.form.markAllAsTouched();
        const v = this.form.getRawValue();
        if (this.needsReason() && this.form.controls.reason.invalid) return;
        const reason = v.reason.trim();
        if (v.kind === 'here') {
            if (this.data.hereWhyNot) return;
            this.ref.close({ kind: 'here', reason });
            return;
        }
        if (this.listWhyNot() || !v.listId) return;
        if (v.listId === NEW_LIST) {
            if (!NOT_BLANK.test(v.newTitle)) return;
            this.ref.close({ kind: 'list', newTitle: v.newTitle.trim(), reason });
            return;
        }
        this.ref.close({ kind: 'list', listId: v.listId, reason });
    }
}

import {
    AfterViewInit,
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    inject,
    signal,
    ViewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    DbBrowserService,
    Expectation,
    ExpectationKind,
    ExpectationsService,
    ExpectationUpsert,
} from 'app/inspecto/api';
import { AiAssistComponent } from 'app/inspecto/ai-assist/ai-assist.component';
import { AiDraft } from 'app/inspecto/ai-assist/ai-draft';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import {
    columnOptionLoader,
    datasetOptionLoader,
    pipelineOrJobOptionLoader,
} from 'app/inspecto/components/entity-option-loaders';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { QueryConditionGroupComponent } from 'app/inspecto/query/query-condition-group.component';
import { dbColumnType } from 'app/inspecto/query/query-columns';
import { Condition, ColumnMeta, ConditionGroup, emptyGroup } from 'app/inspecto/query/query-types';
import { EXPECTATION_ATTRIBUTES } from './expectation-attributes';

/** The distinct fields an existing when-clause already references (typed string until probed) — the
 *  same seeding idiom `decision-rule-form.dialog` uses so a stored condition tree's fields render
 *  before (or without) a successful column probe. */
function referencedFields(group: ConditionGroup): string[] {
    const out: string[] = [];
    const walk = (item: Condition | ConditionGroup): void => {
        if (item.kind === 'group') item.items.forEach(walk);
        else if (item.field) out.push(item.field);
    };
    walk(group);
    return [...new Set(out)];
}

/** Dialog input: an existing expectation ⇒ edit; absent ⇒ create. */
export interface ExpectationFormData {
    expectation?: Expectation;
    /** Ids already in use — on create the name control rejects a duplicate inline (product-wide rule). */
    existingNames?: string[];
}
export interface ExpectationFormResult {
    saved?: Expectation;
}

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) =>
        set.has(
            String(c.value ?? '')
                .trim()
                .toLowerCase(),
        )
            ? { duplicate: true }
            : null;
}

/**
 * Create / edit an Expectation (C2) — fully SchemaForm-driven ({@link EXPECTATION_ATTRIBUTES}); the
 * kind-specific parameters (range bounds / regex / reference) appear via `dependsOn`. The id is
 * immutable on edit (it is the storage key). Create is two steps (ui-design-review R9 — name at
 * save): the config step asks the target/check; the save step then asks the id + description.
 */
@Component({
    selector: 'app-expectation-form-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
        QueryConditionGroupComponent,
        AiAssistComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>
            {{
                isEdit
                    ? 'Edit expectation "' + data.expectation!.name + '"'
                    : step() === 'save'
                      ? 'Save expectation'
                      : 'New expectation'
            }}
        </h2>
        <mat-dialog-content>
            @if (writesDisabled()) {
                <inspecto-alert variant="warning" title="Writes are disabled">
                    The server is running read-only — the expectation was not saved.
                </inspecto-alert>
            }
            <!-- Config step content stays mounted (not @if'd) so the schema-form ViewChild survives the
                 step transition — only visually hidden via [hidden], never destroyed. -->
            <div [hidden]="step() === 'save'">
                <inspecto-schema-form
                    [specs]="attributes"
                    [initial]="initialValue"
                    [optionLoaders]="optionLoaders"
                    (submitted)="save()"
                ></inspecto-schema-form>

                <!-- AGT-6a A2/A3: profile the chosen target+column and fill the check in from what the data
                     actually shows. The dialog already knows both, so nothing is re-stated. -->
                <inspecto-ai-assist
                    class="mt-3 block"
                    tool="suggest_expectations"
                    [args]="aiArgs()"
                    [current]="aiCurrent()"
                    label="Suggest from the data"
                    [disabled]="!aiTarget() || !aiColumn()"
                    disabledReason="Set Target and Column above first"
                    (applyDraft)="applySuggestion($event)"
                ></inspecto-ai-assist>

                @if (kind() === 'condition') {
                    <div class="mt-4 font-semibold">Records violate this check when</div>
                    <inspecto-query-condition-group
                        class="mt-2 block"
                        [group]="when"
                        [columns]="columns()"
                        [root]="true"
                    />
                    @if (!columns().length) {
                        <div class="text-secondary mt-1 text-sm">
                            Field choices load from the target's records — set Target above first.
                        </div>
                    } @else if (whenEmpty()) {
                        <div class="text-secondary mt-1 text-sm">
                            No conditions yet — every record would violate this check.
                        </div>
                    }
                }
            </div>
            @if (!isEdit && step() === 'save') {
                <!-- Save step (create only): id + description, asked only now. -->
                <form [formGroup]="saveForm" aria-label="Name this expectation" class="space-y-1">
                    <div class="text-secondary text-sm">Check configured — give it a unique id to save it.</div>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Expectation id</mat-label>
                        <input matInput formControlName="name" required cdkFocusInitial />
                        @if (saveForm.controls.name; as c) {
                            @if (c.hasError('required')) {
                                <mat-error>An id is required.</mat-error>
                            } @else if (c.hasError('pattern')) {
                                <mat-error
                                    >Start with a letter or digit; then letters, digits,
                                    <code>. _ -</code> only.</mat-error
                                >
                            } @else if (c.hasError('duplicate')) {
                                <mat-error>An expectation with this id already exists.</mat-error>
                            }
                        }
                    </mat-form-field>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Description</mat-label>
                        <textarea matInput formControlName="description" rows="2"></textarea>
                    </mat-form-field>
                </form>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            @if (!isEdit && step() === 'save') {
                <button type="button" mat-button (click)="backToConfig()">Back</button>
            }
            <button type="button" mat-button (click)="requestClose()" [disabled]="saving()">Cancel</button>
            <button type="button" mat-flat-button color="primary" [disabled]="saving()" (click)="save()">
                {{ isEdit ? 'Save' : step() === 'save' ? 'Create' : 'Continue' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class ExpectationFormDialog implements AfterViewInit {
    private fb = inject(FormBuilder);
    private api = inject(ExpectationsService);
    private db = inject(DbBrowserService);
    private destroyRef = inject(DestroyRef);
    private ref = inject(MatDialogRef<ExpectationFormDialog, ExpectationFormResult>);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    readonly data = inject<ExpectationFormData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    /** Guarded close: Esc / backdrop / Cancel confirm before discarding a dirty form. */
    readonly requestClose = guardDirtyClose(
        this.ref,
        () => (this.schemaForm?.isDirty() ?? false) || this.saveForm.dirty,
        this.confirm,
    );

    /** Suggestion sources: `target` follows the Attach-to picker; `refDataset` = dataset components;
     *  `column`/`refColumn` probe the sibling target's / reference dataset's records (R2 follow-up). */
    readonly optionLoaders = {
        target: pipelineOrJobOptionLoader(),
        refDataset: datasetOptionLoader(),
        column: columnOptionLoader('target'),
        refColumn: columnOptionLoader('refDataset'),
    };

    readonly isEdit = !!this.data.expectation;
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly attributes = EXPECTATION_ATTRIBUTES;

    /** Create flow: `config` (target/check) → `save` (id + description, asked last). Edit stays on `config`. */
    readonly step = signal<'config' | 'save'>('config');

    /** Save-step fields (create only): the expectation id IS the unique storage key; description optional. */
    readonly saveForm = this.fb.group({
        name: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                ...(this.data.existingNames?.length ? [uniqueNameValidator(this.data.existingNames)] : []),
            ],
        ],
        description: [''],
    });

    readonly initialValue: Record<string, unknown> | undefined = this.data.expectation
        ? {
              targetType: this.data.expectation.targetType,
              target: this.data.expectation.target,
              column: this.data.expectation.column,
              kind: this.data.expectation.kind,
              min: this.data.expectation.min ?? undefined,
              max: this.data.expectation.max ?? undefined,
              pattern: this.data.expectation.pattern ?? '',
              refDataset: this.data.expectation.refDataset ?? '',
              refColumn: this.data.expectation.refColumn ?? '',
              severity: this.data.expectation.severity,
              enabled: this.data.expectation.enabled,
          }
        : undefined;

    /** Deep-cloned on edit — the condition editor mutates the bound group in place. */
    readonly when: ConditionGroup = this.data.expectation?.when
        ? structuredClone(this.data.expectation.when)
        : emptyGroup('AND');

    /** The when-clause field choices — probed from the target's records, seeded with fields an
     *  existing condition already references (the decision-rule-form idiom). */
    readonly columns = signal<ColumnMeta[]>(this.seedColumns());
    /** The live `kind` value, so the template shows the condition editor only for `kind: 'condition'`. */
    readonly kind = signal<ExpectationKind | undefined>(this.data.expectation?.kind);

    private seedColumns(): ColumnMeta[] {
        return referencedFields(this.when).map((name) => ({ name, type: 'string' as const }));
    }

    private loadColumns(target: string): void {
        const name = target.trim();
        if (!name) {
            this.columns.set(this.seedColumns());
            return;
        }
        this.db.table({ name, limit: 1 }).subscribe({
            next: (res) => {
                const fetched: ColumnMeta[] = res.columns.map((c) => ({ name: c.name, type: dbColumnType(c.type) }));
                const known = new Set(fetched.map((c) => c.name));
                this.columns.set([...fetched, ...this.seedColumns().filter((c) => !known.has(c.name))]);
            },
            error: () => this.columns.set(this.seedColumns()),
        });
    }

    whenEmpty(): boolean {
        return this.when.items.length === 0;
    }

    ngAfterViewInit(): void {
        if (this.isEdit) {
            this.saveForm.patchValue({
                name: this.data.expectation!.name,
                description: this.data.expectation!.description ?? '',
            });
        }
        const target = this.schemaForm.form.get('target');
        target?.valueChanges
            .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
            .subscribe((t) => this.loadColumns(String(t ?? '')));
        this.loadColumns(String(target?.value ?? ''));

        this.schemaForm.form
            .get('kind')
            ?.valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((k) => this.kind.set(k as ExpectationKind));

        // AGT-6a A3: mirror target/column into signals so the inline surface's args stay reactive
        // without it reaching into the form itself.
        this.aiTarget.set(String(target?.value ?? ''));
        target?.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((t) => this.aiTarget.set(String(t ?? '')));
        const column = this.schemaForm.form.get('column');
        this.aiColumn.set(String(column?.value ?? ''));
        column?.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((c) => this.aiColumn.set(String(c ?? '')));
    }

    /** The target/column the operator has chosen — the inline surface's context (AGT-6a A3). */
    readonly aiTarget = signal('');
    readonly aiColumn = signal('');

    /**
     * `suggest_expectations` arguments from what the dialog already knows.
     *
     * ⚠ `target` and `table` are DIFFERENT vocabularies: `target` is a pipeline/job id, `table` is a
     * browsable store. Nothing in the UI maps between them, but the existing column probe
     * (`columnOptionLoader('target')` → `/db/table`) already uses the target value verbatim as a store
     * name, and the backend defaults `target` to `table` when omitted — so passing the same value for
     * both matches established behaviour. When the target is not a browsable store the tool answers
     * "unknown table", which the surface reports as an ordinary rejection; that is expected, not a bug.
     */
    aiArgs(): Record<string, unknown> {
        return { table: this.aiTarget(), target: this.aiTarget(), column: this.aiColumn() };
    }

    /** The configured check as the diff baseline, so a suggestion is reviewed against it, not blind. */
    aiCurrent(): Record<string, unknown> | null {
        const value = this.schemaForm?.value() as Record<string, unknown> | undefined;
        return value && Object.keys(value).length ? value : null;
    }

    /**
     * Adopt a profiled suggestion into the form (AGT-6a A2). Nothing is written here — the operator still
     * completes the existing two-step flow and presses Create/Save, so the write goes through the
     * dialog's own validated route with the human as the audited actor (decision D2).
     */
    applySuggestion(draft: AiDraft): void {
        const s = draft.config as Record<string, unknown>;
        // `kind` must land in the SAME patch as min/max: those controls are only enabled while
        // kind === 'range', and a disabled control's value is excluded from schemaForm.value().
        this.schemaForm.form.patchValue({
            targetType: s['targetType'] ?? 'pipeline',
            target: s['target'] ?? this.aiTarget(),
            column: s['column'] ?? this.aiColumn(),
            kind: s['kind'],
            ...(s['min'] !== undefined ? { min: s['min'] } : {}),
            ...(s['max'] !== undefined ? { max: s['max'] } : {}),
            ...(s['severity'] !== undefined ? { severity: s['severity'] } : {}),
        });
        // An applied suggestion IS an unsaved edit — keep the dirty-close guard honest.
        this.schemaForm.form.markAsDirty();
        // `name`/`description` are save-step fields (R9), so the tool's suggested id goes there.
        if (!this.isEdit) {
            this.saveForm.patchValue({
                name: String(s['name'] ?? this.suggestedName()),
                description: String(s['description'] ?? ''),
            });
            // ⚠ patchValue leaves the control PRISTINE, and the save step regenerates the id from the
            // target/column whenever it is pristine — so the accepted suggestion's own name was silently
            // replaced on Continue while its description survived, changing only the id under the
            // operator. Marking it dirty is what tells that check a human's choice is already in there.
            this.saveForm.controls.name.markAsDirty();
        }
    }

    /** The suggested expectation id: `<target>_<column>_<kind>`. */
    suggestedName(): string {
        const v = this.schemaForm.value() as { target?: string; column?: string; kind?: string };
        const base = [v.target, v.column, v.kind].filter(Boolean).join('_') || 'expectation';
        return base.replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^[^A-Za-z0-9]+/, '');
    }

    /** Create flow only: leave the save step back to the config step (id/description are kept). */
    backToConfig(): void {
        this.step.set('config');
    }

    save(): void {
        if (!this.schemaForm.validate()) return;
        // Create asks the id + description only now, at save time — config valid ⇒ advance.
        if (!this.isEdit && this.step() === 'config') {
            if (this.saveForm.controls.name.pristine) this.saveForm.patchValue({ name: this.suggestedName() });
            this.step.set('save');
            return;
        }
        if (!this.isEdit && this.saveForm.invalid) {
            this.saveForm.markAllAsTouched();
            return;
        }
        const v = this.schemaForm.value() as {
            targetType: 'pipeline' | 'job';
            target: string;
            column: string;
            kind: ExpectationKind;
            min?: number;
            max?: number;
            pattern?: string;
            refDataset?: string;
            refColumn?: string;
            severity?: string;
            enabled?: boolean;
        };
        const body: ExpectationUpsert = {
            name: this.isEdit ? this.data.expectation!.name : String(this.saveForm.getRawValue().name ?? '').trim(),
            description: String(this.saveForm.getRawValue().description ?? '').trim(),
            targetType: v.targetType,
            target: String(v.target ?? '').trim(),
            column: v.kind === 'condition' ? undefined : String(v.column ?? '').trim(),
            kind: v.kind,
            min: v.kind === 'range' ? (v.min ?? null) : null,
            max: v.kind === 'range' ? (v.max ?? null) : null,
            pattern: v.kind === 'regex' ? String(v.pattern ?? '').trim() : null,
            refDataset: v.kind === 'referential' ? String(v.refDataset ?? '').trim() : null,
            refColumn: v.kind === 'referential' ? String(v.refColumn ?? '').trim() : null,
            when: v.kind === 'condition' ? this.when : null,
            severity: v.severity ?? 'MAJOR',
            enabled: v.enabled !== false,
        };
        this.saving.set(true);
        const call = this.isEdit ? this.api.update(body.name, body) : this.api.create(body);
        call.subscribe({
            next: (saved) => this.ref.close({ saved }),
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                else this.toastr.error(apiErrorMessage(e, 'Could not save the expectation.'));
            },
        });
    }
}

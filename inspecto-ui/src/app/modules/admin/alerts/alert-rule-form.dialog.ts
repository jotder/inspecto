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
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { AlertRule, AlertRuleUpsert, AlertsService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import {
    datasetOptionLoader,
    pipelineOptionLoader,
    runbookOptionLoader,
} from 'app/inspecto/components/entity-option-loaders';
import { datasetColumnOptionLoader } from 'app/modules/admin/studio/datasets/dataset-column-option-loader';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { firstValueFrom } from 'rxjs';
import { QueryConditionGroupComponent } from 'app/inspecto/query/query-condition-group.component';
import { Condition, ColumnMeta, ConditionGroup, emptyGroup } from 'app/inspecto/query/query-types';
import { groupByValidator, measureError } from 'app/inspecto/query/measure-grammar';
import { ALERT_RULE_ATTRIBUTES } from './alert-rule-attributes';

/** The ledger-row fields a `when` clause can scope on (the columns `AlertService`'s metric math
 *  reads) — fixed, unlike Decision Rule/Expectation's probed-from-a-store columns, since a ledger
 *  row's shape is the engine's own, not a target's records. */
const LEDGER_COLUMNS: ColumnMeta[] = [
    { name: 'status', type: 'string' },
    { name: 'total_input_rows', type: 'number' },
    { name: 'total_output_rows', type: 'number' },
    { name: 'rejected_count', type: 'number' },
    { name: 'duration_ms', type: 'number' },
    { name: 'start_time', type: 'date' },
    { name: 'end_time', type: 'date' },
];

/** The distinct fields an existing when-clause already references — seeds the column list with any
 *  field not in {@link LEDGER_COLUMNS} (forward-compatible with a ledger schema change). */
function referencedFields(group: ConditionGroup): string[] {
    const out: string[] = [];
    const walk = (item: Condition | ConditionGroup): void => {
        if (item.kind === 'group') item.items.forEach(walk);
        else if (item.field) out.push(item.field);
    };
    walk(group);
    return [...new Set(out)];
}

/**
 * The two rule kinds this form authors: a ledger metric (`metric` + `window`, optional `when`) and a
 * Dataset measure (`dataset` + `measure`, optional per-entity `by` + `stormCap`) — `AlertRule.fromMap`
 * tells them apart by `dataset`. A freshness (`maximumAge`) or Investigation rule is neither: it is
 * edit-only here (threshold, severity, …) and keeps its target as stored — `null`.
 */
type AuthoredKind = 'metric' | 'measure';
function authoredKind(r: AlertRule | undefined): AuthoredKind | null {
    if (!r) return 'metric';
    if (r.maximumAge || r.investigation) return null;
    return r.dataset ? 'measure' : 'metric';
}

/** The keys only one kind writes. All of them count as "edited", so switching kind drops the other's. */
const KIND_KEYS = ['metric', 'window', 'when', 'dataset', 'measure', 'by', 'stormCap', 'healAfterSweeps'];

/** `measure` as `AlertRule` accepts it (`count | agg(column)`), via the shared, contract-pinned grammar. */
const measureValidator: ValidatorFn = (c: AbstractControl) => {
    const error = typeof c.value === 'string' ? measureError(c.value) : null;
    return error ? { message: error } : null;
};

/** `stormCap` is a positive WHOLE number (`AlertRule.wholeNumber`); the spec's `min: 1` covers the sign. */
const wholeNumberValidator: ValidatorFn = (c: AbstractControl) =>
    c.value === null || c.value === '' || Number.isInteger(Number(c.value))
        ? null
        : { message: 'Must be a whole number' };

/** `threshold` must be > 0 (`AlertRule`: "alert.threshold must be a positive number") — `min` would allow 0. */
const positiveValidator: ValidatorFn = (c: AbstractControl) =>
    c.value === null || c.value === '' || Number(c.value) > 0 ? null : { message: 'Threshold must be greater than 0' };

/** A freshness rule's breach is "age exceeded": the engine fixes comparator/threshold, so the form hides both. */
const FRESHNESS_FIXED = ['comparator', 'threshold'];

/** Dialog input: an existing rule ⇒ edit; absent ⇒ create. */
export interface AlertRuleFormData {
    rule?: AlertRule;
    /** Names already armed — on create the name control rejects a duplicate inline (product-wide rule). */
    existingNames?: string[];
    /** Create only: values to start from (a prefilled link, e.g. the Risk Scores pane's per-entity rule, D-RP9). */
    seed?: Partial<AlertRule>;
}
export interface AlertRuleFormResult {
    saved?: AlertRule;
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
 * Create / edit an Alert Rule (audit C3) — spec-driven via `<inspecto-schema-form>`
 * ({@link ALERT_RULE_ATTRIBUTES}); only the ledger kind's `when` tree is host-rendered. `kind` (form-only,
 * never written) picks a ledger-metric or a Dataset-measure rule, and each kind's fields hang off it by
 * `dependsOn`. Create is two steps (ui-design-review R9 — name at save): the config step asks the rule;
 * the save step then asks the rule id. A 503 surfaces the writes-disabled banner (same contract as
 * `job-form.dialog`); any other refusal — e.g. the save-time 422 for a `by` column the Dataset's Schema
 * lacks — is shown in the dialog.
 */
@Component({
    selector: 'app-alert-rule-form-dialog',
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
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>
            {{
                isEdit
                    ? 'Edit alert rule — ' + data.rule?.name
                    : step() === 'save'
                      ? 'Save alert rule'
                      : 'New alert rule'
            }}
        </h2>

        <mat-dialog-content class="!pt-2">
            @if (writesDisabled()) {
                <inspecto-alert class="mb-4 block" variant="warning" icon="heroicons_outline:lock-closed">
                    Alert-rule writes are disabled on this server (no write root configured).
                </inspecto-alert>
            }
            <!-- Config step content stays mounted (not @if'd) so the schema-form ViewChild survives the
                 step transition — only visually hidden via [hidden], never destroyed. -->
            <div [hidden]="step() === 'save'">
                <inspecto-schema-form
                    #sf
                    [specs]="attributes"
                    [initial]="initialValue"
                    [optionLoaders]="optionLoaders"
                    [extraValidators]="extraValidators"
                    (submitted)="save()"
                ></inspecto-schema-form>

                @if (kind() === 'metric') {
                    <div class="mt-4 font-semibold">Only count batches matching (optional)</div>
                    <inspecto-query-condition-group
                        class="mt-2 block"
                        [group]="when"
                        [columns]="columns"
                        [root]="true"
                    />
                    @if (whenEmpty()) {
                        <div class="text-secondary mt-1 text-sm">No conditions — every batch in the window counts.</div>
                    }
                } @else if (!authored) {
                    <!-- Read-only: this form cannot re-author a freshness / Investigation rule's target; save keeps it as stored. -->
                    <dl class="text-secondary mt-4 space-y-1 text-sm" aria-label="Rule target (read-only)">
                        @if (data.rule?.dataset) {
                            <div>
                                <dt class="inline font-semibold">Dataset:</dt>
                                <dd class="inline">{{ data.rule?.dataset }}</dd>
                            </div>
                        }
                        @if (data.rule?.measure) {
                            <div>
                                <dt class="inline font-semibold">Measure:</dt>
                                <dd class="inline">{{ data.rule?.measure }}</dd>
                            </div>
                        }
                        @if (data.rule?.maximumAge) {
                            <div>
                                <dt class="inline font-semibold">Maximum age:</dt>
                                <dd class="inline">{{ data.rule?.maximumAge }}</dd>
                            </div>
                        }
                    </dl>
                }
            </div>
            @if (!isEdit && step() === 'save') {
                <!-- Save step (create only): the rule id, asked only now. -->
                <form [formGroup]="saveForm" aria-label="Name this alert rule" class="space-y-1">
                    <div class="text-secondary text-sm">Rule configured — give it a unique id to save it.</div>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Rule id</mat-label>
                        <input matInput formControlName="name" required cdkFocusInitial />
                        @if (saveForm.controls.name.hasError('required')) {
                            <mat-error>A rule id is required.</mat-error>
                        } @else if (saveForm.controls.name.hasError('pattern')) {
                            <mat-error>Start with a letter; then letters, digits, dash, underscore only.</mat-error>
                        } @else if (saveForm.controls.name.hasError('duplicate')) {
                            <mat-error>An alert rule with this id already exists.</mat-error>
                        }
                    </mat-form-field>
                </form>
            }
            @if (saveError()) {
                <!-- The server's refusal, in its own words (a 422 names the offending field). -->
                <inspecto-alert class="mt-4 block" variant="error" title="The alert rule was not saved">
                    {{ saveError() }}
                </inspecto-alert>
            }
        </mat-dialog-content>

        <mat-dialog-actions align="end">
            @if (!isEdit && step() === 'save') {
                <button mat-button type="button" (click)="backToConfig()">Back</button>
            }
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" (click)="save()" [disabled]="saving()">
                {{ isEdit ? 'Save changes' : step() === 'save' ? 'Create rule' : 'Continue' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class AlertRuleFormDialog implements AfterViewInit {
    private fb = inject(FormBuilder);
    private api = inject(AlertsService);
    private ref = inject(MatDialogRef<AlertRuleFormDialog, AlertRuleFormResult>);
    private confirm = inject(InspectoConfirmService);
    private destroyRef = inject(DestroyRef);
    readonly data = inject<AlertRuleFormData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    /** Guarded close: Esc / backdrop / Cancel confirm before discarding a dirty form. */
    readonly requestClose = guardDirtyClose(
        this.ref,
        () => (this.schemaForm?.isDirty() ?? false) || this.saveForm.dirty,
        this.confirm,
    );

    /** Suggestion sources: metrics seen on armed rules + the engine's documented trio; pipeline scope;
     *  the registered Datasets; and the columns of the Dataset the rule names (for `by`). */
    readonly optionLoaders = {
        metric: async (): Promise<{ value: string; label: string }[]> => {
            const known = new Set(['error_rate', 'rejected_files', 'duration_ms']);
            try {
                for (const r of await firstValueFrom(this.api.rules())) if (r.metric) known.add(r.metric);
            } catch {
                // suggestions are best-effort — the documented trio still shows
            }
            return [...known].sort().map((m) => ({ value: m, label: m }));
        },
        onPipeline: pipelineOptionLoader(),
        dataset: datasetOptionLoader(),
        runbook: runbookOptionLoader(),
        by: datasetColumnOptionLoader('dataset'),
    };

    /** Domain rules a declarative spec cannot phrase — each renders its own message on screen. */
    readonly extraValidators: Record<string, ValidatorFn[]> = {
        measure: [measureValidator],
        by: [groupByValidator()],
        stormCap: [wholeNumberValidator],
        healAfterSweeps: [wholeNumberValidator],
        threshold: [positiveValidator],
    };

    readonly isEdit = !!this.data.rule;
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    /** The server's refusal of the last save (`apiErrorMessage`) — shown in the dialog, not a toast. */
    readonly saveError = signal('');
    /** The kind this form authors for the loaded rule — `null` for a freshness / Investigation rule. */
    readonly authored = authoredKind(
        this.data.rule ?? (this.data.seed?.dataset ? (this.data.seed as AlertRule) : undefined),
    );
    /** The live kind choice — drives the host-rendered `when` editor, which is not a spec. */
    readonly kind = signal<AuthoredKind | null>(this.authored);
    /** A freshness / Investigation rule gets neither kind's fields (the engine refuses them on it). */
    readonly attributes = this.authored
        ? ALERT_RULE_ATTRIBUTES
        : ALERT_RULE_ATTRIBUTES.filter(
              (s) =>
                  s.key !== 'kind' &&
                  !KIND_KEYS.includes(s.key) &&
                  !(this.data.rule?.maximumAge && FRESHNESS_FIXED.includes(s.key)),
          );

    /** Create flow: `config` (the rule) → `save` (rule id, asked last). Edit stays on `config`. */
    readonly step = signal<'config' | 'save'>('config');

    /** Save-step field (create only): the alert rule id — mirrors the schema-form `identifier` pattern
     *  it replaces (letter-start; letters/digits/dash/underscore). */
    readonly saveForm = this.fb.group({
        name: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z][A-Za-z0-9_-]*$/),
                ...(this.data.existingNames?.length ? [uniqueNameValidator(this.data.existingNames)] : []),
            ],
        ],
    });

    readonly initialValue: Record<string, unknown> | undefined = this.data.rule
        ? {
              ...this.data.rule,
              onPipeline: this.data.rule.onPipeline ?? '',
              ...(this.authored ? { kind: this.authored } : {}),
          }
        : this.data.seed
          ? { ...this.data.seed, ...(this.authored ? { kind: this.authored } : {}) }
          : undefined;

    /** Deep-cloned on edit — the condition editor mutates the bound group in place. */
    readonly when: ConditionGroup = this.data.rule?.when ? structuredClone(this.data.rule.when) : emptyGroup('AND');
    /** Fixed ledger columns, plus any field an existing when-clause already references. */
    readonly columns: ColumnMeta[] = [
        ...LEDGER_COLUMNS,
        ...referencedFields(this.when)
            .filter((f) => !LEDGER_COLUMNS.some((c) => c.name === f))
            .map((name) => ({ name, type: 'string' as const })),
    ];

    ngAfterViewInit(): void {
        this.schemaForm.form
            .get('kind')
            ?.valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((k) => this.kind.set(k === 'measure' ? 'measure' : 'metric'));
    }

    whenEmpty(): boolean {
        return this.when.items.length === 0;
    }

    /** The suggested rule id: `<metric>_<comparator>_<window>`, or `<dataset>_<measure>_<comparator>`. */
    suggestedName(): string {
        const v = this.schemaForm.value() as Record<string, string | undefined>;
        const parts =
            this.kind() === 'measure'
                ? [v['dataset'], v['measure'], v['comparator']]
                : [v['metric'], v['comparator'], v['window']];
        const base = parts.filter(Boolean).join('_') || 'alert_rule';
        return base
            .replace(/[^A-Za-z0-9._-]+/g, '_')
            .replace(/_{2,}/g, '_') // `sum(amount)_gt` → `sum_amount_gt`, not `sum_amount__gt`
            .replace(/^[^A-Za-z]+/, '');
    }

    /** Create flow only: leave the save step back to the config step (the id is kept). */
    backToConfig(): void {
        this.step.set('config');
    }

    save(): void {
        if (!this.schemaForm.validate()) return;
        // Create asks the rule id only now, at save time — config valid ⇒ advance to the save step.
        if (!this.isEdit && this.step() === 'config') {
            if (this.saveForm.controls.name.pristine) this.saveForm.patchValue({ name: this.suggestedName() });
            this.step.set('save');
            return;
        }
        if (!this.isEdit && this.saveForm.invalid) {
            this.saveForm.markAllAsTouched();
            return;
        }
        const v = this.schemaForm.value() as Partial<AlertRule>;
        const onPipeline = String(v.onPipeline ?? '').trim();
        const description = String(v.description ?? '').trim();
        const runbook = String(v.runbook ?? '').trim();
        const kind = this.kind();
        // A PUT replaces the whole rule, so every stored key this form does not edit (a freshness rule's
        // dataset/maximumAge, anything newer than this form) is carried over from the loaded rule. The keys
        // the form DOES edit — both kinds' included, so a kind switch drops the other kind's — are dropped
        // first, so clearing one (pipeline scope, description, `by`) removes it.
        const edited = new Set(['name', ...this.attributes.map((s) => s.key), ...(kind ? KIND_KEYS : [])]);
        const kept = Object.fromEntries(Object.entries(this.data.rule ?? {}).filter(([k]) => !edited.has(k)));
        // The list control holds null when cleared and [] when "explicitly none" — both mean no `by`.
        const by = Array.isArray(v.by) ? v.by : [];
        const stormCap = v.stormCap === null || v.stormCap === undefined ? null : Number(v.stormCap);
        const healAfterSweeps =
            v.healAfterSweeps === null || v.healAfterSweeps === undefined ? null : Number(v.healAfterSweeps);
        const body: AlertRuleUpsert = {
            ...kept,
            name: this.isEdit ? this.data.rule!.name : String(this.saveForm.getRawValue().name ?? '').trim(),
            ...(kind === 'metric' ? { metric: String(v.metric ?? '').trim(), window: String(v.window ?? '15m') } : {}),
            ...(kind === 'measure'
                ? {
                      dataset: String(v.dataset ?? '').trim(),
                      measure: String(v.measure ?? '').trim(),
                      // The engine refuses `stormCap` without `by`, so it travels only with one.
                      ...(by.length ? { by, ...(stormCap !== null ? { stormCap } : {}) } : {}),
                      ...(healAfterSweeps !== null ? { healAfterSweeps } : {}),
                  }
                : {}),
            // A freshness rule's comparator/threshold are not form fields (hidden ⇒ absent from `v`): stored values.
            comparator: String(v.comparator ?? this.data.rule?.comparator ?? 'gt'),
            threshold: Number(v.threshold ?? this.data.rule?.threshold),
            severity: String(v.severity ?? 'WARNING'),
            ...(onPipeline ? { onPipeline } : {}),
            ...(description ? { description } : {}),
            ...(runbook ? { runbook } : {}),
            ...(kind === 'metric' && !this.whenEmpty() ? { when: this.when } : {}),
        };
        this.saveError.set('');
        this.saving.set(true);
        const call = this.isEdit ? this.api.updateRule(body.name, body) : this.api.createRule(body);
        call.subscribe({
            next: (saved) => this.ref.close({ saved }),
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                else this.saveError.set(apiErrorMessage(e, 'Could not save the alert rule.'));
            },
        });
    }
}

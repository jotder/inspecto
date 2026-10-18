import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal, computed } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
    AbstractControl,
    FormArray,
    FormBuilder,
    FormGroup,
    ReactiveFormsModule,
    ValidationErrors,
    ValidatorFn,
    Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Dataset } from '../studio/datasets/dataset-types';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { apiErrorMessage, ReconApiService, ReconDatasetColumns, ReconServerConfig } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import {
    CompareColumn,
    DEFAULT_BANDS,
    MeasureAgg,
    ReconBands,
    Reconciliation,
    ReconRunResult,
    ToleranceType,
} from 'app/inspecto/reconciliation';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';

export interface ReconciliationFormResult {
    name: string;
    leftDataset: string;
    rightDataset: string;
    thirdDataset?: string;
    keyColumns: string[];
    compareColumns: CompareColumn[];
    bands: ReconBands;
}

/** Optional dialog data — pass an existing reconciliation to edit it in place, or duplicate-and-rebind. */
export interface ReconciliationFormData {
    recon?: Reconciliation;
    /** Prefill from `recon` but create a NEW reconciliation (the template flow — design §8). */
    duplicate?: boolean;
}

/** Cross-field: the breach threshold can never sit below the warn threshold. */
const breachNotBelowWarn: ValidatorFn = (c: AbstractControl): ValidationErrors | null => {
    const warn = c.parent?.get('warnPct')?.value ?? 0;
    return Number(c.value) < Number(warn) ? { belowWarn: true } : null;
};

/** `Test definition` compares at most this many keys (the route accepts 1..100000). */
export const TEST_SAMPLE_KEYS = 1000;

/** The Break types the test result reports, in reading order. */
const BREAK_TYPE_LABELS: [keyof ReconRunResult['summary']['byType'], string][] = [
    ['missing_left', 'Missing on left'],
    ['missing_right', 'Missing on right'],
    ['value_break', 'Value'],
    ['cardinality_break', 'Cardinality'],
];

type SideColumn = ReconDatasetColumns['columns'][number];

/** Define (or edit) a Dataset-vs-Dataset reconciliation — both sides, the key column(s) whose order is
 *  the Board hierarchy, the compare column(s) each with an aggregation + tolerance, and the Board's
 *  severity bands. Column pickers offer the columns of EVERY chosen side (`/recon/columns`); a key missing on a
 *  side or a non-numeric `sum` column is flagged inline and blocks Save, and `Test definition` runs the real
 *  comparison on a small sample. */
@Component({
    selector: 'app-reconciliation-form-dialog',
    standalone: true,
    imports: [
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatIconModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>
            {{ editing ? 'Edit reconciliation' : duplicating ? 'Duplicate reconciliation' : 'New reconciliation' }}
        </h2>
        <form [formGroup]="form" (ngSubmit)="submit()">
            <mat-dialog-content class="flex flex-col gap-3">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Name</mat-label>
                    <input matInput formControlName="name" placeholder="e.g. switch vs billing" cdkFocusInitial />
                    @if (form.controls.name.hasError('required')) {
                        <mat-error>A name is required.</mat-error>
                    }
                </mat-form-field>

                <div class="flex gap-3">
                    <div class="flex-1">
                        <inspecto-option-picker
                            label="Left dataset (anchor / source of truth)"
                            [options]="datasetOptions()"
                            formControlName="leftDataset"
                            (ngModelChange)="onLeftChange($event)"
                        />
                        @if (form.controls.leftDataset.touched && form.controls.leftDataset.hasError('required')) {
                            <p class="text-warn m-0 text-xs" role="alert">Choose a left dataset.</p>
                        }
                    </div>
                    <div class="flex-1">
                        <inspecto-option-picker
                            label="Right dataset"
                            [options]="datasetOptions()"
                            formControlName="rightDataset"
                        />
                        @if (form.controls.rightDataset.touched && form.controls.rightDataset.hasError('required')) {
                            <p class="text-warn m-0 text-xs" role="alert">Choose a right dataset.</p>
                        }
                    </div>
                </div>

                <inspecto-option-picker
                    class="w-full"
                    label="Third dataset (optional — 3-way vs the anchor)"
                    [options]="thirdDatasetOptions()"
                    formControlName="thirdDataset"
                />

                @if (sideSummary().length) {
                    <ul
                        class="text-secondary m-0 flex list-none flex-wrap gap-x-4 gap-y-1 p-0 text-xs"
                        aria-label="Columns per dataset"
                    >
                        @for (s of sideSummary(); track s.label) {
                            <li>
                                <span class="font-semibold">{{ s.label }}</span> {{ s.text }}
                            </li>
                        }
                    </ul>
                }

                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Key column(s) — selection order is the Board tree</mat-label>
                    <mat-select formControlName="keyColumns" multiple>
                        @for (c of columnOptions(); track c) {
                            <mat-option [value]="c">{{ c }}</mat-option>
                        }
                    </mat-select>
                    @if (form.controls.keyColumns.hasError('required')) {
                        <mat-error>Pick at least one key column.</mat-error>
                    }
                </mat-form-field>
                @for (m of keyIssues(); track m) {
                    <p class="text-warn m-0 text-xs" role="alert">{{ m }}</p>
                }

                <div class="flex items-center justify-between">
                    <span class="text-secondary text-xs font-semibold uppercase tracking-wider">Compare columns</span>
                    <button
                        mat-stroked-button
                        type="button"
                        (click)="addCompare()"
                        [disabled]="!columnOptions().length"
                    >
                        <mat-icon svgIcon="heroicons_outline:plus"></mat-icon><span class="ml-1">Add</span>
                    </button>
                </div>
                <div [formArrayName]="'compareRows'" class="flex flex-col gap-2">
                    @for (row of compareRowsArray.controls; track $index; let i = $index) {
                        <div class="flex items-center gap-2" [formGroupName]="i">
                            <mat-form-field class="flex-1" subscriptSizing="dynamic">
                                <mat-label>Column</mat-label>
                                <mat-select formControlName="column">
                                    @for (c of columnOptions(); track c) {
                                        <mat-option [value]="c">{{ c }}</mat-option>
                                    }
                                </mat-select>
                                @if (row.get('column')?.hasError('required')) {
                                    <mat-error>Required</mat-error>
                                }
                            </mat-form-field>
                            <mat-form-field class="w-24" subscriptSizing="dynamic">
                                <mat-label>Agg</mat-label>
                                <mat-select formControlName="agg">
                                    <mat-option value="sum">sum</mat-option>
                                    <mat-option value="count">count</mat-option>
                                </mat-select>
                            </mat-form-field>
                            <mat-form-field class="w-32" subscriptSizing="dynamic">
                                <mat-label>Tolerance</mat-label>
                                <mat-select formControlName="toleranceType">
                                    <mat-option value="exact">exact</mat-option>
                                    <mat-option value="absolute">± absolute</mat-option>
                                    <mat-option value="percent">± percent</mat-option>
                                </mat-select>
                            </mat-form-field>
                            @if (row.get('toleranceType')?.value !== 'exact') {
                                <mat-form-field class="w-24" subscriptSizing="dynamic">
                                    <mat-label>Value</mat-label>
                                    <input matInput type="number" formControlName="tolerance" />
                                </mat-form-field>
                            }
                            <button
                                mat-icon-button
                                type="button"
                                (click)="removeCompare(i)"
                                aria-label="Remove compare column"
                            >
                                <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                            </button>
                        </div>
                        @for (m of compareIssues()[i] ?? []; track m) {
                            <p class="text-warn m-0 text-xs" role="alert">{{ m }}</p>
                        }
                    }
                </div>

                <span class="text-secondary text-xs font-semibold uppercase tracking-wider">Board bands (|Δ%|)</span>
                <div class="flex items-center gap-3">
                    <mat-form-field class="w-32" subscriptSizing="dynamic">
                        <mat-label>Warn from %</mat-label>
                        <input matInput type="number" min="0" formControlName="warnPct" />
                        @if (form.controls.warnPct.hasError('min')) {
                            <mat-error>Must be ≥ 0.</mat-error>
                        }
                    </mat-form-field>
                    <mat-form-field class="w-32" subscriptSizing="dynamic">
                        <mat-label>Breach over %</mat-label>
                        <input matInput type="number" min="0" formControlName="breachPct" />
                        @if (form.controls.breachPct.hasError('min')) {
                            <mat-error>Must be ≥ 0.</mat-error>
                        } @else if (form.controls.breachPct.hasError('belowWarn')) {
                            <mat-error>Must be ≥ warn %.</mat-error>
                        }
                    </mat-form-field>
                    <span class="text-secondary text-xs">ok &lt; warn · warn–breach · breach &gt;</span>
                </div>

                @if (testError(); as err) {
                    <inspecto-alert variant="error" title="Test failed">
                        {{ err }} Check that every key and compare column exists on each dataset and that a sum column
                        is numeric.
                    </inspecto-alert>
                }
                @if (testResult(); as r) {
                    <div class="bg-card rounded-2xl border p-3 text-sm" role="status">
                        <p class="m-0 font-semibold">
                            Test on {{ r.sampleLabel }} · {{ r.groups }} key groups compared ·
                            {{ r.matchedKeys }} matched · {{ r.elapsedMs }} ms
                        </p>
                        <p class="text-secondary m-0 mt-1 text-xs">Breaks by type</p>
                        <ul class="m-0 flex list-none flex-wrap gap-x-4 p-0 tabular-nums">
                            @for (b of r.breaks; track b.label) {
                                <li>{{ b.label }}: {{ b.count }}</li>
                            }
                        </ul>
                    </div>
                }
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button
                    type="button"
                    mat-stroked-button
                    class="mr-auto"
                    (click)="testDefinition()"
                    [disabled]="!canTest() || testing()"
                >
                    <mat-icon svgIcon="heroicons_outline:beaker"></mat-icon>
                    <span class="ml-1">{{ testing() ? 'Testing…' : 'Test definition' }}</span>
                </button>
                <button type="button" mat-button (click)="requestClose()">Cancel</button>
                <button type="submit" mat-flat-button color="primary" [disabled]="form.invalid || hasIssues()">
                    {{ editing ? 'Save' : 'Create' }}
                </button>
            </mat-dialog-actions>
        </form>
    `,
})
export class ReconciliationFormDialog {
    private fb = inject(FormBuilder);
    private datasetsApi = inject(DatasetsService);
    private ref = inject(MatDialogRef<ReconciliationFormDialog, ReconciliationFormResult>);
    private confirm = inject(InspectoConfirmService);
    private reconApi = inject(ReconApiService);
    private destroyRef = inject(DestroyRef);

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);
    private data = inject<ReconciliationFormData | null>(MAT_DIALOG_DATA, { optional: true });

    readonly duplicating = !!this.data?.recon && !!this.data?.duplicate;
    readonly editing = !!this.data?.recon && !this.data?.duplicate;
    readonly datasets = signal<Dataset[]>([]);
    readonly datasetOptions = computed<PickerOption[]>(() =>
        this.datasets().map((d) => ({ value: d.id, label: d.name })),
    );
    readonly thirdDatasetOptions = computed<PickerOption[]>(() => [
        { value: '', label: '— none (2-way) —' },
        ...this.datasetOptions(),
    ]);
    readonly leftColumns = signal<string[]>([]);

    /** The form's value as a signal, so the per-side checks below are `computed`s. */
    private readonly snap = signal<Record<string, unknown>>({});
    /** Dataset id → its `/recon/columns` inventory; absent while loading or after a failed read. */
    private readonly inventory = signal<Record<string, SideColumn[]>>({});
    private readonly inventoryFailed = signal<Record<string, true>>({});
    private loadedFor = '';
    readonly testing = signal(false);
    /** Bumped by every Test and every edit; a response lands only if it still carries the latest value. */
    private testSeq = 0;
    readonly testError = signal<string | null>(null);
    readonly testResult = signal<{
        sampleLabel: string;
        groups: number;
        matchedKeys: number;
        elapsedMs: number;
        breaks: { label: string; count: number }[];
    } | null>(null);

    /** A stored column map renames columns per side, so a same-name check would be wrong; it is skipped. */
    private readonly columnMapped = (() => {
        const m = (this.data?.recon?.raw as Record<string, unknown> | undefined)?.['columnMap'];
        return typeof m === 'object' && m !== null && Object.keys(m).length > 0;
    })();

    /** The chosen sides in order, with the label the dialog calls them. */
    private readonly sides = computed(() =>
        (
            [
                ['Left', this.snap()['leftDataset']],
                ['Right', this.snap()['rightDataset']],
                ['Third', this.snap()['thirdDataset']],
            ] as [string, string][]
        )
            .filter(([, id]) => !!id)
            .map(([label, id]) => ({ label, id })),
    );

    readonly sideSummary = computed(() =>
        this.sides().map((s) => {
            const cols = this.inventory()[s.id];
            const text = cols
                ? `${cols.length} columns (${cols.filter((c) => c.numeric).length} numeric)`
                : this.inventoryFailed()[s.id]
                  ? 'columns could not be read, not checked'
                  : 'reading columns…';
            return { label: s.label, text };
        }),
    );

    /** Every column of every side whose inventory is known; the left dataset's declared columns until then. */
    readonly columnOptions = computed(() => {
        const inv = this.inventory();
        const names = new Set<string>();
        for (const s of this.sides()) for (const c of inv[s.id] ?? []) names.add(c.name);
        return names.size ? [...names] : this.leftColumns();
    });

    /** Sides whose inventory is known, with their column lookup: the only ones a pick can be checked against. */
    private checkable(): { label: string; cols: Map<string, SideColumn> }[] {
        if (this.columnMapped) return [];
        const inv = this.inventory();
        return this.sides()
            .filter((s) => inv[s.id])
            .map((s) => ({ label: `${s.label} (${s.id})`, cols: new Map(inv[s.id].map((c) => [c.name, c])) }));
    }

    readonly keyIssues = computed<string[]>(() => {
        const sides = this.checkable();
        return ((this.snap()['keyColumns'] as string[] | undefined) ?? []).flatMap((k) => {
            const missing = sides.filter((s) => !s.cols.has(k)).map((s) => s.label);
            return missing.length ? [`Key "${k}" is not a column of ${missing.join(' or ')}.`] : [];
        });
    });

    /** Per compare row: not on every side, or a `sum` over a non-numeric column. */
    readonly compareIssues = computed<string[][]>(() => {
        const sides = this.checkable();
        return ((this.snap()['compareRows'] as { column?: string; agg?: string }[] | undefined) ?? []).map((r) => {
            const col = r.column;
            if (!col) return [];
            const missing = sides.filter((s) => !s.cols.has(col)).map((s) => s.label);
            if (missing.length) return [`Column "${col}" is not a column of ${missing.join(' or ')}.`];
            const text = sides
                .filter((s) => r.agg !== 'count' && s.cols.get(col)?.numeric === false)
                .map((s) => s.label);
            return text.length
                ? [`Column "${col}" is not numeric on ${text.join(' or ')}; use count or pick a numeric column.`]
                : [];
        });
    });

    readonly hasIssues = computed(() => this.keyIssues().length > 0 || this.compareIssues().some((m) => m.length > 0));
    readonly canTest = computed(() => {
        const v = this.snap();
        return (
            !!v['leftDataset'] &&
            !!v['rightDataset'] &&
            ((v['keyColumns'] as string[] | undefined)?.length ?? 0) > 0 &&
            !this.hasIssues() &&
            this.form.valid
        );
    });

    readonly form: FormGroup = this.fb.group({
        name: ['', [Validators.required]],
        leftDataset: ['', [Validators.required]],
        rightDataset: ['', [Validators.required]],
        thirdDataset: [''],
        keyColumns: [[] as string[], [Validators.required]],
        warnPct: [DEFAULT_BANDS.warnPct, [Validators.required, Validators.min(0)]],
        breachPct: [DEFAULT_BANDS.breachPct, [Validators.required, Validators.min(0), breachNotBelowWarn]],
        compareRows: this.fb.array<FormGroup>([]),
    });

    constructor() {
        this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
            this.snap.set(this.form.getRawValue());
            // An edit voids any test in flight: its answer is for the OLD definition.
            this.testSeq++;
            this.testing.set(false);
            this.testResult.set(null);
            this.testError.set(null);
            this.loadInventory();
        });
        this.form.controls['warnPct'].valueChanges.subscribe(() =>
            this.form.controls['breachPct'].updateValueAndValidity({ emitEvent: false }),
        );

        const r = this.data?.recon;
        if (r) {
            this.form.patchValue({
                name: this.duplicating ? `${r.name} copy` : r.name,
                leftDataset: r.leftDataset,
                rightDataset: r.rightDataset,
                thirdDataset: r.thirdDataset ?? '',
                keyColumns: [...r.keyColumns],
                warnPct: r.bands?.warnPct ?? DEFAULT_BANDS.warnPct,
                breachPct: r.bands?.breachPct ?? DEFAULT_BANDS.breachPct,
            });
            for (const c of r.compareColumns)
                this.pushCompareRow(c.column, c.agg ?? 'sum', c.toleranceType, c.tolerance);
        }
        this.datasetsApi.list().subscribe((d) => {
            this.datasets.set(d);
            const left = this.form.controls['leftDataset'].value;
            if (left) this.onLeftChange(left);
            this.snap.set(this.form.getRawValue());
            this.loadInventory();
        });
    }

    /** Read the columns of every chosen side once per distinct set of Datasets (`/recon/columns`). */
    private loadInventory(): void {
        const ids = [...new Set(this.sides().map((s) => s.id))].sort();
        const key = ids.join('|');
        if (!ids.length || key === this.loadedFor) return;
        this.loadedFor = key;
        this.inventory.set({});
        this.inventoryFailed.set({});
        this.reconApi
            .columns(ids)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (res) => {
                    if (this.loadedFor !== key) return;
                    this.inventory.set(Object.fromEntries(res.datasets.map((d) => [d.dataset, d.columns])));
                    this.inventoryFailed.set(
                        Object.fromEntries(
                            ids
                                .filter((i) => !res.datasets.some((d) => d.dataset === i))
                                .map((i) => [i, true as const]),
                        ),
                    );
                },
                error: () => {
                    if (this.loadedFor === key)
                        this.inventoryFailed.set(Object.fromEntries(ids.map((i) => [i, true as const])));
                },
            });
    }

    /** `Test definition`: the existing `/recon/run` over a small sample of keys; nothing is saved or recorded. */
    testDefinition(): void {
        const v = this.form.getRawValue();
        const raw = (this.data?.recon?.raw ?? {}) as Record<string, unknown>;
        const config: ReconServerConfig = {
            datasets: [v.leftDataset, v.rightDataset, ...(v.thirdDataset ? [v.thirdDataset] : [])],
            keyColumns: v.keyColumns,
            compareColumns: (v.compareRows as CompareColumn[])
                .filter((c) => c.column)
                .map((c) => ({ column: c.column, agg: c.agg, toleranceType: c.toleranceType, tolerance: c.tolerance })),
            includeRecordCount: true,
            ...(raw['columnMap'] ? { columnMap: raw['columnMap'] as ReconServerConfig['columnMap'] } : {}),
            ...(raw['filters'] ? { filters: raw['filters'] as ReconServerConfig['filters'] } : {}),
            ...(typeof raw['cardinality'] === 'string' && raw['cardinality']
                ? { cardinality: raw['cardinality'] }
                : {}),
        };
        const seq = ++this.testSeq;
        this.testing.set(true);
        this.testError.set(null);
        this.testResult.set(null);
        this.reconApi
            .run(config, { day: null, offset: 0, limit: 1, filter: 'all', sample: TEST_SAMPLE_KEYS })
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (r) => {
                    if (seq !== this.testSeq) return;
                    this.testing.set(false);
                    const by = r.summary.byType;
                    this.testResult.set({
                        sampleLabel: r.sample?.sampled
                            ? `a sample of ${r.sample.size} keys`
                            : r.day
                              ? `all keys of ${r.day}`
                              : 'all keys',
                        groups: r.summary.groups,
                        matchedKeys: r.summary.matchedKeys,
                        elapsedMs: r.statistics.elapsedMs,
                        breaks: BREAK_TYPE_LABELS.filter(([k]) => k !== 'cardinality_break' || by[k] !== undefined).map(
                            ([k, label]) => ({ label, count: by[k] ?? 0 }),
                        ),
                    });
                },
                error: (e) => {
                    if (seq !== this.testSeq) return;
                    this.testing.set(false);
                    this.testError.set(apiErrorMessage(e, 'The comparison could not run.'));
                },
            });
    }

    get compareRowsArray(): FormArray<FormGroup> {
        return this.form.controls['compareRows'] as FormArray<FormGroup>;
    }

    onLeftChange(id: string): void {
        this.leftColumns.set(
            this.datasets()
                .find((d) => d.id === id)
                ?.columns.map((c) => c.name) ?? [],
        );
        // drop key/compare picks no longer valid for the new left dataset
        const cols = new Set(this.leftColumns());
        const keyCtrl = this.form.controls['keyColumns'];
        keyCtrl.setValue((keyCtrl.value as string[]).filter((k) => cols.has(k)));
        for (let i = this.compareRowsArray.length - 1; i >= 0; i--) {
            if (!cols.has(this.compareRowsArray.at(i).get('column')?.value)) this.compareRowsArray.removeAt(i);
        }
    }

    addCompare(): void {
        this.pushCompareRow(this.leftColumns()[0] ?? '', 'sum', 'absolute', 0);
    }

    removeCompare(i: number): void {
        this.compareRowsArray.removeAt(i);
    }

    private pushCompareRow(column: string, agg: MeasureAgg, toleranceType: ToleranceType, tolerance: number): void {
        this.compareRowsArray.push(
            this.fb.group({
                column: [column, [Validators.required]],
                agg: [agg, [Validators.required]],
                toleranceType: [toleranceType, [Validators.required]],
                tolerance: [tolerance],
            }),
        );
    }

    submit(): void {
        if (this.form.invalid || this.hasIssues()) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        this.ref.close({
            name: String(v.name).trim(),
            leftDataset: v.leftDataset,
            rightDataset: v.rightDataset,
            ...(v.thirdDataset ? { thirdDataset: v.thirdDataset } : {}),
            keyColumns: v.keyColumns,
            compareColumns: (v.compareRows as CompareColumn[])
                .filter((c) => c.column)
                .map((c) => ({ column: c.column, agg: c.agg, toleranceType: c.toleranceType, tolerance: c.tolerance })),
            bands: { warnPct: v.warnPct, breachPct: v.breachPct },
        });
    }
}
